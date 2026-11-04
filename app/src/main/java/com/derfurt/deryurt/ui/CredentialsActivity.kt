package com.derfurt.deryurt.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.derfurt.deryurt.R
import com.derfurt.deryurt.core.Credentials
import com.derfurt.deryurt.data.CredentialStore
import com.derfurt.deryurt.databinding.ActivityCredentialsBinding
import com.derfurt.deryurt.trigger.LoginWork

/**
 * İlk açılışta giriş bilgilerini ister (mockup ekran 1). Ana ekrandaki "Düzenle" de buraya gelir.
 * Kaydettikten sonra ilk açılışta bildirim izni istenir, sonra ana ekrana geçilir ve hemen bir giriş denenir.
 */
class CredentialsActivity : BaseActivity() {

    private lateinit var binding: ActivityCredentialsBinding
    private val credentialStore by lazy { CredentialStore(this) }
    private var firstRun = true

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { finishAndLogin() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCredentialsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets(binding.root)

        firstRun = !credentialStore.hasCredentials()
        applyColors()

        if (!firstRun) {
            credentialStore.load()?.let {
                binding.inputUsername.setText(it.username)
                binding.inputPassword.setText(it.password)
            }
            binding.btnSave.setText(R.string.cred_save)
        }

        binding.privacyText.text = getText(R.string.cred_privacy)
        binding.privacyText.movementMethod = LinkMovementMethod.getInstance()

        binding.btnSave.setOnClickListener { save() }
    }

    private fun applyColors() {
        val c = AppTheme.colors(this)
        val density = resources.displayMetrics.density
        binding.appIcon.background = GradientDrawable().apply {
            cornerRadius = resources.getDimension(R.dimen.dy_app_icon_radius)
            setColor(c.primary)
        }
        binding.privacyIconBox.background = GradientDrawable().apply {
            cornerRadius = 8 * density
            setColor(AppTheme.tonal(this@CredentialsActivity))
        }
        binding.privacyIcon.imageTintList = ColorStateList.valueOf(c.accent)
        binding.privacyText.setLinkTextColor(c.primary)

        val stroke = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(c.primary, ContextCompat.getColor(this, R.color.dy_input_outline)),
        )
        binding.layoutUsername.setBoxStrokeColorStateList(stroke)
        binding.layoutPassword.setBoxStrokeColorStateList(stroke)
        DyButton.primary(binding.btnSave)
    }

    private fun save() {
        val username = binding.inputUsername.text?.toString()?.trim().orEmpty()
        val password = binding.inputPassword.text?.toString().orEmpty()
        binding.layoutUsername.error = if (username.isEmpty()) getString(R.string.cred_error_username_empty) else null
        binding.layoutPassword.error = if (password.isEmpty()) getString(R.string.cred_error_password_empty) else null
        if (username.isEmpty() || password.isEmpty()) return

        credentialStore.save(Credentials(username, password))
        // Yeni bilgilerle kilitler sıfırdan başlar.
        store.resetGuard()
        store.clearOutcome()
        // Hesap değişmiş olabilir: kayıtlı hesabın portal adı yeni girişte yeniden öğrenilir.
        store.ownAccountName = null

        if (firstRun && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            finishAndLogin()
        }
    }

    private fun finishAndLogin() {
        LoginWork.enqueue(this, manual = true, source = "credentials_saved")
        if (firstRun) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
    }
}
