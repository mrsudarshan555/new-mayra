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
 * Dark glass disc / pill with soft colours flowing around its rim (no outline, no gaps).
 * Two colour layers travel in opposite directions so the colours keep mixing.
 */
class RimGlowView(context: Context, private val circle: Boolean) : View(context) {

    private val density = context.resources.displayMetrics.density
    private var palette = intArrayOf(
        0xFFFF4D6D.toInt(), 0xFFFFB400.toInt(), 0xFF22C55E.toInt(),
        0xFF2D8CFF.toInt(), 0xFFB45CFF.toInt(), 0xFFFF4D6D.toInt()
    )
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xB00A0B10.toInt() }
    private val paintA = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val paintB = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val matrixA = Matrix()
    private val matrixB = Matrix()
    private var sweepA: SweepGradient? = null
    private var sweepB: SweepGradient? = null
    private val rect = RectF()
    private var phase = 0f
    private var level = 0f
    private var targetLevel = 0f
    private var lastNanos = 0L

    fun setPalette(colors: IntArray) {
        palette = colors
        rebuildShaders()
        invalidate()
    }

    fun setLevel(value: Float) { targetLevel = value.coerceIn(0f, 1f) }

    private fun rebuildShaders() {
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        sweepA = SweepGradient(cx, cy, palette, null)
        sweepB = SweepGradient(cx, cy, palette.reversedArray(), null)
        paintA.shader = sweepA
        paintB.shader = sweepB
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastNanos = 0L
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.033f else ((now - lastNanos) / 1_000_000_000f).coerceAtMost(0.2f)
        lastNanos = now
        level += (targetLevel - level) * min(1f, dt * 3f)           // slow, gentle reaction
        phase = (phase + dt * (1f + level * 0.5f) / 9f) % 1f         // one colour turn every ~9 s

        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 5f * density
        rect.set(pad, pad, w - pad, h - pad)
        val radius = if (circle) min(rect.width(), rect.height()) / 2f else rect.height() / 2f

        canvas.drawRoundRect(rect, radius, radius, fill)

        matrixA.setRotate(phase * 360f, w / 2f, h / 2f)
        matrixB.setRotate(-phase * 360f * 0.7f + 150f, w / 2f, h / 2f)
        sweepA?.setLocalMatrix(matrixA)
        sweepB?.setLocalMatrix(matrixB)

        val boost = 1f + 0.35f * level
        val passes = floatArrayOf(7f, 4.5f, 2.2f)
        val alphas = floatArrayOf(0.22f, 0.45f, 0.85f)
        for (i in passes.indices) {
            paintA.strokeWidth = passes[i] * density * boost
            paintB.strokeWidth = passes[i] * density * boost
            paintA.alpha = (alphas[i] * 255f * 0.9f).toInt().coerceIn(0, 255)
            paintB.alpha = (alphas[i] * 255f * 0.5f).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, radius, radius, paintA)
            canvas.drawRoundRect(rect, radius, radius, paintB)
        }
        postInvalidateDelayed(33L)
    }
}
