package hu.laurel.sqlpulse.ui.components

/**
 * Where one column becomes two (research summary, §2.0).
 *
 * 720dp is the usual break: a phone in landscape stays below it — there the two panes would each
 * get a strip too narrow to read — while a tablet, and a phone window on a desktop, clears it.
 */
object ScreenWidth {
    const val TWO_PANE_DP = 720

    fun isWide(widthDp: Int): Boolean = widthDp >= TWO_PANE_DP
}
