package com.derfurt.deryurt.net

import android.net.Network
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Wifi ağına BAĞLI OkHttp istemcisi.
 *
 * Captive portal arkasındaki wifi "validated" olmadığı için Android onu varsayılan ağ yapmaz;
 * mobil veri açıksa normal istekler mobil veriden çıkar. Bunu önlemek için hem soketler
 * (socketFactory) hem de DNS sorguları (getAllByName) doğrudan wifi Network'ü üzerinden yapılır.
 * bindProcessToNetwork kullanılmaz; süreç genelini etkiler.
 */
object NetworkBoundClient {
    fun create(network: Network): OkHttpClient = OkHttpClient.Builder()
        .socketFactory(network.socketFactory)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
        })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
