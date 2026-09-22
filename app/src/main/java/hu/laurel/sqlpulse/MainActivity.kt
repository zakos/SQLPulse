package hu.laurel.sqlpulse

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import hu.laurel.sqlpulse.data.settings.Settings
import hu.laurel.sqlpulse.data.settings.SettingsRepository
import hu.laurel.sqlpulse.security.ActivityHolder
import hu.laurel.sqlpulse.security.BiometricUnlock
import hu.laurel.sqlpulse.security.LockManager
import hu.laurel.sqlpulse.ui.SqlPulseApp
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.Spacing
import hu.laurel.sqlpulse.ui.theme.SqlPulseColors
import hu.laurel.sqlpulse.ui.theme.SqlPulseTheme
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * FragmentActivity, because BiometricPrompt needs one.
 *
 * FLAG_SECURE keeps screenshots and the recents-list preview blank (§6). It follows the setting
 * rather than being fixed, so it can be turned off to report a bug with a screenshot; it is set
 * before the first frame either way, so nothing is ever shown unprotected by accident.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var activityHolder: ActivityHolder

    @Inject
    lateinit var lockManager: LockManager

    @Inject
    lateinit var unlock: BiometricUnlock

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Protected from the first frame; the setting can relax it a moment later.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        activityHolder.attach(this)
        lockManager.onUserInteraction()

        setContent {
            val settings by settingsRepository.settings.collectAsState(initial = Settings())
            val locked by lockManager.locked.collectAsState()

            LaunchedEffect(settings.blockScreenshots) {
                if (settings.blockScreenshots) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            SqlPulseTheme(preference = settings.theme, gridFontScale = settings.gridFontScale) {
                if (locked) {
                    LockScreen(onUnlock = ::requestUnlock)
                } else {
                    SqlPulseApp()
                }
            }
        }
    }

    /** Every touch restarts the inactivity timer (§6). */
    override fun onUserInteraction() {
        super.onUserInteraction()
        lockManager.onUserInteraction()
    }

    override fun onResume() {
        super.onResume()
        activityHolder.attach(this)
        lockManager.onUserInteraction()
    }

    override fun onDestroy() {
        activityHolder.detach(this)
        super.onDestroy()
    }

    private fun requestUnlock() {
        lifecycleScope.launch {
            val ok = runCatching {
                unlock.authenticateUser(
                    title = getString(R.string.lock_title),
                    subtitle = getString(R.string.lock_subtitle),
                )
            }.isSuccess
            if (ok) lockManager.unlock()
        }
    }
}

/** What the app shows after locking itself: no data, just the way back in (§6). */
@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    // Rendered instead of SqlPulseApp, so no screen state stays alive behind it.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The launcher icon's own layers: an adaptive icon cannot be drawn by a painter, and
            // its ground stays dark in the light theme too, as it does on the home screen.
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(SqlPulseColors.DarkBackground)
                    .border(1.dp, SqlPulseColors.HairlineDark, RoundedCornerShape(30.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(132.dp),
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Text(
                    text = stringResource(R.string.lock_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.lock_body),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = LocalSemanticColors.current.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 300.dp),
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                Box(
                    modifier = Modifier
                        .size(108.dp)
                        .background(accent.copy(alpha = 0.14f), CircleShape)
                        .padding(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable(
                            onClickLabel = stringResource(R.string.lock_unlock),
                            role = Role.Button,
                            onClick = onUnlock,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Fingerprint,
                        contentDescription = stringResource(R.string.lock_unlock),
                        tint = accent,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.lock_unlock),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalSemanticColors.current.textSecondary,
                )
            }
        }
    }
}
