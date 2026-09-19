package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Test

class QueryParametersTest {

    private fun binding(text: String, type: ParameterType) =
        QueryParameters.binding(ParameterValue(text, type))

    @Test
    fun `a value with no type given travels as text`() {
        assertEquals(ParameterBinding.Text("7"), QueryParameters.binding(ParameterValue("7")))
    }

    @Test
    fun `text is passed through exactly as it was typed`() {
        assertEquals(ParameterBinding.Text(" a "), binding(" a ", ParameterType.TEXT))
        assertEquals(ParameterBinding.Text(""), binding("", ParameterType.TEXT))
    }

    @Test
    fun `a whole number becomes an integer binding`() {
        assertEquals(ParameterBinding.Integer(42), binding("42", ParameterType.NUMBER))
        assertEquals(ParameterBinding.Integer(-3), binding(" -3 ", ParameterType.NUMBER))
    }

    @Test
    fun `a fractional number becomes a decimal binding`() {
        assertEquals(ParameterBinding.Decimal(1.5), binding("1.5", ParameterType.NUMBER))
    }

    @Test
    fun `a number field left empty is null rather than an empty string`() {
        assertEquals(ParameterBinding.Null, binding("", ParameterType.NUMBER))
        assertEquals(ParameterBinding.Null, binding("   ", ParameterType.NUMBER))
    }

    @Test
    fun `something that is not a number is left for the server to complain about`() {
        assertEquals(ParameterBinding.Text("ten"), binding("ten", ParameterType.NUMBER))
    }

    @Test
    fun `a date is bound as the text that was typed`() {
        assertEquals(ParameterBinding.Text("2024-01-31"), binding(" 2024-01-31 ", ParameterType.DATE))
        assertEquals(ParameterBinding.Null, binding("", ParameterType.DATE))
    }

    @Test
    fun `the words people write for true and false are understood`() {
        assertEquals(ParameterBinding.Bool(true), binding("true", ParameterType.BOOLEAN))
        assertEquals(ParameterBinding.Bool(true), binding("YES", ParameterType.BOOLEAN))
        assertEquals(ParameterBinding.Bool(true), binding("1", ParameterType.BOOLEAN))
        assertEquals(ParameterBinding.Bool(false), binding("false", ParameterType.BOOLEAN))
        assertEquals(ParameterBinding.Bool(false), binding("Off", ParameterType.BOOLEAN))
        assertEquals(ParameterBinding.Bool(false), binding("0", ParameterType.BOOLEAN))
    }

    @Test
    fun `a boolean that is neither is left for the server to complain about`() {
        assertEquals(ParameterBinding.Text("maybe"), binding("maybe", ParameterType.BOOLEAN))
        assertEquals(ParameterBinding.Null, binding("", ParameterType.BOOLEAN))
    }

    @Test
    fun `the null type ignores whatever is in the box`() {
        assertEquals(ParameterBinding.Null, binding("still ignored", ParameterType.NULL))
    }
}
