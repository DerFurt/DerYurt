package com.derfurt.deryurt.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.net.toUri
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.derfurt.deryurt.R
import com.derfurt.deryurt.data.CredentialStore
import com.derfurt.deryurt.data.SavedSession
import java.util.Locale
import com.derfurt.deryurt.databinding.ActivityMainBinding
import com.derfurt.deryurt.trigger.LoginWork
import com.derfurt.deryurt.trigger.NetworkWatch
import com.derfurt.deryurt.trigger.PortalWatchService
import com.google.android.material.button.MaterialButton

/** Ana ekran (mockup ekran 2-4): durum kartı ve hesap. Ayarlar sağ üstteki dişliyle [SettingsActivity]'de. */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private val credentialStore by lazy { CredentialStore(this) }
    private var themeAtCreate: String? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { render() }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = post()
        override fun onLost(network: Network) = post()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = post()
        private fun post() = runOnUiThread { if (!isFinishing) render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!credentialStore.hasCredentials()) {
            startActivity(Intent(this, CredentialsActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets(binding.root)

        // Zorla durdurma sonrası sistem kaydı silinmiş olabilir: uygulama açılınca her zaman yeniden kur.
        NetworkWatch.arm(this, force = true)
        if (store.watchActive) PortalWatchService.start(this)

        themeAtCreate = store.themeColor
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        DyButton.text(binding.btnEdit)
        binding.btnEdit.setOnClickListener { startActivity(Intent(this, CredentialsActivity::class.java)) }
        binding.footer.setText(R.string.footer_github)
        binding.footer.setOnClickListener { openUrl(getString(R.string.github_url)) }
    }

    override fun onStart() {
        super.onStart()
        if (!::binding.isInitialized) return
        store.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        try {
            getSystemService(ConnectivityManager::class.java)
                ?.registerNetworkCallback(NetworkWatch.wifiRequest(), networkCallback)
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        if (!credentialStore.hasCredentials()) {
            startActivity(Intent(this, CredentialsActivity::class.java))
            finish()
            return
        }
        // Ayarlar ekranında tema rengi değiştiyse bu ekranı da yeni renkle kur.
        if (store.themeColor != themeAtCreate) {
            recreate()
            return
        }
        binding.maskedUsername.text = credentialStore.load()?.let { CredentialStore.mask(it.username) }.orEmpty()
        render()
        checkUnknownWifi()
    }

    override fun onStop() {
        // Wifi yokken kapatılıyorsa uyandırma kaydını tazele (bkz. NetworkWatch.arm).
        if (::binding.isInitialized) NetworkWatch.arm(this, force = true)
        if (::binding.isInitialized) {
            store.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
            }
        }
        super.onStop()
    }

    /**
     * Uygulama açılınca kontroller yeniden başlar:
     *  - wifi'de internet yok (giriş sayfası bekliyor) → hemen giriş denenir, butona basmak gerekmez
     *  - internet var ama bu bağlantı henüz GSB diye tanınmıyor → sessiz kontrol (hesap + kota okunur)
     * Otomatik iş olduğu için kilitleri kaldırmaz; aynı anda ikinci iş açılmaz.
     */
    private fun checkUnknownWifi() {
        if (!store.autoLogin || store.running) return
        val wifi = NetworkWatch.currentWifi(this) ?: return
        if (!NetworkWatch.isValidated(this, wifi)) {
            LoginWork.enqueue(this, manual = false, fresh = true, source = "app_open_login")
            return
        }
        if (wifi.toString() == store.lastGsbNetwork) return
        LoginWork.enqueue(this, manual = false, source = "app_open")
    }

    // ── Durum kartı ──

    private fun render() {
        val c = AppTheme.colors(this)
        val success = color(R.color.dy_success)
        val error = color(R.color.dy_error)
        val warning = color(R.color.dy_warning)
        val neutral = color(R.color.dy_text_secondary)

        when (StatusResolver.resolve(this)) {
            UiState.NO_CREDENTIALS -> {
                showStatus(R.drawable.ic_error, warning, R.string.status_no_credentials_title, null)
                buttons(primary = R.string.btn_edit_credentials to ::openCredentials)
            }
            UiState.RUNNING -> {
                showStatus(R.drawable.ic_wifi, c.accent, R.string.status_running_title, getString(R.string.status_running_desc))
                binding.statusProgress.setIndicatorColor(c.primary)
                binding.statusProgress.trackColor = AppTheme.tonal(this)
                binding.statusProgress.isVisible = true
                binding.statusButtons.isVisible = false
                return
            }
            UiState.ONLINE -> {
                val time = DateUtils.formatDateTime(this, store.lastSuccessAt, DateUtils.FORMAT_SHOW_TIME or
                    (if (DateUtils.isToday(store.lastSuccessAt)) 0 else DateUtils.FORMAT_SHOW_DATE))
                val session = NetworkWatch.currentWifi(this)?.let { store.sessionFor(it.toString()) }
                val desc = buildString {
                    append(getString(R.string.status_online_desc, time))
                    if (session != null) append('\n').append(getString(R.string.status_account_line, session.accountName))
                    quotaLine(session)?.let { append('\n').append(it) }
                }
                showStatus(R.drawable.ic_check, success, R.string.status_online_title, desc)
                // Kayıtlı hesabın adı henüz bilinmiyorsa (uygulama hiç kendisi girmedi) oturumun kimin olduğunu
                // sadece kullanıcı bilebilir: "Bu benim hesabım değil" ile kayıtlı hesaba geçebilir.
                if (session != null && store.ownAccountName == null) {
                    buttons(tonal = R.string.btn_not_my_account to ::switchAccount)
                } else {
                    buttons(tonal = R.string.btn_login_now to ::loginNow)
                }
            }
            UiState.OTHER_ACCOUNT -> {
                val session = NetworkWatch.currentWifi(this)?.let { store.sessionFor(it.toString()) }
                showStatus(R.drawable.ic_error, warning, R.string.status_other_account_title,
                    getString(R.string.status_other_account_desc, session?.accountName.orEmpty()))
                buttons(primary = R.string.btn_switch_account to ::switchAccount)
            }
            UiState.WRONG_LOCKED, UiState.WRONG_ONCE -> {
                val locked = store.loadGuard().lockedOut
                showStatus(R.drawable.ic_error, error, R.string.status_wrong_title,
                    getString(if (locked) R.string.status_wrong_locked_desc else R.string.status_wrong_once_desc))
                buttons(tonal = R.string.btn_edit_credentials to ::openCredentials, primary = R.string.btn_retry to ::loginNow)
            }
            UiState.CAPTCHA -> {
                showStatus(R.drawable.ic_gpp_maybe, warning, R.string.status_captcha_title, getString(R.string.status_captcha_desc))
                buttons(primary = R.string.btn_open_portal to { openUrl(getString(R.string.portal_url)) })
            }
            UiState.OTHER_DEVICE -> {
                val since = store.otherSessionSince
                val desc = getString(R.string.status_other_desc) +
                    (if (since != null) " " + getString(R.string.other_session_since, since) else "")
                showStatus(R.drawable.ic_error, warning, R.string.status_other_title, desc)
                // "Diğer cihazdan çıkar" yarım genişliğe sığmıyor: butonlar alt alta.
                buttons(tonal = R.string.btn_retry to ::loginNow, primary = R.string.btn_kick_other to ::kickOther, stacked = true)
            }
            UiState.SLOW -> {
                showStatus(R.drawable.ic_wifi_off, warning, R.string.status_slow_title, getString(R.string.status_slow_desc))
                buttons(primary = R.string.btn_retry to ::loginNow)
            }
            UiState.AWAY -> {
                showStatus(R.drawable.ic_wifi_off, neutral, R.string.status_away_title,
                    getString(if (store.autoLogin) R.string.status_away_desc else R.string.status_away_auto_off_desc))
                buttons(tonal = R.string.btn_login_now to ::loginNow)
            }
        }
        binding.statusProgress.isVisible = false
        binding.statusButtons.isVisible = true
    }

    private fun showStatus(@DrawableRes icon: Int, @ColorInt tint: Int, @StringRes title: Int, desc: String?) {
        binding.statusIcon.setImageResource(icon)
        binding.statusIcon.imageTintList = ColorStateList.valueOf(tint)
        binding.statusIconBg.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(AppTheme.tonal(this@MainActivity, tint))
        }
        binding.statusTitle.setText(title)
        binding.statusDesc.text = desc.orEmpty()
        binding.statusDesc.isVisible = desc != null
    }

    /**
     * Kartın altındaki butonlar: soldaki tonlu (ikincil), sağdaki dolu (ana). Biri yoksa diğeri tam genişlik.
     * stacked=true: uzun etiketler için alt alta, ikisi de tam genişlik (üstte ikincil, altta ana).
     */
    private fun buttons(
        tonal: Pair<Int, () -> Unit>? = null,
        primary: Pair<Int, () -> Unit>? = null,
        stacked: Boolean = false,
    ) {
        setButton(binding.btnStatusSecondary, tonal, isPrimary = false)
        setButton(binding.btnStatusPrimary, primary, isPrimary = true)
        binding.statusButtonGap.isVisible = tonal != null && primary != null

        val row = binding.statusButtons
        row.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (b in listOf(binding.btnStatusSecondary, binding.btnStatusPrimary)) {
            b.layoutParams = (b.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (stacked) LinearLayout.LayoutParams.MATCH_PARENT else 0
                weight = if (stacked) 0f else 1f
            }
        }
        val gapSize = resources.getDimensionPixelSize(R.dimen.dy_space_3)
        binding.statusButtonGap.layoutParams = binding.statusButtonGap.layoutParams.apply {
            width = if (stacked) 0 else gapSize
            height = if (stacked) gapSize else 0
        }
    }

    private fun setButton(b: MaterialButton, spec: Pair<Int, () -> Unit>?, isPrimary: Boolean) {
        b.isVisible = spec != null
        if (spec == null) return
        b.setText(spec.first)
        b.setOnClickListener { spec.second() }
        if (isPrimary) DyButton.primary(b) else DyButton.tonal(b)
    }

    private fun loginNow() {
        store.running = true
        LoginWork.enqueue(this, manual = true, source = "app")
    }

    /** Bu telefonda açık oturumu (başka hesap) kapatıp kayıtlı hesapla gir. */
    private fun switchAccount() {
        store.running = true
        LoginWork.enqueue(this, manual = true, source = "app_switch", switch = true)
    }

    /** "Kalan kota: 30,1 GB / 32 GB · bitiş 30.11.2026" */
    private fun quotaLine(s: SavedSession?): String? {
        val left = s?.quotaRemainingMb ?: return null
        val total = s.quotaTotalMb ?: return null
        val exp = s.quotaExpires
        return if (exp != null) getString(R.string.status_quota_line, formatMb(left), formatMb(total), exp)
        else getString(R.string.status_quota_line_no_date, formatMb(left), formatMb(total))
    }

    private fun formatMb(mb: Float): String =
        if (mb >= 1024f) String.format(Locale.getDefault(), "%.1f GB", mb / 1024f)
        else String.format(Locale.getDefault(), "%.0f MB", mb)

    private fun kickOther() {
        store.running = true
        LoginWork.enqueue(this, manual = true, source = "app_kick", kick = true)
    }

    private fun openCredentials() = startActivity(Intent(this, CredentialsActivity::class.java))

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
        }
    }

    @ColorInt
    private fun color(res: Int): Int = ContextCompat.getColor(this, res)
}
