package vn.onplay.vdubbing.presentation.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.AppLanguage
import vn.onplay.vdubbing.domain.model.DisplayMode
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.ThemeMode
import vn.onplay.vdubbing.domain.model.TranslationEngine
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.domain.repository.BackgroundStatusRepository
import vn.onplay.vdubbing.domain.repository.CloudTranslationRepository
import vn.onplay.vdubbing.domain.repository.SettingsRepository
import vn.onplay.vdubbing.domain.usecase.TranslateScreenUseCase
import vn.onplay.vdubbing.presentation.AccessibilityStatus
import vn.onplay.vdubbing.presentation.AppEnvironment
import vn.onplay.vdubbing.presentation.PermissionChecker
import vn.onplay.vdubbing.presentation.ServiceController
import vn.onplay.vdubbing.presentation.uimodel.UiArg
import vn.onplay.vdubbing.presentation.uimodel.UiContent
import vn.onplay.vdubbing.presentation.uimodel.UiMessage
import vn.onplay.vdubbing.presentation.mapper.displayNameRes
import vn.onplay.vdubbing.presentation.mapper.messageRes

/** Screen state and intents; Android operations use ports bound by Hilt. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val cloud: CloudTranslationRepository,
    private val translateScreen: TranslateScreenUseCase,
    private val services: ServiceController,
    private val permissions: PermissionChecker,
    private val environment: AppEnvironment,
    private val accessibility: AccessibilityStatus,
    private val backgroundStatus: BackgroundStatusRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val languageChanges = Channel<AppLanguage>(Channel.BUFFERED)
    val appLanguageChanges = languageChanges.receiveAsFlow()

    init {
        _state.update {
            it.copy(
                savedKey = settings.geminiKey,
                geminiLiveKey = settings.geminiLiveKey,
                geminiLiveAudioEnabled = settings.geminiLiveAudioEnabled,
                cloudEnabled = settings.cloudFallbackEnabled,
                sourceLanguage = settings.sourceLanguage,
                targetLanguage = settings.targetLanguage,
                displayMode = settings.displayMode,
                autoTranslate = settings.autoTranslate,
                themeMode = settings.themeMode,
                appLanguage = settings.appLanguage,
            )
        }
        observeBackground()
        refreshSystemState()
    }

    /** Reload system status and preferences changed by the overlay, AIDL or system settings. */
    fun refreshSystemState() {
        val snapshot = accessibility.diagnose()
        _state.update {
            it.copy(
                accessibilityReady = snapshot.enabled || accessibility.isLive(),
                accessibilityNeedsRebind = snapshot.enabled &&
                    !accessibility.isLive() &&
                    pollAttempts >= REBIND_AFTER_POLLS,
                accessibilityLive = accessibility.isLive(),
                notificationsGranted = permissions.notificationsGranted(),
                // Android 13+ also lets the language be changed in system settings;
                // that path only reaches us through the repository.
                appLanguage = settings.appLanguage,
                sourceLanguage = settings.sourceLanguage,
                targetLanguage = settings.targetLanguage,
                displayMode = settings.displayMode,
                autoTranslate = settings.autoTranslate,
                themeMode = settings.themeMode,
                cloudEnabled = settings.cloudFallbackEnabled,
                savedKey = settings.geminiKey,
                geminiLiveKey = settings.geminiLiveKey,
                geminiLiveAudioEnabled = settings.geminiLiveAudioEnabled,
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
        settings.autoTranslate = false
        // Clear the overlay immediately: onDestroy only runs when the system
        // actually tears the service down, which is not the same moment.
        onHideOverlay()
        _state.update { it.copy(lastTranslation = null) }
        services.stop()
    }

    fun onSourceLanguageChange(language: Language) {
        settings.sourceLanguage = language
        _state.update { it.copy(sourceLanguage = language) }
    }

    fun onTargetLanguageChange(language: Language) {
        settings.targetLanguage = language
        _state.update { it.copy(targetLanguage = language) }
    }

    fun onDisplayModeChange(mode: DisplayMode) {
        settings.displayMode = mode
        _state.update { it.copy(displayMode = mode) }
        // A translation already on screen is redrawn in the new mode; the
        // setting alone would only take effect on the next capture.
        services.setDisplayMode(mode)
    }

    fun onAutoTranslateChange(enabled: Boolean) {
        settings.autoTranslate = enabled
        _state.update { it.copy(autoTranslate = enabled) }
        services.applyAutoTranslate()
    }

    fun onThemeModeChange(mode: ThemeMode) {
        settings.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    fun onAppLanguageChange(language: AppLanguage) {
        if (language == _state.value.appLanguage) return
        settings.appLanguage = language
        _state.update { it.copy(appLanguage = language) }
        // Applies app-wide and restarts the screen; that is platform work, so the
        // activity does it rather than this ViewModel.
        languageChanges.trySend(language)
    }

    fun onTranslateNow() {
        if (_state.value.translating) return
        if (!accessibility.isLive()) {
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

        val source = settings.sourceLanguage
        val target = settings.targetLanguage
        _state.update { it.copy(translating = true) }
        viewModelScope.launch {
            runCatching {
                translateScreen(
                    source = source,
                    target = target,
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
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(
                        translating = false,
                        message = UiMessage(describe(error), isError = true),
                    )
                }
            }
        }
    }

    fun onSaveGeminiLive(key: String, enabled: Boolean) {
        val trimmed = key.trim()
        if ((enabled && trimmed.isEmpty()) || trimmed.length > 512 || trimmed.any { it.code !in 33..126 }) {
            _state.update { it.copy(message = UiMessage(UiContent.Text(R.string.gemini_live_key_required), isError = true)) }
            return
        }
        try {
            val changed = settings.geminiLiveKey != trimmed || settings.geminiLiveAudioEnabled != enabled
            settings.geminiLiveKey = trimmed
            settings.geminiLiveAudioEnabled = enabled
            if (changed) services.stopAudioTranslation()
            _state.update { it.copy(
                geminiLiveKey = trimmed,
                geminiLiveAudioEnabled = enabled,
                message = UiMessage(UiContent.Text(R.string.gemini_live_saved)),
            ) }
        } catch (_: Exception) {
            _state.update { it.copy(message = UiMessage(UiContent.Text(R.string.gemini_live_save_failed), isError = true)) }
        }
    }

    fun onSaveCloud(key: String, enabled: Boolean) {
        settings.geminiKey = key
        settings.cloudFallbackEnabled = enabled
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
            runCatching { cloud.verifyKey(key) }
                .onSuccess {
                    _state.update {
                        it.copy(message = UiMessage(UiContent.Text(R.string.message_key_valid)))
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _state.update { it.copy(message = UiMessage(describe(error), isError = true)) }
                }
        }
    }

    fun onHideOverlay() {
        services.hideOverlay()
    }

    /** Bring back the floating bubble on the current service instance. */
    fun onShowBubble() {
        services.showBubble {
            onTranslateNow()
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun observeBackground() {
        viewModelScope.launch {
            backgroundStatus.state.collect { background ->
                _state.update {
                    it.copy(
                        serviceRunning = background.running,
                        autoTranslate = settings.autoTranslate,
                        // Same rule as refreshSystemState(): the system setting is
                        // authoritative, the live object only says "ready right now".
                        accessibilityReady = accessibility.isEnabledInSystemSettings() ||
                            accessibility.isLive(),
                        accessibilityLive = accessibility.isLive(),
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

    companion object {
        /** ~6s of polling at 500ms: long enough for a real bind, short enough to be useful. */
        private const val REBIND_AFTER_POLLS = 12
    }
}
