package com.derfurt.deryurt.core

/** Portala spam atmamak için tutulan durum. Gizli bilgi içermez. */
data class GuardState(
    /** Üst üste WRONG_CREDENTIALS ile biten çalıştırma sayısı. */
    val failedRuns: Int = 0,
    /** true ise otomatik deneme yapılmaz; kullanıcı "Tekrar dene" demeli ya da bilgileri güncellemeli. */
    val lockedOut: Boolean = false,
    /** true ise portal captcha istedi; kullanıcı elle girene ya da "Tekrar dene" diyene kadar otomatik deneme yok. */
    val captchaBlocked: Boolean = false,
    /** Üst üste zaman aşımı / portal hatasıyla biten çalıştırma sayısı. */
    val slowRuns: Int = 0,
    /** Bu zamana (epoch ms) kadar otomatik deneme yapılmaz (portal uzun süredir yanıt vermiyor). */
    val backoffUntil: Long = 0L,
)

object LoginPolicy {
    /** Bu kadar yanlış şifreli çalıştırmadan sonra otomatik deneme durur. */
    const val MAX_FAILED_RUNS = 2

    /** Bir çalıştırmada en fazla bu kadar deneme (ikincisi sadece sunucu yavaşlığına karşı). */
    const val MAX_ATTEMPTS_PER_RUN = 2

    /** Bu kadar üst üste yavaş/hatalı çalıştırmadan sonra [BACKOFF_MS] beklenir. */
    const val SLOW_RUNS_BEFORE_BACKOFF = 3
    const val BACKOFF_MS = 60 * 60 * 1000L

    fun isLocked(s: GuardState): Boolean = s.lockedOut || s.captchaBlocked

    fun inBackoff(s: GuardState, now: Long): Boolean = now < s.backoffUntil

    fun canAutoAttempt(s: GuardState, now: Long): Boolean = !isLocked(s) && !inBackoff(s, now)

    /** Aynı çalıştırmada tekrar denensin mi? Yanlış şifrede asla; sadece zaman aşımı / portal hatasında. */
    fun shouldRetryInRun(outcome: LoginOutcome, attempt: Int): Boolean =
        attempt < MAX_ATTEMPTS_PER_RUN &&
            (outcome == LoginOutcome.TIMEOUT || outcome == LoginOutcome.PORTAL_ERROR)

    fun next(s: GuardState, outcome: LoginOutcome, now: Long): GuardState = when (outcome) {
        LoginOutcome.SUCCESS, LoginOutcome.ALREADY_ONLINE -> GuardState()
        LoginOutcome.WRONG_CREDENTIALS -> {
            val failed = s.failedRuns + 1
            s.copy(failedRuns = failed, lockedOut = failed >= MAX_FAILED_RUNS, slowRuns = 0, backoffUntil = 0L)
        }
        LoginOutcome.CAPTCHA -> s.copy(captchaBlocked = true)
        LoginOutcome.TIMEOUT, LoginOutcome.PORTAL_ERROR -> {
            val slow = s.slowRuns + 1
            s.copy(
                slowRuns = slow,
                backoffUntil = if (slow >= SLOW_RUNS_BEFORE_BACKOFF) now + BACKOFF_MS else s.backoffUntil,
            )
        }
        // Hesap başka cihazda açık: şifre doğru, kilide sayılmaz. Portal cevap verdiği için yavaşlık sayacı sıfırlanır.
        LoginOutcome.OTHER_DEVICE -> s.copy(slowRuns = 0, backoffUntil = 0L)
        else -> s
    }

    /**
     * Kullanıcı elle denedi: kilitler ve bekleme kalkar, ama yanlış şifre sayacı sınırın bir altında kalır;
     * böylece bu deneme de yanlışsa hemen tekrar kilitlenir.
     */
    fun onManualRetry(s: GuardState): GuardState = GuardState(
        failedRuns = minOf(s.failedRuns, MAX_FAILED_RUNS - 1),
    )

    /** Wifi'ye yeniden bağlanıldı ya da oturum düştü: yavaşlık beklemesi sıfırlanır (kilitler kalır). */
    fun onFreshConnection(s: GuardState): GuardState = s.copy(slowRuns = 0, backoffUntil = 0L)
}
