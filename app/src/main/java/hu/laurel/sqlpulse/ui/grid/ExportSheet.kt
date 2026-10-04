package hu.laurel.sqlpulse.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.export.ExportFormat
import hu.laurel.sqlpulse.data.export.FullExport
import hu.laurel.sqlpulse.data.format.LocaleFormat
import hu.laurel.sqlpulse.ui.appLocale
import hu.laurel.sqlpulse.ui.components.DialogNote
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * Export (§7.7), as a sheet: pick the format, then share. What goes into the file is what is on
 * screen — the sheet says how many rows that is, because a filter can be hiding some — or, when
 * the app cut the result with its own limit, the full result re-run without it. The file exists
 * only until the share sheet has taken it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    rowCount: Int,
    onExport: (ExportFormat) -> Unit,
    onDismiss: () -> Unit,
    /** True when the result on screen is only part of the answer and can be exported in full. */
    fullAvailable: Boolean = false,
    onExportFull: (ExportFormat) -> Unit = {},
) {
    var selected by remember { mutableStateOf(ExportFormat.CSV) }
    var full by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        shape = Shapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        ExportSheetContent(
            rowCount = rowCount,
            selected = selected,
            onSelect = { selected = it },
            onShare = {
                if (full && fullAvailable) onExportFull(selected) else onExport(selected)
                onDismiss()
            },
            fullAvailable = fullAvailable,
            full = full,
            onFullChange = { full = it },
        )
    }
}

/** The sheet's content, apart from its window, so a screenshot can draw it. */
@Composable
fun ExportSheetContent(
    rowCount: Int,
    selected: ExportFormat,
    onSelect: (ExportFormat) -> Unit,
    onShare: () -> Unit,
    fullAvailable: Boolean = false,
    full: Boolean = false,
    onFullChange: (Boolean) -> Unit = {},
) {
    val semantic = LocalSemanticColors.current
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.export),
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 18.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            ExportFormat.entries.forEach { format ->
                val on = format == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clip(Shapes.button)
                        .background(if (on) accent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                        .border(1.dp, if (on) accent else MaterialTheme.colorScheme.outline, Shapes.button)
                        .selectable(selected = on, role = Role.RadioButton) { onSelect(format) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        format.extension.uppercase(),
                        style = MonoStyles.cell.copy(fontWeight = FontWeight.SemiBold),
                        color = if (on) accent else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(format.hintRes()),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = semantic.textSecondary,
                    )
                }
            }
        }
        if (fullAvailable) {
            Column(modifier = Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                ScopeOption(
                    label = stringResource(R.string.export_scope_screen, rowCount),
                    selected = !full,
                    onClick = { onFullChange(false) },
                )
                ScopeOption(
                    label = stringResource(R.string.export_scope_full),
                    detail = stringResource(
                        R.string.export_scope_full_note,
                        LocaleFormat.integer(FullExport.MAX_ROWS, appLocale()),
                        FullExport.MAX_BYTES / (1024 * 1024),
                    ),
                    selected = full,
                    onClick = { onFullChange(true) },
                )
            }
        } else {
            Text(
                stringResource(R.string.export_rows_on_screen, rowCount),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            )
        }
        DialogNote(stringResource(R.string.export_temp_note), Icons.Default.Shield, semantic.textSecondary)
        Button(
            onClick = onShare,
            shape = Shapes.button,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.export_share), modifier = Modifier.padding(start = Spacing.s))
        }
    }
}

/** One of the two scopes, as a radio row: what goes in the file, and for the full one what it costs. */
@Composable
private fun ScopeOption(label: String, selected: Boolean, onClick: () -> Unit, detail: String? = null) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Shapes.button)
            .background(if (selected) accent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (selected) accent else MaterialTheme.colorScheme.outline, Shapes.button)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium))
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = LocalSemanticColors.current.textSecondary,
                )
            }
        }
    }
}

private fun ExportFormat.hintRes(): Int = when (this) {
    ExportFormat.CSV -> R.string.export_hint_csv
    ExportFormat.TSV -> R.string.export_hint_tsv
    ExportFormat.JSON -> R.string.export_hint_json
    ExportFormat.SQL -> R.string.export_hint_sql
    ExportFormat.MARKDOWN -> R.string.export_hint_markdown
}
