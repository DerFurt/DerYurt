package com.derfurt.deryurt.data

import android.content.Context
import com.derfurt.deryurt.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SADECE debug sürümünde: arka planda ne olduğunu görmek için kısa olay günlüğü
 * (files/events.log, son 300 satır). Release'te hiçbir şey yazmaz.
 *
 * Buraya sadece sabit olay adları, enum adları ve hata sınıf adları yazılır.
 * Kimlik bilgisi, sunucu cevabı, ağ adı ASLA yazılmaz.
 */
object EventLog {
    private const val FILE = "events.log"
    private const val MAX_LINES = 300

    fun add(context: Context, event: String, detail: String = "") {
        if (!BuildConfig.DEBUG) return
        try {
            val f = File(context.applicationContext.filesDir, FILE)
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            val line = "$time $event${if (detail.isNotEmpty()) " $detail" else ""}"
            synchronized(this) {
                val lines = if (f.exists()) f.readLines().takeLast(MAX_LINES - 1) else emptyList()
                f.writeText((lines + line).joinToString("\n", postfix = "\n"))
            }
        } catch (_: Exception) {
        }
    }
}
