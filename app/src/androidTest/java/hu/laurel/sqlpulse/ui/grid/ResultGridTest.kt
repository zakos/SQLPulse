package hu.laurel.sqlpulse.ui.grid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import hu.laurel.sqlpulse.data.sql.CellType
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ColumnMeta
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.ui.theme.SqlPulseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The result grid, composed for real on a device.
 *
 * It is the screen the app spends its time in and the one with the most to get wrong: a BLOB must
 * never be rendered as its contents, NULL must be distinguishable from an empty string, and the
 * cell the user taps must be the cell that is handed back. None of that needs a database — a
 * [ResultTable] is a plain value — so it is testable here, unlike anything that opens a session.
 *
 * Test names are camel case rather than the project's backticked sentences: a method name with
 * spaces is rejected by the runtime below API 30, and this module's minimum is 28.
 */
class ResultGridTest {

    @get:Rule
    val compose = createComposeRule()

    private val table = ResultTable(
        columns = listOf(
            ColumnMeta("id", CellType.NUMBER, "BIGINT", "orders"),
            ColumnMeta("note", CellType.TEXT, "VARCHAR", "orders"),
            ColumnMeta("photo", CellType.BLOB, "LONGBLOB", "orders"),
        ),
        rows = listOf(
            listOf(CellValue.Number("1"), CellValue.Text("first"), CellValue.Blob(2048)),
            listOf(CellValue.Number("2"), CellValue.Null, CellValue.Blob(0)),
        ),
    )

    @Test
    fun columnsAndValuesAreShown() {
        compose.setContent { SqlPulseTheme { ResultGrid(table = table) } }

        compose.onNodeWithText("note").assertIsDisplayed()
        compose.onNodeWithText("first").assertIsDisplayed()
        // NULL is shown as the word, not as an empty cell: an empty string is a different value.
        compose.onNodeWithText("NULL").assertIsDisplayed()
    }

    @Test
    fun aColumnSitsUnderItsOwnTitle() {
        // The bug this guards: the header cell hung its sort icon and drag handle off the end of
        // the label, so every header was 48dp wider than the cells beneath it and the columns
        // walked left of their titles — by the fourth column the data was under the third name.
        compose.setContent { SqlPulseTheme { ResultGrid(table = table) } }

        val header = compose.onNodeWithText("note").getUnclippedBoundsInRoot()
        val cell = compose.onNodeWithText("first").getUnclippedBoundsInRoot()

        // Both are padded inside their column by the same amount, so their left edges coincide.
        assertEquals(header.left.value, cell.left.value, 1f)
    }

    @Test
    fun aBlobShowsItsSizeAndNotItsContents() {
        compose.setContent { SqlPulseTheme { ResultGrid(table = table) } }

        // 2048 bytes, rendered as a size. Showing the bytes would mean having fetched them.
        compose.onNodeWithText("2 KB").assertIsDisplayed()
    }

    @Test
    fun tappingACellReportsThatCell() {
        var selected: CellSelection? = null
        compose.setContent {
            SqlPulseTheme { ResultGrid(table = table, onCellClick = { selected = it }) }
        }

        compose.onNodeWithText("first").performClick()

        assertEquals(0, selected?.rowIndex)
        assertEquals("note", selected?.column?.label)
        assertEquals(CellValue.Text("first"), selected?.value)
    }
}
