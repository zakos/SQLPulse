package hu.laurel.sqlpulse.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Copies text to the clipboard.
 *
 * The label is the app's own name rather than what is being copied: on Android 13 and later the
 * system shows a preview of the clipboard content, and a label naming a table or a key would end
 * up in that preview too.
 */
fun Context.copyToClipboard(text: String) {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("sqlpulse", text))
}
