package com.derfurt.deryurt.ui

import android.content.Context
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.derfurt.deryurt.R
import com.derfurt.deryurt.data.StatusStore

data class ThemeColors(
    val key: String,
    @ColorInt val primary: Int,
    @ColorInt val accent: Int,
)

/** Tema rengi ve açık/koyu mod için tek kaynak (DerNotes AppTheme ile aynı mantık). */
object AppTheme {

    val ALL_KEYS = listOf(StatusStore.THEME_BLUE, StatusStore.THEME_ORANGE, StatusStore.THEME_RED, StatusStore.THEME_GREEN)

    fun colors(context: Context): ThemeColors = colorsFor(context, StatusStore(context).themeColor)

    fun colorsFor(context: Context, key: String): ThemeColors {
        val (p, a) = when (key) {
            StatusStore.THEME_BLUE -> R.color.theme_blue_primary to R.color.theme_blue_accent
            StatusStore.THEME_GREEN -> R.color.theme_green_primary to R.color.theme_green_accent
            StatusStore.THEME_RED -> R.color.theme_red_primary to R.color.theme_red_accent
            else -> R.color.theme_orange_primary to R.color.theme_orange_accent
        }
        val k = if (key in ALL_KEYS) key else StatusStore.THEME_ORANGE
        return ThemeColors(k, ContextCompat.getColor(context, p), ContextCompat.getColor(context, a))
    }

    fun overlayStyle(context: Context): Int = when (StatusStore(context).themeColor) {
        StatusStore.THEME_BLUE -> R.style.ThemeOverlay_DerYurt_Blue
        StatusStore.THEME_GREEN -> R.style.ThemeOverlay_DerYurt_Green
        StatusStore.THEME_RED -> R.style.ThemeOverlay_DerYurt_Red
        else -> R.style.ThemeOverlay_DerYurt_Orange
    }

    /** Tonlu zemin: blend(surface, renk, %16). Elle hesaplanmaz, hep buradan alınır. */
    @ColorInt
    fun tonal(context: Context, @ColorInt color: Int = colors(context).primary): Int =
        ColorUtils.blendARGB(ContextCompat.getColor(context, R.color.dy_surface), color, 0.16f)

    fun applyNightMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            if (StatusStore(context).darkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
