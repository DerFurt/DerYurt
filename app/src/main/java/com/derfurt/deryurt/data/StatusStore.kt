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

    /**
     * Portal oturumu hatırlayıp interneti kendiliğinden açtı (ALREADY_ONLINE) ve bu wifi'nin GSB olduğu doğrulandı:
     * ağ GSB olarak kaydedilir, ana ekran "İnternet açık" gösterir.
     */
    fun markGsbNetwork(networkId: String) {
        prefs.edit {
            putString(K_LAST_GSB_NET, networkId)
            putLong(K_LAST_SUCCESS_AT, System.currentTimeMillis())
        }
    }

    /** Diğer cihazdaki oturumun portalda görünen başlama saati (örn. "00:00"); yoksa null. */
    var otherSessionSince: String?
        get() = prefs.getString(K_OTHER_SINCE, null)
        set(v) = prefs.edit { if (v == null) remove(K_OTHER_SINCE) else putString(K_OTHER_SINCE, v) }

    /** Otomatik kapatmanın en son kullanıldığı wifi bağlantısı (bir bağlantıda en fazla bir kez). */
    var lastAutoKickNetwork: String?
        get() = prefs.getString(K_AUTO_KICK_NET, null)
        set(v) = prefs.edit { putString(K_AUTO_KICK_NET, v) }

    // ── Portal oturumu (hangi hesap, kota) ──
    /**
     * Kayıtlı hesabın portalda görünen adı. Uygulama kendi bilgileriyle giriş yapınca öğrenilir;
     * bilgiler değişince silinir. Sadece telefonda durur, loglanmaz.
     */
    var ownAccountName: String?
        get() = prefs.getString(K_OWN_NAME, null)
        set(v) = prefs.edit { if (v == null) remove(K_OWN_NAME) else putString(K_OWN_NAME, v) }

    /** Son görülen portal oturumu; sadece aynı wifi bağlantısındaysak geçerli. */
    fun sessionFor(networkId: String): SavedSession? {
        if (prefs.getString(K_SESSION_NET, null) != networkId) return null
        val name = prefs.getString(K_SESSION_NAME, null) ?: return null
        val total = prefs.getFloat(K_QUOTA_TOTAL, -1f)
        val remaining = prefs.getFloat(K_QUOTA_LEFT, -1f)
        return SavedSession(
            accountName = name,
            quotaTotalMb = total.takeIf { it >= 0f },
            quotaRemainingMb = remaining.takeIf { it >= 0f },
            quotaExpires = prefs.getString(K_QUOTA_EXPIRES, null),
        )
    }

    fun saveSession(networkId: String, s: SavedSession) {
        prefs.edit {
            putString(K_SESSION_NET, networkId)
            putString(K_SESSION_NAME, s.accountName)
            putFloat(K_QUOTA_TOTAL, s.quotaTotalMb ?: -1f)
            putFloat(K_QUOTA_LEFT, s.quotaRemainingMb ?: -1f)
            if (s.quotaExpires == null) remove(K_QUOTA_EXPIRES) else putString(K_QUOTA_EXPIRES, s.quotaExpires)
        }
    }

    fun clearSession() {
        prefs.edit { remove(K_SESSION_NET); remove(K_SESSION_NAME) }
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

    /**
     * "Arka planda sürekli bağlı tut" servisi çalışmalı mı? Otomatik giriş kapalıyken servisin başlattığı
     * işler hiçbir şey yapmaz; boşuna kalıcı bildirim durmasın diye ikisi birlikte açık olmalı.
     */
    val watchActive: Boolean get() = autoLogin && watchSession

    /** Hesap başka cihazda açıksa o oturumu otomatik kapat. Varsayılan kapalı (Ahmet kararı, 04.11). */
    var autoKickOther: Boolean
        get() = prefs.getBoolean(K_AUTO_KICK, false)
        set(v) = prefs.edit { putBoolean(K_AUTO_KICK, v) }

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
        private const val K_OTHER_SINCE = "other_session_since"
        private const val K_AUTO_KICK = "auto_kick_other"
        private const val K_AUTO_KICK_NET = "last_auto_kick_network"
        private const val K_OWN_NAME = "own_account_name"
        private const val K_SESSION_NET = "session_network"
        private const val K_SESSION_NAME = "session_account_name"
        private const val K_QUOTA_TOTAL = "quota_total_mb"
        private const val K_QUOTA_LEFT = "quota_remaining_mb"
        private const val K_QUOTA_EXPIRES = "quota_expires"

        private val TR = java.util.Locale.forLanguageTag("tr")

        /** Portal adları büyük harf ve sonda boşlukla verebiliyor: Türkçe kurallarla karşılaştır. */
        fun sameAccount(a: String, b: String): Boolean {
            fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ").uppercase(TR)
            return norm(a) == norm(b)
        }

        const val THEME_ORANGE = "orange"
        const val THEME_BLUE = "blue"
        const val THEME_GREEN = "green"
        const val THEME_RED = "red"
    }
}

/** Portalda görülen oturum: hangi hesapla açık, kota (MB). */
data class SavedSession(
    val accountName: String,
    val quotaTotalMb: Float?,
    val quotaRemainingMb: Float?,
    val quotaExpires: String?,
)
