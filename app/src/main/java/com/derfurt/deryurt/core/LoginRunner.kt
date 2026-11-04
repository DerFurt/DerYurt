package com.derfurt.deryurt.core

/** Kullanıcı adı + şifre. toString bilerek gizlenir; yanlışlıkla loga düşmesin. */
class Credentials(val username: String, val password: String) {
    override fun toString(): String = "Credentials(***)"
}

/** Portalla konuşan taraf (gerçeği: net.GsbPortal). Testte sahtesi kullanılır. */
interface PortalApi {
    fun isOnline(): Boolean
    /**
     * kickOtherDevice=true: hesap başka cihazda açıksa o oturumu kapatıp yeniden girer.
     * endCurrentSession=true: bu telefonda internet açıksa (örn. başka bir hesapla) önce o oturumu kapatır,
     * sonra kayıtlı hesapla girer.
     */
    fun login(credentials: Credentials, kickOtherDevice: Boolean = false, endCurrentSession: Boolean = false): LoginOutcome
}

/** GuardState'in saklandığı yer (gerçeği: data.StatusStore). */
interface GuardRepository {
    fun loadGuard(): GuardState
    fun saveGuard(state: GuardState)
}

/** Deneme limitlerini uygulayarak bir giriş çalıştırması yapar. Android'e bağımlı değildir. */
class LoginRunner(
    private val portal: PortalApi,
    private val guard: GuardRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * @param manual kullanıcı istedi ("Şimdi giriş yap", "Tekrar dene", kutucuk): kilitler ve bekleme kalkar.
     * @param freshConnection wifi yeni bağlandı ya da oturum düştü: yavaşlık beklemesi sıfırlanır.
     * @param kickOtherDevice hesap başka cihazda açıksa o oturum kapatılsın (buton ya da otomatik kapatma ayarı).
     */
    fun run(
        credentials: Credentials?,
        manual: Boolean,
        freshConnection: Boolean = false,
        kickOtherDevice: Boolean = false,
        endCurrentSession: Boolean = false,
    ): LoginOutcome {
        if (credentials == null) return LoginOutcome.NO_CREDENTIALS

        val now = clock()
        var state = guard.loadGuard()
        if (manual) {
            state = LoginPolicy.onManualRetry(state)
            guard.saveGuard(state)
        } else {
            if (freshConnection) {
                state = LoginPolicy.onFreshConnection(state)
                guard.saveGuard(state)
            }
            if (!LoginPolicy.canAutoAttempt(state, now)) return blocked(state, now)
        }

        var attempt = 0
        var outcome: LoginOutcome
        do {
            attempt++
            outcome = portal.login(credentials, kickOtherDevice, endCurrentSession)
        } while (LoginPolicy.shouldRetryInRun(outcome, attempt))

        guard.saveGuard(LoginPolicy.next(state, outcome, clock()))
        return outcome
    }

    /** Otomatik deneme engelliyken portala istek atılmaz; sadece internet açık mı diye bakılır. */
    private fun blocked(state: GuardState, now: Long): LoginOutcome {
        if (state.lockedOut) {
            // Şifre kilidi sadece "Tekrar dene" ya da bilgileri güncellemekle kalkar
            // (evdeki wifi'de internetin olması şifrenin düzeldiği anlamına gelmez).
            return LoginOutcome.SKIPPED_LOCKED
        }
        if (portal.isOnline()) {
            // Kullanıcı tarayıcıdan elle girdi ya da portal düzeldi: captcha kilidi ve bekleme kalkar.
            guard.saveGuard(state.copy(captchaBlocked = false, slowRuns = 0, backoffUntil = 0L))
            return LoginOutcome.ALREADY_ONLINE
        }
        return if (state.captchaBlocked) LoginOutcome.SKIPPED_LOCKED
        else if (LoginPolicy.inBackoff(state, now)) LoginOutcome.SKIPPED_BACKOFF
        else LoginOutcome.SKIPPED_LOCKED
    }
}
