package vn.onplay.vdubbing.framework.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.onplay.vdubbing.ITranslationCallback
import vn.onplay.vdubbing.ITranslationService
import vn.onplay.vdubbing.MainActivity
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.VDubbingApp
import vn.onplay.vdubbing.di.AppContainer
import vn.onplay.vdubbing.domain.model.AppLanguage
import vn.onplay.vdubbing.domain.model.DisplayMode
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.Translation
import vn.onplay.vdubbing.domain.model.TranslationEngine
import vn.onplay.vdubbing.domain.model.TranslationErrorCode
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.framework.accessibility.AccessibilityBridge
import vn.onplay.vdubbing.framework.locale.AppLocaleController
import vn.onplay.vdubbing.framework.overlay.OverlayHost
import vn.onplay.vdubbing.framework.overlay.PanelState
import vn.onplay.vdubbing.presentation.BackgroundState
import vn.onplay.vdubbing.presentation.ui.displayNameRes

/**
 * Foreground, exported entry point for other apps, and the driver for
 * auto-translate. All actual work is delegated to [AppContainer.translateScreen];
 * this class only handles Android concerns: lifecycle, notification, Binder checks.
 */
class TranslateService : Service(), OverlayHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container: AppContainer get() = (application as VDubbingApp).container

    private var autoJob: Job? = null

    @Volatile
    private var lastResultJson: String = ""

    @Volatile
    private var busy = false

    @Volatile
    private var isRunning = false

    /** Last result as data, so the notification can be redrawn in a new language. */
    private var lastSuccess: Pair<Int, TranslationEngine>? = null

    /**
     * A preference change (Android 12 and below) does not recreate a running
     * service, so its strings would stay in the old language. Redraw what is on
     * screen when the language changes instead.
     */
    private val localeListener: (AppLanguage) -> Unit = {
        createChannel()
        val notification = buildNotification(notificationDetail())
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocaleController.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(string(R.string.notif_text)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        AppLocaleController.addListener(localeListener)
        BackgroundState.shared.value = BackgroundState(
            running = true,
            autoTranslate = container.settings.autoTranslate,
        )
        // The accessibility service may not be connected yet; the bridge callback
        // below covers that case.
        AccessibilityBridge.onServiceAvailable = {
            showBubble()
            setupAccessibilityCallbacks()
        }
        showBubble()
        setupAccessibilityCallbacks()
        if (container.settings.autoTranslate) {
            startAutoTranslate(container.settings.targetLanguage)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRANSLATE -> translate(container.settings.targetLanguage) { }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_AUTO -> {
                // The activity flips auto mode through here: startService carries
                // the command, the persisted flag carries the intent for restarts.
                if (container.settings.autoTranslate) {
                    startAutoTranslate(container.settings.targetLanguage)
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
        AppLocaleController.removeListener(localeListener)
        stopAutoTranslate()
        // Stopping the service must take everything off the screen, or the user
        // is left with an overlay and no way to remove it.
        AccessibilityBridge.onServiceAvailable = null
        AccessibilityBridge.service?.onScreenScrolled = null
        AccessibilityBridge.service?.onWindowChanged = null
        AccessibilityBridge.service?.overlayRenderer?.apply {
            host = null
            hideBubble()
            hide()
        }
        // Cancel first: an in-flight OCR/HTTP call may still be reading a bitmap,
        // and clearing the store recycles bitmaps.
        scope.cancel()
        container.captureStore.clear()
        BackgroundState.shared.value = BackgroundState(running = false)
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

    private fun translate(
        target: Language,
        callback: (Result<Translation>) -> Unit,
    ) {
        val source = container.settings.sourceLanguage
        if (busy) {
            callback(Result.failure(TranslationException.Busy()))
            return
        }
        busy = true
        translatedPackage = AccessibilityBridge.service?.foregroundPackage()
        // The bubble is the only thing visible while another app is in front, so
        // it is also the only place that can say the work is in progress.
        AccessibilityBridge.service?.overlayRenderer?.setBusy(true)
        scope.launch {
            try {
                // Our own windows are part of the screenshot. Close them and give
                // the compositor a frame to actually remove them, or OCR reads the
                // panel instead of the page.
                AccessibilityBridge.service?.overlayRenderer?.dismissTransientWindows()
                delay(CAPTURE_SETTLE_MS)
                val translation = container.translateScreen(source, target, ownPackage = packageName)
                lastResultJson = translation.toJson()
                notifyResult(translation)
                callback(Result.success(translation))
            } catch (e: TranslationException) {
                callback(Result.failure(e))
            } catch (e: Exception) {
                callback(Result.failure(TranslationException.TranslationFailed(e.message.orEmpty(), e)))
            } finally {
                busy = false
                AccessibilityBridge.service?.overlayRenderer?.setBusy(false)
            }
        }
    }

    private var scrollDebounceJob: Job? = null
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
                scrollDebounceJob?.cancel()
                scrollDebounceJob = scope.launch {
                    delay(SCROLL_DEBOUNCE_MS)
                    if (container.settings.autoTranslate && !busy) {
                        translate(container.settings.targetLanguage) { reportFailureQuietly(it) }
                    }
                }
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
                scope.launch {
                    delay(WINDOW_DEBOUNCE_MS)
                    if (container.settings.autoTranslate && !busy) {
                        translate(container.settings.targetLanguage) { reportFailureQuietly(it) }
                    }
                }
            }
        }
    }

    private fun startAutoTranslate(target: Language) {
        autoJob?.cancel()
        setupAccessibilityCallbacks()

        autoJob = scope.launch {
            while (true) {
                if (!busy) translate(target) { result -> reportFailureQuietly(result) }
                delay(AUTO_INTERVAL_MS)
            }
        }
        BackgroundState.shared.value = BackgroundState(running = isRunning, autoTranslate = true)
    }

    private fun stopAutoTranslate() {
        autoJob?.cancel()
        autoJob = null
        scrollDebounceJob?.cancel()
        scrollDebounceJob = null
        BackgroundState.shared.value = BackgroundState(running = isRunning, autoTranslate = false)
    }

    fun lastResult(): String = lastResultJson

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
    )

    override fun onSourceLanguage(language: Language) {
        container.settings.sourceLanguage = language
    }

    override fun onTargetLanguage(language: Language) {
        container.settings.targetLanguage = language
    }

    override fun onDisplayMode(mode: DisplayMode) {
        container.settings.displayMode = mode
        AccessibilityBridge.service?.overlayRenderer?.setDisplayMode(mode)
    }

    override fun onAutoTranslate(enabled: Boolean) {
        container.settings.autoTranslate = enabled
        if (enabled) startAutoTranslate(container.settings.targetLanguage)
        else stopAutoTranslate()
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
        val message = result.exceptionOrNull()?.message ?: return
        if (message.isBlank()) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun reportFailure(message: String) {
        if (message.isBlank()) return
        // Toast rather than an overlay window: a failure needs no interaction, so
        // it must not take touches away from the app being read.
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onStopTranslation() {
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
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(string(R.string.notif_open_hint)))
        }
    }

    override fun onStopService() {
        stopSelf()
    }

    // --- notification ------------------------------------------------------

    private fun createChannel() {
        // Same id: calling this again updates the name and description, which is
        // how a channel that already exists on the device gets the new language.
        val channel = NotificationChannel(
            CHANNEL_ID,
            string(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = string(R.string.notif_channel_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Strings must be read in the language chosen now, not the one this service started in. */
    private fun string(id: Int): String = AppLocaleController.localized(this).getString(id)

    /** Body text: the last result when there is one, otherwise the idle message. */
    private fun notificationDetail(): String {
        val (count, engine) = lastSuccess ?: return string(R.string.notif_text)
        val localized = AppLocaleController.localized(this)
        return localized.resources.getQuantityString(
            R.plurals.notif_translated_done,
            count,
            count,
            localized.getString(engine.displayNameRes()),
        )
    }

    private fun buildNotification(detail: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val translateNow = PendingIntent.getService(
            this,
            1,
            Intent(this, TranslateService::class.java).setAction(ACTION_TRANSLATE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, TranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(string(R.string.notif_title))
            .setContentText(detail)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, string(R.string.notif_action_translate), translateNow)
            .addAction(0, string(R.string.notif_action_stop), stop)
            .build()
    }

    private fun notifyResult(translation: Translation) {
        // Remember the data, not the sentence: a later language change rebuilds it.
        lastSuccess = translation.blocks.size to translation.engine
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(notificationDetail()),
        )
    }

    // --- binder ------------------------------------------------------------

    private val binder = object : ITranslationService.Stub() {
        override fun translateNow(
            sourceLang: String?,
            targetLang: String?,
            callback: ITranslationCallback?,
        ) {
            val target = Language.fromCode(targetLang)
                .takeIf { it in Language.TARGETS }
                ?: container.settings.targetLanguage
            this@TranslateService.translate(target) { result ->
                result
                    .onSuccess { runCatching { callback?.onResult(it.toJson()) } }
                    .onFailure { error ->
                        val code = (error as? TranslationException)?.code
                            ?: TranslationErrorCode.TRANSLATION_FAILED
                        runCatching { callback?.onError(code.value, error.message.orEmpty()) }
                    }
            }
        }

        override fun setAutoTranslate(enabled: Boolean, sourceLang: String?, targetLang: String?) {
            val target = Language.fromCode(targetLang)
                .takeIf { it in Language.TARGETS }
                ?: container.settings.targetLanguage
            if (enabled) startAutoTranslate(target) else stopAutoTranslate()
        }

        override fun getLastResult(): String = lastResultJson

        override fun isAccessibilityReady(): Boolean = AccessibilityBridge.isReady

        override fun hideOverlay() {
            // Called on a Binder thread; WindowManager.removeView requires Main.
            scope.launch { AccessibilityBridge.service?.overlayRenderer?.hide() }
        }
    }

    companion object {
        const val ACTION_BIND = "vn.onplay.vdubbing.TRANSLATE"
        const val PERMISSION = "vn.onplay.vdubbing.permission.CALL_TRANSLATE"
        private const val ACTION_TRANSLATE = "vn.onplay.vdubbing.action.TRANSLATE"
        private const val ACTION_STOP = "vn.onplay.vdubbing.action.STOP"
        private const val ACTION_SET_AUTO = "vn.onplay.vdubbing.action.SET_AUTO"
        private const val CHANNEL_ID = "vdubbing_background"
        private const val NOTIFICATION_ID = 1001
        private const val AUTO_INTERVAL_MS = 3000L
        private const val WINDOW_DEBOUNCE_MS = 1500L
        private const val SCROLL_DEBOUNCE_MS = 800L

        /**
         * Time for the panel to disappear before the screenshot is taken. A
         * frame would be enough on paper; real devices compose overlays on the
         * next vsync, and a capture taken during removal still shows the panel.
         */
        private const val CAPTURE_SETTLE_MS = 250L

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, TranslateService::class.java).setAction(ACTION_BIND),
            )
        }

        fun stop(context: Context) {
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
