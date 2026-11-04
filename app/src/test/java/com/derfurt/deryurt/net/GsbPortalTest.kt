package com.derfurt.deryurt.net

import com.derfurt.deryurt.core.Credentials
import com.derfurt.deryurt.core.LoginOutcome
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Sahte değerler; gerçek kimlik bilgisi kullanılmaz. */
private const val FAKE_USER = "12345678901"
private const val FAKE_PASS = "test-sifre-ğüş"

class GsbPortalTest {

    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()

    /** Sahte portalın davranışı */
    private var online = false
    private var correctPassword = FAKE_PASS
    private var loginPageHtml = """<form action="j_spring_security_check"><input name="j_username"><script src="captcha.js"></script></form>"""
    private var failHtml = """<form><input name="j_username"><span>Hatalı</span></form>"""
    private var postDelaySec = 0L
    private var loginPageCode = 200
    /** Hesap başka cihazda açık: doğru şifrede portal maksimumCihazHakkiDolu.html'e yönlendirir. */
    private var otherDeviceOpen = false
    /** "End" isteği diğer oturumu gerçekten kapatsın mı? */
    private var endWorks = true

    /** 05.11.2026'da yakalanan gerçek sayfa (MAC maskeli). */
    private val realOtherDevicePage: String =
        javaClass.classLoader!!.getResource("gsb/maksimum_cihaz_hakki_dolu.html")!!.readText()

    /** 05.11.2026'da yakalanan girişli ana sayfa (ad "TEST KULLANICI", toplam paket rakamları değiştirildi). */
    private val realMainPage: String =
        javaClass.classLoader!!.getResource("gsb/anasayfa.html")!!.readText()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests.add(request) }
                return when (request.path) {
                    "/" ->
                        if (online) MockResponse().setResponseCode(200).setBody(realMainPage)
                        else MockResponse().setResponseCode(302).setHeader("Location", "/login.html")
                    "/index.html" -> {
                        // Ana sayfadaki "End Session" (PrimeFaces AJAX)
                        online = false
                        MockResponse().setResponseCode(200).setHeader("Content-Type", "text/xml")
                            .setBody("""<?xml version='1.0' encoding='UTF-8'?><partial-response><redirect url="/login.html"/></partial-response>""")
                    }
                    "/generate_204" ->
                        if (online) MockResponse().setResponseCode(204)
                        else MockResponse().setResponseCode(302).setHeader("Location", server.url("/login.html"))
                    "/login.html" -> MockResponse().setResponseCode(loginPageCode)
                        .addHeader("Set-Cookie", "JSESSIONID=abc123; Path=/")
                        .addHeader("Set-Cookie", "BIGipServerpool_aps_portal=xyz; Path=/")
                        .setBody(loginPageHtml)
                    "/maksimumCihazHakkiDolu.html" ->
                        if (request.method == "POST") {
                            if (endWorks) otherDeviceOpen = false
                            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/xml")
                                .setBody("""<?xml version='1.0' encoding='UTF-8'?><partial-response><changes/></partial-response>""")
                        } else {
                            MockResponse().setResponseCode(200).setBody(realOtherDevicePage)
                        }
                    "/j_spring_security_check" -> {
                        val body = request.body.clone().readUtf8()
                        val ok = body.contains("j_password=" + java.net.URLEncoder.encode(correctPassword, "UTF-8"))
                        if (ok && otherDeviceOpen) {
                            return MockResponse().setResponseCode(302).setHeader("Location", "/maksimumCihazHakkiDolu.html")
                        }
                        if (ok) online = true
                        MockResponse().setResponseCode(200)
                            .setBody(if (ok) "<html>ok</html>" else failHtml)
                            .setBodyDelay(postDelaySec, TimeUnit.SECONDS)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun portal(readTimeoutMs: Long = 5000) = GsbPortal(
        client = OkHttpClient.Builder().readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS).build(),
        portalBase = server.url("/"),
        checkUrl = server.url("/generate_204"),
        onlineCheckDelaysMs = listOf(0L, 10L),
        sleeper = { Thread.sleep(it) },
        kickRetryDelaysMs = listOf(0L, 0L, 0L),
        endWaitDelaysMs = listOf(0L, 0L),
    )

    private fun creds(pass: String = FAKE_PASS) = Credentials(FAKE_USER, pass)
    private fun posts() = requests.filter { it.method == "POST" }
    private fun loginPosts() = posts().filter { it.path == "/j_spring_security_check" }
    private fun endPosts() = posts().filter { it.path == "/maksimumCihazHakkiDolu.html" }

    @Test
    fun alreadyOnline_noLoginRequestsSent() {
        online = true
        assertEquals(LoginOutcome.ALREADY_ONLINE, portal().login(creds()))
        assertTrue(requests.none { it.path == "/login.html" })
        assertTrue(posts().isEmpty())
    }

    @Test
    fun success_cookiesFromGetAreSentWithPost() {
        assertEquals(LoginOutcome.SUCCESS, portal().login(creds()))
        val post = posts().single()
        val cookie = post.getHeader("Cookie").orEmpty()
        assertTrue(cookie, cookie.contains("JSESSIONID=abc123"))
        assertTrue(cookie, cookie.contains("BIGipServerpool_aps_portal=xyz"))
    }

    @Test
    fun post_hasFormEncodingRefererAndUtf8Submit() {
        portal().login(creds())
        val post = posts().single()
        assertTrue(post.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"))
        assertEquals(server.url("/login.html").toString(), post.getHeader("Referer"))
        val body = post.body.clone().readUtf8()
        assertTrue(body, body.contains("j_username=$FAKE_USER"))
        assertTrue(body, body.contains("submit=Giri%C5%9F"))
        // Türkçe karakterli şifre UTF-8 kodlanmalı
        assertTrue(body, body.contains("j_password=" + java.net.URLEncoder.encode(FAKE_PASS, "UTF-8")))
    }

    @Test
    fun getComesBeforePost() {
        portal().login(creds())
        val paths = requests.map { it.path }
        assertTrue(paths.indexOf("/login.html") < paths.indexOf("/j_spring_security_check"))
    }

    @Test
    fun wrongPassword_returns200ButStillOffline_isWrongCredentials() {
        correctPassword = "baska-sifre"
        assertEquals(LoginOutcome.WRONG_CREDENTIALS, portal().login(creds()))
    }

    @Test
    fun captchaAppearsAfterPost_isCaptcha() {
        correctPassword = "baska-sifre"
        failHtml = """<form><input type="text" name="captchaInput"><img src="captcha.jpg" id="captchaImage"></form>"""
        assertEquals(LoginOutcome.CAPTCHA, portal().login(creds()))
    }

    @Test
    fun captchaJsOnNormalPage_isNotTreatedAsCaptcha() {
        // Login sayfasında da captcha alanı varsa (her zaman) bu captcha sayılmaz.
        correctPassword = "baska-sifre"
        loginPageHtml = """<form><input name="captchaHidden" type="hidden"></form>"""
        failHtml = loginPageHtml
        assertEquals(LoginOutcome.WRONG_CREDENTIALS, portal().login(creds()))
    }

    @Test
    fun accountOpenOnOtherDevice_isOtherDevice_notWrongPassword() {
        correctPassword = "baska-sifre"
        failHtml = """<div class="ui-messages-error">Bu hesap ile başka bir cihazda oturum açık. Lütfen diğer cihazdaki oturumu kapatın.</div>"""
        assertEquals(LoginOutcome.OTHER_DEVICE, portal().login(creds()))
    }

    @Test
    fun otherDeviceText_withHtmlEntities_isDetected() {
        correctPassword = "baska-sifre"
        failHtml = """<span>Hesab&#305;n&#305;z ba&#351;ka cihazda a&#231;&#305;k</span>"""
        assertEquals(LoginOutcome.OTHER_DEVICE, portal().login(creds()))
    }

    @Test
    fun otherDeviceTextAlreadyOnLoginPage_isNotOtherDevice() {
        correctPassword = "baska-sifre"
        loginPageHtml = """<form><input name="j_username"><p>Başka cihazda oturumunuz varsa kapanır.</p></form>"""
        failHtml = loginPageHtml
        assertEquals(LoginOutcome.WRONG_CREDENTIALS, portal().login(creds()))
    }

    @Test
    fun loginPageError_isPortalError_andNoPost() {
        loginPageCode = 503
        assertEquals(LoginOutcome.PORTAL_ERROR, portal().login(creds()))
        assertTrue(posts().isEmpty())
    }

    @Test
    fun slowPostButLoginWorked_isSuccess() {
        postDelaySec = 2
        assertEquals(LoginOutcome.SUCCESS, portal(readTimeoutMs = 500).login(creds()))
    }

    @Test
    fun unreachablePortal_isNotGsb_andNoCredentialsSent() {
        val p = GsbPortal(
            client = OkHttpClient.Builder().build(),
            portalBase = okhttp3.HttpUrl.Builder().scheme("https").host("deryurt-test.invalid").build(),
            checkUrl = server.url("/generate_204"),
            onlineCheckDelaysMs = listOf(0L),
        )
        assertEquals(LoginOutcome.NOT_GSB, p.login(creds()))
        assertTrue(posts().isEmpty())
    }

    @Test
    fun isOnline_redirectIsNotOnline() {
        online = false
        assertFalse(portal().isOnline())
        online = true
        assertTrue(portal().isOnline())
    }

    // ── Gerçek "maksimum cihaz hakkı dolu" sayfası ──

    @Test
    fun realOtherDevicePage_isOtherDevice_notWrongPassword() {
        otherDeviceOpen = true
        val p = portal()
        assertEquals(LoginOutcome.OTHER_DEVICE, p.login(creds()))
        assertEquals("00:00", p.otherSession?.startClock)
        assertFalse(p.kickedOther)
        assertTrue("istenmeden End gönderilmemeli", endPosts().isEmpty())
    }

    @Test
    fun englishTextWithoutRedirect_isOtherDevice() {
        correctPassword = "baska-sifre"
        failHtml = """<span class="ui-message-error-detail">Login Failed: You have reached the maximum number of logins.</span>"""
        assertEquals(LoginOutcome.OTHER_DEVICE, portal().login(creds()))
    }

    @Test
    fun kick_sendsPrimeFacesEndRequest_thenLogsIn() {
        otherDeviceOpen = true
        val p = portal()
        assertEquals(LoginOutcome.SUCCESS, p.login(creds(), kickOtherDevice = true))
        assertTrue(p.kickedOther)

        val end = endPosts().single()
        assertEquals("partial/ajax", end.getHeader("Faces-Request"))
        assertTrue(end.getHeader("Cookie").orEmpty().contains("JSESSIONID=abc123"))
        val body = java.net.URLDecoder.decode(end.body.clone().readUtf8(), "UTF-8")
        assertTrue(body, body.contains("javax.faces.partial.ajax=true"))
        assertTrue(body, body.contains("javax.faces.source=j_idt20:0:j_idt26:j_idt27"))
        assertTrue(body, body.contains("j_idt20:0:j_idt26:j_idt27=j_idt20:0:j_idt26:j_idt27"))
        assertTrue(body, body.contains("j_idt20:0:j_idt26=j_idt20:0:j_idt26"))
        assertTrue(body, body.contains("javax.faces.ViewState=-3999620524412267256:1186616251694986897"))
        assertFalse("şifre End isteğine gitmemeli", body.contains("j_password"))
        assertEquals(2, loginPosts().size)
    }

    @Test
    fun kick_portalKeepsRefusing_sendsEndOnlyOnce() {
        otherDeviceOpen = true
        endWorks = false
        val p = portal()
        assertEquals(LoginOutcome.OTHER_DEVICE, p.login(creds(), kickOtherDevice = true))
        assertTrue(p.kickedOther)
        assertEquals(1, endPosts().size)
        assertEquals(4, loginPosts().size) // ilk deneme + End sonrası 3 deneme
    }

    @Test
    fun kick_wrongPassword_neverReachesEnd() {
        otherDeviceOpen = true
        correctPassword = "baska-sifre"
        assertEquals(LoginOutcome.WRONG_CREDENTIALS, portal().login(creds(), kickOtherDevice = true))
        assertTrue(endPosts().isEmpty())
    }

    @Test
    fun parseRealPage_readsSessionAndForm() {
        val page = "https://wifi.gsb.gov.tr/maksimumCihazHakkiDolu.html".toHttpUrl()
        val s = OtherDevicePage.parse(realOtherDevicePage, page, "wifi.gsb.gov.tr").single()
        assertEquals("05-11-2026 00:00:48", s.startTime)
        assertEquals("00:00", s.startClock)
        assertEquals("j_idt20:0:j_idt26", s.formId)
        assertEquals("j_idt20:0:j_idt26:j_idt27", s.buttonId)
        assertEquals("-3999620524412267256:1186616251694986897", s.viewState)
        assertEquals("https://wifi.gsb.gov.tr/maksimumCihazHakkiDolu.html", s.action.toString())
    }

    @Test
    fun parse_formPointingToOtherHost_isIgnored() {
        val page = "https://wifi.gsb.gov.tr/maksimumCihazHakkiDolu.html".toHttpUrl()
        val evil = realOtherDevicePage.replace(
            "<form id=\"j_idt20:0:j_idt26\" name=\"j_idt20:0:j_idt26\" method=\"post\" action=\"/maksimumCihazHakkiDolu.html\"",
            "<form id=\"j_idt20:0:j_idt26\" name=\"j_idt20:0:j_idt26\" method=\"post\" action=\"https://example.com/x\"",
        )
        assertTrue(evil != realOtherDevicePage)
        assertTrue(OtherDevicePage.parse(evil, page, "wifi.gsb.gov.tr").isEmpty())
    }

    @Test
    fun loginPage_isNotOtherDevicePage() {
        assertFalse(OtherDeviceDetector.isOtherDevicePage(server.url("/login.html")))
        assertTrue(OtherDeviceDetector.isOtherDevicePage(server.url("/maksimumCihazHakkiDolu.html")))
    }

    // ── Portal oturumu hatırladı (ALREADY_ONLINE) → bu wifi GSB mi? ──

    @Test
    fun isGsbReachable_portalAnswers_true_andNoCredentialsSent() {
        online = true
        assertTrue(portal().isGsbReachable())
        assertTrue(posts().isEmpty())
    }

    @Test
    fun isGsbReachable_unresolvablePortal_false() {
        val p = GsbPortal(
            client = OkHttpClient.Builder().build(),
            portalBase = okhttp3.HttpUrl.Builder().scheme("https").host("deryurt-test.invalid").build(),
            checkUrl = server.url("/generate_204"),
        )
        assertFalse(p.isGsbReachable())
    }

    // ── Ana sayfa: hangi hesapla açık, kota, oturumu kapatma ──

    @Test
    fun probe_loggedIn_readsAccountAndTotalQuota_withoutPost() {
        online = true
        val probe = portal().probe()
        assertTrue(probe.reachable)
        val s = probe.session!!
        assertEquals("TEST KULLANICI", s.accountName)
        val q = s.quota!!
        assertEquals("Total", q.packageName)
        assertEquals(32768.0, q.totalMb, 0.001)
        assertEquals(30000.57, q.remainingMb, 0.001)
        assertEquals("30.11.2026", q.expires)
        assertTrue(posts().isEmpty())
    }

    @Test
    fun probe_notLoggedIn_isReachableWithoutSession() {
        online = false
        val probe = portal().probe()
        assertTrue(probe.reachable)
        assertEquals(null, probe.session)
    }

    @Test
    fun parseRealMainPage_findsEndSessionForm() {
        val page = "https://wifi.gsb.gov.tr/".toHttpUrl()
        val f = SessionPage.parse(realMainPage, page, "wifi.gsb.gov.tr")!!.endForm!!
        assertEquals("servisUpdateForm", f.formId)
        assertEquals("servisUpdateForm:j_idt127", f.buttonId)
        assertEquals("https://wifi.gsb.gov.tr/index.html", f.action.toString())
        assertTrue(f.viewState.isNotBlank())
    }

    @Test
    fun parseQuota_singlePackage_isUsed() {
        val html = """<table><tr class="x" role="row"><td role="gridcell"><label>Package Type:</label></td><td role="gridcell"><span>Social Media</span></td></tr>
            <tr role="row"><td role="gridcell"><label>Total Quota (MB):</label></td><td role="gridcell"><label>5120.0</label></td></tr>
            <tr role="row"><td role="gridcell"><label>Total Remaining Quota (MB):</label></td><td role="gridcell"><label>100.5</label></td></tr></table>"""
        val q = SessionPage.parseQuota(html)!!
        assertEquals("Social Media", q.packageName)
        assertEquals(100.5, q.remainingMb, 0.001)
        assertEquals(null, q.expires)
    }

    @Test
    fun endCurrentSession_endsThenLogsInWithSavedAccount() {
        online = true
        val p = portal()
        assertEquals(LoginOutcome.SUCCESS, p.login(creds(), endCurrentSession = true))
        assertTrue(p.endedCurrentSession)
        val end = posts().single { it.path == "/index.html" }
        assertEquals("partial/ajax", end.getHeader("Faces-Request"))
        val body = java.net.URLDecoder.decode(end.body.clone().readUtf8(), "UTF-8")
        assertTrue(body, body.contains("javax.faces.source=servisUpdateForm:j_idt127"))
        assertTrue(body, body.contains("servisUpdateForm=servisUpdateForm"))
        assertFalse(body, body.contains("javax.faces.partial.render"))
        assertFalse(body, body.contains("j_password"))
        assertEquals(1, loginPosts().size)
        // Önce kapatma, sonra giriş
        assertTrue(requests.indexOfFirst { it.path == "/index.html" } < requests.indexOfFirst { it.path == "/j_spring_security_check" })
    }

    @Test
    fun online_withoutEndCurrentSession_isAlreadyOnline_noPosts() {
        online = true
        assertEquals(LoginOutcome.ALREADY_ONLINE, portal().login(creds()))
        assertTrue(posts().isEmpty())
    }

    @Test
    fun sameAccount_ignoresCaseSpacesTurkish() {
        assertTrue(com.derfurt.deryurt.data.StatusStore.sameAccount("AYŞE YILMAZ ", "Ayşe  Yılmaz"))
        assertTrue(com.derfurt.deryurt.data.StatusStore.sameAccount("İSMAİL", "ismail"))
        assertFalse(com.derfurt.deryurt.data.StatusStore.sameAccount("AYŞE YILMAZ", "FATMA YILMAZ"))
    }
}
