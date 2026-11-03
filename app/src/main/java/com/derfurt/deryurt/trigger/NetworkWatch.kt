package com.derfurt.deryurt.trigger

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.provider.Settings
import com.derfurt.deryurt.BuildConfig
import com.derfurt.deryurt.data.EventLog
import com.derfurt.deryurt.data.StatusStore

/**
 * Wifi bağlanınca uygulamayı uyandıran kayıt.
 *
 * registerNetworkCallback(request, PendingIntent): uygulama öldürülmüş olsa bile sistem,
 * eşleşen ağ geldiğinde [NetworkTriggerReceiver]'a yayın gönderir. Kalıcı servis gerekmez.
 * Bu kayıt yeniden başlatmada / güncellemede / zorla durdurmada silinir; bu yüzden
 * [RearmReceiver] ve uygulama açılışında yeniden kurulur.
 */
object NetworkWatch {

    fun wifiRequest(): NetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    /** force=false: bu açılış + sürüm için zaten kurulduysa tekrar kurmaz. */
    fun arm(context: Context, force: Boolean = false) {
        val store = StatusStore(context)
        val token = armToken(context)
        if (!force && store.armedToken == token) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        // Önce unregister ETMİYORUZ: sistem aynı PendingIntent'le gelen yeni kaydı eskisinin yerine koyar.
        // (İlk sürümde unregister + register art arda yapılıyordu ve cihazda kayıt sistemde görünmüyordu.)
        try {
            cm.registerNetworkCallback(wifiRequest(), pendingIntent(context))
            store.armedToken = token
            EventLog.add(context, "ARM_OK", if (force) "force" else "")
        } catch (e: Exception) {
            store.armedToken = null
            EventLog.add(context, "ARM_FAIL", e.javaClass.simpleName)
        }
    }

    private fun armToken(context: Context): String {
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
        return "$boot:${BuildConfig.VERSION_CODE}"
    }

    private fun pendingIntent(context: Context): PendingIntent {
        // Sistem EXTRA_NETWORK ekleyebilsin diye MUTABLE olmalı (Android 12+).
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val intent = Intent(context, NetworkTriggerReceiver::class.java)
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /** Şu an bağlı wifi ağı (yoksa null). */
    @Suppress("DEPRECATION")
    fun currentWifi(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    fun isValidated(context: Context, network: Network): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
}
