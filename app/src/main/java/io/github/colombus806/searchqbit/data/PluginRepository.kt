package io.github.colombus806.searchqbit.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

class PluginException(message: String) : Exception(message)

/**
 * Installed plugins: the <name>.py files in the Python engine folder, plus a JSON file that keeps
 * each plugin's metadata, its state (enabled or not) and the URL it came from.
 */
class PluginRepository(
    private val engine: PythonEngine,
    private val stateFile: File,
    private val http: OkHttpClient,
    private val contentResolver: ContentResolver,
) {
    private val mutex = Mutex()
    private val _plugins = MutableStateFlow<List<SearchPlugin>?>(null)

    /** `null` until the list is loaded. */
    val plugins: StateFlow<List<SearchPlugin>?> = _plugins.asStateFlow()

    suspend fun load() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val saved = runCatching { appJson.decodeFromString<List<SearchPlugin>>(stateFile.readText()) }
                .getOrDefault(emptyList())
            _plugins.value = saved.filter { pluginFile(it.name).exists() }
        }
    }

    suspend fun installFromUrl(url: String): SearchPlugin {
        val source = rawPluginUrl(url.trim())
        val fileName = source.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
        return install(fileName, download(source), source)
    }

    suspend fun installFromDocument(uri: Uri): SearchPlugin {
        val (fileName, bytes) = withContext(Dispatchers.IO) {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment.orEmpty()
            val content = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw PluginException("Cannot read this file.")
            name to content
        }
        return install(fileName, bytes, source = "")
    }

    suspend fun setEnabled(name: String, enabled: Boolean) = mutex.withLock {
        save(current().map { if (it.name == name) it.copy(enabled = enabled) else it })
    }

    suspend fun remove(name: String) = mutex.withLock {
        withContext(Dispatchers.IO) { pluginFile(name).delete() }
        save(current().filterNot { it.name == name })
    }

    /** Downloads again every plugin installed from a URL; returns a summary to display. */
    suspend fun updateAll(): String {
        val candidates = current().filter { it.source.startsWith("http") }
        if (candidates.isEmpty()) return "No plugin was installed from a URL: nothing to update."
        val updated = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (plugin in candidates) {
            try {
                val after = install("${plugin.name}.py", download(plugin.source), plugin.source)
                if (after.version != plugin.version) updated += "${after.fullName} ${after.version}".trim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += plugin.fullName.ifBlank { plugin.name }
            }
        }
        return when {
            updated.isEmpty() && failed.isEmpty() -> "All plugins are up to date."
            failed.isEmpty() -> "Updated: ${updated.joinToString()}."
            updated.isEmpty() -> "Update failed: ${failed.joinToString()}."
            else -> "Updated: ${updated.joinToString()}. Failed: ${failed.joinToString()}."
        }
    }

    private suspend fun install(fileName: String, bytes: ByteArray, source: String): SearchPlugin = mutex.withLock {
        val name = pluginName(fileName) ?: throw PluginException(
            "\"$fileName\" is not a valid plugin name: it must be a .py file whose name contains only " +
                "letters, digits and _."
        )
        if (bytes.decodeToString().trimStart().startsWith("<")) {
            throw PluginException("This link returns a web page, not the .py file. On GitHub, use the \"Raw\" link.")
        }
        withContext(Dispatchers.IO) {
            val file = pluginFile(name)
            file.parentFile?.mkdirs()
            val previous = if (file.exists()) file.readBytes() else null
            file.writeBytes(bytes)
            val info = try {
                engine.info(name)
            } catch (e: Exception) {
                // Put back the previous version (or nothing) rather than leave a broken plugin.
                if (previous != null) {
                    file.writeBytes(previous)
                    runCatching { engine.info(name) }
                } else {
                    file.delete()
                }
                throw PluginException("This file is not a valid search plugin: ${e.message}")
            }
            val old = current().find { it.name == name }
            val plugin = info.copy(enabled = old?.enabled ?: true, source = source.ifEmpty { old?.source.orEmpty() })
            save(current().filterNot { it.name == name } + plugin)
            plugin
        }
    }

    private suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val httpUrl = url.toHttpUrlOrNull() ?: throw PluginException("Invalid address: $url")
        try {
            http.newCall(Request.Builder().url(httpUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw PluginException("Download failed (HTTP error ${response.code}).")
                val bytes = response.body.bytes()
                if (bytes.size > MAX_PLUGIN_BYTES) throw PluginException("File too large for a plugin.")
                bytes
            }
        } catch (e: IOException) {
            throw PluginException("Download failed: ${e.message}")
        }
    }

    private fun current(): List<SearchPlugin> = _plugins.value.orEmpty()

    private fun pluginFile(name: String) = File(engine.enginesDir, "$name.py")

    private suspend fun save(plugins: List<SearchPlugin>) = withContext(Dispatchers.IO) {
        val sorted = plugins.sortedBy { it.fullName.ifBlank { it.name }.lowercase() }
        stateFile.writeTextAtomically(appJson.encodeToString(sorted))
        _plugins.value = sorted
    }

    private companion object {
        const val MAX_PLUGIN_BYTES = 1_000_000
    }
}

private val PLUGIN_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** "piratebay.py" -> "piratebay"; `null` if it is not a usable Python module name. */
internal fun pluginName(fileName: String): String? {
    val base = fileName.substringBefore('?').substringAfterLast('/')
    if (!base.endsWith(".py")) return null
    return base.removeSuffix(".py").takeIf { PLUGIN_NAME.matches(it) && it != "__init__" }
}

/** A link to a file's GitHub page becomes a link to the raw file. */
internal fun rawPluginUrl(url: String): String {
    val match = Regex("^https?://github\\.com/([^/]+)/([^/]+)/blob/(.+)$").find(url) ?: return url
    val (owner, repo, path) = match.destructured
    return "https://raw.githubusercontent.com/$owner/$repo/$path"
}
