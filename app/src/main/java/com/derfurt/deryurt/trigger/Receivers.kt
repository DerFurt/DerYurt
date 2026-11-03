package com.derfurt.deryurt.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.derfurt.deryurt.data.EventLog
import com.derfurt.deryurt.data.StatusStore

/** Wifi bağlanınca sistemden gelir ([NetworkWatch] PendingIntent'i). */
class NetworkTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EventLog.add(context, "PI_TRIGGER")
        if (!StatusStore(context).autoLogin) return
        LoginWork.enqueue(context, manual = false, fresh = true, source = "connect")
    }
}

/** Bildirimdeki "Tekrar dene". */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RETRY) {
            Notifier.cancelResult(context)
            LoginWork.enqueue(context, manual = true, source = "notif_retry")
        }
    }

    companion object {
        const val ACTION_RETRY = "com.derfurt.deryurt.action.RETRY"
    }
}

/** Yeniden başlatma ve uygulama güncellemesinden sonra ağ dinleyicisini ve isteğe bağlı izleme servisini kurar. */
class RearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                EventLog.add(context, "REARM", intent.action.orEmpty().substringAfterLast('.'))
                NetworkWatch.arm(context, force = true)
                val store = StatusStore(context)
                if (store.autoLogin) LoginWork.schedulePeriodic(context)
                if (store.watchSession) PortalWatchService.start(context)
            }
        }
    }
}
