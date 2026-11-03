package com.derfurt.deryurt.net

import com.derfurt.deryurt.core.Credentials
import com.derfurt.deryurt.core.LoginOutcome
import com.derfurt.deryurt.core.PortalApi
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * GSB wifi portalına giriş:
 *  1) GET  login.html                  → sadece cookie (JSESSIONID, BIGipServer…)
 *  2) POST j_spring_security_check     → j_username, j_password, submit=Giriş (UTF-8 form)
 *  3) GET  generate_204                → 204 ise internet açıldı
 *
 * Kimlik bilgisi sadece HTTPS ile ve sertifikası doğrulanmış [portalBase] adresine gider.
 * Sertifika doğrulanamazsa (başka bir ağın captive portalı) NOT_GSB döner, POST hiç atılmaz.
 *
 * Bu sınıf hiçbir şey loglamaz.
 */
class GsbPortal(
    private val client: OkHttpClient,
    private val portalBase: HttpUrl = DEFAULT_PORTAL,
    private val checkUrl: HttpUrl = DEFAULT_CHECK,
    private val onlineCheckDelaysMs: List<Long> = listOf(0L, 1500L, 3000L),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : PortalApi {

    private val checkClient: OkHttpClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    override fun isOnline(): Boolean = try {
        val request = Request.Builder().url(checkUrl).header("Cache-Control", "no-cache").build()
        checkClient.newCall(request).execute().use { r ->
            r.code == 204 && (r.body?.contentLength() ?: 0L) <= 0L
        }
    } catch (_: IOException) {
        false
    }

    override fun login(credentials: Credentials): LoginOutcome {
        if (isOnline()) return LoginOutcome.ALREADY_ONLINE

        val c = client.newBuilder().cookieJar(MemoryCookieJar()).build()
        val loginPage = portalBase.resolve("login.html")!!
        val loginAction = portalBase.resolve("j_spring_security_check")!!

        // 1) Cookie'leri al
        val pageHtml: String = try {
            c.newCall(Request.Builder().url(loginPage).build()).execute().use { r ->
                if (!r.isSuccessful) return LoginOutcome.PORTAL_ERROR
                r.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            return classifyBeforePost(e)
        }

        // 2) Girişi gönder
        val form = FormBody.Builder(Charsets.UTF_8)
            .add("j_username", credentials.username)
            .add("j_password", credentials.password)
            .add("submit", "Giriş")
            .build()
        val post = Request.Builder()
            .url(loginAction)
            .header("Referer", loginPage.toString())
            .post(form)
            .build()

        var postHtml = ""
        var postTimedOut = false
        try {
            c.newCall(post).execute().use { r -> postHtml = r.body?.string().orEmpty() }
        } catch (_: InterruptedIOException) {
            // Sunucu yavaş: istek gitmiş olabilir, internet açıldı mı yine de bakılır.
            postTimedOut = true
        } catch (_: IOException) {
            if (waitUntilOnline()) return LoginOutcome.SUCCESS
            return LoginOutcome.PORTAL_ERROR
        }

        // 3) Başarıyı HTTP koduna göre değil, gerçek internet erişimine göre anla.
        if (waitUntilOnline()) return LoginOutcome.SUCCESS
        if (postTimedOut) return LoginOutcome.TIMEOUT
        if (OtherDeviceDetector.appearedAfterPost(pageHtml, postHtml)) return LoginOutcome.OTHER_DEVICE
        if (CaptchaDetector.appearedAfterPost(pageHtml, postHtml)) return LoginOutcome.CAPTCHA
        return LoginOutcome.WRONG_CREDENTIALS
    }

    private fun waitUntilOnline(): Boolean {
        for (delay in onlineCheckDelaysMs) {
            if (delay > 0) sleeper(delay)
            if (isOnline()) return true
        }
        return false
    }

    private fun classifyBeforePost(e: IOException): LoginOutcome = when (e) {
        is UnknownHostException, is SSLException -> LoginOutcome.NOT_GSB
        is InterruptedIOException -> LoginOutcome.TIMEOUT
        else -> LoginOutcome.PORTAL_ERROR
    }

    companion object {
        val DEFAULT_PORTAL: HttpUrl = "https://wifi.gsb.gov.tr/".toHttpUrl()
        val DEFAULT_CHECK: HttpUrl = "http://connectivitycheck.gstatic.com/generate_204".toHttpUrl()
    }
}

/**
 * Captcha tespiti (sezgisel). Normal girişte de captcha.js yüklendiği için sadece "captcha" kelimesine bakmak yanlış olur.
 * Bunun yerine: POST cevabında captcha giriş alanı / resmi var ve ilk login sayfasında yoktu → portal captcha istemeye başladı.
 */
object CaptchaDetector {
    private val marker = Regex("""<(input|img)\b[^>]*captcha""", RegexOption.IGNORE_CASE)

    fun hasCaptchaField(html: String): Boolean = marker.containsMatchIn(html)

    fun appearedAfterPost(loginPageHtml: String, postHtml: String): Boolean =
        hasCaptchaField(postHtml) && !hasCaptchaField(loginPageHtml)
}

/**
 * "Bu hesap başka bir cihazda açık" sayfası tespiti.
 * GEÇİCİ: Gerçek sayfanın HTML'i henüz görülmedi; kullanıcının anlattığı metne göre yazıldı.
 * HTML gelince kesin işaretle (metin/form id) değiştirilecek. HTML varlıkları (&#351; gibi) çözülerek bakılır.
 */
object OtherDeviceDetector {
    private val pattern = Regex("""ba[sş]ka\s+(bir\s+)?cihaz""", setOf(RegexOption.IGNORE_CASE))

    fun matches(html: String): Boolean = pattern.containsMatchIn(decodeEntities(html))

    /** Login sayfasında zaten geçen bir yardım metni yanlışlıkla eşleşmesin: sadece POST sonrası çıkan metin sayılır. */
    fun appearedAfterPost(loginPageHtml: String, postHtml: String): Boolean =
        matches(postHtml) && !matches(loginPageHtml)

    private val numeric = Regex("""&#(x?)([0-9a-fA-F]+);""")
    private fun decodeEntities(s: String): String = numeric.replace(s) { m ->
        val radix = if (m.groupValues[1].isEmpty()) 10 else 16
        m.groupValues[2].toIntOrNull(radix)?.toChar()?.toString() ?: m.value
    }
}

/** Sadece bir giriş çalıştırması boyunca yaşayan cookie deposu. Diske yazılmaz. */
class MemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (new in cookies) {
            this.cookies.removeAll { it.name == new.name && it.domain == new.domain && it.path == new.path }
            this.cookies.add(new)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt < now }
        return cookies.filter { it.matches(url) }
    }
}
