package com.stonicx.assistant.ui.orb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.min

/**
 * Soft colour that slowly flows along the very edge of the phone screen while Mayra is active
 * (like Gemini Live). Pure drawing, no touch handling - always placed in a non-touchable overlay window.
 */
class ScreenEdgeGlowView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private var palette = intArrayOf(
        0xFFB44CFF.toInt(), 0xFFFF3D8B.toInt(), 0xFFFF9F1C.toInt(),
        0xFF4C7BFF.toInt(), 0xFFB44CFF.toInt()
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val matrix = Matrix()
    private var sweep: SweepGradient? = null
    private val rect = RectF()
    private var phase = 0f
    private var level = 0f
    private var targetLevel = 0f
    private var lastNanos = 0L

    fun setPalette(colors: IntArray) {
        if (colors.size < 2) return
        palette = colors
        rebuild()
        invalidate()
    }

    fun setLevel(value: Float) { targetLevel = value.coerceIn(0f, 1f) }

    private fun rebuild() {
        if (width == 0 || height == 0) return
        sweep = SweepGradient(width / 2f, height / 2f, palette, null)
        paint.shader = sweep
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastNanos = 0L
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.033f else ((now - lastNanos) / 1_000_000_000f).coerceAtMost(0.2f)
        lastNanos = now
        level += (targetLevel - level) * min(1f, dt * 3f)
        phase = (phase + dt * (1f + level * 0.6f) / 10f) % 1f

        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        val radius = 36f * density

        matrix.setRotate(phase * 360f, w / 2f, h / 2f)
        sweep?.setLocalMatrix(matrix)

        // Several strokes, wide + faint to thin + bright: only the inner half is on screen, so it fades inward.
        val boost = 1f + 0.45f * level
        val widths = floatArrayOf(46f, 34f, 24f, 16f, 9f, 4f)
        val alphas = floatArrayOf(0.05f, 0.08f, 0.12f, 0.18f, 0.28f, 0.45f)
        for (i in widths.indices) {
            paint.strokeWidth = widths[i] * density * boost
            paint.alpha = (alphas[i] * 255f * (0.75f + 0.25f * level)).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        postInvalidateDelayed(33L)
    }
}
