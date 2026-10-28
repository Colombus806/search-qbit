package io.github.colombus806.searchqbit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginNamesTest {
    @Test
    fun `plugin name comes from the py file name`() {
        assertEquals("piratebay", pluginName("piratebay.py"))
        assertEquals("one337x", pluginName("https://example.org/engines/one337x.py?raw=1"))
        assertEquals("torrents_csv", pluginName("torrents_csv.py"))
    }

    @Test
    fun `names that are not python modules are refused`() {
        assertNull(pluginName("piratebay (1).py"))
        assertNull(pluginName("1337x.py"))
        assertNull(pluginName("readme.txt"))
        assertNull(pluginName("__init__.py"))
    }

    @Test
    fun `github page links become raw links`() {
        assertEquals(
            "https://raw.githubusercontent.com/someone/plugins/master/nova3/engines/demo.py",
            rawPluginUrl("https://github.com/someone/plugins/blob/master/nova3/engines/demo.py"),
        )
        val raw = "https://raw.githubusercontent.com/someone/plugins/master/demo.py"
        assertEquals(raw, rawPluginUrl(raw))
    }
}
