package io.github.colombus806.searchqbit.data

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/** Receives the results of a search; called from the plugins' Python threads. */
interface SearchSink {
    fun isCancelled(): Boolean
    fun onResult(plugin: String, json: String)
    fun onError(plugin: String, message: String)
}

/**
 * Bridge to engine.py. Calls block (network): never make them on the main thread.
 * Python starts on the first call, which takes a second or two.
 */
class PythonEngine(private val context: Context) {
    private val novaDir = File(context.filesDir, "nova3")
    val enginesDir = File(novaDir, "engines")

    private val module: PyObject by lazy {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        Python.getInstance().getModule("engine").also {
            it.callAttr("init", novaDir.path, File(context.cacheDir, "python-tmp").path)
        }
    }

    fun warmUp() {
        module
    }

    /** Imports the plugin and reads its metadata; throws if the file is not a plugin. */
    fun info(name: String): SearchPlugin =
        appJson.decodeFromString(module.callAttr("info", name).toString())

    fun search(plugins: List<String>, query: String, category: String, sink: SearchSink) {
        module.callAttr("search", plugins.toTypedArray(), query, category, sink)
    }

    /** A magnet link, or the path of a temporary .torrent file fetched by the plugin. */
    fun download(plugin: String, url: String): String =
        module.callAttr("download", plugin, url).toString()
}
