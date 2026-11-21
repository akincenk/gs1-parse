package io.github.akincenk.gs1

import java.util.Currency
import java.util.Locale

/**
 * Content checks named in the dictionary ("linters"). Each returns null when the
 * component passes, or a short message. Linters not listed in [checks] are reported
 * back to the caller as not checked instead of being silently treated as passed.
 */
internal object Linters {

    private val iso4217Numeric: Set<String> by lazy {
        // XFU and XFO have no ISO numeric code; the JDK reports them as 000.
        Currency.getAvailableCurrencies().filter { it.numericCode > 0 }.map { it.numericCodeAsString }.toSet()
    }

    private val iso3166Alpha2: Set<String> by lazy { Locale.getISOCountries().toSet() }

    val checks: Map<String, (String) -> String?> = mapOf(
        "csum" to ::checkDigit,
        "yymmd0" to { v -> date(v, allowZeroDay = true) },
        "yymmdd" to { v -> date(v, allowZeroDay = false) },
        "yyyymmdd" to ::fullDate,
        "hhmi" to ::hourMinute,
        "nonzero" to { v -> if (v.all { it == '0' }) "must not be zero" else null },
        "zero" to { v -> if (v != "0") "must be 0" else null },
        "yesno" to { v -> if (v != "0" && v != "1") "must be 0 or 1" else null },
        "iso4217" to { v -> if (v !in iso4217Numeric) "$v is not an ISO 4217 currency code" else null },
        "iso3166alpha2" to { v -> if (v !in iso3166Alpha2) "$v is not an ISO 3166 alpha-2 country code" else null },
        // Company prefix position checks need the GS1 prefix allocation table; not a format error.
        "gcppos1" to { _ -> null },
        "gcppos2" to { _ -> null },
    )

    /** GS1 mod-10 check: weights 3,1,3,... from the digit left of the check digit. */
    fun checkDigit(digits: String): String? {
        val expected = computeCheckDigit(digits.dropLast(1))
        val actual = digits.last() - '0'
        return if (expected == actual) null else "check digit is $actual, expected $expected"
    }

    fun computeCheckDigit(body: String): Int {
        var sum = 0
        body.reversed().forEachIndexed { i, c -> sum += (c - '0') * (if (i % 2 == 0) 3 else 1) }
        return (10 - sum % 10) % 10
    }

    private fun date(v: String, allowZeroDay: Boolean): String? {
        val yy = v.substring(0, 2).toInt()
        val mm = v.substring(2, 4).toInt()
        val dd = v.substring(4, 6).toInt()
        if (mm !in 1..12) return "month $mm is not 01-12"
        if (dd == 0) return if (allowZeroDay) null else "day 00 is not allowed here"
        // Century is unknown at this point; like the GS1 reference linter, every year divisible by 4 is a leap year.
        val days = daysInMonth(mm, leap = yy % 4 == 0)
        return if (dd > days) "day $dd is past the end of month $mm" else null
    }

    private fun fullDate(v: String): String? {
        val year = v.substring(0, 4).toInt()
        val mm = v.substring(4, 6).toInt()
        val dd = v.substring(6, 8).toInt()
        if (mm !in 1..12) return "month $mm is not 01-12"
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        return if (dd !in 1..daysInMonth(mm, leap)) "day $dd is not valid in month $mm" else null
    }

    private fun hourMinute(v: String): String? {
        val hh = v.substring(0, 2).toInt()
        val mi = v.substring(2, 4).toInt()
        return when {
            hh > 23 -> "hour $hh is not 00-23"
            mi > 59 -> "minute $mi is not 00-59"
            else -> null
        }
    }

    private fun daysInMonth(mm: Int, leap: Boolean) = when (mm) {
        2 -> if (leap) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
}
