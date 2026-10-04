package hu.laurel.sqlpulse.ui.query

import hu.laurel.sqlpulse.data.export.ExportCap

/** A full export that is running: what has been written so far. */
data class FullExportProgress(val rows: Long = 0, val bytes: Long = 0)

/** A full export that stopped at a hard cap; the file holds [rows] rows and is complete up to there. */
data class ExportNotice(val cap: ExportCap, val rows: Long, val bytes: Long)
