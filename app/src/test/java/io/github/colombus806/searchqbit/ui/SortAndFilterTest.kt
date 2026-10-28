package io.github.colombus806.searchqbit.ui

import io.github.colombus806.searchqbit.data.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SortAndFilterTest {
    private val results = listOf(
        SearchResult(fileName = "Ubuntu 24.04 desktop", fileSize = 6_000, nbSeeders = 50),
        SearchResult(fileName = "debian 13 netinst", fileSize = 700, nbSeeders = 120),
        SearchResult(fileName = "Ubuntu 24.04 server", fileSize = 3_000, nbSeeders = 8),
    )

    private fun names(list: List<SearchResult>) = list.map { it.fileName }

    @Test
    fun `seeders descending by default`() {
        assertEquals(
            listOf("debian 13 netinst", "Ubuntu 24.04 desktop", "Ubuntu 24.04 server"),
            names(sortAndFilter(results, SortOrder.SEEDERS, descending = true, filter = "")),
        )
    }

    @Test
    fun `size and name orders`() {
        assertEquals(
            listOf("Ubuntu 24.04 desktop", "Ubuntu 24.04 server", "debian 13 netinst"),
            names(sortAndFilter(results, SortOrder.SIZE, descending = true, filter = "")),
        )
        assertEquals(
            listOf("debian 13 netinst", "Ubuntu 24.04 desktop", "Ubuntu 24.04 server"),
            names(sortAndFilter(results, SortOrder.NAME, descending = false, filter = "")),
        )
    }

    @Test
    fun `filter keeps results containing every word, ignoring case`() {
        assertEquals(
            listOf("Ubuntu 24.04 server"),
            names(sortAndFilter(results, SortOrder.SEEDERS, descending = true, filter = "  ubuntu SERVER ")),
        )
    }

    @Test
    fun `sizes are formatted with binary units`() {
        assertEquals("unknown size", formatSize(-1))
        assertEquals("512 B", formatSize(512))
        assertEquals("1.5 GB", formatSize(1_610_612_736))
    }
}
