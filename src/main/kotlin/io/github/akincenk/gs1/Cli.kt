package io.github.akincenk.gs1

import java.io.PrintStream
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.system.exitProcess

private const val USAGE = """Usage: gs1-parse [options] [DATA ...]

Parses GS1 element strings from GS1-128, GS1 DataMatrix, GS1 QR and DataBar scans.
DATA is raw scanner output (GS character as separator, optional ]C1 / ]d2 / ]Q3 / ]e0 prefix)
or the bracketed form "(01)09506000134352(17)261231(10)AB12".
With no DATA, reads one scan per line from standard input.

Options:
  --json         print one JSON object per scan
  --sep C        treat character C as the separator, for scanners that do not send GS
  --today DATE   reference date (YYYY-MM-DD) for two-digit years, default today
  --no-associations
                 skip AI pairing rules such as "(02) needs (37)"; use when a scan
                 is only one of several barcodes on the same item
  -h, --help     show this help

Exit status: 0 all scans valid, 1 at least one scan invalid, 2 usage error.
"""

fun main(args: Array<String>) {
    exitProcess(runCli(args.toList(), generateSequence(::readLine), System.out, System.err))
}

/** Runs the command line tool and returns the exit status. Separated from [main] for tests. */
fun runCli(args: List<String>, stdin: Sequence<String>, out: PrintStream, err: PrintStream): Int {
    var json = false
    var options = ParseOptions()
    val inputs = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "-h", "--help" -> {
                out.print(USAGE)
                return 0
            }
            "--json" -> json = true
            "--no-associations" -> options = options.copy(checkAssociations = false)
            "--sep", "--today" -> {
                val v = args.getOrNull(++i) ?: return usage(err, "$a needs a value")
                if (a == "--sep") {
                    if (v.length != 1) return usage(err, "--sep takes a single character")
                    options = options.copy(separator = v[0])
                } else {
                    val d = try {
                        LocalDate.parse(v)
                    } catch (e: DateTimeParseException) {
                        return usage(err, "--today must be YYYY-MM-DD")
                    }
                    options = options.copy(today = d)
                }
            }
            "--" -> {
                inputs += args.drop(i + 1)
                i = args.size
            }
            else -> if (a.startsWith("-") && a.length > 1) return usage(err, "unknown option $a") else inputs += a
        }
        i++
    }

    val scans = if (inputs.isEmpty()) stdin.filter { it.isNotBlank() } else inputs.asSequence()
    var allValid = true
    var first = true
    for (scan in scans) {
        val result = Gs1.parse(scan, options)
        allValid = allValid && result.isValid
        if (json) {
            out.println(toJson(result))
        } else {
            if (!first) out.println()
            printText(scan, result, out)
        }
        first = false
    }
    return if (allValid) 0 else 1
}

private fun usage(err: PrintStream, message: String): Int {
    err.println("gs1-parse: $message")
    err.println("Try gs1-parse --help")
    return 2
}

private fun printText(scan: String, r: Gs1Result, out: PrintStream) {
    out.println("scan: ${visible(scan)}")
    for (e in r.elements) {
        val shown = e.value?.let(::describe) ?: e.raw
        out.println("  (${e.ai}) ${e.title}: $shown")
        if (e.notChecked.isNotEmpty()) out.println("      not checked: ${e.notChecked.joinToString()}")
    }
    for (w in r.warnings) out.println("  warning at ${w.position}: ${w.message}")
    for (e in r.errors) out.println("  error at ${e.position}: ${e.message}")
    out.println(if (r.isValid) "  valid" else "  INVALID")
}

private fun describe(v: Gs1Value): String = when (v) {
    is Gs1Value.Text -> v.text
    is Gs1Value.Date -> if (v.dayOmitted) "${v.date} (no day given, end of month)" else v.date.toString()
    is Gs1Value.DateTime -> v.dateTime.toString()
    is Gs1Value.Count -> v.count.toString()
    is Gs1Value.Decimal -> listOfNotNull(v.value.toPlainString(), v.unit).joinToString(" ")
    is Gs1Value.Money -> "${v.amount.toPlainString()} ${v.currency?.currencyCode ?: v.currencyCode}"
}

/** Shows control characters, so a GS in the input is visible as <GS>. */
private fun visible(s: String) = buildString {
    for (c in s) when {
        c == Gs1.GS -> append("<GS>")
        c < ' ' || c == '\u007F' -> append("<0x%02X>".format(c.code))
        else -> append(c)
    }
}

internal fun toJson(r: Gs1Result): String = buildString {
    append("{\"valid\":").append(r.isValid)
    append(",\"elements\":[")
    r.elements.forEachIndexed { i, e ->
        if (i > 0) append(',')
        append("{\"ai\":").append(str(e.ai))
        append(",\"title\":").append(str(e.title))
        append(",\"raw\":").append(str(e.raw))
        e.value?.let { appendValue(it) }
        if (e.notChecked.isNotEmpty()) append(",\"notChecked\":[").append(e.notChecked.joinToString(",") { str(it) }).append(']')
        append('}')
    }
    append("],\"errors\":").append(issues(r.errors))
    append(",\"warnings\":").append(issues(r.warnings))
    append('}')
}

private fun StringBuilder.appendValue(v: Gs1Value) {
    when (v) {
        is Gs1Value.Text -> append(",\"type\":\"text\"")
        is Gs1Value.Date -> append(",\"type\":\"date\",\"value\":").append(str(v.date.toString()))
            .append(",\"dayOmitted\":").append(v.dayOmitted)
        is Gs1Value.DateTime -> append(",\"type\":\"datetime\",\"value\":").append(str(v.dateTime.toString()))
        is Gs1Value.Count -> append(",\"type\":\"count\",\"value\":").append(v.count)
        // Decimals as strings so trailing zeros and precision survive any JSON reader.
        is Gs1Value.Decimal -> {
            append(",\"type\":\"decimal\",\"value\":").append(str(v.value.toPlainString()))
            v.unit?.let { append(",\"unit\":").append(str(it)) }
        }
        is Gs1Value.Money -> {
            append(",\"type\":\"money\",\"value\":").append(str(v.amount.toPlainString()))
            append(",\"currencyNumeric\":").append(str(v.currencyCode))
            v.currency?.let { append(",\"currency\":").append(str(it.currencyCode)) }
        }
    }
}

private fun issues(list: List<Gs1Issue>) = list.joinToString(",", "[", "]") {
    "{\"position\":${it.position},\"ai\":${it.ai?.let(::str) ?: "null"},\"message\":${str(it.message)}}"
}

private fun str(s: String) = buildString {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
}
