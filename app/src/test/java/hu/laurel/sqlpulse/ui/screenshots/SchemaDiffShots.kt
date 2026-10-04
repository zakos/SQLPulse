package hu.laurel.sqlpulse.ui.screenshots

import hu.laurel.sqlpulse.data.db.ConnectionEntity
import hu.laurel.sqlpulse.data.schema.CachedCheck
import hu.laurel.sqlpulse.data.schema.CachedColumn
import hu.laurel.sqlpulse.data.schema.CachedForeignKey
import hu.laurel.sqlpulse.data.schema.CachedIndex
import hu.laurel.sqlpulse.data.schema.CachedStructure
import hu.laurel.sqlpulse.data.schema.CachedTable
import hu.laurel.sqlpulse.data.schema.CachedTrigger
import hu.laurel.sqlpulse.data.schema.SchemaCapture
import hu.laurel.sqlpulse.data.schema.SchemaDiff
import hu.laurel.sqlpulse.data.schema.SchemaDiffOptions
import hu.laurel.sqlpulse.data.schema.SchemaDiffSide
import hu.laurel.sqlpulse.data.schema.TableKind
import hu.laurel.sqlpulse.ui.schemadiff.DiffSide
import hu.laurel.sqlpulse.ui.schemadiff.SchemaDiffController
import hu.laurel.sqlpulse.ui.schemadiff.SchemaDiffScreenContent
import hu.laurel.sqlpulse.ui.schemadiff.SchemaDiffSideState
import hu.laurel.sqlpulse.ui.schemadiff.SchemaDiffUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

class SchemaDiffShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    @Test
    fun result() = paparazzi.screen { draw(state()) }

    /** The whole list on one tall frame, so the opened table and the notes under it can be checked. */
    @Test
    fun full() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DesignPhone.copy(screenHeight = 3600))
        paparazzi.screen { draw(state()) }
    }

    /** One changed table open on its foreign key rules and CHECK constraints, then a changed view. */
    @Test
    fun checksAndRules() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DesignPhone.copy(screenHeight = 3100))
        paparazzi.screen { draw(only("orders")) }
    }

    @Test
    fun viewDefinition() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DesignPhone.copy(screenHeight = 1700))
        paparazzi.screen { draw(only("v_daily_sales")) }
    }

    /** Triggers never read on side B: the comparison says so instead of calling it a match. */
    @Test
    fun triggersNotRead() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DesignPhone.copy(screenHeight = 1500))
        paparazzi.screen {
            val base = state()
            val b = base.b.capture!!
            val noTriggers = b.copy(side = b.side.copy(triggers = null))
            draw(
                base.copy(
                    b = base.b.copy(capture = noTriggers),
                    result = SchemaDiff.compare(base.a.capture!!.side, noTriggers.side, base.options)
                        .let { it.copy(tables = emptyList()) },
                ),
            )
        }
    }

    private fun only(name: String): SchemaDiffUiState {
        val base = state()
        return base.copy(result = base.result!!.copy(tables = base.result!!.tables.filter { it.name == name }))
    }

    @Test
    fun light() = paparazzi.screen(dark = false) { draw(state()) }

    /** The live side mid-refresh, and the other side never opened on this phone. */
    @Test
    fun refreshing() = paparazzi.screen {
        val base = state()
        draw(
            base.copy(
                a = base.a.copy(progress = 17 to 42),
                b = SchemaDiffSideState(connectionId = 1, database = "shop", databases = listOf("shop"), capture = capture("shop", emptyList(), null)),
                result = null,
            ),
        )
    }

    @androidx.compose.runtime.Composable
    private fun draw(state: SchemaDiffUiState) {
        SchemaDiffScreenContent(onBack = {}, viewModel = object : SchemaDiffController {
            override val uiState: StateFlow<SchemaDiffUiState> = MutableStateFlow(state)
            override fun selectConnection(side: DiffSide, connectionId: Long) = Unit
            override fun selectDatabase(side: DiffSide, database: String) = Unit
            override fun refresh(side: DiffSide) = Unit
            override fun swap() = Unit
            override fun setOptions(options: SchemaDiffOptions) = Unit
        })
    }

    // ------------------------------------------------------------------ fixture

    private fun state(): SchemaDiffUiState {
        val dev = capture("shop_dev", devTables, NOW - 2 * MINUTE, devViews, devTriggers)
        val prod = capture("shop", prodTables, NOW - 3 * DAY, prodViews, prodTriggers)
        val options = SchemaDiffOptions()
        return SchemaDiffUiState(
            connections = listOf(
                connection(1, "Webshop", "PRODUCTION", "Production", "shop"),
                connection(3, "Webshop dev", "DEVELOPMENT", "Blue", "shop_dev"),
            ),
            liveConnectionId = 3,
            a = SchemaDiffSideState(connectionId = 3, database = "shop_dev", databases = listOf("shop_dev"), capture = dev),
            b = SchemaDiffSideState(connectionId = 1, database = "shop", databases = listOf("shop"), capture = prod),
            options = options,
            result = SchemaDiff.compare(dev.side, prod.side, options),
            now = NOW,
        )
    }

    private data class T(val table: CachedTable, val structure: CachedStructure?)

    private fun capture(
        database: String,
        tables: List<T>,
        takenAt: Long?,
        views: Map<String, String> = emptyMap(),
        triggers: List<CachedTrigger>? = null,
    ): SchemaCapture {
        val moved = tables.map { it.copy(table = it.table.copy(database = database)) }
        val structures = moved.mapNotNull { t ->
            t.structure?.let { s ->
                t.table.name to s.copy(
                    foreignKeys = s.foreignKeys.map { it.copy(referencedDatabase = database) },
                    // A captured structure always says whether it has CHECK constraints.
                    checks = s.checks ?: emptyList(),
                )
            }
        }.toMap()
        return SchemaCapture(
            side = SchemaDiffSide(
                database, moved.map { it.table }, structures,
                viewDefinitions = views.mapValues { it.value.replace("@DB@", database) },
                triggers = triggers,
            ),
            tablesCapturedAt = takenAt,
            structuresCaptured = structures.size,
            oldestStructureAt = takenAt?.let { it - if (database == "shop") 9 * DAY else 0 },
        )
    }

    private fun table(name: String, kind: TableKind = TableKind.TABLE, collation: String = "utf8mb4_0900_ai_ci") =
        CachedTable("", name, kind.name, null, null, if (kind == TableKind.VIEW) null else "InnoDB", collation, null, null)

    private fun col(name: String, type: String, nullable: Boolean = false, default: String? = null, extra: String? = null) =
        CachedColumn(name, type, nullable, default, name == "id", extra, null, 0)

    private fun cols(vararg columns: CachedColumn) = columns.mapIndexed { i, c -> c.copy(position = i) }

    private fun pk() = CachedIndex("PRIMARY", true, listOf("id"), 0)

    private val customersDev = cols(
        col("id", "int unsigned", extra = "auto_increment"),
        col("email", "varchar(255)"),
        col("name", "varchar(120)"),
        col("phone", "varchar(32)", nullable = true),
        col("created_at", "datetime", default = "CURRENT_TIMESTAMP", extra = "DEFAULT_GENERATED"),
    )

    private val devTables = listOf(
        T(table("customers"), CachedStructure(customersDev, listOf(pk(), CachedIndex("ux_email", true, listOf("email"), 1)), emptyList())),
        T(
            table("orders"),
            CachedStructure(
                cols(
                    col("id", "int unsigned", extra = "auto_increment"),
                    col("customer_id", "int unsigned"),
                    col("total", "decimal(12,2)"),
                    col("status", "enum('new','paid','sent','cancelled')", default = "new"),
                    col("discount_code", "varchar(32)", nullable = true),
                ),
                listOf(pk(), CachedIndex("ix_customer", false, listOf("customer_id"), 1), CachedIndex("ix_status", false, listOf("status", "created_at"), 2)),
                listOf(CachedForeignKey("fk_orders_customer", "customer_id", "", "customers", "id", onDelete = "CASCADE", onUpdate = "RESTRICT")),
                checks = listOf(
                    CachedCheck("chk_total_positive", "(`total` >= 0)"),
                    CachedCheck("chk_status_known", "(`status` in ('new','paid','sent','cancelled'))"),
                ),
            ),
        ),
        T(table("order_items"), CachedStructure(cols(col("id", "int unsigned"), col("order_id", "int unsigned"), col("qty", "int")), listOf(pk()), emptyList())),
        T(table("coupons"), null),
        T(table("v_daily_sales", TableKind.VIEW), CachedStructure(cols(col("day", "date"), col("total", "decimal(34,2)", nullable = true)), emptyList(), emptyList())),
        T(table("invoices"), null),
    )

    private val prodTables = listOf(
        T(
            table("customers", collation = "utf8mb4_general_ci"),
            CachedStructure(
                cols(
                    col("id", "int(10) unsigned", extra = "auto_increment"),
                    col("email", "varchar(190)"),
                    col("name", "varchar(120)"),
                    col("created_at", "datetime", default = "current_timestamp()"),
                ),
                listOf(pk(), CachedIndex("email", true, listOf("email"), 1)),
                emptyList(),
            ),
        ),
        T(
            table("orders"),
            CachedStructure(
                cols(
                    col("id", "int(10) unsigned", extra = "auto_increment"),
                    col("customer_id", "int(10) unsigned"),
                    col("total", "decimal(10,2)"),
                    col("status", "enum('new','paid','sent')", default = "'new'"),
                ),
                listOf(pk(), CachedIndex("customer_id", false, listOf("customer_id"), 1)),
                listOf(CachedForeignKey("orders_ibfk_1", "customer_id", "", "customers", "id", onDelete = "RESTRICT", onUpdate = "RESTRICT")),
                checks = listOf(CachedCheck("chk_status_known", "`status` in ('new','paid','sent','cancelled')")),
            ),
        ),
        T(table("order_items"), CachedStructure(cols(col("id", "int(10) unsigned"), col("order_id", "int(10) unsigned"), col("qty", "int(11)")), listOf(pk()), emptyList())),
        T(table("legacy_export"), null),
        T(table("v_daily_sales", TableKind.VIEW), CachedStructure(cols(col("day", "date"), col("total", "decimal(34,2)", nullable = true)), emptyList(), emptyList())),
        T(table("invoices"), CachedStructure(cols(col("id", "int(10) unsigned")), listOf(pk()), emptyList())),
    )

    private val devViews = mapOf(
        "v_daily_sales" to "CREATE ALGORITHM=UNDEFINED DEFINER=`dev`@`%` SQL SECURITY DEFINER VIEW `@DB@`.`v_daily_sales` AS " +
            "select cast(`@DB@`.`orders`.`created_at` as date) AS `day`,sum(`@DB@`.`orders`.`total`) AS `total` " +
            "from `@DB@`.`orders` where `@DB@`.`orders`.`status` <> 'cancelled' group by cast(`@DB@`.`orders`.`created_at` as date)",
    )

    private val prodViews = mapOf(
        "v_daily_sales" to "CREATE ALGORITHM=UNDEFINED DEFINER=`deploy`@`10.0.0.%` SQL SECURITY DEFINER VIEW `@DB@`.`v_daily_sales` AS " +
            "select cast(`@DB@`.`orders`.`created_at` as date) AS `day`,sum(`@DB@`.`orders`.`total`) AS `total` " +
            "from `@DB@`.`orders` group by cast(`@DB@`.`orders`.`created_at` as date)",
    )

    private val devTriggers = listOf(
        CachedTrigger("trg_orders_bi", "orders", "BEFORE", "INSERT", "SET NEW.created_at = UTC_TIMESTAMP()"),
        CachedTrigger("trg_items_ai", "order_items", "AFTER", "INSERT", "UPDATE orders SET total = total + NEW.qty WHERE id = NEW.order_id"),
        CachedTrigger("trg_customers_bu", "customers", "BEFORE", "UPDATE", "SET NEW.email = LOWER(NEW.email)"),
    )

    private val prodTriggers = listOf(
        CachedTrigger("trg_orders_bi", "orders", "BEFORE", "INSERT", "SET NEW.created_at = NOW()"),
        CachedTrigger("trg_customers_bu", "customers", "BEFORE", "UPDATE", "SET NEW.email=LOWER(NEW.email)"),
    )

    private fun connection(id: Long, name: String, environment: String, color: String, database: String) = ConnectionEntity(
        id = id,
        name = name,
        color = color,
        sshHost = "bastion.laurel.hu",
        sshUser = "deploy",
        sshKeyId = null,
        dbHost = "db",
        database = database,
        dbUser = "app_ro",
        environment = environment,
    )

    private companion object {
        const val NOW = 1_790_000_000_000L
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
