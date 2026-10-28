package io.github.colombus806.searchqbit.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.util.Locale

open class SubtitleException(message: String) : Exception(message)

/** An error answer from the API; [code] is the HTTP status. */
class SubtitleApiException(val code: Int, message: String) : SubtitleException(message)

/** Settings of the Subtitles tab. The account is optional: it only raises the daily download quota. */
@Serializable
data class OpenSubtitlesAccount(
    val apiKey: String = "",
    val username: String = "",
    val password: String = "",
)

/** What the user typed, split into the filters the API understands. */
data class SubtitleQuery(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/** An English subtitle found on OpenSubtitles. [fileId] is what a download asks for. */
data class Subtitle(
    val fileId: Long,
    val release: String,
    /** The movie or episode it belongs to, e.g. "Inception (2010)". */
    val feature: String,
    val downloads: Long,
    val hearingImpaired: Boolean = false,
    val trusted: Boolean = false,
    val aiTranslated: Boolean = false,
    /** Above 1 for subtitles split per CD; only the first file is downloaded. */
    val fileCount: Int = 1,
    val pageUrl: String = "",
)

/** [remaining]: downloads left in the current 24 hours, when the API tells. */
class DownloadedSubtitle(val file: SubtitleFile, val remaining: Int?, val resetTime: String)

/**
 * Client for the opensubtitles.com REST API. Searching only needs the API key; each download counts
 * against a daily quota: 5 per IP without an account, more when signed in.
 */
class OpenSubtitles(
    private val http: OkHttpClient,
    private val accountFile: File,
    private val userAgent: String,
    private val api: HttpUrl = DEFAULT_API,
) {
    private val mutex = Mutex()
    private val _account = MutableStateFlow<OpenSubtitlesAccount?>(null)

    /** `null` until loaded. */
    val account: StateFlow<OpenSubtitlesAccount?> = _account.asStateFlow()

    /** Token returned by /login, kept while the app runs. */
    @Volatile private var session: Session? = null

    suspend fun load() = mutex.withLock {
        withContext(Dispatchers.IO) {
            _account.value = runCatching { appJson.decodeFromString<OpenSubtitlesAccount>(accountFile.readText()) }
                .getOrDefault(OpenSubtitlesAccount())
        }
    }

    suspend fun saveAccount(account: OpenSubtitlesAccount) = mutex.withLock {
        val trimmed = account.copy(apiKey = account.apiKey.trim(), username = account.username.trim())
        withContext(Dispatchers.IO) { accountFile.writeTextAtomically(appJson.encodeToString(trimmed)) }
        session = null
        _account.value = trimmed
    }

    /** English subtitles, most downloaded first. A year that matches nothing is retried as part of the title. */
    suspend fun search(text: String): List<Subtitle> = withContext(Dispatchers.IO) {
        val query = parseSubtitleQuery(text)
        if (query.title.isBlank()) throw SubtitleException("Type the name of a movie or an episode.")
        val results = search(query)
        if (results.isEmpty() && query.year != null) search(query.copy(title = "${query.title} ${query.year}", year = null))
        else results
    }

    /** Uses one download from the quota, then fetches the file and unpacks it if needed. */
    suspend fun download(subtitle: Subtitle): DownloadedSubtitle = withContext(Dispatchers.IO) {
        val answer = try {
            requestDownload(subtitle.fileId, signIn())
        } catch (e: SubtitleApiException) {
            if (e.code != 401 || session == null) throw e
            // The token has expired: sign in again, once.
            session = null
            requestDownload(subtitle.fileId, signIn())
        }
        val url = answer.link.toHttpUrlOrNull() ?: throw SubtitleException("OpenSubtitles returned no download link.")
        val bytes = call(Request.Builder().url(url).header("User-Agent", userAgent).build()) { it.bytes() }
        val file = extractSubtitle(answer.fileName.ifBlank { subtitle.release }, bytes)
        DownloadedSubtitle(file, answer.remaining, answer.resetTime)
    }

    private fun search(query: SubtitleQuery): List<Subtitle> =
        call(apiRequest(searchUrl(api, query)).build()) { parseSearchResponse(it.string()) }

    private fun requestDownload(fileId: Long, session: Session?): DownloadAnswer {
        val body = buildJsonObject { put("file_id", fileId) }.toString().toRequestBody(JSON)
        val request = apiRequest((session?.api ?: api).resolve("download")!!, session?.token).post(body).build()
        return call(request) { appJson.decodeFromString<DownloadAnswer>(it.string()) }
    }

    /** `null` without an account: downloads then use the anonymous quota. */
    private fun signIn(): Session? {
        val account = _account.value ?: return null
        if (account.username.isBlank() || account.password.isEmpty()) return null
        session?.let { return it }
        val body = buildJsonObject {
            put("username", account.username)
            put("password", account.password)
        }.toString().toRequestBody(JSON)
        val login = call(apiRequest(api.resolve("login")!!).post(body).build()) {
            appJson.decodeFromString<LoginAnswer>(it.string())
        }
        // Signed-in users may be sent to another server (VIP): later calls must go there.
        val server = login.baseUrl.takeIf { it.isNotBlank() }
            ?.let { runCatching { api.newBuilder().host(it).build() }.getOrNull() } ?: api
        return Session(login.token, server).also { session = it }
    }

    private fun apiRequest(url: HttpUrl, token: String? = null): Request.Builder {
        val key = _account.value?.apiKey.orEmpty()
        if (key.isBlank()) throw SubtitleException("Enter your OpenSubtitles API key first (settings button).")
        return Request.Builder().url(url)
            .header("Api-Key", key)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
    }

    private fun <T> call(request: Request, read: (ResponseBody) -> T): T = try {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw failure(response)
            read(response.body)
        }
    } catch (e: IOException) {
        throw SubtitleException("Cannot reach OpenSubtitles: ${e.message}")
    } catch (e: SerializationException) {
        throw SubtitleException("Unexpected answer from OpenSubtitles.")
    }

    private fun failure(response: Response): SubtitleApiException {
        val serverMessage = runCatching {
            appJson.parseToJsonElement(response.body.string()).jsonObject["message"]?.jsonPrimitive?.content
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val code = response.code
        val message = when {
            code == 401 -> "OpenSubtitles refused the sign-in: check the username and password in the settings."
            code == 403 -> "OpenSubtitles refused the API key: check it in the settings."
            code == 429 -> "Too many requests: wait a few seconds and try again."
            // 406: daily quota used up; the server message says when it is renewed.
            serverMessage != null && code < 500 -> serverMessage
            code >= 500 -> "OpenSubtitles is unavailable right now (HTTP error $code)."
            else -> "OpenSubtitles error (HTTP $code)."
        }
        return SubtitleApiException(code, message)
    }

    private class Session(val token: String, val api: HttpUrl)

    companion object {
        val DEFAULT_API = "https://api.opensubtitles.com/api/v1/".toHttpUrl()
        private val JSON = "application/json".toMediaType()
    }
}

/** Search URL with parameters sorted and in lowercase, as the API asks (otherwise it redirects). */
internal fun searchUrl(api: HttpUrl, query: SubtitleQuery): HttpUrl {
    val params = sortedMapOf(
        "languages" to "en",
        "order_by" to "download_count",
        "order_direction" to "desc",
        "query" to query.title.lowercase(Locale.ROOT),
    )
    query.year?.let { params["year"] = it.toString() }
    if (query.season != null) {
        params["season_number"] = query.season.toString()
        params["type"] = "episode"
    }
    query.episode?.let { params["episode_number"] = it.toString() }
    return api.resolve("subtitles")!!.newBuilder()
        .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
        .build()
}

private val EPISODE = Regex("""\bs(\d{1,2})[ .-]?e(\d{1,3})\b|\b(\d{1,2})x(\d{2,3})\b""", RegexOption.IGNORE_CASE)
private val YEAR = Regex("""^(.+?)[\s(\[]+((?:19|20)\d{2})[)\]]?(?:\s.*)?$""")
private val RELEASE_TAG = Regex(
    """\b(2160p|1080p|720p|480p|bluray|blu-ray|brrip|bdrip|web-?dl|webrip|hdtv|dvdrip|x264|x265|h264|h265|hevc|remux|repack)\b.*""",
    RegexOption.IGNORE_CASE,
)

/**
 * "The Office S02E03" -> season 2, episode 3; "Inception (2010)" -> year 2010. A pasted file name
 * also works: dots separate words and the release tags (1080p, WEB-DL…) are dropped.
 */
internal fun parseSubtitleQuery(text: String): SubtitleQuery {
    val trimmed = text.trim()
    val spaced = if (' ' in trimmed) trimmed else trimmed.replace('.', ' ')
    val words = spaced.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
    EPISODE.find(words)?.let { match ->
        val (season, episode, altSeason, altEpisode) = match.destructured
        val title = words.substring(0, match.range.first).trimSeparators()
            .ifEmpty { words.substring(match.range.last + 1).replace(RELEASE_TAG, "").trimSeparators() }
        return SubtitleQuery(title, season = season.ifEmpty { altSeason }.toInt(), episode = episode.ifEmpty { altEpisode }.toInt())
    }
    val withoutTags = words.replace(RELEASE_TAG, "").trimSeparators().ifEmpty { words }
    YEAR.find(withoutTags)?.let { match ->
        val (title, year) = match.destructured
        return SubtitleQuery(title.trimSeparators(), year = year.toInt())
    }
    return SubtitleQuery(withoutTags)
}

private fun String.trimSeparators() = trim(' ', '-', ':', ',', '(', '[')

internal fun parseSearchResponse(json: String): List<Subtitle> =
    appJson.decodeFromString<SearchAnswer>(json).data
        .mapNotNull { it.attributes.toSubtitle() }
        .sortedByDescending { it.downloads }

private fun SubtitleAttributes.toSubtitle(): Subtitle? {
    if (machineTranslated) return null
    val file = files.firstOrNull() ?: return null
    val feature = feature.label()
    return Subtitle(
        fileId = file.fileId,
        release = release.ifBlank { file.fileName }.ifBlank { feature },
        feature = feature,
        downloads = downloadCount,
        hearingImpaired = hearingImpaired,
        trusted = fromTrusted,
        aiTranslated = aiTranslated,
        fileCount = files.size,
        pageUrl = url,
    )
}

private fun FeatureDetails.label(): String = buildString {
    if (episodeNumber > 0 && parentTitle.isNotBlank()) {
        append(parentTitle)
        append(String.format(Locale.US, " S%02dE%02d", seasonNumber, episodeNumber))
        if (title.isNotBlank() && !title.equals(parentTitle, ignoreCase = true)) append(" · ").append(title)
    } else {
        append(title.ifBlank { movieName })
        if (year > 0) append(" ($year)")
    }
}

@Serializable
private class SearchAnswer(val data: List<SearchItem> = emptyList())

@Serializable
private class SearchItem(val attributes: SubtitleAttributes = SubtitleAttributes())

@Serializable
private class SubtitleAttributes(
    @SerialName("download_count") val downloadCount: Long = 0,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean = false,
    @SerialName("from_trusted") val fromTrusted: Boolean = false,
    @SerialName("ai_translated") val aiTranslated: Boolean = false,
    @SerialName("machine_translated") val machineTranslated: Boolean = false,
    val release: String = "",
    val url: String = "",
    @SerialName("feature_details") val feature: FeatureDetails = FeatureDetails(),
    val files: List<SubtitleFileRef> = emptyList(),
)

@Serializable
private class FeatureDetails(
    val title: String = "",
    @SerialName("movie_name") val movieName: String = "",
    val year: Int = 0,
    @SerialName("parent_title") val parentTitle: String = "",
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_number") val episodeNumber: Int = 0,
)

@Serializable
private class SubtitleFileRef(
    @SerialName("file_id") val fileId: Long,
    @SerialName("file_name") val fileName: String = "",
)

@Serializable
private class DownloadAnswer(
    val link: String = "",
    @SerialName("file_name") val fileName: String = "",
    val remaining: Int? = null,
    @SerialName("reset_time") val resetTime: String = "",
)

@Serializable
private class LoginAnswer(
    val token: String,
    @SerialName("base_url") val baseUrl: String = "",
)
