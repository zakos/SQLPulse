package hu.laurel.sqlpulse.ui.screenshots

import hu.laurel.sqlpulse.data.licenses.LicensedComponent
import hu.laurel.sqlpulse.data.licenses.ThirdPartyLicenses
import hu.laurel.sqlpulse.ui.licenses.LicenseDetail
import hu.laurel.sqlpulse.ui.licenses.LicenseTextBlock
import hu.laurel.sqlpulse.ui.licenses.LicensesController
import hu.laurel.sqlpulse.ui.licenses.LicensesScreenContent
import hu.laurel.sqlpulse.ui.licenses.LicensesUiState
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

class LicensesShots {
    @get:Rule
    val paparazzi = designPaparazzi()

    private fun controller(state: LicensesUiState) = object : LicensesController {
        override val uiState: StateFlow<LicensesUiState> = MutableStateFlow(state)
        override fun openApp() = Unit
        override fun open(component: LicensedComponent) = Unit
        override fun closeDetail() = Unit
    }

    // The real bundled files, so the picture shows what the app shows. Gradle runs from app/.
    private fun asset(name: String) = File("src/main/assets/licenses/$name").readText()

    private val base = LicensesUiState(
        versionName = "0.1.1", versionCode = 42, notice = asset(ThirdPartyLicenses.NOTICE_ASSET),
    )

    private fun detailOf(name: String): LicenseDetail {
        val c = ThirdPartyLicenses.components.first { it.name == name }
        return LicenseDetail(
            title = c.name, version = c.version, sourceUrl = c.sourceUrl, copyright = c.copyright, note = c.note,
            texts = c.licenses.flatMap { l -> l.assets.map { LicenseTextBlock(l.label, asset(it)) } },
        )
    }

    @Test
    fun listDark() = paparazzi.screen {
        LicensesScreenContent(onBack = {}, viewModel = controller(base))
    }

    @Test
    fun listLight() = paparazzi.screen(dark = false) {
        LicensesScreenContent(onBack = {}, viewModel = controller(base))
    }

    @Test
    fun textMit() = paparazzi.screen {
        LicensesScreenContent(onBack = {}, viewModel = controller(base.copy(detail = detailOf("SLF4J NOP binding"))))
    }

    @Test
    fun textGplWithException() = paparazzi.screen {
        LicensesScreenContent(onBack = {}, viewModel = controller(base.copy(detail = detailOf("MySQL Connector/J"))))
    }
}
