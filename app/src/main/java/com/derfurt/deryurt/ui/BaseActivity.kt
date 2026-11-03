package com.derfurt.deryurt.ui

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.derfurt.deryurt.data.StatusStore

/** Tema rengi katmanı + kenardan kenara (edge-to-edge) boşluklar. */
abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(AppTheme.overlayStyle(this), true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }

    /** Kök görünüme sistem çubukları ve klavye kadar iç boşluk verir. */
    protected fun applySystemInsets(root: View) {
        val l = root.paddingLeft
        val t = root.paddingTop
        val r = root.paddingRight
        val b = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    protected val store: StatusStore by lazy { StatusStore(this) }
}
