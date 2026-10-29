package vn.onplay.vdubbing.framework.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import kotlin.math.roundToInt
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import vn.onplay.vdubbing.MainActivity
import vn.onplay.vdubbing.BuildConfig
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.VDubbingApp
import vn.onplay.vdubbing.di.AppContainer
import vn.onplay.vdubbing.domain.model.AppLanguage
import vn.onplay.vdubbing.domain.model.DisplayMode
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.Translation
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.framework.accessibility.AccessibilityBridge
import vn.onplay.vdubbing.framework.accessibility.awaitOverlayFrames
import vn.onplay.vdubbing.framework.locale.AppLocaleController
import vn.onplay.vdubbing.framework.audio.AudioCapturePermissionActivity
import vn.onplay.vdubbing.framework.audio.AudioTranslationService
import vn.onplay.vdubbing.framework.audio.messageRes
import vn.onplay.vdubbing.framework.overlay.OverlayHost
import vn.onplay.vdubbing.framework.overlay.PanelState
import vn.onplay.vdubbing.domain.model.BackgroundState

/**
 * Foreground, exported entry point for other apps, and the driver for
 * auto-translate. All actual work is delegated to [AppContainer.translateScreen];
 * this class only handles Android concerns: lifecycle, notification, Binder checks.
 */
class TranslateService : Service(), OverlayHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container: AppContainer get() = (application as VDubbingApp).container

    private val audioManager get() = getSystemService(AudioManager::class.java)

    private var autoJob: Job? = null

    @Volatile
    private var lastResultJson: String = ""

    private val busy: Boolean get() = requests.busy

    @Volatile
    private var isRunning = false

    private val notifications by lazy { TranslationNotifications(this) }
    private val localeListener: (AppLanguage) -> Unit = { notifications.refresh() }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocaleController.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        notifications.createChannel()
        startForeground(
            TranslationNotifications.NOTIFICATION_ID,
            notifications.buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        AppLocaleController.addListener(localeListener)
        container.backgroundStatus.publish(BackgroundState(
            running = true,
            autoTranslate = container.settings.autoTranslate,
        ))
        // The accessibility service may not be connected yet; the bridge callback
        // below covers that case.
        AccessibilityBridge.onServiceAvailable = {
            showBubble()
            setupAccessibilityCallbacks()
        }
        showBubble()
        setupAccessibilityCallbacks()
        scope.launch {
            container.audioSessions.state.collect {
                AccessibilityBridge.service?.overlayRenderer?.refresh()
            }
        }
        if (container.settings.autoTranslate) {
            startAutoTranslate()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRANSLATE -> translate(container.settings.targetLanguage) { }
            ACTION_STOP -> {
                onStopService()
                return START_NOT_STICKY
            }
            ACTION_SET_AUTO -> {
                // The activity flips auto mode through here: startService carries
                // the command, the persisted flag carries the intent for restarts.
                if (container.settings.autoTranslate) {
                    startAutoTranslate()
                } else {
                    stopAutoTranslate()
                }
            }
        }
        return START_STICKY
    }

    /**
     * Bound service: BIND_AUTO_CREATE alone would leave no foreground service, so
     * callers get START_STICKY persistence from [start] plus this binder.
     */
    override fun onBind(intent: Intent?): IBinder? = if (callerAllowed()) binder else null

    override fun onDestroy() {
        isRunning = false
        AudioTranslationService.stop(this)
        AppLocaleController.removeListener(localeListener)
        stopAutoTranslate()
        // Stopping the service must take everything off the screen, or the user
        // is left with an overlay and no way to remove it.
        AccessibilityBridge.onServiceAvailable = null
        AccessibilityBridge.service?.onScreenScrolled = null
        AccessibilityBridge.service?.onWindowChanged = null
        AccessibilityBridge.service?.overlayRenderer?.apply {
            host = null
            setBusy(false)
            hideBubble()
            hide()
        }
        // Each request releases its own capture after its engine has finished.
        scope.cancel()
        container.backgroundStatus.publish(BackgroundState(running = false))
        super.onDestroy()
    }

    // --- caller gate -------------------------------------------------------

    /**
     * The manifest already requires CALL_TRANSLATE to bind, but a future
     * permission-level change would silently open the service; verify directly.
     */
    private fun callerAllowed(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        return try {
            checkCallingOrSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        } catch (e: SecurityException) {
            false
        }
    }

    // --- translation -------------------------------------------------------

    /**
     * The bubble is the only control available while another app is in front:
     * it opens the panel and the menu, and those call back into this service
     * through [OverlayHost].
     */
    private fun showBubble() {
        AccessibilityBridge.service?.overlayRenderer?.let { renderer ->
            // The renderer draws the panel and the menu; this service supplies
            // the values behind them and carries out what was chosen.
            renderer.host = this
            renderer.showBubble {
                translate(container.settings.targetLanguage) { }
            }
        }
    }

    private val requests by lazy {
        TranslationRequestRunner(
            scope = scope,
            execute = { source, target -> container.translateScreen(source, target, ownPackage = packageName) },
            prepare = {
                translatedPackage = AccessibilityBridge.service?.foregroundPackage()
                AccessibilityBridge.service?.overlayRenderer?.dismissTransientWindows()
                awaitOverlayFrames()
            },
            setBusy = { if (isRunning) AccessibilityBridge.service?.overlayRenderer?.setBusy(it) },
            onSuccess = {
                lastResultJson = it.toJson()
                notifications.notifyResult(it)
            },
        )
    }

    private fun translate(target: Language, callback: (Result<Translation>) -> Unit) {
        requests.translate(container.settings.sourceLanguage, target, callback)
    }

    private var translatedPackage: String? = null

    private fun setupAccessibilityCallbacks() {
        val service = AccessibilityBridge.service ?: return
        service.onScreenScrolled = {
            // When user scrolls, hide previous translation only after it has been shown for a brief time
            // and we are not currently busy capturing a new translation.
            val renderer = service.overlayRenderer
            if (!busy && renderer?.hasTranslation == true) {
                val elapsed = android.os.SystemClock.uptimeMillis() - renderer.translationShownTime
                if (elapsed > 800L) {
                    renderer.hide()
                }
            }
            if (container.settings.autoTranslate) {
                scheduleAutoTranslate(SCROLL_DEBOUNCE_MS)
            }
        }
        service.onWindowChanged = {
            val renderer = service.overlayRenderer
            val currentPkg = service.foregroundPackage()
            // Only hide translation if user navigated to a DIFFERENT app (not just panel opening/closing
            // or internal activity transitions within the same app).
            if (!busy && renderer?.hasTranslation == true) {
                if (currentPkg != null && currentPkg != packageName && currentPkg != translatedPackage) {
                    renderer.hide()
                }
            }
            if (container.settings.autoTranslate) {
                scheduleAutoTranslate(WINDOW_DEBOUNCE_MS)
            }
        }
    }

    private fun scheduleAutoTranslate(debounceMs: Long) {
        autoJob?.cancel()
        autoJob = scope.launch {
            container.countdown.await(debounceMs)
            requests.awaitIdle()
            if (container.settings.autoTranslate) {
                translate(container.settings.targetLanguage) { reportFailureQuietly(it) }
            }
        }
    }

    private fun startAutoTranslate() {
        setupAccessibilityCallbacks()
        scheduleAutoTranslate(0)
        container.backgroundStatus.publish(BackgroundState(running = isRunning, autoTranslate = true))
    }

    private fun stopAutoTranslate() {
        autoJob?.cancel()
        autoJob = null
        container.backgroundStatus.publish(BackgroundState(running = isRunning, autoTranslate = false))
    }

    // --- OverlayHost -------------------------------------------------------

    /**
     * Read every time the panel opens rather than pushed into it: the panel is
     * rebuilt per open, so the service needs no way to reach into an open window.
     */
    override fun panelState(): PanelState = PanelState(
        source = container.settings.sourceLanguage,
        target = container.settings.targetLanguage,
        displayMode = container.settings.displayMode,
        autoTranslate = container.settings.autoTranslate,
        translating = busy,
        // Asked of the window, not of the last result: a translation the user
        // dismissed is no longer on screen, whatever the service last produced.
        hasTranslation = AccessibilityBridge.service?.overlayRenderer?.hasTranslation == true,
        originalVolume = volumePercent(AudioManager.STREAM_MUSIC),
        translationVolume = volumePercent(AudioManager.STREAM_ACCESSIBILITY),
        audioActive = container.audioSessions.state.value != null,
        audioStatusRes = container.audioSessions.state.value?.stage?.messageRes ?: 0,
    )

    override fun onSourceLanguage(language: Language) {
        if (language != container.settings.sourceLanguage) AudioTranslationService.stop(this)
        container.settings.sourceLanguage = language
    }

    override fun onTargetLanguage(language: Language) {
        if (language != container.settings.targetLanguage) AudioTranslationService.stop(this)
        container.settings.targetLanguage = language
    }

    override fun onDisplayMode(mode: DisplayMode) {
        container.settings.displayMode = mode
        AccessibilityBridge.service?.overlayRenderer?.setDisplayMode(mode)
    }

    override fun onAutoTranslate(enabled: Boolean) {
        container.settings.autoTranslate = enabled
        if (enabled) startAutoTranslate()
        else stopAutoTranslate()
    }

    override fun onOriginalVolume(volume: Int) = setVolumePercent(AudioManager.STREAM_MUSIC, volume)

    override fun onTranslationVolume(volume: Int) = setVolumePercent(AudioManager.STREAM_ACCESSIBILITY, volume)

    private fun volumePercent(stream: Int): Int =
        (audioManager.getStreamVolume(stream) * 100f / audioManager.getStreamMaxVolume(stream)).roundToInt()

    private fun setVolumePercent(stream: Int, volume: Int) {
        val index = (volume.coerceIn(0, 100) * audioManager.getStreamMaxVolume(stream) / 100f).roundToInt()
        audioManager.setStreamVolume(stream, index, 0)
    }

    override fun onAudioTranslation(enabled: Boolean) {
        if (!enabled) {
            AudioTranslationService.stop(this)
            return
        }
        if (container.audioSessions.state.value != null) return
        val sourcePackage = AccessibilityBridge.service?.foregroundPackage()
        if (sourcePackage == null || sourcePackage == packageName) {
            reportFailure(AppLocaleController.localized(this).getString(R.string.audio_select_app))
            return
        }
        val uid = runCatching { packageManager.getPackageUid(sourcePackage, 0) }.getOrNull()
        if (uid == null || uid == Process.myUid()) {
            reportFailure(AppLocaleController.localized(this).getString(R.string.audio_select_app))
            return
        }
        val session = container.audioSessions.begin(
            sourcePackage, uid, container.settings.sourceLanguage, container.settings.targetLanguage,
        )
        AccessibilityBridge.service?.overlayRenderer?.dismissTransientWindows()
        try {
            startActivity(AudioCapturePermissionActivity.intent(this, session.id))
        } catch (_: RuntimeException) {
            container.audioSessions.end(session.id)
            reportFailure(AppLocaleController.localized(this).getString(R.string.audio_start_failed))
        }
    }

    override fun onStartTranslation() {
        translate(container.settings.targetLanguage) { result ->
            // The panel and the menu are the only UI, and both are gone by now.
            // Without this the bubble simply stops spinning and the user is left
            // guessing whether anything happened.
            result.exceptionOrNull()?.let { error ->
                val message = when (error) {
                    is TranslationException -> error.message
                    else -> error.message.orEmpty()
                }
                reportFailure(message)
            }
        }
    }

    /**
     * Auto mode retries every few seconds, so a toast per attempt would be a
     * stream of them; the notification is the right place for that repetition.
     */
    private fun reportFailureQuietly(result: Result<Translation>) {
        if (!isRunning) return
        val message = result.exceptionOrNull()?.message ?: return
        if (message.isBlank()) return
        notifications.show(message)
    }

    private fun reportFailure(message: String) {
        if (!isRunning || message.isBlank()) return
        // Toast rather than an overlay window: a failure needs no interaction, so
        // it must not take touches away from the app being read.
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
        notifications.show(message)
    }

    override fun onStopTranslation() {
        AudioTranslationService.stop(this)
        AccessibilityBridge.service?.overlayRenderer?.hide()
    }

    override fun onOpenAppSettings() {
        // A service may start an activity only in narrow cases, and an overlay
        // tap is not one of them on every OEM build. When the system refuses,
        // the ongoing notification is the documented way into the app, so it is
        // pointed out rather than left as a dead button.
        val opened = runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
            )
        }.isSuccess
        if (!opened) {
            notifications.show(AppLocaleController.localized(this).getString(R.string.notif_open_hint))
        }
    }

    override fun onStopService() {
        AudioTranslationService.stop(this)
        stopSelf()
    }

    // --- binder ------------------------------------------------------------

    private val binder by lazy {
        TranslationBinder(
            scope = scope,
            settings = container.settings,
            requests = requests,
            lastResult = { lastResultJson },
            accessibilityReady = { AccessibilityBridge.isReady },
            hideOverlay = { AccessibilityBridge.service?.overlayRenderer?.hide() },
            setAutoTranslate = ::onAutoTranslate,
        )
    }

    companion object {
        const val ACTION_BIND = BuildConfig.APPLICATION_ID + ".TRANSLATE"
        const val PERMISSION = BuildConfig.APPLICATION_ID + ".permission.CALL_TRANSLATE"
        internal const val ACTION_TRANSLATE = BuildConfig.APPLICATION_ID + ".action.TRANSLATE"
        internal const val ACTION_STOP = BuildConfig.APPLICATION_ID + ".action.STOP"
        private const val ACTION_SET_AUTO = BuildConfig.APPLICATION_ID + ".action.SET_AUTO"
        private const val WINDOW_DEBOUNCE_MS = 1500L
        private const val SCROLL_DEBOUNCE_MS = 800L

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, TranslateService::class.java).setAction(ACTION_BIND),
            )
        }

        fun stop(context: Context) {
            AudioTranslationService.stop(context)
            context.stopService(Intent(context, TranslateService::class.java))
        }

        /** Restarts the service if needed; it reads the persisted auto flag. */
        fun applyAutoTranslate(context: Context) {
            context.startForegroundService(
                Intent(context, TranslateService::class.java).setAction(ACTION_SET_AUTO),
            )
        }
    }
}
