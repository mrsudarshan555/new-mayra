package com.stonicx.assistant.coding.agent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** Renders a page in an off-screen phone-sized WebView and returns a JPEG - this is how the agent "LOOKs" at its own work. */
object PageShot {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun capture(ctx: Context, url: String): ByteArray? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(20_000L) {
            suspendCancellableCoroutine<ByteArray?> { cont ->
                val app = ctx.applicationContext
                val d = app.resources.displayMetrics.density
                val w = (412 * d).toInt(); val h = (892 * d).toInt()
                val handler = Handler(Looper.getMainLooper())
                var wv: WebView? = null
                try {
                    wv = WebView(app).apply {
                        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.loadWithOverviewMode = true; settings.useWideViewPort = true
                        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                        layout(0, 0, w, h)
                    }
                    wv?.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, u: String?) {
                            handler.postDelayed({
                                var out: ByteArray? = null
                                try {
                                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    wv?.draw(Canvas(bmp))
                                    val scaled = Bitmap.createScaledBitmap(bmp, 540, (540f * h / w).toInt(), true)
                                    val bos = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, 78, bos); out = bos.toByteArray()
                                } catch (e: Throwable) { out = null }
                                try { wv?.destroy() } catch (_: Throwable) { }
                                if (cont.isActive) cont.resume(out)
                            }, 1100)
                        }
                        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            if (cont.isActive) cont.resume(null); return true
                        }
                    }
                    wv?.loadUrl(url)
                } catch (e: Throwable) {
                    try { wv?.destroy() } catch (_: Throwable) { }
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation { handler.post { try { wv?.destroy() } catch (_: Throwable) { } } }
            }
        }
    }
}
