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
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * GSB wifi portalına giriş:
 *  1) GET  login.html                  → sadece cookie (JSESSIONID, BIGipServer…)
 *  2) POST j_spring_security_check     → j_username, j_password, submit=Giriş (UTF-8 form)
 *  3) GET  generate_204                → 204 ise internet açıldı
 *
 * Hesap başka cihazda açıksa portal POST'u maksimumCihazHakkiDolu.html'e yönlendirir; o sayfada açık oturumlar
 * ve her birinde "End" butonu var. kickOtherDevice=true ise o oturum kapatılıp yeniden giriş yapılır.
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
    /** Diğer cihazın oturumu kapatıldıktan sonra yeniden giriş denemeleri arasındaki bekleme. */
    private val kickRetryDelaysMs: List<Long> = listOf(3_000L, 5_000L, 8_000L),
    /** Bu telefondaki oturum kapatıldıktan sonra internetin kapandığını görmek için bekleme. */
    private val endWaitDelaysMs: List<Long> = listOf(1_000L, 2_000L, 3_000L, 5_000L),
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

    /** Son girişte görülen diğer cihaz oturumu (yoksa null). Sadece başlama saati tutulur, MAC tutulmaz. */
    @Volatile var otherSession: OtherSession? = null
        private set

    /** Son girişte diğer cihazın oturumu kapatıldı mı? */
    @Volatile var kickedOther: Boolean = false
        private set

    /** Son girişte bu telefondaki (başka hesapla açık) oturum kapatıldı mı? */
    @Volatile var endedCurrentSession: Boolean = false
        private set

    override fun login(credentials: Credentials, kickOtherDevice: Boolean, endCurrentSession: Boolean): LoginOutcome {
        otherSession = null
        kickedOther = false
        endedCurrentSession = false
        val c = client.newBuilder().cookieJar(MemoryCookieJar()).build()

        if (isOnline()) {
            if (!endCurrentSession) return LoginOutcome.ALREADY_ONLINE
            // Kullanıcı istedi: bu telefonda açık olan oturumu (başka hesap) kapat, sonra kayıtlı hesapla gir.
            if (!endPortalSession(c)) return LoginOutcome.PORTAL_ERROR
            endedCurrentSession = true
            if (!waitUntilOffline()) return LoginOutcome.PORTAL_ERROR
        }
        val first = attempt(c, credentials)
        if (first.outcome != LoginOutcome.OTHER_DEVICE) return first.outcome

        val session = first.otherSessions.firstOrNull()
        otherSession = session
        if (!kickOtherDevice || session == null) return LoginOutcome.OTHER_DEVICE
        if (!endSession(c, session)) return LoginOutcome.OTHER_DEVICE
        kickedOther = true

        // Portal "oturumun kapanması zaman alabilir" diyor: biraz bekleyip yeniden giriş yapılır.
        for (delay in kickRetryDelaysMs) {
            sleeper(delay)
            val again = attempt(c, credentials)
            if (again.outcome != LoginOutcome.OTHER_DEVICE) return again.outcome
            again.otherSessions.firstOrNull()?.let { otherSession = it }
        }
        return LoginOutcome.OTHER_DEVICE
    }

    /**
     * Bu wifi GSB mi? Kimlik bilgisi göndermeden portala bakar. wifi.gsb.gov.tr sadece GSB ağında
     * çözülür (iç IP) ve sertifikası doğrulanmalı; herhangi bir HTTP cevabı gelirse GSB'deyiz.
     * Portal oturumu hatırlayıp interneti kendiliğinden açtığında (ALREADY_ONLINE) kullanılır.
     */
    fun isGsbReachable(): Boolean = probe().reachable

    /**
     * Portalın ana sayfasına şifre göndermeden bakar (sadece GET):
     *  - cevap yoksa → GSB değil
     *  - girişliyse → oturumun açık olduğu hesabın adı + kota
     *  - giriş sayfasına yönlendirdiyse → GSB ama oturum yok
     */
    fun probe(): PortalProbe = try {
        val c = client.newBuilder().cookieJar(MemoryCookieJar()).callTimeout(10, TimeUnit.SECONDS).build()
        c.newCall(Request.Builder().url(portalBase).build()).execute().use { r ->
            val html = r.body?.string().orEmpty()
            PortalProbe(reachable = true, session = if (r.isSuccessful) SessionPage.parse(html, r.request.url, portalBase.host) else null)
        }
    } catch (_: IOException) {
        PortalProbe(reachable = false, session = null)
    }

    /** Ana sayfadaki "End Session" butonunun yaptığı PrimeFaces AJAX isteği (aynı cookie oturumuyla). */
    private fun endPortalSession(c: OkHttpClient): Boolean {
        val form = try {
            c.newCall(Request.Builder().url(portalBase).build()).execute().use { r ->
                if (!r.isSuccessful) return false
                SessionPage.parse(r.body?.string().orEmpty(), r.request.url, portalBase.host)?.endForm
            }
        } catch (_: IOException) {
            return false
        } ?: return false
        return postAjax(c, form, render = null)
    }

    private fun waitUntilOffline(): Boolean {
        for (delay in endWaitDelaysMs) {
            sleeper(delay)
            if (!isOnline()) return true
        }
        return false
    }

    private class Attempt(val outcome: LoginOutcome, val otherSessions: List<OtherSession> = emptyList())

    private fun attempt(c: OkHttpClient, credentials: Credentials): Attempt {
        val loginPage = portalBase.resolve("login.html")!!
        val loginAction = portalBase.resolve("j_spring_security_check")!!

        // 1) Cookie'leri al
        val pageHtml: String = try {
            c.newCall(Request.Builder().url(loginPage).build()).execute().use { r ->
                if (!r.isSuccessful) return Attempt(LoginOutcome.PORTAL_ERROR)
                r.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            return Attempt(classifyBeforePost(e))
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
        var postUrl: HttpUrl? = null
        var postTimedOut = false
        try {
            c.newCall(post).execute().use { r ->
                postUrl = r.request.url
                postHtml = r.body?.string().orEmpty()
            }
        } catch (_: InterruptedIOException) {
            // Sunucu yavaş: istek gitmiş olabilir, internet açıldı mı yine de bakılır.
            postTimedOut = true
        } catch (_: IOException) {
            if (waitUntilOnline()) return Attempt(LoginOutcome.SUCCESS)
            return Attempt(LoginOutcome.PORTAL_ERROR)
        }

        // Portal "maksimum cihaz hakkı dolu" sayfasına yönlendirdiyse beklemeye gerek yok: şifre doğru, hesap başka cihazda açık.
        val finalUrl = postUrl
        if (finalUrl != null && OtherDeviceDetector.isOtherDevicePage(finalUrl)) {
            return Attempt(LoginOutcome.OTHER_DEVICE, OtherDevicePage.parse(postHtml, finalUrl, portalBase.host))
        }

        // 3) Başarıyı HTTP koduna göre değil, gerçek internet erişimine göre anla.
        if (waitUntilOnline()) return Attempt(LoginOutcome.SUCCESS)
        if (postTimedOut) return Attempt(LoginOutcome.TIMEOUT)
        if (OtherDeviceDetector.appearedAfterPost(pageHtml, postHtml)) {
            val sessions = finalUrl?.let { OtherDevicePage.parse(postHtml, it, portalBase.host) }.orEmpty()
            return Attempt(LoginOutcome.OTHER_DEVICE, sessions)
        }
        if (CaptchaDetector.appearedAfterPost(pageHtml, postHtml)) return Attempt(LoginOutcome.CAPTCHA)
        return Attempt(LoginOutcome.WRONG_CREDENTIALS)
    }

    /**
     * Portaldaki "End" butonunun yaptığını yapar: PrimeFaces AJAX isteği (aynı cookie oturumuyla).
     * Form/buton id'leri (j_idt…) sabit değil; her seferinde sayfadan okunur.
     */
    private fun endSession(c: OkHttpClient, s: OtherSession): Boolean =
        postAjax(c, PortalForm(s.formId, s.buttonId, s.viewState, s.action), render = s.formId)

    private fun postAjax(c: OkHttpClient, s: PortalForm, render: String?): Boolean {
        val body = FormBody.Builder(Charsets.UTF_8)
            .add("javax.faces.partial.ajax", "true")
            .add("javax.faces.source", s.buttonId)
            .add("javax.faces.partial.execute", "@all")
            .apply { if (render != null) add("javax.faces.partial.render", render) }
            .add(s.buttonId, s.buttonId)
            .add(s.formId, s.formId)
            .add("javax.faces.ViewState", s.viewState)
            .build()
        val request = Request.Builder()
            .url(s.action)
            .header("Faces-Request", "partial/ajax")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", s.action.toString())
            .post(body)
            .build()
        return try {
            c.newCall(request).execute().use { it.isSuccessful }
        } catch (_: IOException) {
            false
        }
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
 * "Maksimum cihaz hakkı dolu" sayfası tespiti. Gerçek sayfa 05.11.2026'da görüldü
 * (test/resources/gsb/maksimum_cihaz_hakki_dolu.html):
 *  - Asıl işaret: POST'tan sonra varılan adres /maksimumCihazHakkiDolu.html
 *  - Yedek: sayfa metni. Portal dil başlığı olmayınca İngilizce döner ("maximum number of logins");
 *    Türkçe metin görülmedi, o yüzden birkaç olası ifade aranır. Varlıklar (&#351; gibi) çözülerek bakılır.
 */
object OtherDeviceDetector {
    private const val PAGE = "maksimumcihazhakkidolu"
    private val pattern = Regex(
        """maximum number of logins|maximum device allowance|maksimum cihaz|ba[sş]ka\s+(bir\s+)?cihaz""",
        RegexOption.IGNORE_CASE,
    )

    fun isOtherDevicePage(url: HttpUrl): Boolean = url.encodedPath.lowercase().contains(PAGE)

    fun matches(html: String): Boolean = pattern.containsMatchIn(decodeEntities(html))

    /** Login sayfasında zaten geçen bir yardım metni yanlışlıkla eşleşmesin: sadece POST sonrası çıkan metin sayılır. */
    fun appearedAfterPost(loginPageHtml: String, postHtml: String): Boolean =
        matches(postHtml) && !matches(loginPageHtml)

    private val numeric = Regex("""&#(x?)([0-9a-fA-F]+);""")
    internal fun decodeEntities(s: String): String = numeric.replace(s) { m ->
        val radix = if (m.groupValues[1].isEmpty()) 10 else 16
        m.groupValues[2].toIntOrNull(radix)?.toChar()?.toString() ?: m.value
    }
}

/** Diğer cihazdaki açık oturum ve onu kapatan "End" formunun bilgileri. MAC adresi bilerek tutulmaz. */
class OtherSession(
    /** Portalın gösterdiği haliyle, örn. "05-11-2026 00:00:48". */
    val startTime: String,
    val formId: String,
    val buttonId: String,
    val viewState: String,
    val action: HttpUrl,
) {
    /** "05-11-2026 00:00:48" → "00:00" (biçim değişirse olduğu gibi). */
    val startClock: String
        get() = Regex("""\b(\d{1,2}:\d{2})(:\d{2})?\s*$""").find(startTime.trim())?.groupValues?.get(1) ?: startTime.trim()
}

/** maksimumCihazHakkiDolu.html'deki oturum tablosunu okur (PrimeFaces DataTable). */
object OtherDevicePage {
    private val row = Regex("""<tr\b[^>]*\bdata-ri="\d+"[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
    private val cell = Regex("""<td\b[^>]*role="gridcell"[^>]*>([^<]*)</td>""")
    private val form = Regex("""<form\b[^>]*>""")
    private val button = Regex("""<button\b[^>]*\bid="([^"]+)"""")
    private val viewState = Regex("""<input\b[^>]*name="javax\.faces\.ViewState"[^>]*>""")

    /** Sadece portalla aynı sunucuya giden formlar alınır (cookie'ler başka yere gitmesin). */
    fun parse(html: String, pageUrl: HttpUrl, portalHost: String): List<OtherSession> =
        row.findAll(html).mapNotNull { m ->
            val r = m.groupValues[1]
            val start = cell.find(r)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val formTag = form.find(r)?.value ?: return@mapNotNull null
            val formId = attr(formTag, "id") ?: return@mapNotNull null
            val action = pageUrl.resolve(unescape(attr(formTag, "action") ?: "")) ?: return@mapNotNull null
            if (action.host != portalHost) return@mapNotNull null
            val buttonId = button.find(r)?.groupValues?.get(1) ?: return@mapNotNull null
            val vs = viewState.find(r)?.value?.let { attr(it, "value") } ?: return@mapNotNull null
            OtherSession(unescape(start), unescape(formId), unescape(buttonId), unescape(vs), action)
        }.toList()

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    private fun unescape(s: String): String = OtherDeviceDetector.decodeEntities(s)
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}

/** PrimeFaces formundaki bir butona basmak için gerekenler. */
class PortalForm(val formId: String, val buttonId: String, val viewState: String, val action: HttpUrl)

class PortalProbe(val reachable: Boolean, val session: PortalSession?)

/** Kota paketi (portal MB cinsinden verir). */
class Quota(val packageName: String?, val totalMb: Double, val remainingMb: Double, val expires: String?)

/** Bu telefonda açık portal oturumu: hangi hesapla açık, kota, "End Session" formu. */
class PortalSession(val accountName: String, val quota: Quota?, val endForm: PortalForm?)

/**
 * Portal ana sayfası (girişliyken). Gerçek sayfa 05.11.2026'da görüldü (test/resources/gsb/anasayfa.html, ad değiştirildi):
 *  - hesap adı: <span class="myinfo">AD SOYAD </span>
 *  - kota: mainPanel:kota içinde iki sütunlu satırlar ("Package Type:" | "Social Media", "Total Remaining Quota (MB):" | "5119.95"…)
 *  - "End Session" butonu: servisUpdateForm içinde PrimeFaces butonu
 * Sayfa dil başlığı gönderilmediği için İngilizce gelir; Türkçe etiketler de yedek olarak aranır.
 */
object SessionPage {
    private val name = Regex("""<span\b[^>]*class="myinfo"[^>]*>([^<]*)</span>""")
    private val row = Regex("""<tr\b[^>]*role="row"[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
    private val cell = Regex("""<td\b[^>]*role="gridcell"[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
    private val tag = Regex("""<[^>]+>""")
    private val formTag = Regex("""<form\b[^>]*>""")
    private val button = Regex("""<button\b[^>]*\bid="([^"]+)"[^>]*>(.*?)</button>""", RegexOption.DOT_MATCHES_ALL)
    private val viewState = Regex("""<input\b[^>]*name="javax\.faces\.ViewState"[^>]*>""")
    private val endLabel = Regex("""end\s+session|oturumu?\s+sonland[ıi]r|oturumu?\s+kapat""", RegexOption.IGNORE_CASE)

    fun parse(html: String, pageUrl: HttpUrl, portalHost: String): PortalSession? {
        val account = name.find(html)?.groupValues?.get(1)?.let(::text)?.takeIf { it.isNotBlank() } ?: return null
        return PortalSession(account, parseQuota(html), parseEndForm(html, pageUrl, portalHost))
    }

    /** Birden çok paket varsa "Total/Toplam" paketi, yoksa en büyük kotalı paket. */
    fun parseQuota(html: String): Quota? {
        val packages = mutableListOf<MutableMap<String, String>>()
        for (r in row.findAll(html)) {
            val cells = cell.findAll(r.groupValues[1]).map { text(it.groupValues[1]) }.toList()
            if (cells.size != 2) continue
            val key = cells[0].trimEnd(':', ' ').lowercase()
            if (key.startsWith("package type") || key.startsWith("paket")) packages.add(mutableMapOf())
            packages.lastOrNull()?.put(key, cells[1])
        }
        val quotas = packages.mapNotNull { p ->
            val total = p.entries.firstOrNull { isTotal(it.key) }?.value?.toDoubleOrNull() ?: return@mapNotNull null
            val remaining = p.entries.firstOrNull { isRemaining(it.key) }?.value?.toDoubleOrNull() ?: return@mapNotNull null
            val type = p.entries.firstOrNull { it.key.startsWith("package type") || it.key.startsWith("paket") }?.value
            val expires = p.entries.firstOrNull { it.key.startsWith("expiration") || it.key.startsWith("bitiş") }?.value
            Quota(type, total, remaining, expires)
        }
        return quotas.firstOrNull { it.packageName.equals("Total", true) || it.packageName.equals("Toplam", true) }
            ?: quotas.maxByOrNull { it.totalMb }
    }

    private fun isTotal(k: String) = (k.startsWith("total quota") || k.startsWith("toplam kota")) && "remaining" !in k && "kalan" !in k
    private fun isRemaining(k: String) = "remaining quota (mb)" in k || "kalan kota" in k

    private fun parseEndForm(html: String, pageUrl: HttpUrl, portalHost: String): PortalForm? {
        val b = button.findAll(html).firstOrNull { endLabel.containsMatchIn(text(it.groupValues[2])) } ?: return null
        val before = html.substring(0, b.range.first)
        val formStart = before.lastIndexOf("<form")
        if (formStart < 0) return null
        val formEnd = html.indexOf("</form>", b.range.last).takeIf { it > 0 } ?: html.length
        val form = formTag.find(html, formStart)?.value ?: return null
        val formId = attr(form, "id") ?: return null
        val action = pageUrl.resolve(text(attr(form, "action") ?: "")) ?: return null
        if (action.host != portalHost) return null
        val vs = viewState.find(html.substring(formStart, formEnd))?.value?.let { attr(it, "value") } ?: return null
        return PortalForm(text(formId), text(b.groupValues[1]), text(vs), action)
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    private fun text(s: String): String = OtherDeviceDetector.decodeEntities(tag.replace(s, ""))
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace(Regex("""\s+"""), " ").trim()
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
