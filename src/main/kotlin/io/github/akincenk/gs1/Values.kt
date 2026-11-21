package io.github.akincenk.gs1

import java.math.BigDecimal
import java.math.BigInteger
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.util.Currency

/** Typed value of one element. Anything without a more specific type is [Text]. */
sealed interface Gs1Value {
    data class Text(val text: String) : Gs1Value

    /** A date. When the scan had day 00 ("month only"), [date] is the last day of that month and [dayOmitted] is true. */
    data class Date(val date: LocalDate, val dayOmitted: Boolean = false) : Gs1Value

    data class DateTime(val dateTime: LocalDateTime) : Gs1Value

    /** A count from AI (30) or (37). */
    data class Count(val count: Long) : Gs1Value

    /** A measure, price or percentage with the implied decimal point applied. [unit] comes from the AI title, e.g. "kg". */
    data class Decimal(val value: BigDecimal, val unit: String?) : Gs1Value

    /** An amount with an ISO 4217 currency, from AIs 391n and 393n. [currency] is null if the JDK does not know the code. */
    data class Money(val amount: BigDecimal, val currencyCode: String, val currency: Currency?) : Gs1Value
}

internal object Values {

    private val UNIT = Regex("""\(([^)]+)\)""")

    /**
     * Converts a valid raw value. [today] fixes the century of two-digit years.
     * A date that does not exist in the resolved century (29 February 2100) is reported
     * through [onError] and returned as [Gs1Value.Text].
     */
    fun convert(def: AiDefinition, raw: String, today: LocalDate, onError: (String) -> Unit): Gs1Value {
        val ai = def.ai
        val comps = def.components
        val firstLinters = comps.first().linters

        if (ai == "30" || ai == "37") return Gs1Value.Count(raw.toLong())

        if (ai.length == 4 && ai[0] == '3') {
            val scale = ai[3] - '0'
            if ("iso4217" in firstLinters) {
                val code = raw.substring(0, 3)
                val currency = Currency.getAvailableCurrencies().firstOrNull { it.numericCode > 0 && it.numericCodeAsString == code }
                return Gs1Value.Money(BigDecimal(BigInteger(raw.substring(3)), scale), code, currency)
            }
            val unit = UNIT.find(def.title)?.groupValues?.get(1)
            return Gs1Value.Decimal(BigDecimal(BigInteger(raw), scale), unit)
        }

        val isDate = "yymmd0" in firstLinters || "yymmdd" in firstLinters
        // Covers single dates and AIs whose optional parts were left out, e.g. (7011) with no time.
        if (isDate && raw.length == 6) {
            return date(raw, today, onError) ?: Gs1Value.Text(raw)
        }
        if (isDate && comps.size == 2 && "hhmi" in comps[1].linters && raw.length == 10) {
            val d = date(raw.substring(0, 6), today, onError) ?: return Gs1Value.Text(raw)
            val time = LocalTime.of(raw.substring(6, 8).toInt(), raw.substring(8, 10).toInt())
            return Gs1Value.DateTime(d.date.atTime(time))
        }
        return Gs1Value.Text(raw)
    }

    private fun date(yymmdd: String, today: LocalDate, onError: (String) -> Unit): Gs1Value.Date? {
        val year = resolveYear(yymmdd.substring(0, 2).toInt(), today)
        val month = yymmdd.substring(2, 4).toInt()
        val day = yymmdd.substring(4, 6).toInt()
        return try {
            if (day == 0) Gs1Value.Date(YearMonth.of(year, month).atEndOfMonth(), dayOmitted = true)
            else Gs1Value.Date(LocalDate.of(year, month, day))
        } catch (e: DateTimeException) {
            onError("date $yymmdd does not exist in $year")
            null
        }
    }

    /**
     * GS1 General Specifications, "Determination of century in dates": a two-digit year is
     * placed in the century that keeps it within 49 years before or 50 years after [today].
     */
    fun resolveYear(yy: Int, today: LocalDate): Int {
        val current = today.year % 100
        val century = today.year - current
        return when (yy - current) {
            in 51..99 -> century - 100 + yy
            in -99..-50 -> century + 100 + yy
            else -> century + yy
        }
    }
}
