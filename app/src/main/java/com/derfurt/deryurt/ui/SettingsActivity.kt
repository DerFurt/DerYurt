package com.derfurt.deryurt.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.derfurt.deryurt.R
import com.derfurt.deryurt.data.StatusStore
import com.derfurt.deryurt.databinding.ActivitySettingsBinding
import com.derfurt.deryurt.trigger.LoginWork
import com.derfurt.deryurt.trigger.NetworkWatch
import com.derfurt.deryurt.trigger.PortalWatchService
import com.google.android.material.materialswitch.MaterialSwitch

/** Ayarlar ekranı (ana ekranda sağ üstteki dişli): Bağlantı + Görünüm. */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets(binding.root)

        binding.btnBack.imageTintList = ColorStateList.valueOf(AppTheme.colors(this).primary)
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        setupConnection()
        setupAppearance()
    }

    override fun onResume() {
        super.onResume()
        renderBattery()
    }

    // ── Bağlantı ──

    private fun setupConnection() {
        tintSwitch(binding.switchAuto)
        tintSwitch(binding.switchWatch)

        binding.switchAuto.isChecked = store.autoLogin
        binding.switchAuto.setOnCheckedChangeListener { _, on ->
            store.autoLogin = on
            if (on) {
                NetworkWatch.arm(this, force = true)
                LoginWork.schedulePeriodic(this)
            } else {
                LoginWork.cancelPeriodic(this)
            }
        }
        binding.rowAuto.setOnClickListener { binding.switchAuto.toggle() }

        binding.switchWatch.isChecked = store.watchSession
        binding.switchWatch.setOnCheckedChangeListener { _, on ->
            store.watchSession = on
            if (on) PortalWatchService.start(this) else PortalWatchService.stop(this)
        }
        binding.rowWatch.setOnClickListener { binding.switchWatch.toggle() }

        binding.rowBattery.setOnClickListener { openBatterySettings() }
    }

    private fun renderBattery() {
        val pm = getSystemService(PowerManager::class.java)
        val ignoring = pm?.isIgnoringBatteryOptimizations(packageName) == true
        binding.batteryDesc.setText(if (ignoring) R.string.setting_battery_off_desc else R.string.setting_battery_on_desc)
        binding.batteryChip.isVisible = !ignoring
        binding.batteryChip.background = GradientDrawable().apply {
            cornerRadius = resources.getDimension(R.dimen.dy_radius_full)
            setColor(AppTheme.tonal(this@SettingsActivity, color(R.color.dy_warning)))
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    // ── Görünüm ──

    private fun setupAppearance() {
        val circles = mapOf(
            StatusStore.THEME_BLUE to binding.circleBlue,
            StatusStore.THEME_ORANGE to binding.circleOrange,
            StatusStore.THEME_RED to binding.circleRed,
            StatusStore.THEME_GREEN to binding.circleGreen,
        )
        val selected = AppTheme.colors(this).key
        for ((key, view) in circles) {
            drawCircle(view, AppTheme.colorsFor(this, key).primary, key == selected)
            view.setOnClickListener {
                if (store.themeColor != key) {
                    store.themeColor = key
                    recreate()
                }
            }
        }

        tintSwitch(binding.switchDark)
        binding.switchDark.isChecked = store.darkMode
        binding.switchDark.setOnCheckedChangeListener { _, on ->
            store.darkMode = on
            AppTheme.applyNightMode(this)
        }
    }

    private fun drawCircle(view: ImageView, @ColorInt color: Int, selected: Boolean) {
        val density = resources.displayMetrics.density
        view.setImageDrawable(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (selected) setStroke((3 * density).toInt(), color(R.color.dy_text_primary))
        })
        view.isSelected = selected
    }

    private fun tintSwitch(s: MaterialSwitch) {
        val checked = intArrayOf(android.R.attr.state_checked)
        val none = intArrayOf()
        s.trackTintList = ColorStateList(arrayOf(checked, none),
            intArrayOf(AppTheme.colors(this).primary, color(R.color.dy_switch_off_track)))
        s.thumbTintList = ColorStateList(arrayOf(checked, none),
            intArrayOf(color(R.color.dy_on_primary), color(R.color.dy_switch_off_thumb)))
        s.trackDecorationTintList = ColorStateList.valueOf(color(android.R.color.transparent))
    }

    @ColorInt
    private fun color(res: Int): Int = ContextCompat.getColor(this, res)
}
