package hu.laurel.sqlpulse.ui.keys

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.crypto.wipe
import hu.laurel.sqlpulse.data.db.SshKeyEntity
import hu.laurel.sqlpulse.data.keys.DuplicateKeyException
import hu.laurel.sqlpulse.data.keys.KeyInUseException
import hu.laurel.sqlpulse.data.keys.KeyRejectedException
import hu.laurel.sqlpulse.data.keys.KeyRejection
import hu.laurel.sqlpulse.data.keys.SshKeyParser
import hu.laurel.sqlpulse.data.keys.SshKeyRepository
import hu.laurel.sqlpulse.di.IoDispatcher
import hu.laurel.sqlpulse.security.NoDeviceCredentialException
import hu.laurel.sqlpulse.security.UnlockCancelledException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class KeyImportState(
    val busy: Boolean = false,
    /** Set when the chosen key is passphrase-protected, so the form asks for one. */
    val needsPassphrase: Boolean = false,
    val error: String? = null,
    /** Public key of the key that was just added, shown so it can go into authorized_keys. */
    val addedPublicKey: String? = null,
    /** Seconds the passphrase field stays locked after repeated failures (§11). */
    val lockedForSeconds: Int = 0,
)

@HiltViewModel
class KeyStoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SshKeyRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val keys: StateFlow<List<SshKeyEntity>> = repository.observeKeys()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importState = MutableStateFlow(KeyImportState())
    val importState: StateFlow<KeyImportState> = _importState.asStateFlow()

    private var passphraseFailures = 0

    /** Reads a key file through the Storage Access Framework; the original file is left alone (§5). */
    fun loadFromUri(uri: Uri, onLoaded: (String) -> Unit) {
        viewModelScope.launch {
            _importState.value = _importState.value.copy(busy = true, error = null)
            val text = withContext(io) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text == null) {
                _importState.value = KeyImportState(error = context.getString(R.string.error_key_unreadable))
            } else {
                _importState.value = KeyImportState(needsPassphrase = SshKeyParser.isEncrypted(text))
                onLoaded(text)
            }
        }
    }

    fun onKeyTextChanged(text: String) {
        _importState.value = _importState.value.copy(
            needsPassphrase = text.isNotBlank() && SshKeyParser.isEncrypted(text),
            error = null,
        )
    }

    fun import(name: String, text: String, passphrase: CharArray?) {
        if (_importState.value.lockedForSeconds > 0) return
        viewModelScope.launch {
            _importState.value = _importState.value.copy(busy = true, error = null)
            try {
                val key = repository.import(name.ifBlank { defaultName() }, text, passphrase)
                passphraseFailures = 0
                _importState.value = KeyImportState(addedPublicKey = key.publicKey)
            } catch (e: Exception) {
                _importState.value = _importState.value.copy(busy = false, error = message(e))
                if (e is KeyRejectedException && e.rejection == KeyRejection.WrongPassphrase) {
                    onPassphraseFailure()
                }
            } finally {
                passphrase?.wipe()
            }
        }
    }

    fun generate(name: String) {
        viewModelScope.launch {
            _importState.value = _importState.value.copy(busy = true, error = null)
            try {
                val key = repository.generate(name.ifBlank { defaultName() })
                _importState.value = KeyImportState(addedPublicKey = key.publicKey)
            } catch (e: Exception) {
                _importState.value = KeyImportState(error = message(e))
            }
        }
    }

    fun delete(key: SshKeyEntity) {
        viewModelScope.launch {
            try {
                repository.delete(key.id)
            } catch (e: Exception) {
                // Most often KeyInUseException: a connection still points at this key.
                _importState.value = _importState.value.copy(
                    error = message(e) ?: context.getString(R.string.error_key_delete_failed),
                )
            }
        }
    }

    fun dismissImportState() {
        _importState.value = KeyImportState()
    }

    /** Clears only the message, so a failure shown outside the import form can be dismissed. */
    fun clearError() {
        _importState.value = _importState.value.copy(error = null)
    }

    /** §11: after five wrong passphrases the field is locked for thirty seconds. */
    private suspend fun onPassphraseFailure() {
        passphraseFailures++
        if (passphraseFailures < MAX_PASSPHRASE_ATTEMPTS) return
        for (remaining in PASSPHRASE_LOCK_SECONDS downTo 1) {
            _importState.value = _importState.value.copy(
                lockedForSeconds = remaining,
                error = context.getString(R.string.error_passphrase_backoff, remaining),
            )
            kotlinx.coroutines.delay(1_000)
        }
        passphraseFailures = 0
        _importState.value = _importState.value.copy(lockedForSeconds = 0, error = null)
    }

    private fun defaultName(): String = "key-" + System.currentTimeMillis().toString().takeLast(6)

    private fun message(e: Exception): String? = when (e) {
        is UnlockCancelledException -> null
        is NoDeviceCredentialException -> context.getString(R.string.error_no_device_credential)
        is DuplicateKeyException -> context.getString(R.string.error_key_duplicate, e.existingName)
        is KeyInUseException ->
            context.resources.getQuantityString(R.plurals.key_in_use, e.connections, e.connections)
        is KeyRejectedException -> when (val rejection = e.rejection) {
            KeyRejection.PuttyFormat -> context.getString(R.string.error_key_ppk)
            KeyRejection.DsaAlgorithm -> context.getString(R.string.error_key_dsa)
            is KeyRejection.RsaTooShort -> context.getString(R.string.error_key_rsa_short, rejection.bits)
            KeyRejection.WrongPassphrase -> context.getString(R.string.error_passphrase)
            is KeyRejection.Unreadable -> context.getString(R.string.error_key_unreadable)
        }

        else -> e.message ?: context.getString(R.string.error_key_unreadable)
    }

    private companion object {
        const val MAX_PASSPHRASE_ATTEMPTS = 5
        const val PASSPHRASE_LOCK_SECONDS = 30
    }
}
