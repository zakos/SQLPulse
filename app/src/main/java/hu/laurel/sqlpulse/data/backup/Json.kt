package hu.laurel.sqlpulse.data.backup

/**
 * A very small JSON reader and writer.
 *
 * The project has no serialisation library on the classpath (see gradle/libs.versions.toml), and
 * `org.json` is an Android platform class that does not exist in a plain JVM unit test — where
 * every rule in this package has to be testable. A few hundred lines of hand-written JSON is the
 * cheaper of the two, and it keeps the backup format readable by anything that can read JSON.
 *
 * Deliberately strict: an unexpected token is a parse error rather than something silently
 * ignored, because a backup file that is quietly half-understood is worse than one that is
 * refused.
 */
sealed class JsonValue {

    object Null : JsonValue()

    data class Bool(val value: Boolean) : JsonValue()

    /** Kept as text so a Long survives the round trip without going through a Double. */
    data class Num(val text: String) : JsonValue() {
        constructor(value: Long) : this(value.toString())
        constructor(value: Int) : this(value.toString())
    }

    data class Str(val value: String) : JsonValue()

    data class Arr(val items: List<JsonValue>) : JsonValue()

    /** Insertion ordered, so writing the same payload twice produces the same bytes. */
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()

    fun write(): String = StringBuilder().also { writeTo(it) }.toString()

    private fun writeTo(out: StringBuilder) {
        when (this) {
            is Null -> out.append("null")
            is Bool -> out.append(if (value) "true" else "false")
            is Num -> out.append(text)
            is Str -> writeString(value, out)
            is Arr -> {
                out.append('[')
                items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    item.writeTo(out)
                }
                out.append(']')
            }
            is Obj -> {
                out.append('{')
                var first = true
                fields.forEach { (key, value) ->
                    if (!first) out.append(',')
                    first = false
                    writeString(key, out)
                    out.append(':')
                    value.writeTo(out)
                }
                out.append('}')
            }
        }
    }

    companion object {

        fun parse(text: String): JsonValue {
            val parser = JsonParser(text)
            val value = parser.parseValue()
            parser.skipWhitespace()
            if (!parser.atEnd()) throw JsonException("trailing content at offset ${parser.offset}")
            return value
        }

        private fun writeString(value: String, out: StringBuilder) {
            out.append('"')
            value.forEach { c ->
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> out.append("\\r")
                    c == '\t' -> out.append("\\t")
                    // Control characters, and nothing else: the payload is UTF-8 and stays so.
                    c.code < 0x20 -> out.append("\\u").append("%04x".format(c.code))
                    else -> out.append(c)
                }
            }
            out.append('"')
        }
    }
}

/** The bytes are not the JSON they claimed to be. */
class JsonException(message: String) : Exception(message)

private class JsonParser(private val text: String) {

    var offset: Int = 0
        private set

    fun atEnd(): Boolean = offset >= text.length

    fun skipWhitespace() {
        while (offset < text.length && text[offset].isWhitespace()) offset++
    }

    fun parseValue(): JsonValue {
        skipWhitespace()
        if (atEnd()) throw JsonException("unexpected end of input")
        return when (val c = text[offset]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't' -> literal("true", JsonValue.Bool(true))
            'f' -> literal("false", JsonValue.Bool(false))
            'n' -> literal("null", JsonValue.Null)
            else -> if (c == '-' || c.isDigit()) parseNumber() else {
                throw JsonException("unexpected character '$c' at offset $offset")
            }
        }
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
        if (!text.startsWith(word, offset)) throw JsonException("bad literal at offset $offset")
        offset += word.length
        return value
    }

    private fun parseNumber(): JsonValue {
        val start = offset
        if (offset < text.length && text[offset] == '-') offset++
        while (offset < text.length && (text[offset].isDigit() || text[offset] in ".eE+-")) offset++
        val slice = text.substring(start, offset)
        if (slice.isEmpty() || slice == "-") throw JsonException("bad number at offset $start")
        return JsonValue.Num(slice)
    }

    private fun parseString(): String {
        if (text[offset] != '"') throw JsonException("expected a string at offset $offset")
        offset++
        val out = StringBuilder()
        while (true) {
            if (atEnd()) throw JsonException("unterminated string")
            when (val c = text[offset++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (atEnd()) throw JsonException("unterminated escape")
                    when (val esc = text[offset++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (offset + 4 > text.length) throw JsonException("short \\u escape")
                            val hex = text.substring(offset, offset + 4)
                            offset += 4
                            val code = hex.toIntOrNull(16)
                                ?: throw JsonException("bad \\u escape '$hex'")
                            out.append(code.toChar())
                        }
                        else -> throw JsonException("unknown escape '\\$esc'")
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private fun parseArray(): JsonValue {
        offset++
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (!atEnd() && text[offset] == ']') {
            offset++
            return JsonValue.Arr(items)
        }
        while (true) {
            items += parseValue()
            skipWhitespace()
            if (atEnd()) throw JsonException("unterminated array")
            when (text[offset++]) {
                ',' -> Unit
                ']' -> return JsonValue.Arr(items)
                else -> throw JsonException("expected ',' or ']' at offset ${offset - 1}")
            }
        }
    }

    private fun parseObject(): JsonValue {
        offset++
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (!atEnd() && text[offset] == '}') {
            offset++
            return JsonValue.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            if (atEnd() || text[offset++] != ':') throw JsonException("expected ':' after '$key'")
            fields[key] = parseValue()
            skipWhitespace()
            if (atEnd()) throw JsonException("unterminated object")
            when (text[offset++]) {
                ',' -> Unit
                '}' -> return JsonValue.Obj(fields)
                else -> throw JsonException("expected ',' or '}' at offset ${offset - 1}")
            }
        }
    }
}

/* Small readers, so the codec below reads as a description of the format rather than as casts. */

internal fun JsonValue.asObject(where: String): JsonValue.Obj =
    this as? JsonValue.Obj ?: throw JsonException("$where is not an object")

internal fun JsonValue.asArray(where: String): List<JsonValue> =
    (this as? JsonValue.Arr)?.items ?: throw JsonException("$where is not an array")

internal fun JsonValue.Obj.str(name: String): String =
    (fields[name] as? JsonValue.Str)?.value ?: throw JsonException("missing text field '$name'")

internal fun JsonValue.Obj.strOrNull(name: String): String? =
    when (val value = fields[name]) {
        null, is JsonValue.Null -> null
        is JsonValue.Str -> value.value
        else -> throw JsonException("field '$name' is not text")
    }

internal fun JsonValue.Obj.long(name: String): Long =
    (fields[name] as? JsonValue.Num)?.text?.toLongOrNull()
        ?: throw JsonException("missing number field '$name'")

internal fun JsonValue.Obj.longOrNull(name: String): Long? =
    when (val value = fields[name]) {
        null, is JsonValue.Null -> null
        is JsonValue.Num -> value.text.toLongOrNull() ?: throw JsonException("field '$name' is not a whole number")
        else -> throw JsonException("field '$name' is not a number")
    }

internal fun JsonValue.Obj.int(name: String): Int =
    long(name).toInt()

internal fun JsonValue.Obj.intOr(name: String, fallback: Int): Int =
    longOrNull(name)?.toInt() ?: fallback

internal fun JsonValue.Obj.bool(name: String): Boolean =
    (fields[name] as? JsonValue.Bool)?.value ?: throw JsonException("missing flag field '$name'")

internal fun JsonValue.Obj.boolOr(name: String, fallback: Boolean): Boolean =
    (fields[name] as? JsonValue.Bool)?.value ?: fallback

internal fun JsonValue.Obj.obj(name: String): JsonValue.Obj =
    (fields[name] ?: throw JsonException("missing object field '$name'")).asObject("'$name'")

internal fun JsonValue.Obj.arr(name: String): List<JsonValue> =
    when (val value = fields[name]) {
        null, is JsonValue.Null -> emptyList()
        else -> value.asArray("'$name'")
    }
