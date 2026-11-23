package io.github.akincenk.gs1

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {
    private val gs = Gs1.GS
    private val opts = ParseOptions(today = LocalDate.of(2026, 11, 21))
    private fun parse(s: String) = Gs1.parse(s, opts)

    @Test
    fun `GS1-128 scan with symbology identifier and separator`() {
        val r = parse("]C10109506000134352" + "17261231" + "10AB-12" + gs + "21S0042")
        assertTrue(r.isValid, r.errors.toString())
        assertEquals(listOf("01", "17", "10", "21"), r.elements.map { it.ai })
        assertEquals(Gs1Value.Text("09506000134352"), r["01"]?.value)
        assertEquals(Gs1Value.Date(LocalDate.of(2026, 12, 31)), r["17"]?.value)
        assertEquals("AB-12", r["10"]?.raw)
        assertEquals("S0042", r["21"]?.raw)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun `bracketed form gives the same elements`() {
        val raw = parse("0109506000134352" + "17261231" + "10AB-12" + gs + "21S0042")
        val bracketed = parse("(01)09506000134352(17)261231(10)AB-12(21)S0042")
        assertEquals(raw.elements, bracketed.elements)
    }

    @Test
    fun `escaped bracket in bracketed form is data`() {
        val r = parse("(01)09506000134352(10)A\\(1)")
        assertTrue(r.isValid, r.errors.toString())
        assertEquals("A(1)", r["10"]?.raw)
    }

    @Test
    fun `wrong check digit is an error and leaves the value empty`() {
        val r = parse("0109506000134353")
        assertFalse(r.isValid)
        assertEquals("(01) check digit is 3, expected 2", r.errors.single().message)
        assertNull(r["01"]?.value)
    }

    @Test
    fun `positions count the symbology identifier`() {
        val r = parse("]C10109506000134353")
        assertEquals(5, r.errors.single().position)
        assertEquals(2, parse("0109506000134353").errors.single().position)
    }

    @Test
    fun `SSCC on a pallet label`() {
        val r = parse("]C100106141411234567897")
        assertTrue(r.isValid, r.errors.toString())
        assertEquals("SSCC", r["00"]?.title)
    }

    @Test
    fun `weight with implied decimal point`() {
        val r = parse("0109506000134352" + "3103001250")
        assertEquals(Gs1Value.Decimal(BigDecimal("1.250"), "kg"), r["3103"]?.value)
    }

    @Test
    fun `amount with currency`() {
        val r = parse("(8020)INV7(415)5412345000013(3912)124" + "1999")
        assertTrue(r.isValid, r.errors.toString())
        val money = r["3912"]?.value as Gs1Value.Money
        assertEquals(BigDecimal("19.99"), money.amount)
        assertEquals("124", money.currencyCode)
        assertEquals("CAD", money.currency?.currencyCode)
    }

    @Test
    fun `unknown currency code fails the iso4217 check`() {
        val r = parse("(3912)0001999")
        assertEquals("(3912) 000 is not an ISO 4217 currency code", r.errors.single().message)
    }

    @Test
    fun `day 00 means end of month`() {
        val r = parse("0109506000134352" + "17280200")
        assertEquals(Gs1Value.Date(LocalDate.of(2028, 2, 29), dayOmitted = true), r["17"]?.value)
    }

    @Test
    fun `day 00 is not allowed for yymmdd AIs`() {
        val r = parse("(01)09506000134352(7003)2612000930")
        assertEquals("(7003) day 00 is not allowed here", r.errors.single().message)
    }

    @Test
    fun `date and time`() {
        val r = parse("(01)09506000134352(7003)2612311830")
        assertEquals(Gs1Value.DateTime(LocalDateTime.of(2026, 12, 31, 18, 30)), r["7003"]?.value)
    }

    @Test
    fun `impossible dates are errors`() {
        assertEquals("(17) month 13 is not 01-12", parse("17261301").errors.single().message)
        assertEquals("(17) day 30 is past the end of month 2", parse("17270230").errors.single().message)
        assertTrue(parse("17280229").isValid)
    }

    @Test
    fun `century follows the GS1 sliding window`() {
        val today = LocalDate.of(2026, 11, 21)
        assertEquals(2076, Values.resolveYear(76, today))
        assertEquals(1977, Values.resolveYear(77, today))
        assertEquals(2000, Values.resolveYear(0, today))
        assertEquals(2101, Values.resolveYear(1, LocalDate.of(2099, 6, 1)))
        assertEquals(1999, Values.resolveYear(99, LocalDate.of(2001, 6, 1)))
    }

    @Test
    fun `counts are numbers`() {
        val r = parse("(00)106141411234567897(02)00614141999996(37)48")
        assertTrue(r.isValid, r.errors.toString())
        assertEquals(Gs1Value.Count(48), r["37"]?.value)
    }

    @Test
    fun `missing separator after a fixed length AI outside the predefined table is a warning`() {
        val r = parse("0109506000134352" + "422124" + "10LOT1")
        assertTrue(r.isValid, r.errors.toString())
        assertEquals("(422) should be followed by a separator (FNC1)", r.warnings.single().message)
        assertEquals(listOf("iso3166"), r["422"]?.notChecked)
        assertEquals("LOT1", r["10"]?.raw)
    }

    @Test
    fun `missing separator after variable length data swallows the next AI`() {
        // Without a separator, "17261231" becomes part of the batch number. Nothing can detect this,
        // but the result is still a valid batch, so callers must not trust a missing (17).
        val r = parse("0109506000134352" + "10AB12" + "17261231")
        assertEquals("AB1217261231", r["10"]?.raw)
        assertNull(r["17"])
    }

    @Test
    fun `too long variable data is an error`() {
        val r = parse("10" + "A".repeat(21))
        assertEquals("(10) is 21 characters, at most 20 allowed", r.errors.single().message)
    }

    @Test
    fun `character outside set 82 is an error`() {
        val r = parse("10AB#1")
        assertEquals("(10) character '#' is not allowed (GS1 character set 82)", r.errors.single().message)
    }

    @Test
    fun `truncated fixed data is an error`() {
        val r = parse("01095060001343")
        assertEquals("(01) needs 14 characters, got 12", r.errors.single().message)
    }

    @Test
    fun `separator inside fixed data is an error`() {
        val r = parse("0109506000$gs" + "134352")
        assertEquals("(01) needs 14 characters, got 8", r.errors.single().message)
    }

    @Test
    fun `unknown AI stops parsing`() {
        val r = parse("0109506000134352" + "23123")
        assertEquals("no known AI at \"2312\"", r.errors.single().message)
        assertEquals(16, r.errors.single().position)
        assertEquals(1, r.elements.size)
    }

    @Test
    fun `same AI twice with different values`() {
        val r = parse("(10)A(10)B")
        assertEquals("(10) appears more than once with different values", r.errors.single().message)
        assertTrue(parse("(10)A(10)A").isValid)
    }

    @Test
    fun `non GS1 symbology identifier`() {
        val r = parse("]C0ABC")
        assertEquals("symbology identifier ]C0 does not mark GS1 data", r.errors.single().message)
    }

    @Test
    fun `scanner that sends another separator character`() {
        val r = Gs1.parse("10AB12|17261231", opts.copy(separator = '|'))
        assertTrue(r.isValid, r.errors.toString())
        assertEquals("AB12", r["10"]?.raw)
    }

    @Test
    fun `optional component may be left out`() {
        assertTrue(parse("(253)5412345000013").isValid)
        assertTrue(parse("(253)5412345000013ABC").isValid)
    }

    @Test
    fun `empty input`() {
        assertEquals("no data", parse("").errors.single().message)
        assertEquals("no data", parse("]d2").errors.single().message)
    }

    @Test
    fun `lost separator after a batch is a warning, the data stays as scanned`() {
        val r = parse("]C10109506000134352" + "10AB12" + "15270115")
        assertTrue(r.isValid, r.errors.toString())
        assertEquals("AB1215270115", r["10"]?.raw)
        val w = r.warnings.single()
        assertEquals("(10) may contain (15) 270115; was a separator lost?", w.message)
        assertEquals("10", w.ai)
        assertEquals(25, w.position)
    }

    @Test
    fun `lost separator before several fixed-length elements lists all of them`() {
        val r = parse("]d201095060001340172" + "1SN000183" + "17280531" + "15280430")
        assertEquals(
            "(21) may contain (17) 280531 (15) 280430; was a separator lost?",
            r.warnings.single().message,
        )
    }

    @Test
    fun `no swallowed-AI warning for ordinary values`() {
        // Too short to hold an AI plus data.
        assertTrue(parse("0109506000134352" + "101234").warnings.isEmpty())
        // Tail would be (15) with month 99, which is not a date.
        assertTrue(parse("0109506000134352" + "10AB1215279915").warnings.isEmpty())
        // Tail starts with a variable-length AI (21); too ambiguous to report.
        assertTrue(parse("0109506000134352" + "10AB21XYZ").warnings.isEmpty())
        // Tails that start with an AI without a date or check digit test, here (20) and (3103).
        assertTrue(parse("0109506000134352" + "10AB2057").warnings.isEmpty())
        assertTrue(parse("0109506000134352" + "10AB3103001250").warnings.isEmpty())
        // Separator present: nothing swallowed.
        assertTrue(parse("0109506000134352" + "10AB12" + gs + "15270115").warnings.isEmpty())
        // Bracketed form has explicit boundaries.
        assertTrue(parse("(01)09506000134352(10)AB1215270115").warnings.isEmpty())
    }
}
