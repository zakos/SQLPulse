package hu.laurel.sqlpulse.ui.query

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.export.FullExport
import hu.laurel.sqlpulse.data.format.LocaleFormat
import hu.laurel.sqlpulse.ui.appLocale
import hu.laurel.sqlpulse.ui.components.DialogButtons
import hu.laurel.sqlpulse.ui.components.DialogCard
import hu.laurel.sqlpulse.ui.components.DialogHeading
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * A full export while it runs: how many rows are in the file so far, against the hard cap, and the
 * one thing to do about it — stop. The total is unknown (the statement is still streaming), so the
 * bar shows progress towards the cap rather than towards the end.
 */
@Composable
fun FullExportProgressCard(progress: FullExportProgress, onCancel: () -> Unit) {
    val semantic = LocalSemanticColors.current
    val locale = appLocale()
    DialogCard {
        DialogHeading(title = stringResource(R.string.export_full_title))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                LocaleFormat.integer(progress.rows, locale),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            )
            Text(
                stringResource(
                    R.string.export_full_progress,
                    LocaleFormat.integer(FullExport.MAX_ROWS, locale),
                    megabytes(progress.bytes),
                ),
                style = MonoStyles.cell.copy(fontSize = 12.sp),
                color = semantic.textSecondary,
            )
            LinearProgressIndicator(
                progress = { (progress.rows.toFloat() / FullExport.MAX_ROWS).coerceIn(0.02f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        DialogButtons(
            cancelLabel = stringResource(R.string.cancel),
            onCancel = onCancel,
            actionLabel = "",
            onAction = {},
            enabled = false,
            showAction = false,
        )
    }
}

/** Bytes as megabytes with one decimal, in the app's locale. */
private fun megabytes(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0))

/** What the notice about a capped export says: the reason and what the file holds. */
@Composable
fun exportNoticeText(notice: ExportNotice): String {
    val rows = LocaleFormat.integer(notice.rows, appLocale())
    return when (notice.cap) {
        hu.laurel.sqlpulse.data.export.ExportCap.ROWS ->
            stringResource(R.string.export_cap_rows, rows, LocaleFormat.integer(FullExport.MAX_ROWS, appLocale()))
        hu.laurel.sqlpulse.data.export.ExportCap.BYTES ->
            stringResource(R.string.export_cap_bytes, rows, FullExport.MAX_BYTES / (1024 * 1024))
    }
}
