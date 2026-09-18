package hu.laurel.sqlpulse.data.sql

/**
 * What a `:name` value is meant to be (§7.4).
 *
 * Everything used to travel as text and MySQL coerced it against the column, which is fine until
 * it is not: `''` becomes 0 against a number and `'0000-00-00'` against a date, and neither says
 * so. Naming the type is how "no value" and "the empty string" stop looking the same.
 */
enum class ParameterType {
    TEXT,
    NUMBER,
    DATE,
    BOOLEAN,
    NULL,
}

/** A parameter as the dialog holds it: what was typed, and what the user said it is. */
data class ParameterValue(
    val text: String = "",
    /** Text is the default, so a query written before types existed behaves as it always did. */
    val type: ParameterType = ParameterType.TEXT,
)

/** What the executor sets on the prepared statement, one per JDBC setter. */
sealed interface ParameterBinding {
    data object Null : ParameterBinding
    data class Text(val value: String) : ParameterBinding
    data class Integer(val value: Long) : ParameterBinding
    data class Decimal(val value: Double) : ParameterBinding
    data class Bool(val value: Boolean) : ParameterBinding
}

/**
 * Decides how a typed parameter reaches the server.
 *
 * It lives here rather than in the dialog so the decision can be read and tested on its own: this
 * is the step where "1" becomes a number and an empty box becomes NULL, and getting it wrong is
 * silent — the query runs and answers about different rows.
 */
object QueryParameters {

    fun binding(value: ParameterValue): ParameterBinding {
        val text = value.text.trim()
        return when (value.type) {
            ParameterType.NULL -> ParameterBinding.Null

            // The box was left empty in a field that is not text: that is "no value", not the
            // empty string, which MySQL would silently read as 0 or as a zero date.
            ParameterType.NUMBER -> when {
                text.isEmpty() -> ParameterBinding.Null
                else -> text.toLongOrNull()?.let { ParameterBinding.Integer(it) }
                    ?: text.toDoubleOrNull()?.let { ParameterBinding.Decimal(it) }
                    // Not a number after all. It goes as text so the server explains what is wrong
                    // with it, rather than this deciding on the user's behalf.
                    ?: ParameterBinding.Text(value.text)
            }

            // A date is bound as its own text: MySQL parses `2024-01-31` itself, and going through
            // java.sql.Date would drag a time zone into a date somebody typed by hand.
            ParameterType.DATE -> if (text.isEmpty()) ParameterBinding.Null else ParameterBinding.Text(text)

            ParameterType.BOOLEAN -> when (text.lowercase()) {
                in TRUE_WORDS -> ParameterBinding.Bool(true)
                in FALSE_WORDS -> ParameterBinding.Bool(false)
                "" -> ParameterBinding.Null
                else -> ParameterBinding.Text(value.text)
            }

            // Text is passed through untrimmed: a trailing space may be exactly what is stored.
            ParameterType.TEXT -> ParameterBinding.Text(value.text)
        }
    }

    private val TRUE_WORDS = setOf("true", "1", "yes", "y", "on")
    private val FALSE_WORDS = setOf("false", "0", "no", "n", "off")
}
