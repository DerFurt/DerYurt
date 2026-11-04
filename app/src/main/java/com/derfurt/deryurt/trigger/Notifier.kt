package com.derfurt.deryurt.trigger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.derfurt.deryurt.R
import com.derfurt.deryurt.ui.AppTheme
import com.derfurt.deryurt.ui.MainActivity

/** Bütün bildirimler buradan. Kullanıcıya görünen metinler strings.xml'de. */
object Notifier {
    private const val CH_RESULTS = "results"
    private const val CH_PROBLEMS = "problems"
    private const val CH_BACKGROUND = "background"

    /** Sonuç bildirimleri tek ID'yi paylaşır; yenisi eskisinin yerine geçer. */
    const val ID_RESULT = 1
    const val ID_WORKING = 2
    const val ID_WATCH = 3

    private const val SUCCESS_TIMEOUT_MS = 6_000L

    fun createChannels(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CH_RESULTS, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.channel_results)).build(),
                NotificationChannelCompat.Builder(CH_PROBLEMS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.channel_problems)).build(),
                NotificationChannelCompat.Builder(CH_BACKGROUND, NotificationManagerCompat.IMPORTANCE_MIN)
                    .setName(context.getString(R.string.channel_background)).build(),
            )
        )
    }

    fun success(context: Context, kickedOther: Boolean = false) {
        val text = context.getString(if (kickedOther) R.string.notif_success_kicked_text else R.string.notif_success_text)
        val n = base(context, CH_RESULTS)
            .setContentTitle(context.getString(R.string.notif_success_title))
            .setContentText(text)
            .setTimeoutAfter(SUCCESS_TIMEOUT_MS)
            .addAction(0, context.getString(R.string.notif_action_settings), openApp(context))
            .build()
        post(context, ID_RESULT, n)
    }

    fun failure(context: Context, locked: Boolean) {
        val text = context.getString(if (locked) R.string.notif_fail_locked_text else R.string.notif_fail_text)
        val n = base(context, CH_PROBLEMS)
            .setContentTitle(context.getString(R.string.notif_fail_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(0, context.getString(R.string.notif_action_retry), retry(context))
            .addAction(0, context.getString(R.string.notif_action_settings), openApp(context))
            .build()
        post(context, ID_RESULT, n)
    }

    fun captcha(context: Context) {
        val n = base(context, CH_PROBLEMS)
            .setContentTitle(context.getString(R.string.notif_captcha_title))
            .setContentText(context.getString(R.string.notif_captcha_text))
            .addAction(0, context.getString(R.string.notif_action_open_portal), openPortal(context))
            .build()
        post(context, ID_RESULT, n)
    }

    /**
     * @param since diğer oturumun başlama saati (portaldan), yoksa null
     * @param retaken otomatik kapatma bu bağlantıda zaten kullanıldı ve diğer cihaz oturumu geri aldı
     * @param kickFailed diğer oturum kapatıldı ama portal hâlâ izin vermedi
     */
    fun otherDevice(context: Context, since: String?, retaken: Boolean, kickFailed: Boolean) {
        val text = buildString {
            append(context.getString(when {
                kickFailed -> R.string.notif_other_kick_failed_text
                retaken -> R.string.notif_other_retaken_text
                else -> R.string.notif_other_text
            }))
            if (since != null) append(' ').append(context.getString(R.string.other_session_since, since))
        }
        val n = base(context, CH_PROBLEMS)
            .setContentTitle(context.getString(R.string.notif_other_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(0, context.getString(R.string.btn_kick_other), kick(context))
            .addAction(0, context.getString(R.string.notif_action_retry), retry(context))
            .build()
        post(context, ID_RESULT, n)
    }

    fun slow(context: Context) {
        val n = base(context, CH_PROBLEMS)
            .setContentTitle(context.getString(R.string.notif_slow_title))
            .setContentText(context.getString(R.string.notif_slow_text))
            .addAction(0, context.getString(R.string.notif_action_retry), retry(context))
            .build()
        post(context, ID_RESULT, n)
    }

    fun cancelResult(context: Context) = NotificationManagerCompat.from(context).cancel(ID_RESULT)

    fun working(context: Context): Notification = base(context, CH_BACKGROUND)
        .setContentTitle(context.getString(R.string.notif_working))
        .setOngoing(true)
        .setAutoCancel(false)
        .build()

    fun watching(context: Context): Notification = base(context, CH_BACKGROUND)
        .setContentTitle(context.getString(R.string.notif_watching))
        .setOngoing(true)
        .setAutoCancel(false)
        .build()

    private fun base(context: Context, channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_wifi)
        .setColor(AppTheme.colors(context).primary)
        .setContentIntent(openApp(context))
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)

    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 10,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun retry(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 11,
        Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_RETRY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun kick(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 13,
        Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_KICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openPortal(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 12,
        Intent(Intent.ACTION_VIEW, context.getString(R.string.portal_url).toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
