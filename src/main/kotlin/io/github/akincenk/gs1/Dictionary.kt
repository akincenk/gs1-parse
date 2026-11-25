package io.github.akincenk.gs1

/** Character sets used by the GS1 Barcode Syntax Dictionary. */
enum class CharSet {
    /** Digits 0-9. */
    N,

    /** GS1 AI encodable character set 82. */
    X,

    /** GS1 AI encodable character set 39. */
    Y,

    /** Base64url (RFC 4648 section 5) without padding. */
    Z;

    fun accepts(c: Char): Boolean = when (this) {
        N -> c in '0'..'9'
        X -> c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z' || c in CSET82_SYMBOLS
        Y -> c in '0'..'9' || c in 'A'..'Z' || c == '#' || c == '-' || c == '/'
        Z -> c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z' || c == '-' || c == '_'
    }

    private companion object {
        const val CSET82_SYMBOLS = "!\"%&'()*+,-./:;<=>?_"
    }
}

/** One part of an AI's data field, for example `N13,csum` or the optional `[X..17]`. */
data class Component(
    val charSet: CharSet,
    val minLength: Int,
    val maxLength: Int,
    val optional: Boolean,
    val linters: List<String>,
) {
    val isFixed: Boolean get() = minLength == maxLength
}

/** Definition of one Application Identifier as given in the dictionary. */
data class AiDefinition(
    val ai: String,
    val title: String,
    /** True when the AI is in the predefined-length table and needs no FNC1 separator after it. */
    val predefinedLength: Boolean,
    val components: List<Component>,
    /** Raw attributes such as `req=01,02` or `dlpkey`. `req=` and `ex=` are checked by [Gs1.parse]. */
    val attributes: List<String>,
) {
    /** Total data length when every component is mandatory and fixed, otherwise null. */
    val fixedLength: Int? =
        if (components.all { it.isFixed && !it.optional }) components.sumOf { it.maxLength } else null

    val maxLength: Int = components.sumOf { it.maxLength }
}

/**
 * The GS1 Barcode Syntax Dictionary, loaded from the copy bundled with this library
 * (see `gs1-syntax-dictionary.txt` in the resources) or from any text in the same format.
 */
class Gs1Dictionary(private val definitions: Map<String, AiDefinition>) {

    operator fun get(ai: String): AiDefinition? = definitions[ai]

    val size: Int get() = definitions.size

    /** Finds the AI that starts at [index] of [data]. AIs are prefix-free, so at most one matches. */
    fun matchAt(data: CharSequence, index: Int): AiDefinition? {
        for (len in 2..4) {
            if (index + len > data.length) return null
            definitions[data.substring(index, index + len)]?.let { return it }
        }
        return null
    }

    companion object {
        private const val RESOURCE = "gs1-syntax-dictionary.txt"

        /** The dictionary bundled with this library. */
        val bundled: Gs1Dictionary by lazy {
            val stream = Gs1Dictionary::class.java.getResourceAsStream(RESOURCE)
                ?: error("bundled $RESOURCE is missing from the classpath")
            stream.bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
        }

        private val SPEC = Regex("""^(\[)?([NXYZ])(\d+|\.\.\d+)(])?((?:,[a-z0-9]+)*)$""")
        private val FLAGS = Regex("""^[*!?"$%&'()+,\-./:;<=>@\[\\\]^_`{|}~]+$""")
        private val AI_RANGE = Regex("""^(\d{2,4})(?:-(\d{2,4}))?$""")

        /** Parses dictionary text. Throws [IllegalArgumentException] with the line number on bad input. */
        fun parse(text: String): Gs1Dictionary {
            val map = LinkedHashMap<String, AiDefinition>()
            text.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                try {
                    for (def in parseLine(line)) {
                        require(map.put(def.ai, def) == null) { "AI ${def.ai} is defined twice" }
                    }
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("dictionary line ${index + 1}: ${e.message}", e)
                }
            }
            return Gs1Dictionary(map)
        }

        private fun parseLine(line: String): List<AiDefinition> {
            val hash = line.indexOf('#')
            val title = if (hash >= 0) line.substring(hash + 1).trim() else ""
            val tokens = (if (hash >= 0) line.substring(0, hash) else line).trim().split(Regex("\\s+"))

            val range = AI_RANGE.matchEntire(tokens[0]) ?: throw IllegalArgumentException("bad AI '${tokens[0]}'")
            var i = 1
            val flags = if (i < tokens.size && FLAGS.matches(tokens[i]) && !tokens[i].startsWith("[")) tokens[i++] else ""

            val components = mutableListOf<Component>()
            while (i < tokens.size) {
                val m = SPEC.matchEntire(tokens[i]) ?: break
                val (open, type, len, close, linters) = m.destructured
                require(open.isNotEmpty() == close.isNotEmpty()) { "unbalanced brackets in '${tokens[i]}'" }
                val variable = len.startsWith("..")
                val max = len.removePrefix("..").toInt()
                components += Component(
                    charSet = CharSet.valueOf(type),
                    minLength = if (variable) 1 else max,
                    maxLength = max,
                    optional = open.isNotEmpty(),
                    linters = linters.split(',').filter { it.isNotEmpty() },
                )
                i++
            }
            require(components.isNotEmpty()) { "no data specification" }
            val attributes = tokens.drop(i)

            val first = range.groupValues[1]
            val last = range.groupValues[2].ifEmpty { first }
            require(first.length == last.length && first <= last) { "bad AI range '${tokens[0]}'" }
            return (first.toInt()..last.toInt()).map { n ->
                AiDefinition(
                    ai = n.toString().padStart(first.length, '0'),
                    title = title,
                    predefinedLength = '*' in flags,
                    components = components,
                    attributes = attributes,
                )
            }
        }
    }
}
