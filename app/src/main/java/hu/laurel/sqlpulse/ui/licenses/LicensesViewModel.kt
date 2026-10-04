package hu.laurel.sqlpulse.ui.licenses

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.BuildConfig
import hu.laurel.sqlpulse.data.licenses.LicenseInfo
import hu.laurel.sqlpulse.data.licenses.LicensedComponent
import hu.laurel.sqlpulse.data.licenses.ThirdPartyLicenses
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class LicensesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel(), LicensesController {

    private val state = MutableStateFlow(
        LicensesUiState(versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE),
    )
    override val uiState: StateFlow<LicensesUiState> = state

    init {
        viewModelScope.launch {
            val notice = readAsset(ThirdPartyLicenses.NOTICE_ASSET)
            state.update { it.copy(notice = notice) }
        }
    }

    override fun openApp() {
        viewModelScope.launch {
            val detail = LicenseDetail(
                title = "SQLPulse",
                version = BuildConfig.VERSION_NAME,
                sourceUrl = ThirdPartyLicenses.APP_REPO_URL,
                copyright = listOf(ThirdPartyLicenses.APP_COPYRIGHT),
                note = null,
                texts = textsFor(listOf(LicenseInfo(ThirdPartyLicenses.APP_LICENSE_NAME, ThirdPartyLicenses.APP_LICENSE_ASSET))),
            )
            state.update { it.copy(detail = detail) }
        }
    }

    override fun open(component: LicensedComponent) {
        viewModelScope.launch {
            val detail = LicenseDetail(
                title = component.name,
                version = component.version,
                sourceUrl = component.sourceUrl,
                copyright = component.copyright,
                note = component.note,
                texts = textsFor(component.licenses),
            )
            state.update { it.copy(detail = detail) }
        }
    }

    override fun closeDetail() = state.update { it.copy(detail = null) }

    private suspend fun textsFor(licenses: List<LicenseInfo>): List<LicenseTextBlock> =
        licenses.flatMap { license ->
            license.assets.map { asset -> LicenseTextBlock(label = license.label, text = readAsset(asset)) }
        }

    /** Reads off the main thread; a missing file shows as empty text rather than crashing the screen. */
    private suspend fun readAsset(name: String): String = withContext(Dispatchers.IO) {
        try {
            context.assets.open("${ThirdPartyLicenses.ASSET_DIR}/$name").bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            ""
        }
    }
}
