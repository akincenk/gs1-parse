package io.github.akincenk.gs1

import java.time.LocalDate

/** One AI and its data, for example (17) 261231. */
data class Gs1Element(
    val ai: String,
    val title: String,
    /** Data exactly as scanned, without the AI. */
    val raw: String,
    /** Typed value, or null when the data failed a check. */
    val value: Gs1Value?,
    /** Dictionary checks this library does not implement yet, e.g. "iso3166". */
    val notChecked: List<String> = emptyList(),
)

/** A problem found in the input. [position] is a 0-based index into the input as given, symbology identifier included. */
data class Gs1Issue(val position: Int, val ai: String?, val message: String)

data class Gs1Result(
    val elements: List<Gs1Element>,
    val errors: List<Gs1Issue>,
    val warnings: List<Gs1Issue>,
) {
    val isValid: Boolean get() = errors.isEmpty()

    /** First element with this AI, or null. */
    operator fun get(ai: String): Gs1Element? = elements.firstOrNull { it.ai == ai }
}

data class ParseOptions(
    /** Reference date for two-digit years. */
    val today: LocalDate = LocalDate.now(),
    /** Character the scanner sends for FNC1. Most send ASCII 29 (GS). */
    val separator: Char = Gs1.GS,
    val dictionary: Gs1Dictionary = Gs1Dictionary.bundled,
    /**
     * Check the dictionary's AI pairings (`req=`, `ex=`), e.g. (02) needs (37). GS1 applies them to all
     * barcodes on an item together, so turn this off when a scan is only one of several on the item.
     */
    val checkAssociations: Boolean = true,
)

object Gs1 {
    const val GS: Char = '\u001D'

    /** Symbology identifiers (ISO/IEC 15424) that announce GS1 data. */
    private val GS1_SYMBOLOGY_IDS = setOf("]C1", "]e0", "]d2", "]Q3", "]J1")

    /**
     * Parses scanner output such as `]C1` + `0109506000134352` + `17261231` + GS + `10AB12`,
     * or the bracketed form `(01)09506000134352(17)261231(10)AB12`.
     * Never throws for bad input; problems are returned in [Gs1Result.errors].
     */
    fun parse(input: String, options: ParseOptions = ParseOptions()): Gs1Result {
        val errors = mutableListOf<Gs1Issue>()
        val warnings = mutableListOf<Gs1Issue>()

        var data = input.trimEnd('\r', '\n')
        var prefix = 0
        if (data.startsWith("]")) {
            val id = data.take(3)
            if (id !in GS1_SYMBOLOGY_IDS) {
                errors += Gs1Issue(0, null, "symbology identifier $id does not mark GS1 data")
                return Gs1Result(emptyList(), errors, warnings)
            }
            data = data.substring(3)
            prefix = 3
        }
        if (data.isEmpty()) {
            errors += Gs1Issue(0, null, "no data")
            return Gs1Result(emptyList(), errors, warnings)
        }

        val pieces = if (data.startsWith("(")) {
            splitBracketed(data, options.dictionary, errors)
        } else {
            if (options.separator != GS) data = data.replace(options.separator, GS)
            splitRaw(data, options.dictionary, errors, warnings).also { warnSwallowed(it, options, warnings) }
        }

        val elements = pieces.map { validate(it, options.today, errors) }
        checkDuplicates(pieces, errors)
        // Pairings only make sense when every element was read; a broken scan already has its error.
        if (options.checkAssociations && errors.isEmpty()) checkAssociations(pieces, errors)
        if (prefix == 0) return Gs1Result(elements, errors, warnings)
        return Gs1Result(elements, errors.map { it.copy(position = it.position + prefix) }, warnings.map { it.copy(position = it.position + prefix) })
    }

    private class Piece(val position: Int, val def: AiDefinition, val raw: String)

    private fun splitRaw(
        data: String,
        dict: Gs1Dictionary,
        errors: MutableList<Gs1Issue>,
        warnings: MutableList<Gs1Issue>,
    ): List<Piece> {
        val pieces = mutableListOf<Piece>()
        var pos = 0
        while (pos < data.length) {
            if (data[pos] == GS) {
                // A separator at the start or after a predefined-length AI is redundant but harmless.
                pos++
                continue
            }
            val def = dict.matchAt(data, pos)
            if (def == null) {
                errors += Gs1Issue(pos, null, "no known AI at \"${data.substring(pos).take(4)}\"")
                return pieces
            }
            val start = pos + def.ai.length
            val fixed = def.fixedLength
            if (fixed != null) {
                val end = minOf(start + fixed, data.length)
                val raw = data.substring(start, end)
                val gs = raw.indexOf(GS)
                if (gs >= 0 || raw.length < fixed) {
                    val got = if (gs >= 0) gs else raw.length
                    errors += Gs1Issue(start, def.ai, "(${def.ai}) needs $fixed characters, got $got")
                    return pieces
                }
                pieces += Piece(start, def, raw)
                pos = end
                if (!def.predefinedLength && pos < data.length && data[pos] != GS) {
                    warnings += Gs1Issue(pos, def.ai, "(${def.ai}) should be followed by a separator (FNC1)")
                }
            } else {
                val gs = data.indexOf(GS, start)
                val end = if (gs < 0) data.length else gs
                pieces += Piece(start, def, data.substring(start, end))
                pos = end
            }
        }
        return pieces
    }

    /**
     * A scanner that drops the separator after a variable-length value glues the next element onto it,
     * e.g. batch "AB12" + best-before "15270115" becomes batch "AB1215270115". The data is still valid,
     * so this only warns when some tail of the value parses on its own as complete, valid elements and
     * the first of them passed a date or check digit test.
     * shortcut: tails that start with a variable-length AI (e.g. "21...") or an unchecked fixed one
     * (e.g. "20", "3103") are skipped, because random batch text matches them too often (about 1 in 40
     * in a test with random batches); a lost separator before such an AI is not reported.
     */
    private fun warnSwallowed(pieces: List<Piece>, options: ParseOptions, warnings: MutableList<Gs1Issue>) {
        for (piece in pieces) {
            if (piece.def.fixedLength != null) continue
            for (k in 1 until piece.raw.length) {
                val tail = piece.raw.substring(k)
                val next = options.dictionary.matchAt(tail, 0) ?: continue
                if (next.fixedLength == null || next.components.none { c -> c.linters.any { it in STRONG_CHECKS } }) continue
                val errors = mutableListOf<Gs1Issue>()
                val found = splitRaw(tail, options.dictionary, errors, mutableListOf())
                val elements = found.map { validate(it, options.today, errors) }
                if (errors.isNotEmpty()) continue
                val shown = elements.joinToString(" ") { "(${it.ai}) ${it.raw}" }
                warnings += Gs1Issue(piece.position + k, piece.def.ai, "(${piece.def.ai}) may contain $shown; was a separator lost?")
                break
            }
        }
    }

    private val STRONG_CHECKS = setOf("csum", "yymmd0", "yymmdd", "yyyymmdd")

    private fun splitBracketed(data: String, dict: Gs1Dictionary, errors: MutableList<Gs1Issue>): List<Piece> {
        val pieces = mutableListOf<Piece>()
        var pos = 0
        while (pos < data.length) {
            val close = data.indexOf(')', pos)
            if (data[pos] != '(' || close < 0) {
                errors += Gs1Issue(pos, null, "expected \"(AI)\" at position $pos")
                return pieces
            }
            val ai = data.substring(pos + 1, close)
            val def = dict[ai]
            if (def == null) {
                errors += Gs1Issue(pos, null, "unknown AI ($ai)")
                return pieces
            }
            // Value runs to the next unescaped "("; "\(" stands for a literal "(".
            val value = StringBuilder()
            var i = close + 1
            while (i < data.length && data[i] != '(') {
                if (data[i] == '\\' && i + 1 < data.length && data[i + 1] == '(') i++
                value.append(data[i])
                i++
            }
            pieces += Piece(close + 1, def, value.toString())
            pos = i
        }
        return pieces
    }

    private fun validate(piece: Piece, today: LocalDate, errors: MutableList<Gs1Issue>): Gs1Element {
        val def = piece.def
        val raw = piece.raw
        val notChecked = mutableListOf<String>()
        val before = errors.size
        fun fail(offset: Int, message: String) {
            errors += Gs1Issue(piece.position + offset, def.ai, "(${def.ai}) $message")
        }

        if (raw.isEmpty()) fail(0, "has no data")
        if (raw.length > def.maxLength) fail(def.maxLength, "is ${raw.length} characters, at most ${def.maxLength} allowed")

        var offset = 0
        for (comp in def.components) {
            if (errors.size > before) break
            if (offset >= raw.length) {
                if (!comp.optional) fail(offset, "is too short")
                break
            }
            val len = if (comp.isFixed) comp.maxLength else minOf(comp.maxLength, raw.length - offset)
            if (offset + len > raw.length) {
                fail(offset, "is too short, characters ${offset + 1}-${offset + len} are incomplete")
                break
            }
            val part = raw.substring(offset, offset + len)
            val bad = part.indexOfFirst { !comp.charSet.accepts(it) }
            if (bad >= 0) {
                fail(offset + bad, "character '${printable(part[bad])}' is not allowed (${describe(comp.charSet)})")
                break
            }
            for (linter in comp.linters) {
                val check = Linters.checks[linter]
                if (check == null) {
                    notChecked += linter
                    continue
                }
                check(part)?.let { fail(offset, it) }
            }
            offset += len
        }

        val value = if (errors.size == before) {
            Values.convert(def, raw, today) { fail(0, it) }.takeIf { errors.size == before }
        } else {
            null
        }
        return Gs1Element(def.ai, def.title, raw, value, notChecked)
    }

    private fun checkDuplicates(pieces: List<Piece>, errors: MutableList<Gs1Issue>) {
        pieces.groupBy { it.def.ai }.forEach { (ai, same) ->
            if (same.map { it.raw }.distinct().size > 1) {
                errors += Gs1Issue(same[1].position, ai, "($ai) appears more than once with different values")
            }
        }
    }

    /**
     * Evaluates `req=` and `ex=` attributes as the GS1 Syntax Dictionary defines them: `req=01+21,02` is
     * satisfied by (01) together with (21), or by (02); `ex=01,394n` forbids (01) and any (394n).
     * Each `req=` attribute must hold on its own. `n` in a pattern matches one digit, and an AI never
     * matches itself, so (3940) with ex=394n only conflicts with another (394n).
     */
    private fun checkAssociations(pieces: List<Piece>, errors: MutableList<Gs1Issue>) {
        val present = pieces.map { it.def.ai }.distinct()
        fun found(pattern: String, self: String) = present.firstOrNull { ai ->
            ai != self && ai.length == pattern.length &&
                pattern.indices.all { pattern[it] == ai[it] || (pattern[it] == 'n' && ai[it].isDigit()) }
        }
        val reportedPairs = mutableSetOf<Set<String>>()
        for (piece in pieces.distinctBy { it.def.ai }) {
            val ai = piece.def.ai
            for (attr in piece.def.attributes) {
                val list = attr.substringAfter('=').split(',')
                if (attr.startsWith("req=")) {
                    val groups = list.map { it.split('+') }
                    if (groups.none { g -> g.all { found(it, ai) != null } }) {
                        val shown = groups.map { g -> g.joinToString("+") { "($it)" } }
                        val needs = if (shown.size == 1) shown[0] else "one of " + shown.joinToString(", ")
                        errors += Gs1Issue(piece.position, ai, "($ai) needs $needs in the same data")
                    }
                } else if (attr.startsWith("ex=")) {
                    for (pattern in list) {
                        val other = found(pattern, ai) ?: continue
                        if (reportedPairs.add(setOf(ai, other))) {
                            errors += Gs1Issue(piece.position, ai, "($ai) must not appear together with ($other)")
                        }
                    }
                }
            }
        }
    }

    private fun describe(cs: CharSet) = when (cs) {
        CharSet.N -> "digits only"
        CharSet.X -> "GS1 character set 82"
        CharSet.Y -> "GS1 character set 39"
        CharSet.Z -> "base64url"
    }

    private fun printable(c: Char) = if (c < ' ' || c == '\u007F') "\\u%04x".format(c.code) else c.toString()
}
