package hu.laurel.sqlpulse.ui.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.snapshot.CellChange
import hu.laurel.sqlpulse.data.snapshot.ComparedSide
import hu.laurel.sqlpulse.data.snapshot.ComparisonOutcome
import hu.laurel.sqlpulse.data.snapshot.KeyProblem
import hu.laurel.sqlpulse.data.snapshot.MatchStrategy
import hu.laurel.sqlpulse.data.snapshot.ResultDiff
import hu.laurel.sqlpulse.data.snapshot.RowChangeKind
import hu.laurel.sqlpulse.data.snapshot.RowDiff
import hu.laurel.sqlpulse.data.snapshot.SnapshotOrigin
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.ui.snapshot.OutcomeBody
import org.junit.Rule
import org.junit.Test

/** The cross-connection comparison (dev against production) as the sheet draws it. */
class DataCompareShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    private fun num(v: String) = CellValue.Number(v)
    private fun txt(v: String) = CellValue.Text(v)

    private val columns = listOf("kod", "nev", "ar", "keszlet")
    private val dev = SnapshotOrigin(1, "Fejlesztői adatbázis", ConnectionEnvironment.DEVELOPMENT, "Blue", "webshop", "SELECT * FROM termek")
    private val prod = SnapshotOrigin(2, "Éles webshop", ConnectionEnvironment.PRODUCTION, "Production", "webshop", "SELECT * FROM termek")

    private fun diff(strategy: MatchStrategy, rows: List<RowDiff>) = ResultDiff(
        columns = columns,
        strategy = strategy,
        rows = rows,
        unchangedCount = 1184,
        beforeRowCount = 1190,
        afterRowCount = 1189,
        partial = false,
        takenAt = 1_790_000_000_000L,
        comparedAt = 1_790_000_240_000L,
        beforeOrigin = dev,
        afterOrigin = prod,
    )

    private val rows = listOf(
        RowDiff(RowChangeKind.ADDED, listOf(txt("K-2210")), null, listOf(txt("K-2210"), txt("Kábelkötegelő"), num("890"), num("120"))),
        RowDiff(RowChangeKind.REMOVED, listOf(txt("K-0042")), listOf(txt("K-0042"), txt("Tesztterméke"), num("1"), num("0")), null),
        RowDiff(RowChangeKind.REMOVED, listOf(txt("K-0043")), listOf(txt("K-0043"), txt("Teszt csomag"), num("1"), num("0")), null),
        RowDiff(
            RowChangeKind.CHANGED, listOf(txt("K-1001")), listOf(txt("K-1001"), txt("USB-C kábel"), num("2490"), num("35")),
            listOf(txt("K-1001"), txt("USB-C kábel"), num("2990"), num("35")),
            listOf(CellChange(2, "ar", num("2490"), num("2990"))),
        ),
        RowDiff(
            RowChangeKind.CHANGED, listOf(txt("K-1007")), listOf(txt("K-1007"), txt("Hálózati töltő"), num("4990"), num("12")),
            listOf(txt("K-1007"), txt("Hálózati töltő"), num("4990"), num("0")),
            listOf(CellChange(3, "keszlet", num("12"), num("0"))),
        ),
    )

    @Test
    fun crossConnectionDiff() = paparazzi.screen {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutcomeBody(ComparisonOutcome.Compared(diff(MatchStrategy.PrimaryKey(listOf("kod")), rows)), onPickKey = {})
        }
    }

    @Test
    fun noKeyDetected() = paparazzi.screen {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutcomeBody(
                ComparisonOutcome.Compared(diff(MatchStrategy.WholeRow, rows.filter { it.kind != RowChangeKind.CHANGED })),
                onPickKey = {},
            )
        }
    }

    @Test
    fun duplicateKey() = paparazzi.screen {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutcomeBody(
                ComparisonOutcome.KeyUnusable(
                    keyColumns = listOf("nev"),
                    problem = KeyProblem.DUPLICATE,
                    side = ComparedSide.AFTER,
                    rowCount = 3,
                    example = listOf(txt("USB-C kábel")),
                    columns = columns,
                ),
                onPickKey = {},
            )
        }
    }
}
