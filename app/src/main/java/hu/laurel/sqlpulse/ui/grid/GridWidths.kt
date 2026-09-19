package hu.laurel.sqlpulse.ui.grid

/**
 * How wide a result column starts out, in dp.
 *
 * Its own file so the arithmetic can be tested without a device. The header spends part of every
 * column on a sort icon and a drag handle, and the cells underneath get the same total width —
 * that is what keeps a column under its own title. A width that forgets the controls squeezes the
 * label instead; a header that hangs them off the end of the label, as this grid first did, makes
 * every header wider than its column and the two rows drift apart by 48dp per column.
 */
/** The sort icon's touch target, and the drag handle between two headers. */
internal const val SORT_ICON_TARGET = 36
internal const val RESIZE_HANDLE = 12

internal object GridWidths {

    /** What those two take out of every column, before the label gets any. */
    const val HEADER_CONTROLS_DP = SORT_ICON_TARGET + RESIZE_HANDLE

    /** Rough advance width of JetBrains Mono at 13sp. */
    const val CHAR_WIDTH_DP = 8

    const val MIN_CHARS = 6
    const val MAX_CHARS = 32

    /** Narrow enough to be useful, wide enough that a label still fits beside the controls. */
    const val MIN_WIDTH_DP = 96
    const val MAX_WIDTH_DP = 480

    fun columnWidthDp(labelLength: Int, widestValueLength: Int): Int {
        val characters = maxOf(labelLength, widestValueLength).coerceIn(MIN_CHARS, MAX_CHARS)
        return characters * CHAR_WIDTH_DP + HEADER_CONTROLS_DP
    }
}
