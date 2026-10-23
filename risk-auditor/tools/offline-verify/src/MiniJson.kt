package verify

/*
 * A dependency-free JSON reader for the offline verification harness.
 *
 * It exists only because kotlinx-serialization cannot be downloaded in the
 * authoring environment. It parses the REAL data files (rubric.json,
 * permission_catalog.json, trusted_tools.json) so those files are genuinely
 * validated here, not mocked.
 *
 * Not production code; never shipped.
 */

sealed interface JsonValue {
    data class JObj(val fields: Map<String, JsonValue>) : JsonValue
    data class JArr(val items: List<JsonValue>) : JsonValue
    data class JStr(val value: String) : JsonValue
    data class JNum(val value: Double) : JsonValue
    data class JBool(val value: Boolean) : JsonValue
    data object JNull : JsonValue
}

class MiniJsonException(message: String) : RuntimeException(message)

object MiniJson {

    fun parse(text: String): JsonValue {
        val p = Parser(text)
        p.skipWhitespace()
        val value = p.parseValue()
        p.skipWhitespace()
        if (!p.atEnd()) throw MiniJsonException("trailing content at offset ${p.position()}")
        return value
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun position() = i
        fun atEnd() = i >= s.length

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) throw MiniJsonException("unexpected end of input")
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.JStr(parseString())
                't' -> { expect("true"); JsonValue.JBool(true) }
                'f' -> { expect("false"); JsonValue.JBool(false) }
                'n' -> { expect("null"); JsonValue.JNull }
                else -> if (c == '-' || c.isDigit()) parseNumber()
                else throw MiniJsonException("unexpected character '$c' at offset $i")
            }
        }

        private fun expect(literal: String) {
            if (!s.startsWith(literal, i)) {
                throw MiniJsonException("expected '$literal' at offset $i")
            }
            i += literal.length
        }

        private fun parseObject(): JsonValue.JObj {
            i++ // consume '{'
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd() && s[i] == '}') { i++; return JsonValue.JObj(fields) }
            while (true) {
                skipWhitespace()
                if (atEnd() || s[i] != '"') throw MiniJsonException("expected object key at offset $i")
                val key = parseString()
                skipWhitespace()
                if (atEnd() || s[i] != ':') throw MiniJsonException("expected ':' at offset $i")
                i++
                fields[key] = parseValue()
                skipWhitespace()
                if (atEnd()) throw MiniJsonException("unterminated object")
                when (s[i]) {
                    ',' -> { i++; continue }
                    '}' -> { i++; return JsonValue.JObj(fields) }
                    else -> throw MiniJsonException("expected ',' or '}' at offset $i")
                }
            }
        }

        private fun parseArray(): JsonValue.JArr {
            i++ // consume '['
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (!atEnd() && s[i] == ']') { i++; return JsonValue.JArr(items) }
            while (true) {
                items += parseValue()
                skipWhitespace()
                if (atEnd()) throw MiniJsonException("unterminated array")
                when (s[i]) {
                    ',' -> { i++; continue }
                    ']' -> { i++; return JsonValue.JArr(items) }
                    else -> throw MiniJsonException("expected ',' or ']' at offset $i")
                }
            }
        }

        private fun parseString(): String {
            i++ // consume opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw MiniJsonException("unterminated string")
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        if (atEnd()) throw MiniJsonException("unterminated escape")
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i + 1, i + 5)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> throw MiniJsonException("bad escape '\\$e' at offset $i")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        private fun parseNumber(): JsonValue.JNum {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                    s[i] == '+' || s[i] == '-')
            ) i++
            val raw = s.substring(start, i)
            return JsonValue.JNum(raw.toDoubleOrNull() ?: throw MiniJsonException("bad number '$raw'"))
        }
    }
}

// ---- Small accessors that keep the harness loaders readable ----

fun JsonValue.obj(): Map<String, JsonValue> =
    (this as? JsonValue.JObj)?.fields ?: throw MiniJsonException("expected an object, got $this")

fun JsonValue.arr(): List<JsonValue> =
    (this as? JsonValue.JArr)?.items ?: throw MiniJsonException("expected an array, got $this")

fun JsonValue.str(): String =
    (this as? JsonValue.JStr)?.value ?: throw MiniJsonException("expected a string, got $this")

fun JsonValue.num(): Double =
    (this as? JsonValue.JNum)?.value ?: throw MiniJsonException("expected a number, got $this")

fun JsonValue.int(): Int = num().toInt()

fun JsonValue.bool(): Boolean =
    (this as? JsonValue.JBool)?.value ?: throw MiniJsonException("expected a boolean, got $this")

fun JsonValue.isNull(): Boolean = this is JsonValue.JNull

fun Map<String, JsonValue>.req(key: String): JsonValue =
    this[key] ?: throw MiniJsonException("missing required key '$key'")

fun Map<String, JsonValue>.opt(key: String): JsonValue? =
    this[key]?.takeUnless { it.isNull() }
