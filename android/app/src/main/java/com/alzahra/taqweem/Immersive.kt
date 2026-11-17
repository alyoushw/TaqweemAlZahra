package com.alzahra.taqweem

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Full-screen mode: hides the status bar and navigation bar; a swipe from the edge shows them briefly.
 * The user can choose to keep the phone's navigation buttons visible (settings).
 */
object Immersive {
    private fun sp(w: Window) = w.context.getSharedPreferences("zahra_prefs", android.content.Context.MODE_PRIVATE)
    fun showNav(ctx: android.content.Context) = ctx.getSharedPreferences("zahra_prefs", android.content.Context.MODE_PRIVATE).getBoolean("show_nav", false)
    fun setShowNav(ctx: android.content.Context, on: Boolean) =
        ctx.getSharedPreferences("zahra_prefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("show_nav", on).apply()

    @Suppress("DEPRECATION")
    fun apply(window: Window) {
        window.peekDecorView() ?: return
        val keepNav = sp(window).getBoolean("show_nav", false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // Pages always draw edge to edge; views that sit at the bottom add the buttons' height as padding (padBottom).
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                if (keepNav) {
                    it.hide(WindowInsets.Type.statusBars())
                    it.show(WindowInsets.Type.navigationBars())
                } else it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            val base = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            window.decorView.systemUiVisibility = if (keepNav) base else base or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
        window.decorView.requestApplyInsets()
    }

    /**
     * Keeps [view]'s content above the phone's navigation buttons when they are shown: its own background
     * continues underneath them, and its bottom padding grows by their height.
     */
    @Suppress("DEPRECATION")
    fun padBottom(view: View) {
        val base = view.paddingBottom
        view.setOnApplyWindowInsetsListener { v, ins ->
            val b = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.navigationBars()).bottom else ins.systemWindowInsetBottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, base + b)
            ins
        }
        view.requestApplyInsets()
    }
}

/** Shows a dialog without bringing the system bars back. */
fun android.app.Dialog.showImmersive() {
    val w = window
    if (w == null) { show(); return }
    w.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    show()
    Immersive.apply(w)
    w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
}
