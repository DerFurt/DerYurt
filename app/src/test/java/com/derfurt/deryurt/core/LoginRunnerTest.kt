package com.derfurt.deryurt.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginRunnerTest {

    private class FakePortal(var results: MutableList<LoginOutcome>, var online: Boolean = false) : PortalApi {
        var loginCalls = 0
        override fun isOnline() = online
        override fun login(credentials: Credentials): LoginOutcome {
            loginCalls++
            return if (results.isEmpty()) LoginOutcome.WRONG_CREDENTIALS else results.removeAt(0)
        }
    }

    private class MemGuard(var state: GuardState = GuardState()) : GuardRepository {
        override fun loadGuard() = state
        override fun saveGuard(state: GuardState) { this.state = state }
    }

    private val creds = Credentials("12345678901", "sahte")

    @Test
    fun noCredentials() {
        val r = LoginRunner(FakePortal(mutableListOf()), MemGuard())
        assertEquals(LoginOutcome.NO_CREDENTIALS, r.run(null, manual = false))
    }

    @Test
    fun wrongPassword_isNotRetriedInSameRun() {
        val portal = FakePortal(mutableListOf(LoginOutcome.WRONG_CREDENTIALS))
        LoginRunner(portal, MemGuard()).run(creds, manual = false)
        assertEquals(1, portal.loginCalls)
    }

    @Test
    fun timeout_isRetriedOnce() {
        val portal = FakePortal(mutableListOf(LoginOutcome.TIMEOUT, LoginOutcome.TIMEOUT, LoginOutcome.SUCCESS))
        val out = LoginRunner(portal, MemGuard()).run(creds, manual = false)
        assertEquals(2, portal.loginCalls)
        assertEquals(LoginOutcome.TIMEOUT, out)
    }

    @Test
    fun twoWrongRuns_lockOut_thenNoMoreRequests() {
        val guard = MemGuard()
        val portal = FakePortal(mutableListOf())
        val r = LoginRunner(portal, guard)
        r.run(creds, manual = false)
        assertFalse(guard.state.lockedOut)
        r.run(creds, manual = false)
        assertTrue(guard.state.lockedOut)
        assertEquals(2, portal.loginCalls)

        // Kilitliyken otomatik çalıştırma portala POST atmaz
        assertEquals(LoginOutcome.SKIPPED_LOCKED, r.run(creds, manual = false))
        assertEquals(2, portal.loginCalls)
    }

    @Test
    fun manualRetryWhileLocked_triesOnce_andRelocksOnFailure() {
        val guard = MemGuard(GuardState(failedRuns = 2, lockedOut = true))
        val portal = FakePortal(mutableListOf(LoginOutcome.WRONG_CREDENTIALS))
        LoginRunner(portal, guard).run(creds, manual = true)
        assertEquals(1, portal.loginCalls)
        assertTrue(guard.state.lockedOut)
    }

    @Test
    fun success_resetsGuard() {
        val guard = MemGuard(GuardState(failedRuns = 1))
        LoginRunner(FakePortal(mutableListOf(LoginOutcome.SUCCESS)), guard).run(creds, manual = false)
        assertEquals(GuardState(), guard.state)
    }

    @Test
    fun captcha_blocksAutoAttempts() {
        val guard = MemGuard()
        val portal = FakePortal(mutableListOf(LoginOutcome.CAPTCHA))
        val r = LoginRunner(portal, guard)
        assertEquals(LoginOutcome.CAPTCHA, r.run(creds, manual = false))
        assertEquals(LoginOutcome.SKIPPED_LOCKED, r.run(creds, manual = false))
        assertEquals(1, portal.loginCalls)
    }

    @Test
    fun captchaBlocked_userLoggedInViaBrowser_unblocks() {
        val guard = MemGuard(GuardState(captchaBlocked = true))
        val portal = FakePortal(mutableListOf(), online = true)
        assertEquals(LoginOutcome.ALREADY_ONLINE, LoginRunner(portal, guard).run(creds, manual = false))
        assertEquals(GuardState(), guard.state)
        assertEquals(0, portal.loginCalls)
    }

    @Test
    fun wrongPasswordLock_isNotClearedByInternetElsewhere() {
        val guard = MemGuard(GuardState(failedRuns = 2, lockedOut = true))
        val portal = FakePortal(mutableListOf(), online = true)
        assertEquals(LoginOutcome.SKIPPED_LOCKED, LoginRunner(portal, guard).run(creds, manual = false))
        assertTrue(guard.state.lockedOut)
        assertEquals(0, portal.loginCalls)
    }

    // ── Yavaş portal: 3 başarısız çalıştırmadan sonra 1 saat bekleme ──

    private class Clock(var now: Long = 1_000_000L) { fun get() = now }

    @Test
    fun threeSlowRuns_startOneHourBackoff_withoutPortalRequests() {
        val clock = Clock()
        val guard = MemGuard()
        val portal = FakePortal(MutableList(10) { LoginOutcome.TIMEOUT })
        val r = LoginRunner(portal, guard, clock::get)
        repeat(3) { r.run(creds, manual = false) }
        assertEquals(6, portal.loginCalls) // çalıştırma başına en fazla 2 deneme
        assertTrue(LoginPolicy.inBackoff(guard.state, clock.now))

        assertEquals(LoginOutcome.SKIPPED_BACKOFF, r.run(creds, manual = false))
        assertEquals(6, portal.loginCalls)

        clock.now += LoginPolicy.BACKOFF_MS - 1
        assertEquals(LoginOutcome.SKIPPED_BACKOFF, r.run(creds, manual = false))
        clock.now += 2
        r.run(creds, manual = false)
        assertEquals(8, portal.loginCalls)
    }

    @Test
    fun twoSlowRuns_noBackoffYet() {
        val guard = MemGuard()
        val r = LoginRunner(FakePortal(MutableList(4) { LoginOutcome.PORTAL_ERROR }), guard)
        repeat(2) { r.run(creds, manual = false) }
        assertFalse(LoginPolicy.inBackoff(guard.state, System.currentTimeMillis()))
    }

    @Test
    fun freshConnection_clearsBackoff() {
        val clock = Clock()
        val guard = MemGuard(GuardState(slowRuns = 3, backoffUntil = clock.now + LoginPolicy.BACKOFF_MS))
        val portal = FakePortal(mutableListOf(LoginOutcome.SUCCESS))
        assertEquals(LoginOutcome.SUCCESS, LoginRunner(portal, guard, clock::get).run(creds, manual = false, freshConnection = true))
        assertEquals(1, portal.loginCalls)
    }

    @Test
    fun freshConnection_doesNotClearWrongPasswordLock() {
        val guard = MemGuard(GuardState(failedRuns = 2, lockedOut = true))
        val portal = FakePortal(mutableListOf())
        assertEquals(LoginOutcome.SKIPPED_LOCKED, LoginRunner(portal, guard).run(creds, manual = false, freshConnection = true))
        assertEquals(0, portal.loginCalls)
    }

    @Test
    fun manual_clearsBackoff() {
        val clock = Clock()
        val guard = MemGuard(GuardState(slowRuns = 5, backoffUntil = clock.now + LoginPolicy.BACKOFF_MS))
        val portal = FakePortal(mutableListOf(LoginOutcome.SUCCESS))
        LoginRunner(portal, guard, clock::get).run(creds, manual = true)
        assertEquals(1, portal.loginCalls)
        assertEquals(GuardState(), guard.state)
    }

    @Test
    fun internetOnDuringBackoff_clearsBackoff() {
        val clock = Clock()
        val guard = MemGuard(GuardState(slowRuns = 3, backoffUntil = clock.now + LoginPolicy.BACKOFF_MS))
        val portal = FakePortal(mutableListOf(), online = true)
        assertEquals(LoginOutcome.ALREADY_ONLINE, LoginRunner(portal, guard, clock::get).run(creds, manual = false))
        assertEquals(GuardState(), guard.state)
    }

    // ── Hesap başka cihazda açık ──

    @Test
    fun otherDevice_neverLocksAsWrongPassword() {
        val guard = MemGuard()
        val portal = FakePortal(MutableList(5) { LoginOutcome.OTHER_DEVICE })
        val r = LoginRunner(portal, guard)
        repeat(5) { assertEquals(LoginOutcome.OTHER_DEVICE, r.run(creds, manual = false)) }
        assertFalse(guard.state.lockedOut)
        assertEquals(0, guard.state.failedRuns)
        assertEquals(5, portal.loginCalls) // her çalıştırmada tek istek, aynı çalıştırmada tekrar yok
    }

    @Test
    fun credentialsToString_hidesSecrets() {
        assertFalse(creds.toString().contains("12345678901"))
        assertFalse(creds.toString().contains("sahte"))
    }
}
