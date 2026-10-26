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
import android.os.IBinder
import android.os.Process
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
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.Translation
import vn.onplay.vdubbing.domain.model.TranslationEngine
import vn.onplay.vdubbing.domain.model.TranslationErrorCode
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.framework.accessibility.AccessibilityBridge
import vn.onplay.vdubbing.framework.locale.AppLocaleController
import vn.onplay.vdubbing.presentation.BackgroundState
import vn.onplay.vdubbing.presentation.ui.displayNameRes

/**
 * Foreground, exported entry point for other apps, and the driver for
 * auto-translate. All actual work is delegated to [AppContainer.translateScreen];
 * this class only handles Android concerns: lifecycle, notification, Binder checks.
 */
class TranslateService : Service() {

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
        AccessibilityBridge.onServiceAvailable = { showBubble() }
        showBubble()
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
        AccessibilityBridge.service?.overlayRenderer?.hideBubble()
        AccessibilityBridge.service?.overlayRenderer?.hide()
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
     * The bubble is the only control available while another app is in front.
     * Tapping it runs a translation for the current target language.
     */
    private fun showBubble() {
        AccessibilityBridge.service?.overlayRenderer?.showBubble {
            translate(container.settings.targetLanguage) { }
        }
    }

    private fun translate(target: Language, callback: (Result<Translation>) -> Unit) {
        if (busy) {
            callback(Result.failure(TranslationException.Busy()))
            return
        }
        busy = true
        scope.launch {
            try {
                val translation = container.translateScreen(Language.AUTO, target, ownPackage = packageName)
                lastResultJson = translation.toJson()
                notifyResult(translation)
                callback(Result.success(translation))
            } catch (e: TranslationException) {
                callback(Result.failure(e))
            } catch (e: Exception) {
                callback(Result.failure(TranslationException.TranslationFailed(e.message.orEmpty(), e)))
            } finally {
                busy = false
            }
        }
    }

    private fun startAutoTranslate(target: Language) {
        val service = AccessibilityBridge.service ?: return
        autoJob?.cancel()

        service.onWindowChanged = {
            scope.launch {
                // Window changes arrive in bursts (dialogs, keyboards); a short
                // debounce keeps us off the screenshot rate limit.
                delay(WINDOW_DEBOUNCE_MS)
                if (!busy) translate(target) { }
            }
        }

        autoJob = scope.launch {
            while (true) {
                if (!busy) translate(target) { }
                delay(AUTO_INTERVAL_MS)
            }
        }
        BackgroundState.shared.value = BackgroundState(running = isRunning, autoTranslate = true)
    }

    private fun stopAutoTranslate() {
        autoJob?.cancel()
        autoJob = null
        AccessibilityBridge.service?.onWindowChanged = null
        BackgroundState.shared.value = BackgroundState(running = isRunning, autoTranslate = false)
    }

    fun lastResult(): String = lastResultJson

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
