package io.github.colombus806.searchqbit.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleQueryTest {
    @Test
    fun `plain title`() {
        assertEquals(SubtitleQuery("The Matrix"), parseSubtitleQuery("  The   Matrix "))
    }

    @Test
    fun `year in parentheses or at the end`() {
        assertEquals(SubtitleQuery("Inception", year = 2010), parseSubtitleQuery("Inception (2010)"))
        assertEquals(SubtitleQuery("Dune Part Two", year = 2024), parseSubtitleQuery("Dune Part Two 2024"))
    }

    @Test
    fun `a title that is only a year stays a title`() {
        assertEquals(SubtitleQuery("1917"), parseSubtitleQuery("1917"))
    }

    @Test
    fun `episode as S01E02 or 1x02`() {
        assertEquals(SubtitleQuery("The Office", season = 2, episode = 3), parseSubtitleQuery("The Office S02E03"))
        assertEquals(SubtitleQuery("breaking bad", season = 1, episode = 2), parseSubtitleQuery("breaking bad s1 e2"))
        assertEquals(SubtitleQuery("Lost", season = 4, episode = 12), parseSubtitleQuery("Lost 4x12"))
    }

    @Test
    fun `pasted file names lose dots and release tags`() {
        assertEquals(
            SubtitleQuery("The Office US", season = 2, episode = 3),
            parseSubtitleQuery("The.Office.US.S02E03.720p.WEB-DL.x264"),
        )
        assertEquals(SubtitleQuery("Inception", year = 2010), parseSubtitleQuery("Inception.2010.1080p.BluRay.x264-GROUP"))
        assertEquals(SubtitleQuery("Arrival"), parseSubtitleQuery("Arrival 1080p WEBRip"))
    }

    @Test
    fun `search URL has sorted lowercase parameters`() {
        val api = "https://api.example.com/api/v1/".toHttpUrl()
        assertEquals(
            "https://api.example.com/api/v1/subtitles?languages=en&query=inception&year=2010",
            searchUrl(api, SubtitleQuery("Inception", year = 2010)).toString(),
        )
        assertEquals(
            "https://api.example.com/api/v1/subtitles?episode_number=3&languages=en&page=2&query=the%20office" +
                "&season_number=2&type=episode",
            searchUrl(api, SubtitleQuery("The Office", season = 2, episode = 3), page = 2).toString(),
        )
    }
}
