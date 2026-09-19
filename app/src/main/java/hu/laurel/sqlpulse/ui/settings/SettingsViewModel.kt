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
) : ViewModel() {

    val settings: StateFlow<Settings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    fun setRowLimit(limit: Int) = launch { repository.setDefaultRowLimit(limit) }

    fun setAutoLock(minutes: Int) = launch { repository.setAutoLockMinutes(minutes) }

    fun setGridFontScale(scale: Int) = launch { repository.setGridFontScale(scale) }

    fun setTheme(theme: ThemePreference) = launch { repository.setTheme(theme) }

    fun setBlockWritesWithoutWhere(block: Boolean) =
        launch { repository.setBlockWritesWithoutWhere(block) }

    fun setMaxAffectedRows(rows: Int) = launch { repository.setMaxAffectedRows(rows) }

    fun setBlockScreenshots(block: Boolean) = launch { repository.setBlockScreenshots(block) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
