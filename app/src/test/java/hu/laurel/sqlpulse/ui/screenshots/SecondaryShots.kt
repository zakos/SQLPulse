package hu.laurel.sqlpulse.ui.screenshots

import android.net.Uri
import hu.laurel.sqlpulse.LockScreen
import hu.laurel.sqlpulse.data.backup.MergeResolution
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.db.KeyMaterialFormat
import hu.laurel.sqlpulse.data.db.SshKeyAlgorithm
import hu.laurel.sqlpulse.data.db.SshKeyEntity
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.schema.ForeignKey
import hu.laurel.sqlpulse.data.schema.RowLink
import hu.laurel.sqlpulse.data.schema.SchemaColumn
import hu.laurel.sqlpulse.data.schema.SchemaIndex
import hu.laurel.sqlpulse.data.schema.TableStructure
import hu.laurel.sqlpulse.data.sql.SslMode
import hu.laurel.sqlpulse.ssh.HostKeyPrompt
import hu.laurel.sqlpulse.ssh.TunnelState
import hu.laurel.sqlpulse.ui.backup.BackupController
import hu.laurel.sqlpulse.ui.backup.BackupScreenContent
import hu.laurel.sqlpulse.ui.backup.BackupUiState
import hu.laurel.sqlpulse.ui.connections.ConnectionEditorController
import hu.laurel.sqlpulse.ui.connections.ConnectionEditorScreenContent
import hu.laurel.sqlpulse.ui.connections.ConnectionForm
import hu.laurel.sqlpulse.ui.diagnostics.DiagnosticsController
import hu.laurel.sqlpulse.ui.diagnostics.DiagnosticsScreenContent
import hu.laurel.sqlpulse.ui.diagnostics.DiagnosticsUiState
import hu.laurel.sqlpulse.ui.schema.TableDetailController
import hu.laurel.sqlpulse.ui.schema.TableDetailScreenContent
import hu.laurel.sqlpulse.ui.schema.TableDetailUiState
import hu.laurel.sqlpulse.ui.schema.TableTab
import hu.laurel.sqlpulse.ui.theme.ConnectionColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

class SecondaryShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    @Test
    fun lock() = paparazzi.screen { LockScreen(onUnlock = {}) }

    @Test
    fun backup() = paparazzi.screen {
        BackupScreenContent(onBack = {}, viewModel = object : BackupController {
            override val state: StateFlow<BackupUiState> = MutableStateFlow(
                BackupUiState(includeSecrets = true, exportPassphrase = "tavasz-kert-hordó-vonat-kilenc", exportConfirmation = "tavasz-kert-hordó-vonat-kilenc"),
            )
            override fun chooseFile(uri: Uri) = Unit
            override fun dismissProblem() = Unit
            override fun export(uri: Uri) = Unit
            override fun import() = Unit
            override fun setDefaultResolution(resolution: MergeResolution) = Unit
            override fun setExportConfirmation(value: String) = Unit
            override fun setExportPassphrase(value: String) = Unit
            override fun setImportPassphrase(value: String) = Unit
            override fun setIncludeSecrets(include: Boolean) = Unit
            override fun setResolution(sourceName: String, resolution: MergeResolution) = Unit
            override fun startOver() = Unit
            override fun suggestedFileName() = "sqlpulse.spb"
            override fun unlock() = Unit
        })
    }

    @Test
    fun diagnostics() = paparazzi.screen {
        DiagnosticsScreenContent(onBack = {}, viewModel = object : DiagnosticsController {
            override val uiState: StateFlow<DiagnosticsUiState> = MutableStateFlow(DiagnosticsUiState(loading = false, connected = true))
            override fun markCopied() = Unit
        })
    }

    @Test
    fun tableStructure() = paparazzi.screen {
        TableDetailScreenContent(onBack = {}, onOpenTable = { _, _ -> }, viewModel = FakeTable(
            TableDetailUiState(
                database = "billing",
                table = "invoices",
                tab = TableTab.STRUCTURE,
                structure = TableStructure(
                    columns = listOf(
                        SchemaColumn("id", "bigint unsigned", false, null, true, "auto_increment", null),
                        SchemaColumn("customer_id", "bigint unsigned", false, null, false, null, null),
                        SchemaColumn("total", "decimal(12,2)", false, "0.00", false, null, null),
                        SchemaColumn("status", "enum('draft','paid','overdue','void')", false, "'draft'", false, null, null),
                        SchemaColumn("issued_at", "datetime", true, null, false, null, null),
                        SchemaColumn("total_gross", "decimal(12,2)", true, null, false, "STORED GENERATED", null, generationExpression = "total * 1.27"),
                        SchemaColumn("note", "json", true, null, false, null, null),
                    ),
                    indexes = listOf(SchemaIndex("PRIMARY", true, listOf("id")), SchemaIndex("idx_customer", false, listOf("customer_id"))),
                    foreignKeys = listOf(ForeignKey("fk_invoice_customer", "customer_id", "billing", "customers", "id", onDelete = "RESTRICT")),
                ),
            ),
        ))
    }

    private class FakeTable(state: TableDetailUiState) : TableDetailController {
        override val uiState: StateFlow<TableDetailUiState> = MutableStateFlow(state)
        override fun closeWalk() = Unit
        override fun confirmEdit() = Unit
        override fun confirmImport() = Unit
        override fun dismissConflict() = Unit
        override fun dismissEdit() = Unit
        override fun dismissError() = Unit
        override fun dismissImport() = Unit
        override fun export(format: ExportFormat) = Unit
        override fun loadMore() = Unit
        override fun openChildren(link: RowLink) = Unit
        override fun openParent(rowIndex: Int, columnLabel: String) = Unit
        override fun openParentFromWalk(rowIndex: Int, columnLabel: String) = Unit
        override fun overwriteConflict() = Unit
        override fun parentLinkFor(rowIndex: Int, columnLabel: String): RowLink? = null
        override fun prepareCellEdit(rowIndex: Int, columnLabel: String, newValue: String?) = Unit
        override fun prepareImport(uri: Uri) = Unit
        override fun prepareRowDelete(rowIndex: Int) = Unit
        override fun select(tab: TableTab) = Unit
        override fun selectWalkRow(rowIndex: Int) = Unit
        override fun setFilter(column: String?, contains: String) = Unit
        override fun shareIntentHandled() = Unit
        override fun showChildrenOf(rowIndex: Int) = Unit
        override fun sortBy(column: String) = Unit
        override fun undo() = Unit
        override fun walkBack() = Unit
        override fun walkParentLinkFor(rowIndex: Int, columnLabel: String): RowLink? = null
    }
}

class EditorShots {
    /** The editor scrolls; the artboard shows all of it, so the screenshot is as tall. */
    @get:Rule
    val paparazzi = app.cash.paparazzi.Paparazzi(deviceConfig = DesignPhone.copy(screenHeight = 3440), showSystemUi = false)

    @Test
    fun editor() = paparazzi.screen {
        ConnectionEditorScreenContent(onBack = {}, onOpenKeyStore = {}, viewModel = object : ConnectionEditorController {
            override val form: StateFlow<ConnectionForm> = MutableStateFlow(
                ConnectionForm(
                    name = "Számlázó", color = ConnectionColor.Blue, useSsh = true, sshHost = "jump.test.local", sshUser = "deploy",
                    sshKeyId = 1, dbHost = "10.0.4.12", database = "billing", dbUser = "app_ro", readOnly = true,
                    environment = ConnectionEnvironment.TEST, sslMode = SslMode.VERIFY_CA, caCertificate = "laurel-internal-ca.pem",
                ),
            )
            override val error: StateFlow<String?> = MutableStateFlow(null)
            override val hostKeyPrompt: StateFlow<HostKeyPrompt?> = MutableStateFlow(null)
            override val keys: StateFlow<List<SshKeyEntity>> = MutableStateFlow(
                listOf(SshKeyEntity(1, "laptop-ed25519", SshKeyAlgorithm.ED25519, 256, "SHA256:q3Vx0kLr2cX7yQmN8pT5sLd0Fj1aB9fKc", KeyMaterialFormat.ED25519_SEED, ByteArray(0), "ssh-ed25519 AAAA", 0)),
            )
            override val readOnlyOffer: StateFlow<Boolean> = MutableStateFlow(false)
            override val serverVersion: StateFlow<String?> = MutableStateFlow("8.0.39")
            override val tunnel: StateFlow<TunnelState> = MutableStateFlow(TunnelState.Disconnected)
            override fun acceptHostKey() = Unit
            override fun acceptReadOnlyOffer() = Unit
            override fun clearCertificate() = Unit
            override fun dismissReadOnlyOffer() = Unit
            override fun importCertificate(uri: Uri, name: String) = Unit
            override fun rejectHostKey() = Unit
            override fun save(onSaved: (Long) -> Unit) = Unit
            override fun setEnvironment(environment: ConnectionEnvironment) = Unit
            override fun setUseSsh(useSsh: Boolean) = Unit
            override fun stopTest() = Unit
            override fun test() = Unit
            override fun update(transform: (ConnectionForm) -> ConnectionForm) = Unit
        })
    }
}
