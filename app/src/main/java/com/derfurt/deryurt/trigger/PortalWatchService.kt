package com.derfurt.deryurt.trigger

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.derfurt.deryurt.data.EventLog
import com.derfurt.deryurt.data.StatusStore

/**
 * İsteğe bağlı "Arka planda sürekli bağlı tut" (ayarlardan açılır, varsayılan kapalı; otomatik giriş de açık olmalı).
 * Canlı bir ağ dinleyicisi tutar; wifi "doğrulanmış" durumdan captive portal'a dönerse
 * (oturum düştü) hemen giriş işi başlatır. Açıkken kalıcı, sessiz bir bildirim gösterir.
 */
class PortalWatchService : Service() {

    private var callback: ConnectivityManager.NetworkCallback? = null
    private val lastValidated = HashMap<Network, Boolean>()

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.ID_WATCH, Notifier.watching(this), type)
        EventLog.add(this, "WATCH_START")

        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val portal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                val previous = synchronized(lastValidated) { lastValidated.put(network, validated) }
                if (previous != validated) EventLog.add(applicationContext, "WATCH_CAPS", "validated=$validated portal=$portal")
                // İlk kez görülen captive ağ ya da doğrulanmışken portala dönen ağ → giriş dene
                if (portal && !validated && previous != false) {
                    LoginWork.enqueue(applicationContext, manual = false, fresh = true, source = "session_drop")
                }
            }

            override fun onLost(network: Network) {
                synchronized(lastValidated) { lastValidated.remove(network) }
            }
        }
        try {
            cm?.registerNetworkCallback(NetworkWatch.wifiRequest(), cb)
            callback = cb
        } catch (_: Exception) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!StatusStore(this).watchActive) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        EventLog.add(this, "WATCH_STOP")
        callback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        callback = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Arka plandan başlatma yasaksa (Android 12+) sessizce vazgeçer; uygulama açılınca tekrar denenir. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PortalWatchService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PortalWatchService::class.java))
        }
    }
}
