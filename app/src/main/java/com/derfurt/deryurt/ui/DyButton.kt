package com.derfurt.deryurt.ui

import android.content.res.ColorStateList
import com.google.android.material.button.MaterialButton

/** Buton renkleri tek yerden (Tasarım Sistemi §4 "Tonlu ikincil"). */
object DyButton {
    fun primary(b: MaterialButton) {
        val c = AppTheme.colors(b.context)
        b.backgroundTintList = ColorStateList.valueOf(c.primary)
        b.setTextColor(b.context.getColor(com.derfurt.deryurt.R.color.dy_on_primary))
    }

    fun tonal(b: MaterialButton) {
        val c = AppTheme.colors(b.context)
        b.backgroundTintList = ColorStateList.valueOf(AppTheme.tonal(b.context))
        b.setTextColor(c.accent)
    }

    fun text(b: MaterialButton) {
        b.setTextColor(AppTheme.colors(b.context).primary)
    }
}
