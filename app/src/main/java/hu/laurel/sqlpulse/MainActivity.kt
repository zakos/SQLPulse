package hu.laurel.sqlpulse

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import hu.laurel.sqlpulse.security.ActivityHolder
import hu.laurel.sqlpulse.ui.SqlPulseApp
import hu.laurel.sqlpulse.ui.theme.SqlPulseTheme
import javax.inject.Inject

/**
 * FragmentActivity, because BiometricPrompt needs one.
 *
 * FLAG_SECURE is set for the whole app (§6): no screenshots, and no preview in the recents list.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var activityHolder: ActivityHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        activityHolder.attach(this)
        setContent {
            SqlPulseTheme {
                SqlPulseApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activityHolder.attach(this)
    }

    override fun onDestroy() {
        activityHolder.detach(this)
        super.onDestroy()
    }
}
