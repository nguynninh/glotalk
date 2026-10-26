package vn.onplay.vdubbing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.onplay.vdubbing.domain.model.AppLanguage
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.framework.accessibility.AccessibilityBridge
import vn.onplay.vdubbing.framework.accessibility.SystemAccessibilityStatus
import vn.onplay.vdubbing.framework.locale.AppLocaleController
import vn.onplay.vdubbing.framework.service.TranslateService
import vn.onplay.vdubbing.presentation.AppEnvironment
import vn.onplay.vdubbing.presentation.PermissionChecker
import vn.onplay.vdubbing.presentation.ServiceController
import vn.onplay.vdubbing.presentation.TranslatorViewModel
import vn.onplay.vdubbing.presentation.ui.SettingsScreen
import vn.onplay.vdubbing.presentation.ui.theme.VDubbingTheme

/**
 * Composition of the settings screen: it supplies the platform-flavoured
 * collaborators (service start, permission checks) and forwards intents to the
 * ViewModel. No translation logic happens here.
 */
class MainActivity : ComponentActivity() {

    private var notificationsRequested = false
    private var accessibilityPoll: Job? = null

    private val serviceController = object : ServiceController {
        override fun start() = TranslateService.start(this@MainActivity)

        override fun stop() = TranslateService.stop(this@MainActivity)

        override fun setAutoTranslate(enabled: Boolean, target: Language) {
            // The ViewModel already persisted the flag; the service reads it.
            TranslateService.applyAutoTranslate(this@MainActivity)
        }
    }

    private val permissionChecker: PermissionChecker = object : PermissionChecker {
        override fun notificationsGranted(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
    }

    private val viewModel: TranslatorViewModel by viewModels {
        TranslatorViewModel.Factory(
            container = (application as VDubbingApp).container,
            services = serviceController,
            permissions = permissionChecker,
            environment = object : AppEnvironment {
                override val packageName: String get() = this@MainActivity.packageName

                override fun applyAppLanguage(language: AppLanguage) {
                    AppLocaleController.apply(this@MainActivity, language)
                }
            },
            accessibility = SystemAccessibilityStatus(this@MainActivity),
        )
    }

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

        setContent {
            // state must be read before it can drive the theme.
            val state by viewModel.state.collectAsStateWithLifecycle()

            VDubbingTheme(themeMode = state.themeMode) {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    SettingsScreen(
                        state = state,
                        modifier = Modifier.padding(padding),
                        onStartService = viewModel::onStartService,
                        onStopService = viewModel::onStopService,
                        onOpenAccessibility = viewModel::onOpenAccessibilitySettings,
                        onRequestNotifications = ::requestNotifications,
                        onTranslateNow = viewModel::onTranslateNow,
                        onHideOverlay = viewModel::onHideOverlay,
                        onShowBubble = viewModel::onShowBubble,
                        onTargetLanguageChange = viewModel::onTargetLanguageChange,
                        onThemeModeChange = viewModel::onThemeModeChange,
                        onAppLanguageChange = viewModel::onAppLanguageChange,
                        onAutoTranslateChange = viewModel::onAutoTranslateChange,
                        onSaveCloud = viewModel::onSaveCloud,
                        onVerifyKey = viewModel::onVerifyKey,
                        onDismissMessage = viewModel::consumeMessage,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
