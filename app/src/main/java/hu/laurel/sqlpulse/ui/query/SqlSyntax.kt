package hu.laurel.sqlpulse.ui.query

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import hu.laurel.sqlpulse.data.sql.SqlHighlighter
import hu.laurel.sqlpulse.data.sql.TokenRole
import hu.laurel.sqlpulse.data.sql.dialect.MySqlDialect
import hu.laurel.sqlpulse.data.sql.dialect.SqlSyntax
import hu.laurel.sqlpulse.ui.theme.SqlPulseColors

/**
 * Syntax highlighting for the editor (§7.4).
 *
 * The tokenising lives in [SqlHighlighter], which knows nothing about Compose; this only paints
 * the ranges it returns. Since nothing is inserted or removed, offsets map one to one.
 */
class SqlVisualTransformation(
    private val plain: Color,
    private val keyword: Color = SqlPulseColors.DarkAccent,
    private val string: Color = SqlPulseColors.DarkSuccess,
    private val number: Color = SqlPulseColors.CellNumber,
    private val comment: Color = SqlPulseColors.CellNull,
    private val identifier: Color = SqlPulseColors.CellDate,
    private val parameter: Color = SqlPulseColors.DarkWarning,
    /** The engine's quoting, comments and keywords; MySQL's until a session says otherwise. */
    private val syntax: SqlSyntax = MySqlDialect,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val annotated = AnnotatedString.Builder(source).apply {
            addStyle(SpanStyle(color = plain), 0, source.length)
            SqlHighlighter.tokenize(source, syntax.grammar, syntax.keywords).forEach { token ->
                val style = when (token.role) {
                    TokenRole.KEYWORD -> SpanStyle(color = keyword, fontWeight = FontWeight.Medium)
                    TokenRole.STRING -> SpanStyle(color = string)
                    TokenRole.NUMBER -> SpanStyle(color = number)
                    TokenRole.COMMENT -> SpanStyle(color = comment)
                    TokenRole.QUOTED_IDENTIFIER -> SpanStyle(color = identifier)
                    TokenRole.PARAMETER -> SpanStyle(color = parameter, fontWeight = FontWeight.Medium)
                }
                // Tokenising is defensive, but never trust a range against a live text field.
                val end = token.end.coerceAtMost(source.length)
                if (token.start < end) addStyle(style, token.start, end)
            }
        }.toAnnotatedString()

        return TransformedText(annotated, OffsetMapping.Identity)
    }
}

