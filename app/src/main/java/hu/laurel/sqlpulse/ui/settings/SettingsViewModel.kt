package hu.laurel.sqlpulse.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.laurel.sqlpulse.data.settings.Settings
import hu.laurel.sqlpulse.data.settings.SettingsRepository
import hu.laurel.sqlpulse.ui.theme.ThemePreference
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel(), SettingsController {

    override val settings: StateFlow<Settings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    override fun setRowLimit(limit: Int) = launch { repository.setDefaultRowLimit(limit) }

    override fun setAutoLock(minutes: Int) = launch { repository.setAutoLockMinutes(minutes) }

    override fun setGridFontScale(scale: Int) = launch { repository.setGridFontScale(scale) }

    override fun setTheme(theme: ThemePreference) = launch { repository.setTheme(theme) }

    override fun setBlockWritesWithoutWhere(block: Boolean) =
        launch { repository.setBlockWritesWithoutWhere(block) }

    override fun setMaxAffectedRows(rows: Int) = launch { repository.setMaxAffectedRows(rows) }

    override fun setBlockScreenshots(block: Boolean) = launch { repository.setBlockScreenshots(block) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
