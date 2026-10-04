package hu.laurel.sqlpulse.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.laurel.sqlpulse.data.backup.BackupFile
import hu.laurel.sqlpulse.data.backup.BackupFormatException
import hu.laurel.sqlpulse.data.backup.BackupMerge
import hu.laurel.sqlpulse.data.backup.BackupMetadata
import hu.laurel.sqlpulse.data.backup.BackupPayload
import hu.laurel.sqlpulse.data.backup.BackupRepository
import hu.laurel.sqlpulse.data.backup.BackupUnlockException
import hu.laurel.sqlpulse.data.backup.ImportOutcome
import hu.laurel.sqlpulse.data.backup.MergeDecision
import hu.laurel.sqlpulse.data.backup.MergeResolution
import hu.laurel.sqlpulse.data.backup.PassphrasePolicy
import hu.laurel.sqlpulse.data.backup.UnsupportedBackupVersionException
import hu.laurel.sqlpulse.data.crypto.wipe
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A refusal the screen has a sentence for. [detail] carries whatever the exception said. */
sealed interface BackupProblem {
    data object NotABackup : BackupProblem
    data class FutureVersion(val fileVersion: Int, val supportedVersion: Int) : BackupProblem
    data object WrongPassphrase : BackupProblem
    data class Damaged(val detail: String) : BackupProblem
    data class Failed(val detail: String) : BackupProblem
}

/** How far the restore has got. Nothing is written before [Previewed] has been accepted. */
enum class ImportStage { NOTHING_CHOSEN, FILE_CHOSEN, PREVIEWED, DONE }

data class BackupUiState(
    val busy: Boolean = false,
    val problem: BackupProblem? = null,

    /** Settings-only is the default; the user has to ask for the secrets. */
    val includeSecrets: Boolean = false,
    val exportPassphrase: String = "",
    val exportConfirmation: String = "",
    val exportProblem: PassphrasePolicy.Problem? = null,
    val exported: Boolean = false,

    val stage: ImportStage = ImportStage.NOTHING_CHOSEN,
    val fileMetadata: BackupMetadata? = null,
    val importPassphrase: String = "",
    val decisions: List<MergeDecision> = emptyList(),
    val defaultResolution: MergeResolution = MergeResolution.KEEP_BOTH,
    val outcome: ImportOutcome? = null,
) {
    val conflicts: List<MergeDecision> get() = decisions.filter { it.conflicting }
    val importable: Int get() = decisions.count { it.imports }
    val canExport: Boolean
        get() = !busy && PassphrasePolicy.check(exportPassphrase, exportConfirmation) == null
}

/**
 * Drives the backup screen.
 *
 * The passphrases live in this state as Strings because a text field hands out Strings and
 * nothing else; each is turned into a CharArray at the moment of use and wiped, and the state is
 * cleared as soon as the operation finishes, so the passphrase does not outlive the screen.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val repository: BackupRepository,
) : ViewModel(), BackupController {

    private val _state = MutableStateFlow(BackupUiState())
    override val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** The bytes of the chosen file, held only until the import finishes or is abandoned. */
    private var chosenFile: ByteArray? = null
    private var unlocked: BackupPayload? = null

    override fun setIncludeSecrets(include: Boolean) =
        _state.update { it.copy(includeSecrets = include, exported = false) }

    override fun setExportPassphrase(value: String) =
        _state.update { it.copy(exportPassphrase = value, exportProblem = null, exported = false) }

    override fun setExportConfirmation(value: String) =
        _state.update { it.copy(exportConfirmation = value, exportProblem = null, exported = false) }

    override fun suggestedFileName(): String = BackupFile.suggestedFileName(System.currentTimeMillis())

    override fun export(uri: Uri) {
        val current = _state.value
        val problem = PassphrasePolicy.check(current.exportPassphrase, current.exportConfirmation)
        if (problem != null) {
            _state.update { it.copy(exportProblem = problem) }
            return
        }
        launchGuarded {
            val payload = repository.collect(includeSecrets = current.includeSecrets)
            val passphrase = current.exportPassphrase.toCharArray()
            try {
                repository.writeTo(uri, payload, passphrase)
            } finally {
                passphrase.wipe()
            }
            _state.update {
                it.copy(exported = true, exportPassphrase = "", exportConfirmation = "")
            }
        }
    }

    /** Reads the file and shows its cleartext header. Still nothing decrypted, nothing written. */
    override fun chooseFile(uri: Uri) = launchGuarded {
        val bytes = repository.readFile(uri)
        val metadata = BackupFile.peek(bytes)
        chosenFile = bytes
        unlocked = null
        _state.update {
            it.copy(
                stage = ImportStage.FILE_CHOSEN,
                fileMetadata = metadata,
                importPassphrase = "",
                decisions = emptyList(),
                outcome = null,
            )
        }
    }

    override fun setImportPassphrase(value: String) =
        _state.update { it.copy(importPassphrase = value, problem = null) }

    /** Decrypts and verifies. A wrong passphrase stops here, with everything still untouched. */
    override fun unlock() {
        val bytes = chosenFile ?: return
        val passphrase = _state.value.importPassphrase.toCharArray()
        launchGuarded(onComplete = { passphrase.wipe() }) {
            val payload = BackupFile.read(bytes, passphrase)
            val existing = repository.connectionNames()
            unlocked = payload
            _state.update {
                it.copy(
                    stage = ImportStage.PREVIEWED,
                    importPassphrase = "",
                    decisions = BackupMerge.plan(payload, existing, it.defaultResolution),
                )
            }
        }
    }

    override fun setDefaultResolution(resolution: MergeResolution) {
        val payload = unlocked ?: return
        launchGuarded {
            val existing = repository.connectionNames()
            _state.update {
                it.copy(
                    defaultResolution = resolution,
                    decisions = BackupMerge.plan(payload, existing, resolution),
                )
            }
        }
    }

    override fun setResolution(sourceName: String, resolution: MergeResolution) {
        val payload = unlocked ?: return
        launchGuarded {
            val existing = repository.connectionNames()
            val perConnection = _state.value.decisions
                .filter { it.conflicting }
                .associate { it.sourceName to it.resolution }
                .toMutableMap()
            perConnection[sourceName] = resolution
            _state.update {
                it.copy(
                    decisions = BackupMerge.plan(
                        payload,
                        existing,
                        it.defaultResolution,
                        perConnection,
                    ),
                )
            }
        }
    }

    override fun import() {
        val payload = unlocked ?: return
        launchGuarded {
            val outcome = repository.apply(payload, _state.value.decisions)
            unlocked = null
            chosenFile = null
            _state.update { it.copy(stage = ImportStage.DONE, outcome = outcome) }
        }
    }

    override fun startOver() {
        chosenFile = null
        unlocked = null
        _state.update {
            BackupUiState(includeSecrets = it.includeSecrets)
        }
    }

    override fun dismissProblem() = _state.update { it.copy(problem = null) }

    override fun onCleared() {
        chosenFile = null
        unlocked = null
    }

    private fun launchGuarded(
        onComplete: (() -> Unit)? = null,
        block: suspend () -> Unit,
    ) {
        _state.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                val problem = e.toProblem()
                _state.update { it.copy(problem = problem) }
            } finally {
                onComplete?.invoke()
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun Exception.toProblem(): BackupProblem = when (this) {
        is UnsupportedBackupVersionException -> BackupProblem.FutureVersion(fileVersion, supportedVersion)
        is BackupUnlockException -> BackupProblem.WrongPassphrase
        is BackupFormatException ->
            if (message.orEmpty().contains("not a SQLPulse backup")) {
                BackupProblem.NotABackup
            } else {
                BackupProblem.Damaged(message.orEmpty())
            }
        else -> BackupProblem.Failed(message ?: this::class.java.simpleName)
    }
}
