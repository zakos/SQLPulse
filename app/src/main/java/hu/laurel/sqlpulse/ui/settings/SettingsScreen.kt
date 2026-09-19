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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.ui.components.HairlineCard
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.ThemePreference

/** Settings (§7.7): key store, default row limit, auto-lock, theme and grid font size. */
@OptIn(ExperimentalMaterial3Api::class)
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
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Section(stringResource(R.string.keys_title)) {
                OutlinedButton(onClick = onOpenKeyStore, shape = Shapes.button) {
                    Text(stringResource(R.string.settings_manage_keys))
                }
            }

            Section(stringResource(R.string.settings_backup)) {
                OutlinedButton(onClick = onOpenBackup, shape = Shapes.button) {
                    Text(stringResource(R.string.settings_open_backup))
                }
            }

            Section(stringResource(R.string.query_title)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = settings.blockWritesWithoutWhere,
                        onCheckedChange = viewModel::setBlockWritesWithoutWhere,
                    )
                    Text(
                        stringResource(R.string.settings_block_unguarded_writes),
                        modifier = Modifier.padding(start = Spacing.s),
                    )
                }
            }

            Section(stringResource(R.string.settings_max_affected)) {
                Text(
                    text = if (settings.maxAffectedRows == 0) {
                        stringResource(R.string.settings_max_affected_off)
                    } else {
                        "${settings.maxAffectedRows}"
                    },
                    style = MonoStyles.cell,
                )
                Slider(
                    value = settings.maxAffectedRows.toFloat(),
                    onValueChange = { viewModel.setMaxAffectedRows(it.toInt()) },
                    valueRange = 0f..5000f,
                    // Steps of 500, so the slider can reach 0 — the way the ceiling is turned off.
                    steps = 9,
                )
                Text(
                    text = stringResource(R.string.settings_max_affected_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }

            Section(stringResource(R.string.settings_privacy)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = settings.blockScreenshots,
                        onCheckedChange = viewModel::setBlockScreenshots,
                    )
                    Text(
                        stringResource(R.string.settings_block_screenshots),
                        modifier = Modifier.padding(start = Spacing.s),
                    )
                }
                Text(
                    text = stringResource(R.string.settings_block_screenshots_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }

            Section(stringResource(R.string.settings_default_limit)) {
                Text("${settings.defaultRowLimit}", style = MonoStyles.cell)
                Slider(
                    value = settings.defaultRowLimit.toFloat(),
                    onValueChange = { viewModel.setRowLimit(it.toInt()) },
                    valueRange = 50f..5000f,
                    steps = 19,
                )
            }

            Section(stringResource(R.string.settings_auto_lock)) {
                Text(
                    text = stringResource(R.string.settings_minutes, settings.autoLockMinutes),
                    style = MonoStyles.cell,
                )
                Slider(
                    value = settings.autoLockMinutes.toFloat(),
                    onValueChange = { viewModel.setAutoLock(it.toInt()) },
                    valueRange = 1f..30f,
                    steps = 28,
                )
                Text(
                    text = stringResource(R.string.settings_auto_lock_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
            }

            Section(stringResource(R.string.settings_theme)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    ThemePreference.entries.forEach { theme ->
                        FilterChip(
                            selected = settings.theme == theme,
                            onClick = { viewModel.setTheme(theme) },
                            label = {
                                Text(
                                    stringResource(
                                        when (theme) {
                                            ThemePreference.System -> R.string.settings_theme_system
                                            ThemePreference.Light -> R.string.settings_theme_light
                                            ThemePreference.Dark -> R.string.settings_theme_dark
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            Section(stringResource(R.string.settings_grid_font)) {
                Text("${settings.gridFontScale}%", style = MonoStyles.cell)
                Slider(
                    value = settings.gridFontScale.toFloat(),
                    onValueChange = { viewModel.setGridFontScale(it.toInt()) },
                    valueRange = 80f..150f,
                    steps = 13,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        HairlineCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) { content() }
        }
    }
}
