package io.github.akincenk.gs1

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {
    private class Run(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String, stdin: List<String> = emptyList()): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(listOf("--today", "2026-11-21") + args, stdin.asSequence(), PrintStream(out, true), PrintStream(err, true))
        return Run(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `text output for a valid scan`() {
        val r = run("]C10109506000134352" + "17280200" + "3103001250" + "10AB12" + Gs1.GS + "21X1")
        assertEquals(0, r.code)
        assertEquals(
            """
            scan: ]C1010950600013435217280200310300125010AB12<GS>21X1
              (01) GTIN: 09506000134352
              (17) USE BY or EXPIRY: 2028-02-29 (no day given, end of month)
              (3103) NET WEIGHT (kg): 1.250 kg
              (10) BATCH/LOT: AB12
              (21) SERIAL: X1
              valid
            """.trimIndent() + "\n",
            r.out,
        )
    }

    @Test
    fun `invalid scan exits 1 and says why`() {
        val r = run("(01)09506000134353")
        assertEquals(1, r.code)
        assertTrue("error at 4: (01) check digit is 3, expected 2" in r.out, r.out)
        assertTrue(r.out.trimEnd().endsWith("INVALID"))
    }

    @Test
    fun `json output`() {
        val r = run("--json", "--no-associations", "(01)09506000134352(3912)1241999(422)124")
        assertEquals(0, r.code)
        assertEquals(
            """{"valid":true,"elements":[""" +
                """{"ai":"01","title":"GTIN","raw":"09506000134352","type":"text"},""" +
                """{"ai":"3912","title":"AMOUNT","raw":"1241999","type":"money","value":"19.99","currencyNumeric":"124","currency":"CAD"},""" +
                """{"ai":"422","title":"ORIGIN","raw":"124","type":"text","notChecked":["iso3166"]}""" +
                """],"errors":[],"warnings":[]}""" + "\n",
            r.out,
        )
    }

    @Test
    fun `reads stdin line by line and skips blank lines`() {
        val r = run("--json", stdin = listOf("(10)A", "", "(01)1"))
        assertEquals(1, r.code)
        assertEquals(2, r.out.lines().count { it.isNotEmpty() })
    }

    @Test
    fun `custom separator`() {
        val r = run("--sep", "|", "010950600013435210AB|21X")
        assertEquals(0, r.code)
        assertTrue("(21) SERIAL: X" in r.out)
    }

    @Test
    fun `pairing errors and the option to skip them`() {
        val scan = "]C1" + "0210614141000453" + "15270115"
        assertEquals(1, run(scan).code)
        assertTrue("error at 5: (02) needs (37) in the same data" in run(scan).out)
        assertEquals(0, run("--no-associations", scan).code)
    }

    @Test
    fun `usage errors exit 2`() {
        assertEquals(2, run("--bogus").code)
        assertEquals(2, run("--sep").code)
        assertEquals(2, run("--sep", "ab", "x").code)
        assertEquals(2, run("--today", "21/11/2026", "x").code)
        assertEquals(0, run("--help").code)
    }

    @Test
    fun `control characters are escaped in json`() {
        val r = run("--json", "10A" + Gs1.GS + "\u0001")
        assertTrue("\\u0001" in r.out, r.out)
    }
}
