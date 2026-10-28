package io.github.colombus806.searchqbit.data

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Headers.Companion.headersOf
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The opensubtitles.com client against a local fake server. */
class OpenSubtitlesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var client: OpenSubtitles

    @Before
    fun setUp() {
        server.start()
        client = OpenSubtitles(OkHttpClient(), folder.newFile("account.json"), "SearchQBit vTest", server.url("/api/v1/"))
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `search sends the key and returns the most downloaded first`() = runBlocking {
        configure()
        server.enqueue(json(SEARCH_ANSWER))

        val results = client.search("Inception (2010)")

        assertEquals(listOf(22L, 11L, 44L), results.map { it.fileId })
        assertEquals("inception.srt", results[0].release)
        assertEquals("Inception (2010)", results[0].feature)
        assertTrue(results[0].hearingImpaired)
        assertEquals("The Office S02E12 · The Injury (2006)", results[2].feature)
        val request = server.takeRequest()
        assertEquals("key123", request.headers["Api-Key"])
        assertEquals("SearchQBit vTest", request.headers["User-Agent"])
        assertNull(request.headers["Authorization"])
        assertEquals("inception", request.url.queryParameter("query"))
        assertEquals("2010", request.url.queryParameter("year"))
        assertEquals("en", request.url.queryParameter("languages"))
    }

    @Test
    fun `every page is read, then sorted by downloads`() = runBlocking {
        configure()
        // As the real API does: page 1 is not sorted, and the most downloaded can be on page 2.
        server.enqueue(json(page(total = 2, 120 to 1, 30 to 2)))
        server.enqueue(json(page(total = 2, 900 to 3)))

        assertEquals(listOf(3L, 1L, 2L), client.search("Inception").map { it.fileId })

        assertNull(server.takeRequest().url.queryParameter("page"))
        assertEquals("2", server.takeRequest().url.queryParameter("page"))
    }

    @Test
    fun `a year that matches nothing is searched again as part of the title`() = runBlocking {
        configure()
        server.enqueue(json("""{"data": []}"""))
        server.enqueue(json(SEARCH_ANSWER))

        assertEquals(3, client.search("Wonder Woman 1984").size)

        assertEquals("1984", server.takeRequest().url.queryParameter("year"))
        val retry = server.takeRequest().url
        assertNull(retry.queryParameter("year"))
        assertEquals("wonder woman 1984", retry.queryParameter("query"))
    }

    @Test
    fun `nothing is sent without an API key`() {
        assertThrows(SubtitleException::class.java) { runBlocking { client.search("Inception") } }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `anonymous download fetches the file and unzips it`() = runBlocking {
        configure()
        server.enqueue(json(downloadAnswer("/files/abc", remaining = 4)))
        server.enqueue(MockResponse.Builder().body(Buffer().write(zipOf("Inception.eng.srt", SRT))).build())

        val result = client.download(subtitle(11))

        assertEquals("Inception.eng.srt", result.file.name)
        assertEquals(SRT.decodeToString(), result.file.bytes.decodeToString())
        assertEquals(4, result.remaining)
        assertEquals("23 hours and 59 minutes", result.resetTime)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/download", request.url.encodedPath)
        assertEquals("""{"file_id":11}""", request.body?.utf8())
        assertNull(request.headers["Authorization"])
        assertEquals("/files/abc", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `used up quota shows the server message`() = runBlocking {
        configure()
        val message = "You have downloaded your allowed 5 subtitles for 24h. Your quota will be renewed in 07 hours."
        server.enqueue(json("""{"requests": 6, "remaining": 0, "message": "$message"}""", code = 406))

        val error = assertThrows(SubtitleException::class.java) { runBlocking { client.download(subtitle(11)) } }
        assertEquals(message, error.message)
    }

    @Test
    fun `signed-in downloads send the token and sign in again when it has expired`() = runBlocking {
        configure(username = "me", password = "secret")
        server.enqueue(json(loginAnswer("token1")))
        server.enqueue(json(downloadAnswer("/files/one", remaining = 99)))
        server.enqueue(MockResponse(body = SRT.decodeToString()))
        server.enqueue(json("""{"message": "token expired"}""", code = 401))
        server.enqueue(json(loginAnswer("token2")))
        server.enqueue(json(downloadAnswer("/files/two", remaining = 98)))
        server.enqueue(MockResponse(body = SRT.decodeToString()))

        assertEquals(99, client.download(subtitle(11)).remaining)
        assertEquals(98, client.download(subtitle(22)).remaining)

        val login = server.takeRequest()
        assertEquals("/api/v1/login", login.url.encodedPath)
        assertEquals("""{"username":"me","password":"secret"}""", login.body?.utf8())
        assertEquals("Bearer token1", server.takeRequest().headers["Authorization"])
        server.takeRequest()
        assertEquals("Bearer token1", server.takeRequest().headers["Authorization"])
        assertEquals("/api/v1/login", server.takeRequest().url.encodedPath)
        assertEquals("Bearer token2", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a 500 invalid token also signs in again`() = runBlocking {
        configure(username = "me", password = "secret")
        server.enqueue(json(loginAnswer("token1")))
        server.enqueue(json("""{"message": "invalid token"}""", code = 500))
        server.enqueue(json(loginAnswer("token2")))
        server.enqueue(json(downloadAnswer("/files/one", remaining = 19)))
        server.enqueue(MockResponse(body = SRT.decodeToString()))

        assertEquals(19, client.download(subtitle(11)).remaining)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `a refused API key gets a clear message`() = runBlocking {
        configure()
        server.enqueue(json("""{"message": "You cannot consume this service"}""", code = 403))

        val error = assertThrows(SubtitleException::class.java) { runBlocking { client.search("Inception") } }
        assertTrue(error.message!!.contains("API key"))
    }

    private fun configure(username: String = "", password: String = "") = runBlocking {
        client.saveAccount(OpenSubtitlesAccount(apiKey = " key123 ", username = username, password = password))
    }

    private fun subtitle(fileId: Long) = Subtitle(fileId = fileId, release = "Inception.2010", feature = "Inception (2010)", downloads = 1)

    private fun json(body: String, code: Int = 200) =
        MockResponse(code = code, headers = headersOf("Content-Type", "application/json"), body = body)

    private fun downloadAnswer(path: String, remaining: Int) = """
        {"link": "${server.url(path)}", "file_name": "Inception.eng.srt", "requests": 1,
         "remaining": $remaining, "message": "", "reset_time": "23 hours and 59 minutes", "reset_time_utc": ""}
    """

    /** A search answer page; each pair is (download count, file id). */
    private fun page(total: Int, vararg subtitles: Pair<Int, Long>) = """
        {"total_pages": $total, "data": [${
            subtitles.joinToString { (downloads, fileId) ->
                """{"attributes": {"download_count": $downloads, "release": "r$fileId", "files": [{"file_id": $fileId}]}}"""
            }
        }]}
    """

    private fun loginAnswer(token: String) = """
        {"user": {"allowed_downloads": 100, "level": "Sub leecher", "user_id": 1, "vip": false},
         "base_url": "${server.hostName}", "token": "$token", "status": 200}
    """

    private fun zipOf(name: String, bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private companion object {
        val SRT = "1\n00:00:01,000 --> 00:00:02,000\nHello\n".toByteArray()

        // Shortened from a real /subtitles answer.
        const val SEARCH_ANSWER = """
            {"total_count": 5, "data": [
              {"id": "1", "type": "subtitle", "attributes": {
                "download_count": 120, "hearing_impaired": false, "from_trusted": true, "ai_translated": false,
                "machine_translated": false, "release": "Inception.2010.720p.BluRay",
                "url": "https://www.opensubtitles.com/en/subtitles/a",
                "feature_details": {"feature_type": "Movie", "year": 2010, "title": "Inception", "movie_name": "2010 - Inception"},
                "files": [{"file_id": 11, "cd_number": 1, "file_name": "Inception.2010.720p.BluRay.srt"}]}},
              {"id": "2", "type": "subtitle", "attributes": {
                "download_count": 5000, "hearing_impaired": true, "from_trusted": null, "release": null,
                "feature_details": {"feature_type": "Movie", "year": 2010, "title": "Inception"},
                "files": [{"file_id": 22, "file_name": "inception.srt"}]}},
              {"id": "3", "type": "subtitle", "attributes": {
                "download_count": 9999, "machine_translated": true, "release": "machine",
                "files": [{"file_id": 33}]}},
              {"id": "4", "type": "subtitle", "attributes": {"download_count": 3, "release": "no files", "files": []}},
              {"id": "5", "type": "subtitle", "attributes": {
                "download_count": 7, "release": "The.Office.S02E12",
                "feature_details": {"feature_type": "Episode", "year": 2006, "title": "The Injury",
                  "parent_title": "The Office", "season_number": 2, "episode_number": 12},
                "files": [{"file_id": 44, "file_name": "The.Office.S02E12.srt"}]}}
            ]}
        """
    }
}
