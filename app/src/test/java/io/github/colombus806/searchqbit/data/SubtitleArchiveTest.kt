package io.github.colombus806.searchqbit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubtitleArchiveTest {
    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n".toByteArray()

    @Test
    fun `a bare subtitle is kept as is`() {
        val file = extractSubtitle("Movie.2010.srt", srt)
        assertEquals("Movie.2010.srt", file.name)
        assertEquals(srt.toList(), file.bytes.toList())
    }

    @Test
    fun `a zip gives its largest subtitle file`() {
        val zip = zipOf(
            "readme.nfo" to "ignored".toByteArray(),
            "subs/short.srt" to "1".toByteArray(),
            "subs/Movie.2010.eng.srt" to srt,
        )
        val file = extractSubtitle("download.zip", zip)
        assertEquals("Movie.2010.eng.srt", file.name)
        assertEquals(srt.toList(), file.bytes.toList())
    }

    @Test
    fun `a zip without subtitles is an error`() {
        assertThrows(SubtitleException::class.java) {
            extractSubtitle("download.zip", zipOf("readme.nfo" to "nothing".toByteArray()))
        }
    }

    @Test
    fun `a gzip file is unpacked`() {
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(srt) } }.toByteArray()
        val file = extractSubtitle("Movie.srt.gz", gz)
        assertEquals("Movie.srt", file.name)
        assertEquals(srt.toList(), file.bytes.toList())
    }

    @Test
    fun `file names are made safe and get an extension`() {
        assertEquals("a_b_c.srt", safeSubtitleName("a/b:c"))
        assertEquals("Show S01E02.ass", safeSubtitleName("Show S01E02.ass"))
        assertEquals("subtitles.srt", safeSubtitleName("  "))
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
