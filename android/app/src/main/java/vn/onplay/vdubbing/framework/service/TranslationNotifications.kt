package vn.onplay.vdubbing.framework.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import vn.onplay.vdubbing.MainActivity
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Translation
import vn.onplay.vdubbing.domain.model.TranslationEngine
import vn.onplay.vdubbing.framework.locale.AppLocaleController
import vn.onplay.vdubbing.presentation.mapper.displayNameRes

internal class TranslationNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var lastSuccess: Pair<Int, TranslationEngine>? = null

    fun refresh() {
        createChannel()
        show(notificationDetail())
    }

    fun show(message: String) {
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    fun createChannel() {
        // Same id: calling this again updates the name and description, which is
        // how a channel that already exists on the device gets the new language.
        val channel = NotificationChannel(
            CHANNEL_ID,
            string(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = string(R.string.notif_channel_desc) }
        manager.createNotificationChannel(channel)
    }

    /** Strings must be read in the language chosen now, not the one this service started in. */
    private fun string(id: Int): String = AppLocaleController.localized(context).getString(id)

    /** Body text: the last result when there is one, otherwise the idle message. */
    private fun notificationDetail(): String {
        val (count, engine) = lastSuccess ?: return string(R.string.notif_text)
        val localized = AppLocaleController.localized(context)
        return localized.resources.getQuantityString(
            R.plurals.notif_translated_done,
            count,
            count,
            localized.getString(engine.displayNameRes()),
        )
    }

    fun buildNotification(detail: String = notificationDetail()): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val translateNow = PendingIntent.getService(
            context,
            1,
            Intent(context, TranslateService::class.java).setAction(TranslateService.ACTION_TRANSLATE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context,
            2,
            Intent(context, TranslateService::class.java).setAction(TranslateService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(string(R.string.notif_title))
            .setContentText(detail)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, string(R.string.notif_action_translate), translateNow)
            .addAction(0, string(R.string.notif_action_stop), stop)
            .build()
    }

    fun notifyResult(translation: Translation) {
        // Remember the data, not the sentence: a later language change rebuilds it.
        lastSuccess = translation.blocks.size to translation.engine
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(notificationDetail()),
        )
    }

    companion object {
        private const val CHANNEL_ID = "vdubbing_background"
        const val NOTIFICATION_ID = 1001
    }
}
