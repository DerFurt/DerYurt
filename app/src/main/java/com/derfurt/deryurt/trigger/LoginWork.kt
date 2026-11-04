package com.derfurt.deryurt.trigger

import android.content.Context
import com.derfurt.deryurt.data.EventLog
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Giriş işlerini sıraya koyan yardımcı. */
object LoginWork {
    const val KEY_MANUAL = "manual"
    const val KEY_FRESH = "fresh"
    const val KEY_SOURCE = "source"
    const val KEY_KICK = "kick_other"
    const val KEY_SWITCH = "switch_account"
    private const val UNIQUE_NOW = "login_now"
    private const val UNIQUE_PERIODIC = "login_periodic"

    /**
     * manual=true: kullanıcı istedi ("Şimdi giriş yap", "Tekrar dene", kutucuk). Kilitler kalkar.
     * manual=false: wifi bağlandı / oturum düştü. Aynı anda ikinci iş açılmaz.
     * fresh=true: yeni bağlantı ya da oturum düşmesi; portal yavaşlığı beklemesi sıfırlanır.
     * source: sadece debug olay günlüğü için (connect, session_drop, app, tile, notif...).
     * kick=true: hesap başka cihazda açıksa o oturumu kapat ("Diğer cihazdan çıkar" butonu).
     * switch=true: bu telefonda başka hesapla açık oturumu kapatıp kayıtlı hesapla gir.
     */
    fun enqueue(
        context: Context,
        manual: Boolean,
        fresh: Boolean = false,
        source: String,
        kick: Boolean = false,
        switch: Boolean = false,
    ) {
        EventLog.add(context, "ENQUEUE", "$source manual=$manual fresh=$fresh kick=$kick switch=$switch")
        val request = OneTimeWorkRequestBuilder<LoginWorker>()
            .setInputData(workDataOf(KEY_MANUAL to manual, KEY_FRESH to fresh, KEY_SOURCE to source,
                KEY_KICK to kick, KEY_SWITCH to switch))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NOW,
            if (manual) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Oturum düşmesine karşı 15 dakikada bir kontrol (Android'in izin verdiği en kısa aralık). */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<LoginWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_MANUAL to false, KEY_SOURCE to "periodic"))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
    }
}
