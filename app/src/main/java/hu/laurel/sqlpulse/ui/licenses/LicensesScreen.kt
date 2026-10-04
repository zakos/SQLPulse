package hu.laurel.sqlpulse.ui.licenses

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.licenses.LicenseGroup
import hu.laurel.sqlpulse.data.licenses.LicensedComponent
import hu.laurel.sqlpulse.data.licenses.ThirdPartyLicenses
import hu.laurel.sqlpulse.ui.components.HairlineCard
import hu.laurel.sqlpulse.ui.components.InfoBadge
import hu.laurel.sqlpulse.ui.components.ListRow
import hu.laurel.sqlpulse.ui.components.RowChevron
import hu.laurel.sqlpulse.ui.components.SectionCaption
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors

/** Licenses: SQLPulse's own, and every library and font the app bundles, each with its full text. */
@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    viewModel: LicensesViewModel = hiltViewModel(),
) {
    LicensesScreenContent(onBack = onBack, viewModel = viewModel)
}

/** The screen itself, drawn from whatever [LicensesController] it is handed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreenContent(
    onBack: () -> Unit,
    viewModel: LicensesController,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val detail = state.detail
    val context = LocalContext.current

    // One route, two levels: back from a license text returns to the list first.
    BackHandler(enabled = detail != null) { viewModel.closeDetail() }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = sqlPulseTopBarColors(),
                title = { Text(detail?.title ?: stringResource(R.string.licenses_title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { if (detail != null) viewModel.closeDetail() else onBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        if (detail == null) {
            LicenseList(state, viewModel, Modifier.padding(padding), onOpenUrl = { openUrl(context, it) })
        } else {
            LicenseTextView(detail, Modifier.padding(padding), onOpenUrl = { openUrl(context, it) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LicenseList(
    state: LicensesUiState,
    viewModel: LicensesController,
    modifier: Modifier,
    onOpenUrl: (String) -> Unit,
) {
    val semantic = LocalSemanticColors.current
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            HairlineCard(modifier = Modifier.padding(Spacing.l)) {
                Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text("SQLPulse", style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.licenses_version, state.versionName, state.versionCode),
                        style = MonoStyles.cell,
                        color = semantic.textSecondary,
                    )
                    // The badge opens the full Apache-2.0 text, like every other license badge here.
                    Row(
                        modifier = Modifier.clickable { viewModel.openApp() },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        InfoBadge(ThirdPartyLicenses.APP_LICENSE_NAME, MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.licenses_read_text),
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.textSecondary,
                        )
                    }
                    Text(ThirdPartyLicenses.APP_COPYRIGHT, style = MaterialTheme.typography.bodyMedium)
                    if (state.notice.isNotBlank()) {
                        HorizontalDivider(color = semantic.hairline, modifier = Modifier.padding(vertical = Spacing.xs))
                        // NOTICE is hard-wrapped at 80 columns; joining the lines lets it wrap to the phone.
                        Text(
                            state.notice.trim().replace(Regex("(?<!\n)\n(?!\n)"), " "),
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.textSecondary,
                        )
                    }
                }
            }
        }
        item {
            ListRow(
                title = stringResource(R.string.licenses_source_code),
                subtitle = ThirdPartyLicenses.APP_REPO_URL,
                onClick = { onOpenUrl(ThirdPartyLicenses.APP_REPO_URL) },
            ) { OpenIcon() }
        }

        item { Caption(stringResource(R.string.licenses_libraries)) }
        items(ThirdPartyLicenses.byGroup(LicenseGroup.LIBRARIES), key = { it.id }) { component ->
            ComponentRow(component, onClick = { viewModel.open(component) })
        }
        item { Caption(stringResource(R.string.licenses_fonts)) }
        items(ThirdPartyLicenses.byGroup(LicenseGroup.FONTS), key = { it.id }) { component ->
            ComponentRow(component, onClick = { viewModel.open(component) })
        }

        item {
            Column(
                modifier = Modifier.padding(Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Text(
                    stringResource(R.string.licenses_dbx_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.textSecondary,
                )
                Row(
                    modifier = Modifier.clickable { onOpenUrl(ThirdPartyLicenses.DBX_URL) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        ThirdPartyLicenses.DBX_URL,
                        style = MonoStyles.cell.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OpenIcon()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComponentRow(component: LicensedComponent, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(component.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    component.version?.let {
                        Text(it, style = MonoStyles.cell.copy(fontSize = 12.sp), color = semantic.textSecondary)
                    }
                    component.licenses.forEach { license ->
                        // Copyleft licenses are the ones with obligations beyond the notice, so they stand out.
                        val copyleft = license.label.contains("GPL")
                        InfoBadge(license.label, if (copyleft) semantic.warning else semantic.textSecondary)
                    }
                }
            }
            RowChevron()
        }
        HorizontalDivider(color = semantic.hairline)
    }
}

@Composable
private fun LicenseTextView(detail: LicenseDetail, modifier: Modifier, onOpenUrl: (String) -> Unit) {
    val semantic = LocalSemanticColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
    ) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            detail.version?.let { Text(it, style = MonoStyles.cell, color = semantic.textSecondary) }
            detail.copyright.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            detail.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = semantic.textSecondary)
            }
        }
        detail.sourceUrl?.let { url ->
            ListRow(
                title = stringResource(R.string.licenses_source),
                subtitle = url,
                onClick = { onOpenUrl(url) },
            ) { OpenIcon() }
        }
        detail.texts.forEach { block ->
            Caption(stringResource(R.string.licenses_text_caption, block.label))
            HairlineCard(modifier = Modifier.padding(horizontal = Spacing.l)) {
                // Selectable so a clause can be copied out; mono because the texts are hard-wrapped.
                SelectionContainer {
                    Text(
                        block.text.trim(),
                        style = MonoStyles.cell.copy(fontSize = 11.sp, lineHeight = 16.sp),
                        modifier = Modifier.padding(Spacing.m),
                    )
                }
            }
        }
    }
}

@Composable
private fun OpenIcon() {
    Icon(
        Icons.Default.OpenInNew,
        contentDescription = null,
        tint = LocalSemanticColors.current.textSecondary,
        modifier = Modifier.padding(start = Spacing.xs),
    )
}

@Composable
private fun Caption(text: String) {
    SectionCaption(text, modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.xl, bottom = Spacing.s))
}

/** Only ever called from a tap: nothing in this screen reaches the network by itself. */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.licenses_no_browser), Toast.LENGTH_SHORT).show()
    }
}
