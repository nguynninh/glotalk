package vn.onplay.vdubbing

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.onplay.vdubbing.framework.accessibility.AccessibilityBridge
import vn.onplay.vdubbing.framework.accessibility.AppForeground
import vn.onplay.vdubbing.framework.locale.AppLocaleController
import vn.onplay.vdubbing.presentation.designsystem.theme.VDubbingTheme
import vn.onplay.vdubbing.presentation.feature.home.HomeUI
import vn.onplay.vdubbing.presentation.feature.settings.SettingsUI
import vn.onplay.vdubbing.presentation.feature.settings.SettingsViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var notificationsRequested = false
    private var accessibilityPoll: Job? = null

    private val viewModel: SettingsViewModel by viewModels()

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refreshSystemState()
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleController.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            viewModel.appLanguageChanges.collect { AppLocaleController.apply(this@MainActivity, it) }
        }
        setContent {
            // state must be read before it can drive the theme.
            val state by viewModel.state.collectAsStateWithLifecycle()

            VDubbingTheme(themeMode = state.themeMode) {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    HomeUI(
                        modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                    ) {
                        SettingsUI(
                            viewModel = viewModel,
                            onRequestNotifications = ::requestNotifications,
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Not onStop: a paused activity is already behind whatever is in front,
        // and onStop can lag behind the next app's first frame.
        AppForeground.onActivityPaused()
    }

    override fun onResume() {
        super.onResume()
        AppForeground.onActivityResumed()
        // The accessibility toggle lives in system settings, so re-read it here.
        viewModel.refreshSystemState()
        pollUntilAccessibilityLive()
        viewModel.onShowBubble()
    }

    /**
     * Enabling the service is asynchronous: the setting flips immediately, but
     * the service object is bound a moment later. Poll briefly so the status
     * does not stay stuck on "đang khởi động" for the user.
     */
    private fun pollUntilAccessibilityLive() {
        if (accessibilityPoll != null) return
        viewModel.onAccessibilityPollReset()
        accessibilityPoll = lifecycleScope.launch {
            repeat(ACCESSIBILITY_POLL_ATTEMPTS) {
                delay(ACCESSIBILITY_POLL_INTERVAL_MS)
                viewModel.onAccessibilityPollTick()
                if (AccessibilityBridge.isReady) return@launch
            }
        }.also { job ->
            job.invokeOnCompletion { accessibilityPoll = null }
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsRequested) {
            notificationsRequested = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val ACCESSIBILITY_POLL_ATTEMPTS = 12
        const val ACCESSIBILITY_POLL_INTERVAL_MS = 500L
    }
}
