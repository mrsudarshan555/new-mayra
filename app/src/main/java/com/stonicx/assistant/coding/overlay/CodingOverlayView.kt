package com.stonicx.assistant.coding.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The floating "Mayra is coding" window. Drawn entirely on a Canvas: a dark terminal-style panel where the agent's
 * output is "typed" live (catch-up typing speed 240..1800 chars/s), with Minimize / Stop buttons, and a small pill
 * when collapsed. Colours follow the original design: thought=grey, text=pale green, code=light green,
 * tool=light blue, ok=neon green, error=red, status=white, wait=amber.
 */
class CodingOverlayView(context: Context) : View(context) {

    enum class Kind(val color: Int) {
        THOUGHT(0xFF8A8F98.toInt()), TEXT(0xFFB9F5C4.toInt()), CODE(0xFF7CFF7C.toInt()), TOOL(0xFF5FD3FF.toInt()),
        OK(0xFF39FF14.toInt()), ERR(0xFFFF4D4D.toInt()), STATUS(Color.WHITE), WAIT(0xFFFFC24D.toInt())
    }

    private class Line(val text: String, val kind: Kind)

    var title: String = "Mayra is coding"
    var collapsed: Boolean = false
        set(v) { field = v; invalidate() }
    var realisticTyping: Boolean = true
    var onStop: (() -> Unit)? = null
    var onMinimize: (() -> Unit)? = null
    var onExpand: (() -> Unit)? = null
    /** Called while the user drags the panel; dx/dy are pixel deltas. */
    var onDrag: ((Float, Float) -> Unit)? = null
    /** Shown in the collapsed pill and as the header sub-status. */
    var statusText: String = "Coding…"
    var finished: Boolean = false

    private val density = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    private val lines = ArrayList<Line>()
    private var shown = 0.0
    private var lastFrameNs = 0L
    private val maxLines = 320

    private val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = 12.5f * sp }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); textSize = 14.5f * sp; color = Color.WHITE }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = 12f * sp }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }

    private val panel = RectF(); private val minBtn = RectF(); private val stopBtn = RectF()
    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f; private var dragging = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAttachedToWindow) return
            val dt = if (lastFrameNs == 0L) 0.016 else ((frameTimeNanos - lastFrameNs) / 1e9).coerceIn(0.0, 0.05)
            lastFrameNs = frameTimeNanos
            val total = totalChars().toDouble()
            if (shown < total) {
                val speed = (total - shown).coerceIn(240.0, 1800.0)
                shown = min(total, shown + speed * dt)
            }
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); lastFrameNs = 0L; Choreographer.getInstance().postFrameCallback(frame) }
    override fun onDetachedFromWindow() { Choreographer.getInstance().removeFrameCallback(frame); super.onDetachedFromWindow() }

    private fun totalChars(): Int = lines.sumOf { it.text.length + 1 }

    /** Add text (may contain newlines). Each line is typed out in turn. */
    @Synchronized fun append(text: String, kind: Kind) {
        if (!realisticTyping && kind != Kind.STATUS && kind != Kind.OK && kind != Kind.ERR && kind != Kind.WAIT && kind != Kind.TOOL) return
        text.replace("\r", "").split("\n").forEach { lines += Line(it.replace("\t", "  "), kind) }
        while (lines.size > maxLines) { val drop = lines.removeAt(0); shown = max(0.0, shown - (drop.text.length + 1)) }
        postInvalidateOnAnimation()
    }

    @Synchronized fun clearAll() { lines.clear(); shown = 0.0; invalidate() }

    private fun pill(c: Canvas, r: RectF, label: String, bg: Int, border: Int, fg: Int) {
        fill.shader = null; fill.color = bg; c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
        stroke.color = border; c.drawRoundRect(r, r.height() / 2, r.height() / 2, stroke)
        btnPaint.color = fg
        val w = btnPaint.measureText(label)
        c.drawText(label, r.centerX() - w / 2, r.centerY() - (btnPaint.descent() + btnPaint.ascent()) / 2, btnPaint)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = (System.nanoTime() / 1e9)
        val pulse = (0.5 + 0.5 * sin(t * 4.0)).toFloat()
        panel.set(1f * density, 1f * density, w - 1f * density, h - 1f * density)
        if (collapsed) {
            fill.shader = null; fill.color = 0xF20E2C54.toInt(); c.drawRoundRect(panel, h / 2, h / 2, fill)
            stroke.color = 0x5527A8FF; c.drawRoundRect(panel, h / 2, h / 2, stroke)
            fill.color = if (finished) Kind.OK.color else Kind.OK.color; fill.alpha = (110 + 145 * pulse).toInt()
            c.drawCircle(18f * density, h / 2, 5f * density, fill); fill.alpha = 255
            titlePaint.color = Color.WHITE
            val label = statusText.take(26)
            c.drawText(label, 32f * density, h / 2 - (titlePaint.descent() + titlePaint.ascent()) / 2, titlePaint)
            return
        }
        val r = 18f * density
        fill.shader = LinearGradient(0f, 0f, 0f, h, 0xF207172E.toInt(), 0xF203070F.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(panel, r, r, fill)
        // header glow
        val headerH = 46f * density
        fill.shader = LinearGradient(0f, 0f, 0f, headerH, 0x3327A8FF, 0x0027A8FF, Shader.TileMode.CLAMP)
        c.save(); c.clipRect(0f, 0f, w, headerH); c.drawRoundRect(panel, r, r, fill); c.restore()
        fill.shader = null
        stroke.color = 0x5527A8FF; c.drawRoundRect(panel, r, r, stroke)
        stroke.color = 0x2227A8FF; c.drawLine(12f * density, headerH, w - 12f * density, headerH, stroke)

        // title + live dot
        fill.color = if (finished) Kind.OK.color else 0xFF33B5FF.toInt(); fill.alpha = if (finished) 255 else (110 + 145 * pulse).toInt()
        c.drawCircle(18f * density, headerH / 2, 4.5f * density, fill); fill.alpha = 255
        titlePaint.color = 0xFFCFE3FF.toInt()
        c.drawText(title, 30f * density, headerH / 2 - (titlePaint.descent() + titlePaint.ascent()) / 2, titlePaint)

        // buttons
        val bh = 28f * density; val top = (headerH - bh) / 2
        stopBtn.set(w - 12f * density - 62f * density, top, w - 12f * density, top + bh)
        minBtn.set(stopBtn.left - 8f * density - 86f * density, top, stopBtn.left - 8f * density, top + bh)
        pill(c, minBtn, "– Minimize", 0x2227A8FF, 0xFF33B5FF.toInt(), 0xFFCFE3FF.toInt())
        pill(c, stopBtn, "■ Stop", 0x33FF3B4E, 0x66FF3B4E, 0xFFFF6B7A.toInt())

        // code area: wrap what has been typed so far and show the newest rows that fit
        val padX = 14f * density; val padTop = headerH + 10f * density; val padBottom = 12f * density
        val charW = codePaint.measureText("M")
        val cols = max(8, ((w - 2 * padX) / charW).toInt())
        val lineH = codePaint.fontSpacing * 1.12f
        val rowsFit = max(1, ((h - padTop - padBottom) / lineH).toInt())
        val rows = ArrayList<Pair<String, Kind>>()
        var budget = shown.toInt()
        val snapshot = synchronized(this) { ArrayList(lines) }
        var cursorKind = Kind.STATUS
        for (ln in snapshot) {
            if (budget <= 0) break
            val typed = if (budget >= ln.text.length + 1) ln.text else ln.text.take(budget)
            budget -= ln.text.length + 1
            cursorKind = ln.kind
            if (typed.isEmpty()) { rows += "" to ln.kind; continue }
            var i = 0
            while (i < typed.length) { rows += typed.substring(i, min(typed.length, i + cols)) to ln.kind; i += cols }
        }
        val first = max(0, rows.size - rowsFit)
        var y = padTop - codePaint.ascent()
        var lastRowW = 0f
        for (k in first until rows.size) {
            codePaint.color = rows[k].second.color
            c.drawText(rows[k].first, padX, y, codePaint)
            lastRowW = codePaint.measureText(rows[k].first)
            y += lineH
        }
        if (!finished) {
            val blink = ((t * 2.2).toLong() % 2L) == 0L || shown < totalChars()
            if (blink) {
                fill.color = Kind.OK.color; fill.alpha = 220
                val cy = y - lineH - codePaint.ascent() + codePaint.descent()
                val cx = padX + (if (rows.isEmpty()) 0f else lastRowW)
                c.drawRect(cx + 1f * density, cy - codePaint.textSize * 0.95f, cx + charW * 0.6f, cy, fill); fill.alpha = 255
            }
        }
        if (rows.isEmpty()) { codePaint.color = Kind.WAIT.color; c.drawText("Soch rahi hoon…", padX, padTop - codePaint.ascent(), codePaint) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; lastX = e.rawX; lastY = e.rawY; dragging = false; return true }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(e.rawX - downX) > 8 * density || abs(e.rawY - downY) > 8 * density)) dragging = true
                if (dragging) { onDrag?.invoke(e.rawX - lastX, e.rawY - lastY); lastX = e.rawX; lastY = e.rawY }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    if (collapsed) onExpand?.invoke()
                    else {
                        val pad = 6f * density
                        when {
                            stopBtn.left - pad <= e.x && e.x <= stopBtn.right + pad && stopBtn.top - pad <= e.y && e.y <= stopBtn.bottom + pad -> onStop?.invoke()
                            minBtn.left - pad <= e.x && e.x <= minBtn.right + pad && minBtn.top - pad <= e.y && e.y <= minBtn.bottom + pad -> onMinimize?.invoke()
                        }
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
