package com.stonicx.assistant.ui.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.MotionEvent
import android.view.Gravity
import android.view.WindowManager
import android.view.ViewConfiguration
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ImageView
import android.content.Intent
import android.os.SystemClock
import com.stonicx.assistant.R
import com.stonicx.assistant.ui.home.MainActivity
import com.stonicx.assistant.ui.orb.RimGlowView
import com.stonicx.assistant.StonicXApp
import com.stonicx.assistant.background.StonicXConversationService
import android.view.ViewGroup
import com.stonicx.assistant.automation.agent.AgentTaskSnapshot
import com.stonicx.assistant.automation.agent.TaskStepStatus

/** Small task HUD owned by the foreground conversation service. */
class TaskProgressOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()
    private val root = FrameLayout(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false
    }
    private val pill = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = GradientDrawable().apply {
            setColor(Color.argb(146, 8, 9, 12))
            cornerRadius = dp(13).toFloat()
            setStroke(dp(1), Color.argb(65, 225, 230, 238))
        }
        elevation = dp(12).toFloat()
    }
    private val header = TextView(context).apply {
        text = "Mayra  ⋮⋮"
        setTextColor(Color.rgb(215, 220, 228))
        textSize = 10f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(5))
        contentDescription = "Drag StonicX task overlay anywhere on screen"
    }
    private val title = label(11f, true).apply { maxLines = 1 }
    private val progressText = label(11f, true).apply {
        setTextColor(Color.rgb(190, 196, 206))
        textSize = 9f
        setPadding(0, dp(2), 0, 0)
    }
    private val stepRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(8, 9, 12))
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(55, 230, 234, 240))
    }
    private var pageReady = false
    private var pendingState = "idle"
    private var lastLevelPush = 0L
    private var barOpen = false
    private var userMoved = false

    /** The same particle orb as the home screen, drawn on a transparent background. */
    private val orbWeb = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
        }
        webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                applyOrbColor()
                runJs("window.StonicXOrb && window.StonicXOrb.setState('$pendingState');")
            }
        }
        loadUrl("https://appassets.androidplatform.net/assets/orb.html?embedded=1")
    }
    private val rimGlow = RimGlowView(context, circle = true)
    private val avatar = FrameLayout(context).apply {
        clipChildren = false
        addView(rimGlow, FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER))
        addView(orbWeb, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
    }

    // --- Gemini-style bar: video · screen share ........ mic · keyboard · close ---
    private val barGlow = RimGlowView(context, circle = false)
    private val micButton = barButton(R.drawable.ic_overlay_mic, "Microphone") { toggleMic() }
    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        addView(barButton(R.drawable.ic_overlay_video, "Share video") { launchApp("camera") }, barBtnParams())
        addView(barButton(R.drawable.ic_overlay_screen, "Share screen") { launchApp("screen") }, barBtnParams())
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(micButton, barBtnParams())
        addView(barButton(R.drawable.ic_overlay_keyboard, "Type a message") { launchApp("chat") }, barBtnParams())
        addView(barButton(R.drawable.ic_overlay_close, "Close") { closeBar() }, barBtnParams())
    }
    private val barBox = FrameLayout(context).apply {
        addView(barGlow, FrameLayout.LayoutParams(-1, -1))
        addView(bar, FrameLayout.LayoutParams(-1, -1))
    }
    private val transcriptCard = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 15f
        maxLines = 4
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.argb(225, 18, 20, 26))
            cornerRadius = dp(22).toFloat()
        }
        visibility = View.GONE
    }
    private val barRoot = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(transcriptCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        addView(barBox, LinearLayout.LayoutParams(-1, dp(66)))
    }
    private var hideTranscript: Runnable? = null
    private val singleTapRunnable = Runnable {
        if (avatarMode && !barOpen) {
            barOpen = true
            refreshMicIcon()
            applyMode()
        }
    }
    private val params = WindowManager.LayoutParams(
        minOf(dp(236), context.resources.displayMetrics.widthPixels - dp(16)),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.RIGHT or Gravity.BOTTOM
        x = dp(8)
        y = dp(110)
    }
    private var attached = false
    private var avatarMode = true
    private var displayedTaskId: String? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var currentEdge = ""
    private var dragging = false
    private var lastTapAt = 0L
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        pill.visibility = View.GONE
        avatar.visibility = View.VISIBLE
        pill.addView(header)
        pill.addView(title, matchWrap())
        pill.addView(stepRows, matchWrap())
        pill.addView(progress, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(4); bottomMargin = dp(1) })
        pill.addView(progressText, matchWrap())
        root.addView(pill, FrameLayout.LayoutParams(-1, -2))
        root.addView(avatar, FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER))
        root.addView(barRoot, FrameLayout.LayoutParams(-1, -2))
        pill.isClickable = true
        pill.setOnTouchListener { _, event -> handleOverlayTouch(event) }
        avatar.setOnTouchListener { _, event -> handleOverlayTouch(event) }
        orbWeb.setOnTouchListener { _, event -> handleOverlayTouch(event) }
        barBox.setOnTouchListener { _, event -> handleOverlayTouch(event, fromBar = true) }
        transcriptCard.setOnTouchListener { _, event -> handleOverlayTouch(event, fromBar = true) }
    }

    fun update(snapshot: AgentTaskSnapshot?, showIdleAvatar: Boolean = false): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            remove()
            return false
        }
        val preferences = StonicXApp.instance.preferences
        if (snapshot == null && !showIdleAvatar && preferences.taskOverlayMode != "always") {
            remove()
            displayedTaskId = null
            return true
        }
        if (!preferences.isTaskOverlayEnabled) {
            remove()
            return true
        }
        val nextTaskId = snapshot?.taskId
        if (displayedTaskId != nextTaskId) {
            displayedTaskId = nextTaskId
            avatarMode = true
        }
        if (!attached) {
            try {
                // Every time the orb comes out it starts in the corner; it can then be dragged anywhere.
                userMoved = false
                barOpen = false
                applyEdge(preferences.taskOverlayEdge)
                windowManager.addView(root, params)
                attached = true
            } catch (_: Exception) {
                attached = false
                return false
            }
        }
        if (currentEdge != preferences.taskOverlayEdge && !userMoved) applyEdge(preferences.taskOverlayEdge)
        applyOrbColor()
        if (snapshot == null) renderIdle() else render(snapshot)
        applyMode()
        return true
    }

    fun remove() {
        root.removeCallbacks(singleTapRunnable)
        barOpen = false
        if (attached) {
            try { windowManager.removeView(root) } catch (_: Exception) {}
            attached = false
        }
    }

    private fun render(snapshot: AgentTaskSnapshot) {
        progress.visibility = View.VISIBLE
        title.text = snapshot.originalGoal
        progress.progress = snapshot.progressPercentage.coerceIn(0, 100)
        val completed = snapshot.steps.count { it.status == TaskStepStatus.COMPLETED }
        progressText.text = "$completed/${snapshot.steps.size} done  ·  ${snapshot.progressPercentage}%"
        stepRows.removeAllViews()
        val activeIndex = snapshot.steps.indexOfFirst { it.stepId == snapshot.currentStepId }.coerceAtLeast(0)
        val first = (activeIndex - 2).coerceAtLeast(0)
        snapshot.steps.drop(first).take(4).forEach { step ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val active = step.status in setOf(TaskStepStatus.RUNNING, TaskStepStatus.VERIFYING, TaskStepStatus.WAITING_FOR_UI, TaskStepStatus.RECOVERING)
            val marker: View = if (active) {
                ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.rgb(205, 210, 218))
                }
            } else TextView(context).apply {
                text = if (step.status == TaskStepStatus.COMPLETED) "✓" else if (step.status in setOf(TaskStepStatus.FAILED, TaskStepStatus.BLOCKED)) "!" else "·"
                setTextColor(if (step.status == TaskStepStatus.COMPLETED) Color.rgb(175, 210, 188) else Color.rgb(165, 171, 181))
                textSize = 10f
                gravity = Gravity.CENTER
            }
            row.addView(marker, LinearLayout.LayoutParams(dp(12), dp(12)))
            row.addView(label(9f, active).apply {
                text = step.title
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                alpha = if (step.status == TaskStepStatus.COMPLETED) 0.72f else 1f
                setPadding(dp(5), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, dp(14), 1f))
            stepRows.addView(row, LinearLayout.LayoutParams(-1, dp(15)))
        }
        try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private fun renderIdle() {
        title.text = "Mayra"
        stepRows.removeAllViews()
        stepRows.addView(label(9f, false).apply { text = "Ready · Double-tap for task status" }, matchWrap())
        progress.visibility = View.GONE
        progressText.text = "Double-tap for status"
        try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private fun handleOverlayTouch(event: MotionEvent, fromBar: Boolean = false): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                val location = IntArray(2)
                root.getLocationOnScreen(location)
                startX = location[0]
                startY = location[1]
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (dragging || kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                    dragging = true
                    userMoved = true
                    root.removeCallbacks(singleTapRunnable)
                    params.gravity = Gravity.TOP or Gravity.LEFT
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    clampPosition()
                    try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    clampPosition()
                    val preferences = StonicXApp.instance.preferences
                    preferences.taskOverlayFreePosition = true
                    preferences.taskOverlayX = params.x
                    preferences.taskOverlayY = params.y
                    return true
                }
                if (fromBar) return false
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTapAt <= 360L) {
                    lastTapAt = 0L
                    root.removeCallbacks(singleTapRunnable)
                    barOpen = false
                    toggleAvatarMode()
                    return true
                }
                lastTapAt = now
                root.postDelayed(singleTapRunnable, 360L)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return false
            }
        }
        return false
    }

    private fun applyEdge(edge: String) {
        currentEdge = edge
        when (edge) {
            "left" -> { params.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL; params.x = dp(8); params.y = 0 }
            "top" -> { params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; params.x = 0; params.y = dp(90) }
            "bottom" -> { params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; params.x = 0; params.y = dp(90) }
            else -> { params.gravity = Gravity.RIGHT or Gravity.BOTTOM; params.x = dp(8); params.y = dp(110) }
        }
        try { if (attached) windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private fun toggleAvatarMode() {
        avatarMode = !avatarMode
        applyMode()
    }

    private fun applyMode() {
        val showBar = avatarMode && barOpen
        val showBubble = avatarMode && !barOpen
        pill.visibility = if (avatarMode) View.GONE else View.VISIBLE
        avatar.visibility = if (showBubble) View.VISIBLE else View.GONE
        barRoot.visibility = if (showBar) View.VISIBLE else View.GONE
        val screenW = context.resources.displayMetrics.widthPixels
        params.width = when {
            showBar -> minOf(dp(340), screenW - dp(16))
            showBubble -> dp(88)
            else -> minOf(dp(236), screenW - dp(16))
        }
        params.height = if (showBubble) dp(88) else WindowManager.LayoutParams.WRAP_CONTENT
        if (userMoved) clampPosition()
        try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private fun closeBar() {
        barOpen = false
        applyMode()
    }

    private fun launchApp(action: String) {
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra("overlay_action", action)
            )
        } catch (_: Exception) {
        }
        closeBar()
    }

    private fun toggleMic() {
        val prefs = StonicXApp.instance.preferences
        val muted = !prefs.isMicMuted
        prefs.isMicMuted = muted
        StonicXConversationService.setMicMuted(muted)
        refreshMicIcon()
    }

    private fun refreshMicIcon() {
        val muted = StonicXApp.instance.preferences.isMicMuted
        micButton.setColorFilter(if (muted) Color.rgb(255, 99, 99) else Color.WHITE)
    }

    // ---- live feed from the service: voice level, state, captions ----
    fun setLevel(level: Float) {
        rimGlow.setLevel(level)
        barGlow.setLevel(level)
        if (!pageReady || !attached) return
        val now = SystemClock.uptimeMillis()
        if (now - lastLevelPush < 50L) return
        lastLevelPush = now
        val l = (level * 0.7f).coerceIn(0f, 1f)
        runJs("window.StonicXOrb && window.StonicXOrb.setExternalAudio($l, $l, ${l * 0.6f});")
    }

    fun setState(state: String) {
        pendingState = state
        if (pageReady) runJs("window.StonicXOrb && window.StonicXOrb.setState('$state');")
    }

    /** Captions fade in softly above the bar, then fade out again. */
    fun setTranscript(text: String) {
        if (text.isBlank()) return
        transcriptCard.text = text
        if (transcriptCard.visibility != View.VISIBLE) {
            transcriptCard.alpha = 0f
            transcriptCard.visibility = View.VISIBLE
        }
        transcriptCard.animate().alpha(1f).setDuration(350L).start()
        hideTranscript?.let { root.removeCallbacks(it) }
        val hide = Runnable {
            transcriptCard.animate().alpha(0f).setDuration(500L).withEndAction {
                transcriptCard.visibility = View.GONE
                try { if (attached) windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
            }.start()
        }
        hideTranscript = hide
        root.postDelayed(hide, 7000L)
        try { if (attached) windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private fun runJs(code: String) {
        orbWeb.post { orbWeb.evaluateJavascript(code, null) }
    }

    private fun applyOrbColor() {
        val mode = StonicXApp.instance.preferences.floatingOrbColor
        val palette = orbPalette(mode)
        rimGlow.setPalette(palette)
        barGlow.setPalette(palette)
        if (!pageReady) return
        val hex = ORB_HEX[mode]
        runJs(
            if (hex == null) "window.StonicXOrb && window.StonicXOrb.setRainbow(true);"
            else "window.StonicXOrb && window.StonicXOrb.setHue('$hex');"
        )
    }

    private fun orbPalette(mode: String): IntArray {
        val base = ORB_COLOR[mode] ?: return intArrayOf(
            0xFFFF4D6D.toInt(), 0xFFFFB400.toInt(), 0xFF22C55E.toInt(),
            0xFF2D8CFF.toInt(), 0xFFB45CFF.toInt(), 0xFFFF4D6D.toInt()
        )
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        val a = hsv.clone().also { it[0] = (it[0] + 28f) % 360f }
        val b = hsv.clone().also { it[0] = (it[0] + 332f) % 360f }
        return intArrayOf(base, Color.HSVToColor(a), Color.HSVToColor(b), base)
    }

    private fun barButton(icon: Int, description: String, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        contentDescription = description
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(56, 255, 255, 255))
        }
        setOnClickListener { onClick() }
    }

    private fun barBtnParams() = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
        marginStart = dp(4)
        marginEnd = dp(4)
    }

    private companion object {
        val ORB_COLOR = mapOf(
            "blue" to 0xFF2D8CFF.toInt(), "purple" to 0xFFB45CFF.toInt(), "pink" to 0xFFFF4D8D.toInt(),
            "green" to 0xFF22C55E.toInt(), "orange" to 0xFFFF9F1C.toInt(), "red" to 0xFFFF3B30.toInt(),
            "cyan" to 0xFF22D3EE.toInt()
        )
        val ORB_HEX = mapOf(
            "blue" to "#2d8cff", "purple" to "#b45cff", "pink" to "#ff4d8d", "green" to "#22c55e",
            "orange" to "#ff9f1c", "red" to "#ff3b30", "cyan" to "#22d3ee"
        )
    }

    private fun clampPosition() {
        val metrics = context.resources.displayMetrics
        val overlayWidth = root.width.takeIf { it > 0 } ?: params.width.takeIf { it > 0 } ?: dp(88)
        val overlayHeight = root.height.takeIf { it > 0 } ?: params.height.takeIf { it > 0 } ?: dp(88)
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = params.x.coerceIn(0, (metrics.widthPixels - overlayWidth).coerceAtLeast(0))
        params.y = params.y.coerceIn(dp(24), (metrics.heightPixels - overlayHeight).coerceAtLeast(dp(24)))
    }

    private fun label(size: Float, bold: Boolean) = TextView(context).apply {
        setTextColor(Color.rgb(226, 232, 240))
        textSize = size
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
