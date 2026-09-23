package hu.laurel.sqlpulse.ui.connections

import android.net.Uri
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.data.db.SshKeyEntity
import hu.laurel.sqlpulse.ssh.HostKeyPrompt
import hu.laurel.sqlpulse.ssh.TunnelState
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screen reads and asks for. [ConnectionEditorViewModel] is the one real implementation; the interface lets
 * the screen be drawn from a fixed state in the screenshot tests that hold it against the design.
 */
interface ConnectionEditorController {
    fun acceptHostKey()
    fun acceptReadOnlyOffer()
    fun clearCertificate()
    fun dismissReadOnlyOffer()
    val error: StateFlow<String?>
    val form: StateFlow<ConnectionForm>
    val hostKeyPrompt: StateFlow<HostKeyPrompt?>
    fun importCertificate(uri: Uri, name: String)
    val keys: StateFlow<List<SshKeyEntity>>
    val readOnlyOffer: StateFlow<Boolean>
    fun rejectHostKey()
    fun save(onSaved: (Long) -> Unit)
    val serverVersion: StateFlow<String?>
    fun setEnvironment(environment: ConnectionEnvironment)
    fun setUseSsh(useSsh: Boolean)
    fun stopTest()
    fun test()
    val tunnel: StateFlow<TunnelState>
    fun update(transform: (ConnectionForm) -> ConnectionForm)
}
