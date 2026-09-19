package hu.laurel.sqlpulse.ui

import androidx.annotation.StringRes
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.export.ExportFormat

/** The export menu's wording, kept out of the data layer so it stays translatable. */
@StringRes
fun ExportFormat.labelRes(): Int = when (this) {
    ExportFormat.CSV -> R.string.export_csv
    ExportFormat.TSV -> R.string.export_tsv
    ExportFormat.JSON -> R.string.export_json
    ExportFormat.SQL -> R.string.export_sql
    ExportFormat.XLSX -> R.string.export_xlsx
}
