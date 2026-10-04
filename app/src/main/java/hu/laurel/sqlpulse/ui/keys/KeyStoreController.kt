package hu.laurel.sqlpulse.ui.keys

import android.net.Uri
import hu.laurel.sqlpulse.data.db.SshKeyEntity
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screen reads and asks for. [KeyStoreViewModel] is the one real implementation; the interface lets
 * the screen be drawn from a fixed state in the screenshot tests that hold it against the design.
 */
interface KeyStoreController {
    fun clearError()
    fun delete(key: SshKeyEntity)
    fun dismissImportState()
    fun generate(name: String)
    fun import(name: String, text: String, passphrase: CharArray?)
    val importState: StateFlow<KeyImportState>
    val keys: StateFlow<List<SshKeyEntity>>
    fun loadFromUri(uri: Uri, onLoaded: (String) -> Unit)
    fun onKeyTextChanged(text: String)
}
