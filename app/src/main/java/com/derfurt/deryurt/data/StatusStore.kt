package com.derfurt.deryurt.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.derfurt.deryurt.core.GuardRepository
import com.derfurt.deryurt.core.GuardState
import com.derfurt.deryurt.core.LoginOutcome

/**
 * Gizli OLMAYAN durum ve ayarlar (son sonuç, deneme sayacı, tema...).
 * Kimlik bilgisi burada değil, [CredentialStore]'da.
 */
class StatusStore(context: Context) : GuardRepository {

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Deneme kilidi ──
    override fun loadGuard() = GuardState(
        failedRuns = prefs.getInt(K_FAILED, 0),
        lockedOut = prefs.getBoolean(K_LOCKED, false),
        captchaBlocked = prefs.getBoolean(K_CAPTCHA, false),
        slowRuns = prefs.getInt(K_SLOW, 0),
        backoffUntil = prefs.getLong(K_BACKOFF, 0L),
    )

    override fun saveGuard(state: GuardState) {
        prefs.edit {
            putInt(K_FAILED, state.failedRuns)
            putBoolean(K_LOCKED, state.lockedOut)
            putBoolean(K_CAPTCHA, state.captchaBlocked)
            putInt(K_SLOW, state.slowRuns)
            putLong(K_BACKOFF, state.backoffUntil)
        }
    }

    fun resetGuard() = saveGuard(GuardState())

    // ── Son sonuç ──
    /** Giriş sürüyor mu? Süreç yarıda öldürülürse takılı kalmasın diye 2 dakikadan eski kayıt sayılmaz. */
    var running: Boolean
        get() {
            val since = prefs.getLong(K_RUNNING, 0L)
            return since > 0L && System.currentTimeMillis() - since < RUNNING_MAX_MS
        }
        set(v) = prefs.edit { putLong(K_RUNNING, if (v) System.currentTimeMillis() else 0L) }

    val lastOutcome: LoginOutcome?
        get() = prefs.getString(K_LAST_OUTCOME, null)?.let { runCatching { LoginOutcome.valueOf(it) }.getOrNull() }

    val lastSuccessAt: Long get() = prefs.getLong(K_LAST_SUCCESS_AT, 0L)

    /** Son başarılı girişin yapıldığı wifi Network'ünün kimliği (Network.toString). Aynı bağlantıdaysak GSB'deyiz demektir. */
    val lastGsbNetwork: String? get() = prefs.getString(K_LAST_GSB_NET, null)

    fun recordOutcome(outcome: LoginOutcome, networkId: String?) {
        prefs.edit {
            putString(K_LAST_OUTCOME, outcome.name)
            if (outcome == LoginOutcome.SUCCESS) {
                putLong(K_LAST_SUCCESS_AT, System.currentTimeMillis())
                if (networkId != null) putString(K_LAST_GSB_NET, networkId)
            }
        }
    }

    fun clearOutcome() {
        prefs.edit { remove(K_LAST_OUTCOME) }
    }

    // ── Ayarlar ──
    var autoLogin: Boolean
        get() = prefs.getBoolean(K_AUTO, true)
        set(v) = prefs.edit { putBoolean(K_AUTO, v) }

    var watchSession: Boolean
        get() = prefs.getBoolean(K_WATCH, false)
        set(v) = prefs.edit { putBoolean(K_WATCH, v) }

    var themeColor: String
        get() = prefs.getString(K_THEME, THEME_ORANGE) ?: THEME_ORANGE
        set(v) = prefs.edit { putString(K_THEME, v) }

    var darkMode: Boolean
        get() = prefs.getBoolean(K_DARK, true)
        set(v) = prefs.edit { putBoolean(K_DARK, v) }

    /** Ağ dinleyicisinin bu açılışta/sürümde kurulup kurulmadığı (gereksiz yeniden kaydı önler). */
    var armedToken: String?
        get() = prefs.getString(K_ARMED, null)
        set(v) = prefs.edit { putString(K_ARMED, v) }

    companion object {
        private const val PREFS = "deryurt_status"
        private const val K_FAILED = "guard_failed_runs"
        private const val K_LOCKED = "guard_locked"
        private const val K_CAPTCHA = "guard_captcha"
        private const val K_SLOW = "guard_slow_runs"
        private const val K_BACKOFF = "guard_backoff_until"
        private const val K_RUNNING = "running_since"
        private const val RUNNING_MAX_MS = 2 * 60 * 1000L
        const val K_LAST_OUTCOME = "last_outcome"
        private const val K_LAST_SUCCESS_AT = "last_success_at"
        private const val K_LAST_GSB_NET = "last_gsb_network"
        private const val K_AUTO = "auto_login"
        private const val K_WATCH = "watch_session"
        private const val K_THEME = "theme_color"
        private const val K_DARK = "dark_mode"
        private const val K_ARMED = "armed_token"

        const val THEME_ORANGE = "orange"
        const val THEME_BLUE = "blue"
        const val THEME_GREEN = "green"
        const val THEME_RED = "red"
    }
}
