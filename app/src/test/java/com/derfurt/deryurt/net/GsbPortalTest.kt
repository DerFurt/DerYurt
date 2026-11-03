package com.derfurt.deryurt.net

import com.derfurt.deryurt.core.Credentials
import com.derfurt.deryurt.core.LoginOutcome
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

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests.add(request) }
                return when (request.path) {
                    "/generate_204" ->
                        if (online) MockResponse().setResponseCode(204)
                        else MockResponse().setResponseCode(302).setHeader("Location", server.url("/login.html"))
                    "/login.html" -> MockResponse().setResponseCode(loginPageCode)
                        .addHeader("Set-Cookie", "JSESSIONID=abc123; Path=/")
                        .addHeader("Set-Cookie", "BIGipServerpool_aps_portal=xyz; Path=/")
                        .setBody(loginPageHtml)
                    "/j_spring_security_check" -> {
                        val body = request.body.clone().readUtf8()
                        val ok = body.contains("j_password=" + java.net.URLEncoder.encode(correctPassword, "UTF-8"))
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
    )

    private fun creds(pass: String = FAKE_PASS) = Credentials(FAKE_USER, pass)
    private fun posts() = requests.filter { it.method == "POST" }

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
}
