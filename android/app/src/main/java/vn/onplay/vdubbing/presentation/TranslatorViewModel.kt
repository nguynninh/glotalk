package vn.onplay.vdubbing.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.di.AppContainer
import vn.onplay.vdubbing.domain.model.AppLanguage
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.ThemeMode
import vn.onplay.vdubbing.domain.model.TranslationEngine
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.framework.accessibility.AccessibilityBridge
import vn.onplay.vdubbing.presentation.ui.AppUiState
import vn.onplay.vdubbing.presentation.ui.UiArg
import vn.onplay.vdubbing.presentation.ui.UiContent
import vn.onplay.vdubbing.presentation.ui.UiMessage
import vn.onplay.vdubbing.presentation.ui.displayNameRes
import vn.onplay.vdubbing.presentation.ui.messageRes

/**
 * Owns screen state and user intents. It talks to the domain use case and the
 * settings repository; it never touches Binder, Bitmap, WindowManager or OkHttp,
 * and it does not start services — [ServiceController] does that, handed in by
 * the activity so the ViewModel stays free of platform lifecycles.
 */
class TranslatorViewModel(
    private val container: AppContainer,
    private val services: ServiceController,
    private val permissions: PermissionChecker,
    private val environment: AppEnvironment,
    private val accessibility: AccessibilityStatus,
) : ViewModel() {

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        _state.update {
            it.copy(
                savedKey = container.settings.geminiKey,
                cloudEnabled = container.settings.cloudFallbackEnabled,
                targetLanguage = container.settings.targetLanguage,
                autoTranslate = container.settings.autoTranslate,
                themeMode = container.settings.themeMode,
                appLanguage = container.settings.appLanguage,
            )
        }
        observeBackground()
        refreshSystemState()
    }

    /**
     * Re-read things only the system can answer (accessibility toggle, notifications).
     *
     * The system setting is the source of truth: the live service object is
     * absent whenever our process was not running when the user flipped the
     * switch, which is the common case for someone who just enabled it.
     */
    fun refreshSystemState() {
        val snapshot = accessibility.diagnose()
        _state.update {
            it.copy(
                accessibilityReady = snapshot.enabled || AccessibilityBridge.isReady,
                accessibilityNeedsRebind = snapshot.enabled &&
                    !AccessibilityBridge.isReady &&
                    pollAttempts >= REBIND_AFTER_POLLS,
                accessibilityLive = AccessibilityBridge.isReady,
                notificationsGranted = permissions.notificationsGranted(),
                // Android 13+ also lets the language be changed in system settings;
                // that path only reaches us through the repository.
                appLanguage = container.settings.appLanguage,
                accessibilitySnapshot = snapshot,
            )
        }
    }

    fun onOpenAccessibilitySettings() {
        accessibility.openSystemSettings()
    }

    fun onStartService() {
        services.start()
        // The service publishes the real state through BackgroundState once it is
        // up; don't fake it here, or a refused start would look successful.
    }

    fun onStopService() {
        container.settings.autoTranslate = false
        // Clear the overlay immediately: onDestroy only runs when the system
        // actually tears the service down, which is not the same moment.
        onHideOverlay()
        _state.update { it.copy(lastTranslation = null) }
        services.stop()
    }

    fun onTargetLanguageChange(language: Language) {
        container.settings.targetLanguage = language
        _state.update { it.copy(targetLanguage = language) }
    }

    fun onAutoTranslateChange(enabled: Boolean) {
        container.settings.autoTranslate = enabled
        _state.update { it.copy(autoTranslate = enabled) }
        services.setAutoTranslate(enabled, _state.value.targetLanguage)
    }

    fun onThemeModeChange(mode: ThemeMode) {
        container.settings.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    fun onAppLanguageChange(language: AppLanguage) {
        if (language == _state.value.appLanguage) return
        container.settings.appLanguage = language
        _state.update { it.copy(appLanguage = language) }
        // Applies app-wide and restarts the screen; that is platform work, so the
        // activity does it rather than this ViewModel.
        environment.applyAppLanguage(language)
    }

    fun onTranslateNow() {
        if (_state.value.translating) return
        if (AccessibilityBridge.service == null) {
            val enabledInSettings = accessibility.isEnabledInSystemSettings()
            _state.update {
                it.copy(
                    accessibilityReady = enabledInSettings,
                    message = UiMessage(
                        UiContent.Text(
                            if (enabledInSettings) {
                                R.string.message_service_waking
                            } else {
                                R.string.message_no_accessibility
                            },
                        ),
                        isError = true,
                    ),
                )
            }
            return
        }

        _state.update { it.copy(translating = true) }
        viewModelScope.launch {
            runCatching {
                container.translateScreen(
                    source = Language.AUTO,
                    target = _state.value.targetLanguage,
                    // We are in our own app, so the result shows on this screen
                    // instead of an overlay covering it.
                    ownPackage = ownPackage,
                )
            }.onSuccess { translation ->
                _state.update {
                    it.copy(
                        translating = false,
                        lastTranslation = translation,
                        message = summarize(translation.blocks.size, translation.engine),
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        translating = false,
                        message = UiMessage(describe(error), isError = true),
                    )
                }
            }
        }
    }

    fun onSaveCloud(key: String, enabled: Boolean) {
        container.settings.geminiKey = key
        container.settings.cloudFallbackEnabled = enabled
        _state.update {
            it.copy(
                savedKey = key.trim(),
                cloudEnabled = enabled,
                message = UiMessage(UiContent.Text(R.string.message_cloud_saved)),
            )
        }
    }

    fun onVerifyKey(key: String) {
        if (key.isBlank()) return
        viewModelScope.launch {
            runCatching { container.gemini.verifyKey(key) }
                .onSuccess {
                    _state.update {
                        it.copy(message = UiMessage(UiContent.Text(R.string.message_key_valid)))
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(message = UiMessage(describe(error), isError = true)) }
                }
        }
    }

    fun onHideOverlay() {
        AccessibilityBridge.service?.overlayRenderer?.hide()
    }

    /** Bring back the floating bubble on the current service instance. */
    fun onShowBubble() {
        AccessibilityBridge.service?.overlayRenderer?.showBubble {
            onTranslateNow()
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun observeBackground() {
        viewModelScope.launch {
            BackgroundState.shared.collect { background ->
                _state.update {
                    it.copy(
                        serviceRunning = background.running,
                        autoTranslate = background.autoTranslate,
                        // Same rule as refreshSystemState(): the system setting is
                        // authoritative, the live object only says "ready right now".
                        accessibilityReady = accessibility.isEnabledInSystemSettings() ||
                            AccessibilityBridge.isReady,
                        accessibilityLive = AccessibilityBridge.isReady,
                        accessibilitySnapshot = accessibility.diagnose(),
                    )
                }
            }
        }
    }

    /** Bumped by the activity's poll loop; used to distinguish "starting" from "dead". */
    private var pollAttempts = 0

    fun onAccessibilityPollTick() {
        pollAttempts++
        refreshSystemState()
    }

    fun onAccessibilityPollReset() {
        pollAttempts = 0
    }

    private val ownPackage: String get() = environment.packageName

    private fun summarize(count: Int, engine: TranslationEngine) =
        UiMessage(
            UiContent.Quantity(
                id = R.plurals.message_translated_done,
                count = count,
                args = listOf(UiArg.Res(engine.displayNameRes())),
            ),
        )

    /**
     * The exception's own message is the AIDL wire text (Vietnamese, documented in
     * docs/INTEGRATION.md) and must not change; the screen is built from the stable
     * code instead, so it can be shown in any language.
     */
    private fun describe(error: Throwable): UiContent =
        if (error is TranslationException) {
            UiContent.Text(error.code.messageRes())
        } else {
            UiContent.Text(R.string.error_unknown)
        }

    class Factory(
        private val container: AppContainer,
        private val services: ServiceController,
        private val permissions: PermissionChecker,
        private val environment: AppEnvironment,
        private val accessibility: AccessibilityStatus,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TranslatorViewModel(container, services, permissions, environment, accessibility) as T
    }

    companion object {
        /** ~6s of polling at 500ms: long enough for a real bind, short enough to be useful. */
        private const val REBIND_AFTER_POLLS = 12
    }
}
