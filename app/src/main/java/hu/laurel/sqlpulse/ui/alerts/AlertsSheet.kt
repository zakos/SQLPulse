package hu.laurel.sqlpulse.ui.alerts

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.alerts.AlertCatalog
import hu.laurel.sqlpulse.data.alerts.AlertRule
import hu.laurel.sqlpulse.data.alerts.AlertState
import hu.laurel.sqlpulse.data.alerts.AlertText
import hu.laurel.sqlpulse.ui.components.HairlineCard
import hu.laurel.sqlpulse.ui.components.LabeledField
import hu.laurel.sqlpulse.ui.components.SectionCaption
import hu.laurel.sqlpulse.ui.components.SegmentedChoice
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Shapes
import hu.laurel.sqlpulse.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date

/**
 * The alert rules of the open connection, in a bottom sheet over the Pulse screen.
 *
 * This wrapper owns the Android parts: the sheet window and the notification permission, which is
 * asked for when the first rule is switched on (Android 13+). Saying no is fine — the rules still
 * run and their state shows here, only the notification is not posted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsSheet(controller: AlertsController, onDismiss: () -> Unit) {
    val state by controller.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        controller.refreshPermission()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = Shapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        AlertsSheetContent(
            state = state,
            onSave = { rule ->
                if (rule.enabled && !state.notificationsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                controller.save(rule)
            },
            onOpenNotificationSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
    }
}

/** The sheet's content, apart from its window, so a screenshot can draw it. */
@Composable
fun AlertsSheetContent(
    state: AlertsUiState,
    onSave: (AlertRule) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    initiallyExpanded: hu.laurel.sqlpulse.data.schema.MetricId? = null,
    nowMs: Long = System.currentTimeMillis(),
) {
    val semantic = LocalSemanticColors.current
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            stringResource(R.string.alerts_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 18.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        state.connectionName?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = semantic.textSecondary)
        }

        // Said in the sheet itself, not in a help page: this is the one thing people assume otherwise.
        HairlineCard {
            Text(
                stringResource(R.string.alerts_scope_note, (hu.laurel.sqlpulse.data.alerts.AlertMonitor.INTERVAL_MS / 1000).toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
                modifier = Modifier.padding(Spacing.m),
            )
        }

        if (state.engine == null) {
            Text(
                stringResource(R.string.alerts_no_session),
                style = MaterialTheme.typography.bodyMedium,
                color = semantic.textSecondary,
            )
            return@Column
        }

        if (!state.notificationsAllowed && state.rules.any { it.enabled }) {
            HairlineCard {
                Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        stringResource(R.string.alerts_notifications_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.warning,
                    )
                    TextButton(onClick = onOpenNotificationSettings) {
                        Text(stringResource(R.string.alerts_notification_settings))
                    }
                }
            }
        }

        SectionCaption(stringResource(R.string.alerts_rules_caption))
        Column {
            state.rules.forEach { rule ->
                val engine = state.engine
                RuleRow(
                    rule = rule,
                    unit = AlertCatalog.unit(engine, rule.metric),
                    state = state.states[rule.metric],
                    expanded = expanded == rule.metric,
                    onToggleExpanded = { expanded = if (expanded == rule.metric) null else rule.metric },
                    onSave = onSave,
                    nowMs = nowMs,
                )
                HorizontalDivider(color = semantic.hairline)
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: AlertRule,
    unit: hu.laurel.sqlpulse.data.alerts.AlertUnit,
    state: AlertState?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSave: (AlertRule) -> Unit,
    nowMs: Long,
) {
    val semantic = LocalSemanticColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(AlertText.metricLabel(rule.metric)),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                Text(
                    stringResource(
                        R.string.alerts_rule_summary,
                        AlertCatalog.formatThreshold(rule.threshold, unit),
                        rule.sustainSamples,
                    ),
                    style = MonoStyles.cell.copy(fontSize = 12.sp),
                    color = semantic.textSecondary,
                )
                val status = statusLine(rule, state, nowMs)
                Text(
                    status.first,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = if (status.second) semantic.danger else semantic.textSecondary,
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = { onSave(rule.copy(enabled = it)) })
        }
        if (expanded) {
            var text by remember(rule.metric) { mutableStateOf(AlertCatalog.formatThreshold(rule.threshold, hu.laurel.sqlpulse.data.alerts.AlertUnit.COUNT)) }
            LabeledField(
                value = text,
                onValueChange = {
                    text = it
                    it.replace(',', '.').toDoubleOrNull()?.let { value -> onSave(rule.copy(threshold = value)) }
                },
                label = { Text(stringResource(R.string.alerts_threshold) + if (unit.suffix.isEmpty()) "" else " (${unit.suffix})") },
                mono = true,
                isError = text.replace(',', '.').toDoubleOrNull() == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Text(
                stringResource(R.string.alerts_sustain_label),
                style = MaterialTheme.typography.labelMedium,
                color = semantic.textSecondary,
            )
            SegmentedChoice(
                options = SUSTAIN_CHOICES,
                selected = SUSTAIN_CHOICES.minByOrNull { kotlin.math.abs(it - rule.sustainSamples) } ?: 3,
                label = { it.toString() },
                onSelect = { onSave(rule.copy(sustainSamples = it)) },
            )
            Text(
                stringResource(R.string.alerts_cooldown_label),
                style = MaterialTheme.typography.labelMedium,
                color = semantic.textSecondary,
            )
            SegmentedChoice(
                options = COOLDOWN_CHOICES,
                selected = COOLDOWN_CHOICES.minByOrNull { kotlin.math.abs(it - rule.cooldownSeconds / 60) } ?: 10,
                label = { stringResource(R.string.alerts_minutes, it) },
                onSelect = { onSave(rule.copy(cooldownSeconds = it * 60)) },
            )
        }
    }
}

/** The line under a rule: where it stands, and whether it deserves the alarm colour. */
@Composable
private fun statusLine(rule: AlertRule, state: AlertState?, nowMs: Long): Pair<String, Boolean> {
    val time = { ms: Long -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms)) }
    return when {
        !rule.enabled -> stringResource(R.string.alerts_state_off) to false
        state?.firing == true -> stringResource(R.string.alerts_state_firing, time(state.firingSinceMs ?: nowMs)) to true
        state?.lastFiredMs != null -> stringResource(R.string.alerts_state_ok_last, time(state.lastFiredMs)) to false
        state?.lastValue != null -> stringResource(R.string.alerts_state_ok) to false
        else -> stringResource(R.string.alerts_state_waiting) to false
    }
}

private val SUSTAIN_CHOICES = listOf(1, 3, 5, 10)
private val COOLDOWN_CHOICES = listOf(5, 10, 30, 60)
