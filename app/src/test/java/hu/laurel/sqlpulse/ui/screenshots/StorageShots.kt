package hu.laurel.sqlpulse.ui.screenshots

import hu.laurel.sqlpulse.data.schema.CounterKind
import hu.laurel.sqlpulse.data.schema.IndexRef
import hu.laurel.sqlpulse.data.schema.IndexSize
import hu.laurel.sqlpulse.data.schema.RedundantIndex
import hu.laurel.sqlpulse.data.schema.StorageSnapshot
import hu.laurel.sqlpulse.data.schema.StorageSort
import hu.laurel.sqlpulse.data.schema.StorageSource
import hu.laurel.sqlpulse.data.schema.StorageTable
import hu.laurel.sqlpulse.data.schema.UnavailableKind
import hu.laurel.sqlpulse.data.schema.UnusedBasis
import hu.laurel.sqlpulse.data.schema.UnusedIndexes
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.ui.storage.StorageController
import hu.laurel.sqlpulse.ui.storage.StorageScreenContent
import hu.laurel.sqlpulse.ui.storage.StorageUiState
import java.math.BigInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

class StorageShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    private fun controller(state: StorageUiState) = object : StorageController {
        override val uiState: StateFlow<StorageUiState> = MutableStateFlow(state)
        override fun setSort(sort: StorageSort) = Unit
        override fun refresh() = Unit
    }

    private fun mb(n: Long) = n * 1024 * 1024

    private fun table(
        name: String, dataMb: Long, indexMb: Long, freeMb: Long = 0, rows: Long? = null,
        next: BigInteger? = null, type: String? = null, indexes: List<IndexSize> = emptyList(),
    ) = StorageTable(
        name = name, engine = "InnoDB", rowFormat = "Dynamic", rowsEstimate = rows,
        dataBytes = mb(dataMb), indexBytes = mb(indexMb), freeBytes = mb(freeMb),
        autoIncrement = next, autoIncrementType = type,
        createTime = "2025-01-10 08:00:00", updateTime = "2026-10-01 17:42:10",
        collation = "utf8mb4_0900_ai_ci", indexes = indexes,
    )

    private val tables = listOf(
        table(
            "order_items", 1240, 610, 24, 8_412_000, BigInteger.valueOf(8_412_551), "int(10) unsigned",
            listOf(IndexSize("idx_order", mb(300)), IndexSize("idx_product", mb(240)), IndexSize("idx_created", mb(70))),
        ),
        table("orders", 420, 150, 8, 2_100_300, BigInteger.valueOf(2_100_811), "int(10) unsigned"),
        table("customers", 96, 41, 0, 310_200, BigInteger.valueOf(310_900), "int(10) unsigned"),
        table("audit_log", 38, 4, 120, 190_000, BigInteger.valueOf(190_100), "bigint(20) unsigned"),
        table("settings", 0, 0, 0, 42),
    )

    private val snapshot = StorageSnapshot(
        database = "webshop",
        tables = tables,
        indexSizesAvailable = true,
        unused = StorageSource.Loaded(
            UnusedIndexes(listOf(IndexRef("orders", "idx_legacy_ref"), IndexRef("customers", "idx_phone")), fromUserstat = false),
        ),
        redundant = StorageSource.Loaded(
            listOf(RedundantIndex("order_items", "idx_order", "order_id", "idx_order_product", "order_id, product_id")),
        ),
        uptimeSeconds = 41 * 86_400L + 5 * 3_600,
    )

    private val base = StorageUiState(connected = true, database = "webshop", snapshot = snapshot)

    @Test
    fun normal() = paparazzi.screen {
        StorageScreenContent(onBack = {}, onOpenTable = { _, _ -> }, viewModel = controller(base))
    }

    @Test
    fun indexSections() = paparazzi.screen {
        StorageScreenContent(
            onBack = {},
            onOpenTable = { _, _ -> },
            viewModel = controller(base.copy(snapshot = snapshot.copy(tables = tables.takeLast(2)))),
        )
    }

    @Test
    fun withWarnings() = paparazzi.screen {
        val risky = listOf(
            table("events", 5_200, 900, 700, 1_900_000_000, BigInteger.valueOf(3_900_000_000), "int(10) unsigned"),
            table("sessions", 300, 20, 10, 4_000_000, BigInteger.valueOf(30_000), "smallint(5) unsigned"),
            table("customers", 96, 41, 0, 310_200, BigInteger.valueOf(310_900), "int(10) unsigned"),
        )
        StorageScreenContent(
            onBack = {},
            onOpenTable = { _, _ -> },
            viewModel = controller(base.copy(snapshot = snapshot.copy(tables = risky))),
        )
    }

    @Test
    fun sourcesUnavailable() = paparazzi.screen {
        StorageScreenContent(
            onBack = {},
            onOpenTable = { _, _ -> },
            viewModel = controller(
                base.copy(
                    snapshot = snapshot.copy(
                        tables = tables.drop(1).take(2),
                        indexSizesAvailable = false,
                        unused = StorageSource.Unavailable(UnavailableKind.USERSTAT_OFF),
                        redundant = StorageSource.Unavailable(
                            UnavailableKind.FAILED,
                            "SELECT command denied to user 'ro'@'%' for table 'schema_redundant_indexes'",
                        ),
                        uptimeSeconds = null,
                    ),
                ),
            ),
        )
    }

    private fun engineTable(
        name: String, dataMb: Long, indexMb: Long, rows: Long?, next: BigInteger? = null, type: String? = null,
        counter: CounterKind = CounterKind.SEQUENCE, indexes: List<IndexSize> = emptyList(),
    ) = StorageTable(
        name = name, engine = null, rowFormat = null, rowsEstimate = rows,
        dataBytes = mb(dataMb), indexBytes = mb(indexMb), freeBytes = 0,
        autoIncrement = next, autoIncrementType = type, createTime = null, updateTime = null,
        collation = null, indexes = indexes, counter = counter,
    )

    @Test
    fun postgres() = paparazzi.screen {
        val pgTables = listOf(
            engineTable(
                "order_items", 1240, 610, 8_412_000, BigInteger.valueOf(8_412_551), "integer",
                indexes = listOf(IndexSize("order_items_order_id_idx", mb(300)), IndexSize("order_items_product_id_idx", mb(240))),
            ),
            engineTable("orders", 420, 150, 2_100_300, BigInteger.valueOf(2_100_811), "integer"),
            engineTable("sessions", 300, 20, 4_000_000, BigInteger.valueOf(30_001), "smallint"),
            engineTable("settings", 0, 0, 42),
        )
        StorageScreenContent(
            onBack = {},
            onOpenTable = { _, _ -> },
            viewModel = controller(
                base.copy(
                    database = "public",
                    snapshot = snapshot.copy(
                        database = "public",
                        engine = DatabaseEngine.POSTGRESQL,
                        tables = pgTables,
                        unused = StorageSource.Loaded(
                            UnusedIndexes(
                                listOf(IndexRef("orders", "orders_legacy_ref_idx")),
                                fromUserstat = false,
                                basis = UnusedBasis.STATS_RESET,
                            ),
                        ),
                        redundant = StorageSource.Unavailable(UnavailableKind.NOT_FOR_ENGINE),
                        uptimeSeconds = 12 * 86_400L + 3 * 3_600,
                    ),
                ),
            ),
        )
    }

    @Test
    fun sqliteFile() = paparazzi.screen {
        val liteTables = listOf(
            engineTable("messages", 18, 4, 120_400),
            engineTable("contacts", 1, 0, 812),
        )
        StorageScreenContent(
            onBack = {},
            onOpenTable = { _, _ -> },
            viewModel = controller(
                base.copy(
                    database = "main",
                    snapshot = snapshot.copy(
                        database = "main",
                        engine = DatabaseEngine.SQLITE,
                        tables = liteTables,
                        unused = StorageSource.Unavailable(UnavailableKind.NOT_FOR_ENGINE),
                        redundant = StorageSource.Unavailable(UnavailableKind.NOT_FOR_ENGINE),
                        uptimeSeconds = null,
                        fileBytes = mb(26),
                        fileFreeBytes = mb(3),
                    ),
                ),
            ),
        )
    }
}
