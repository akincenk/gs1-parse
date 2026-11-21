package io.github.akincenk.gs1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DictionaryTest {
    private val dict = Gs1Dictionary.bundled

    @Test
    fun `bundled dictionary loads every entry`() {
        assertTrue(dict.size > 500, "expected the full AI list, got ${dict.size}")
    }

    @Test
    fun `fixed length AI with check digit`() {
        val gtin = assertNotNull(dict["01"])
        assertEquals("GTIN", gtin.title)
        assertTrue(gtin.predefinedLength)
        assertEquals(14, gtin.fixedLength)
        assertEquals(listOf("csum", "gcppos2"), gtin.components.single().linters)
    }

    @Test
    fun `variable length AI`() {
        val batch = assertNotNull(dict["10"])
        assertFalse(batch.predefinedLength)
        assertNull(batch.fixedLength)
        assertEquals(20, batch.maxLength)
        assertEquals(CharSet.X, batch.components.single().charSet)
    }

    @Test
    fun `ranges expand to every AI`() {
        for (ai in listOf("3100", "3103", "3105")) assertEquals("NET WEIGHT (kg)", dict[ai]?.title)
        assertNull(dict["3106"])
        for (ai in 91..99) assertNotNull(dict[ai.toString()])
    }

    @Test
    fun `optional components and linters after the bracket`() {
        val gdti = assertNotNull(dict["253"])
        assertEquals(2, gdti.components.size)
        assertTrue(gdti.components[1].optional)
        assertNull(gdti.fixedLength)

        val country = assertNotNull(dict["423"])
        assertEquals(5, country.components.size)
        assertEquals(listOf("iso3166"), country.components[4].linters)
        assertTrue(country.components[4].optional)
    }

    @Test
    fun `fixed length AI outside the predefined table needs a separator`() {
        val origin = assertNotNull(dict["422"])
        assertEquals(3, origin.fixedLength)
        assertFalse(origin.predefinedLength)
    }

    @Test
    fun `matchAt finds two three and four digit AIs`() {
        val data = "01095060001343523103001250"
        assertEquals("01", dict.matchAt(data, 0)?.ai)
        assertEquals("3103", dict.matchAt(data, 16)?.ai)
        assertEquals("422", dict.matchAt("422124", 0)?.ai)
        assertNull(dict.matchAt("23", 0))
    }

    @Test
    fun `bad dictionary text reports the line`() {
        val e = assertFailsWith<IllegalArgumentException> { Gs1Dictionary.parse("# c\n01 *? Q14 # bad\n") }
        assertTrue(e.message!!.startsWith("dictionary line 2"), e.message)
        assertFailsWith<IllegalArgumentException> { Gs1Dictionary.parse("10 X..20\n10 X..20\n") }
    }
}
