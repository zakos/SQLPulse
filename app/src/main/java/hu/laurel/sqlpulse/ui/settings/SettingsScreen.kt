package hu.laurel.sqlpulse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.BuildConfig
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.ui.components.ListRow
import hu.laurel.sqlpulse.ui.components.RowChevron
import hu.laurel.sqlpulse.ui.components.RowValue
import hu.laurel.sqlpulse.ui.components.SectionCaption
import hu.laurel.sqlpulse.ui.components.SegmentedChoice
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.ThemePreference
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors

/** Settings (§7.7): key store, default row limit, auto-lock, theme and grid font size. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenKeyStore: () -> Unit,
    /**
     * Opens the backup screen (Routes.BACKUP). Defaulted to nothing so the navigation graph — owned
     * elsewhere — can be pointed at it in its own change without this file having to land first.
     */
    onOpenBackup: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    SettingsScreenContent(
        onBack = onBack,
        onOpenKeyStore = onOpenKeyStore,
        onOpenBackup = onOpenBackup,
        viewModel = viewModel,
    )
}

/** The screen itself, drawn from whatever [SettingsController] it is handed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    onBack: () -> Unit,
    onOpenKeyStore: () -> Unit,
    onOpenBackup: () -> Unit = {},
    viewModel: SettingsController,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current

    Scaffold(
        topBar = {
            TopAppBar(
                colors = sqlPulseTopBarColors(),
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        // A list, as settings are everywhere else on the phone: grouped under small captions, each
        // line a title with its value or switch at the end. Sliders sit under the line they set.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xl),
        ) {
            Caption(stringResource(R.string.query_title))
            ListRow(
                title = stringResource(R.string.settings_default_limit),
                subtitle = stringResource(R.string.settings_default_limit_note),
                divider = false,
            ) { RowValue("${settings.defaultRowLimit}") }
            RowSlider(
                value = settings.defaultRowLimit.toFloat(),
                onValueChange = { viewModel.setRowLimit(it.toInt()) },
                valueRange = 50f..5000f,
                steps = 19,
            )
            ListRow(
                title = stringResource(R.string.settings_block_unguarded_writes),
                onClick = { viewModel.setBlockWritesWithoutWhere(!settings.blockWritesWithoutWhere) },
            ) {
                Switch(
                    checked = settings.blockWritesWithoutWhere,
                    onCheckedChange = viewModel::setBlockWritesWithoutWhere,
                )
            }
            ListRow(
                title = stringResource(R.string.settings_max_affected),
                subtitle = stringResource(R.string.settings_max_affected_note),
                divider = false,
            ) {
                RowValue(
                    if (settings.maxAffectedRows == 0) {
                        stringResource(R.string.settings_max_affected_off)
                    } else {
                        "${settings.maxAffectedRows}"
                    },
                )
            }
            RowSlider(
                value = settings.maxAffectedRows.toFloat(),
                onValueChange = { viewModel.setMaxAffectedRows(it.toInt()) },
                valueRange = 0f..5000f,
                // Steps of 500, so the slider can reach 0 — the way the ceiling is turned off.
                steps = 9,
            )

            Caption(stringResource(R.string.settings_appearance))
            Column(
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Text(
                    stringResource(R.string.settings_theme),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                SegmentedChoice(
                    options = ThemePreference.entries,
                    selected = settings.theme,
                    label = { theme ->
                        stringResource(
                            when (theme) {
                                ThemePreference.System -> R.string.settings_theme_system
                                ThemePreference.Light -> R.string.settings_theme_light
                                ThemePreference.Dark -> R.string.settings_theme_dark
                            },
                        )
                    },
                    onSelect = viewModel::setTheme,
                )
            }
            ListRow(title = stringResource(R.string.settings_grid_font), divider = false) {
                RowValue("${settings.gridFontScale}%")
            }
            RowSlider(
                value = settings.gridFontScale.toFloat(),
                onValueChange = { viewModel.setGridFontScale(it.toInt()) },
                valueRange = 80f..150f,
                steps = 13,
            ) {
                // Drawn at the size it sets, so the slider shows what it does while it is moved.
                Text(
                    text = stringResource(R.string.settings_grid_font_sample),
                    style = MonoStyles.cell.copy(
                        fontSize = MonoStyles.cell.fontSize * (settings.gridFontScale / 100f),
                    ),
                    color = semantic.textSecondary,
                )
            }

            Caption(stringResource(R.string.settings_security))
            ListRow(
                title = stringResource(R.string.settings_auto_lock),
                subtitle = stringResource(R.string.settings_auto_lock_note),
                divider = false,
            ) { RowValue(stringResource(R.string.settings_minutes, settings.autoLockMinutes)) }
            RowSlider(
                value = settings.autoLockMinutes.toFloat(),
                onValueChange = { viewModel.setAutoLock(it.toInt()) },
                valueRange = 1f..30f,
                steps = 28,
            )
            ListRow(
                title = stringResource(R.string.settings_block_screenshots),
                subtitle = stringResource(R.string.settings_block_screenshots_note),
                onClick = { viewModel.setBlockScreenshots(!settings.blockScreenshots) },
            ) {
                Switch(checked = settings.blockScreenshots, onCheckedChange = viewModel::setBlockScreenshots)
            }
            ListRow(
                title = stringResource(R.string.settings_manage_keys),
                onClick = onOpenKeyStore,
            ) { RowChevron() }
            ListRow(
                title = stringResource(R.string.settings_open_backup),
                onClick = onOpenBackup,
            ) { RowChevron() }

            Text(
                text = "SQLPulse ${BuildConfig.VERSION_NAME}",
                style = MonoStyles.cell.copy(fontSize = 12.sp),
                color = semantic.textSecondary.copy(alpha = 0.7f),
                modifier = Modifier.padding(Spacing.l),
            )
        }
    }
}

@Composable
private fun Caption(text: String) {
    SectionCaption(text, modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.xl, bottom = Spacing.s))
}

/** A slider set in under the row whose value it changes, with room for a line under it. */
@Composable
private fun RowSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    below: @Composable () -> Unit = {},
) {
    val hairline = LocalSemanticColors.current.hairline
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    hairline,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 1.dp.toPx()),
                    size = size.copy(height = 1.dp.toPx()),
                )
            }
            .padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.m),
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            // The steps still snap; drawn as dots they would turn the track into a ruler.
            colors = SliderDefaults.colors(
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        below()
    }
}
