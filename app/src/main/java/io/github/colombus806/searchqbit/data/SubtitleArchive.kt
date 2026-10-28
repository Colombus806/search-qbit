package io.github.colombus806.searchqbit.data

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** A subtitle ready to be saved: always the bare file, never an archive. */
class SubtitleFile(val name: String, val bytes: ByteArray)

private val SUBTITLE_EXTENSIONS = listOf("srt", "ass", "ssa", "vtt", "sub", "smi")

/**
 * OpenSubtitles usually sends the .srt itself, but a .zip or .gz is unpacked here: a zip keeps its
 * largest subtitle file.
 */
internal fun extractSubtitle(fileName: String, bytes: ByteArray): SubtitleFile {
    val name = fileName.substringAfterLast('/').ifBlank { "subtitles.srt" }
    return when {
        bytes.startsWith(0x50, 0x4B) -> unzip(bytes)
        bytes.startsWith(0x1F, 0x8B) -> {
            val content = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            extractSubtitle(name.removeSuffix(".gz"), content)
        }
        else -> SubtitleFile(safeSubtitleName(name), bytes)
    }
}

private fun unzip(bytes: ByteArray): SubtitleFile {
    var best: SubtitleFile? = null
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name.substringAfterLast('/')
            if (entry.isDirectory || name.substringAfterLast('.').lowercase() !in SUBTITLE_EXTENSIONS) continue
            val content = zip.readBytes()
            if (content.size > (best?.bytes?.size ?: -1)) best = SubtitleFile(safeSubtitleName(name), content)
        }
    }
    return best ?: throw SubtitleException("The archive contains no subtitle file.")
}

/** Keeps the name readable but valid on every file system; adds ".srt" when there is no known extension. */
internal fun safeSubtitleName(name: String): String {
    val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim().take(150).ifEmpty { "subtitles" }
    return if (cleaned.substringAfterLast('.', "").lowercase() in SUBTITLE_EXTENSIONS) cleaned else "$cleaned.srt"
}

private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
