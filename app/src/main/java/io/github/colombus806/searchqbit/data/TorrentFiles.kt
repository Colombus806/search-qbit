package io.github.colombus806.searchqbit.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What gets handed to LibreTorrent: a magnet link, or a .torrent file shared by the app. */
data class TorrentTarget(val uri: Uri, val mimeType: String?)

class TorrentFiles(private val context: Context, private val engine: PythonEngine) {
    private val dir = File(context.cacheDir, "torrents")

    /** Blocking: may download the .torrent through the plugin that found the result. */
    fun prepare(result: SearchResult): TorrentTarget {
        val target = if (result.fileUrl.startsWith("magnet:")) result.fileUrl
        else engine.download(result.engineName, result.fileUrl)
        if (target.startsWith("magnet:")) return TorrentTarget(Uri.parse(target), null)

        val downloaded = File(target)
        try {
            val bytes = downloaded.readBytes()
            // A .torrent file is a bencoded dictionary: it always starts with "d".
            if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) {
                throw IOException("the site returned a web page instead of a .torrent file")
            }
            dir.mkdirs()
            dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1) }
                ?.forEach { it.delete() }
            val file = File(dir, safeFileName(result.fileName) + ".torrent")
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            return TorrentTarget(uri, "application/x-bittorrent")
        } finally {
            downloaded.delete()
        }
    }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(80).ifEmpty { "torrent" }
}
