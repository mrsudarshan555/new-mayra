package com.stonicx.assistant.coding.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

/** Adds / removes the floating coding window over every app and the home screen. Safe to call from any thread. */
object CodingOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: CodingOverlayView? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var appCtx: Context? = null
    @Volatile var onStopRequested: (() -> Unit)? = null

    private fun dp(c: Context, v: Float) = (v * c.resources.displayMetrics.density).toInt()

    fun canShow(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun show(ctx: Context, title: String, realistic: Boolean) {
        main.post {
            val app = ctx.applicationContext
            if (!canShow(app)) return@post
            if (view != null) { view?.title = title; return@post }
            appCtx = app
            val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = CodingOverlayView(app).apply {
                this.title = title; realisticTyping = realistic
                onStop = { onStopRequested?.invoke() }
                onMinimize = { setCollapsedMode(true) }
                onExpand = { setCollapsedMode(false) }
                onDrag = { dx, dy -> moveBy(dx, dy) }
            }
            val screenW = app.resources.displayMetrics.widthPixels
            val lp = WindowManager.LayoutParams(
                minOf(screenW - dp(app, 24f), dp(app, 420f)), dp(app, 300f),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val p = app.getSharedPreferences("mayra_coding_overlay", Context.MODE_PRIVATE)
                x = p.getInt("x", (screenW - width) / 2); y = p.getInt("y", dp(app, 90f))
            }
            try { manager.addView(v, lp); view = v; params = lp; wm = manager } catch (e: Exception) { view = null }
        }
    }

    fun append(text: String, kind: CodingOverlayView.Kind) { view?.append(text, kind) }

    fun setStatus(s: String) { main.post { view?.statusText = s } }

    fun finish(ok: Boolean, status: String, autoHideMs: Long = 9000L) {
        main.post {
            view?.apply { finished = true; statusText = status; title = if (ok) "Mayra ne coding poori ki" else "Mayra ki coding ruki" }
            main.postDelayed({ hide() }, autoHideMs)
        }
    }

    private fun setCollapsedMode(collapsed: Boolean) {
        val app = appCtx ?: return; val v = view ?: return; val lp = params ?: return
        v.collapsed = collapsed
        if (collapsed) { lp.width = dp(app, 200f); lp.height = dp(app, 46f) }
        else { lp.width = minOf(app.resources.displayMetrics.widthPixels - dp(app, 24f), dp(app, 420f)); lp.height = dp(app, 300f) }
        try { wm?.updateViewLayout(v, lp) } catch (e: Exception) { }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val lp = params ?: return; val v = view ?: return; val app = appCtx ?: return
        val m = app.resources.displayMetrics
        lp.x = (lp.x + dx.toInt()).coerceIn(0, maxOf(0, m.widthPixels - lp.width))
        lp.y = (lp.y + dy.toInt()).coerceIn(0, maxOf(0, m.heightPixels - lp.height))
        try { wm?.updateViewLayout(v, lp) } catch (e: Exception) { }
        app.getSharedPreferences("mayra_coding_overlay", Context.MODE_PRIVATE).edit().putInt("x", lp.x).putInt("y", lp.y).apply()
    }

    fun hide() {
        main.post {
            try { view?.let { wm?.removeView(it) } } catch (e: Exception) { }
            view = null; params = null
        }
    }
}
