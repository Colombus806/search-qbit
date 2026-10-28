package io.github.colombus806.searchqbit.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

const val ALL_CATEGORIES = "all"

/** qBittorrent categories, in the order of its interface. */
val CATEGORY_LABELS = linkedMapOf(
    "all" to "All",
    "movies" to "Movies",
    "tv" to "TV shows",
    "music" to "Music",
    "games" to "Games",
    "anime" to "Anime",
    "software" to "Software",
    "pictures" to "Pictures",
    "books" to "Books",
)

/** A result as returned by novaprinter.py. [engineName] is the plugin that found it. */
@Serializable
data class SearchResult(
    val fileName: String = "",
    val fileUrl: String = "",
    val fileSize: Long = -1,
    val nbSeeders: Long = -1,
    val nbLeechers: Long = -1,
    val siteUrl: String = "",
    val descrLink: String = "",
    val engineName: String = "",
    val pubDate: Long = -1,
)

/** An installed plugin. [source] is the URL it came from, reused for updates. */
@Serializable
data class SearchPlugin(
    val name: String,
    val fullName: String = "",
    val version: String = "",
    val url: String = "",
    val categories: List<String> = emptyList(),
    val enabled: Boolean = true,
    val source: String = "",
)

val appJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** Writes through a temporary file, so a crash never leaves a half-written file. */
internal fun File.writeTextAtomically(text: String) {
    val temp = File("$path.tmp")
    temp.writeText(text)
    if (!temp.renameTo(this)) {
        delete()
        temp.renameTo(this)
    }
}
