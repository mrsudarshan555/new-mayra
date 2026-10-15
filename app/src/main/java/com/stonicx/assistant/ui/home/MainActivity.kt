package com.stonicx.assistant.ui.home

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.stonicx.assistant.R
import com.stonicx.assistant.background.StonicXConversationService
import com.stonicx.assistant.data.model.ConversationState
import com.stonicx.assistant.databinding.ActivityMainBinding
import com.stonicx.assistant.ui.camera.CameraFragment
import com.stonicx.assistant.ui.chat.ChatFragment
import com.stonicx.assistant.ui.settings.SettingsFragment
import com.stonicx.assistant.util.PermissionHelper
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAB_HOME = 0
        const val TAB_CHAT = 1
        const val TAB_SETTINGS = 2
        const val TAB_UPDATES = 3
        const val TAB_CAMERA = 4
    }

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()
    private var activeSnackbar: Snackbar? = null

    private var currentTab = TAB_HOME
    private val homeFragment by lazy { HomeFragment() }
    private val chatFragment by lazy { ChatFragment() }
    private val settingsFragment by lazy { SettingsFragment() }
    private val updatesFragment by lazy { UpdatesFragment() }
    private val cameraFragment by lazy { CameraFragment() }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkPermissionsAndStart()
        } else {
            Toast.makeText(
                this,
                "Microphone permission is required for voice conversation.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkPermissionsAndStart()
        } else {
            Toast.makeText(
                this,
                "Notification permission is required to run in the background.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val mediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
    }

    val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            try {
                if (!StonicXConversationService.isSessionOn.value || StonicXConversationService.connectionStatus.value != "LIVE") {
                    Toast.makeText(this, "Start StonicX and wait for LIVE before sharing your screen.", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                if (!StonicXConversationService.elevateToMediaProjection()) {
                    Toast.makeText(this, "Could not start Android's screen-capture service.", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                val projection = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                val started = StonicXConversationService.startScreenCapture(projection, metrics)
                if (!started) {
                    StonicXConversationService.demoteFromMediaProjection()
                    Toast.makeText(this, "Android did not start screen capture. Please try again.", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                Toast.makeText(this, "Checking screen capture…", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    delay(2500L)
                    val captureManager = com.stonicx.assistant.vision.ScreenCaptureManager.getInstance()
                    if (!captureManager.isSharing.value) return@launch
                    if (captureManager.hasReceivedFrame.value) {
                        Toast.makeText(this@MainActivity, "Screen sharing is live. StonicX is receiving screen frames.", Toast.LENGTH_LONG).show()
                    } else {
                        StonicXConversationService.stopScreenCapture()
                        Toast.makeText(this@MainActivity, "No screen frames arrived, so sharing was stopped. Please try again.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error starting MediaProjection: ${e.message}", e)
                StonicXConversationService.demoteFromMediaProjection()
                Toast.makeText(this, "Screen capture failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    private var appliedThemeKey: String = ""
    private var appliedCustomHex: String = ""

    /** Handles the video / screen-share / keyboard buttons of the floating orb bar. */
    private fun handleOverlayAction(i: android.content.Intent?) {
        val action = i?.getStringExtra("overlay_action") ?: return
        i.removeExtra("overlay_action")
        when (action) {
            "camera" -> selectTab(TAB_CAMERA)
            "chat" -> selectTab(TAB_CHAT)
            "screen" -> toggleScreenShare()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOverlayAction(intent)
    }

    fun toggleScreenShare() {
        if (com.stonicx.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value) {
            StonicXConversationService.stopScreenCapture()
            Toast.makeText(this, "Screen sharing stopped.", Toast.LENGTH_SHORT).show()
        } else {
            if (!StonicXConversationService.isSessionOn.value || StonicXConversationService.connectionStatus.value != "LIVE") {
                Toast.makeText(this, "Start StonicX and wait for LIVE before sharing your screen.", Toast.LENGTH_LONG).show()
                return
            }
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.stonicx.assistant.util.ThemeManager.applyTheme(this)
        val prefs = com.stonicx.assistant.StonicXApp.instance.preferences
        appliedThemeKey = prefs.appTheme
        appliedCustomHex = prefs.customThemeColor
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!com.stonicx.assistant.security.AppIdentityVerifier.isTrustedRelease(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("App integrity check failed")
                .setMessage("This StonicX build is not signed with the configured official release certificate. Sensitive features have been blocked.")
                .setCancelable(false)
                .setPositiveButton("Close") { _, _ -> finish() }
                .show()
            return
        }

        com.stonicx.assistant.util.CrashReporter.showPendingReport(this)

        com.stonicx.assistant.util.ThemeManager.applyDynamicBackground(binding.rootMainLayout)

        currentTab = savedInstanceState?.getInt("CURRENT_TAB", TAB_HOME) ?: TAB_HOME

        // Clear any old restored fragments to prevent ghost overlapping
        val existingFragments = supportFragmentManager.fragments
        if (existingFragments.isNotEmpty()) {
            val trans = supportFragmentManager.beginTransaction()
            for (fragment in existingFragments) {
                trans.remove(fragment)
            }
            trans.commitNowAllowingStateLoss()
        }

        // Add all 4 fragments fresh into container
        val trans = supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, homeFragment, "TAB_0")
            .add(R.id.fragmentContainer, chatFragment, "TAB_1")
            .add(R.id.fragmentContainer, settingsFragment, "TAB_2")
            .add(R.id.fragmentContainer, updatesFragment, "TAB_3")
            .add(R.id.fragmentContainer, cameraFragment, "TAB_4")

        val allTabs = listOf(
            TAB_HOME to homeFragment,
            TAB_CHAT to chatFragment,
            TAB_SETTINGS to settingsFragment,
            TAB_UPDATES to updatesFragment,
            TAB_CAMERA to cameraFragment
        )

        allTabs.forEach { (tabId, frag) ->
            if (tabId == currentTab) {
                trans.show(frag)
            } else {
                trans.hide(frag)
            }
        }
        trans.commitNow()

        setupNavigationDock()
        selectTab(currentTab)
        observeViewModel()
        window.decorView.post { handleOverlayAction(intent) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab != TAB_HOME) {
                    selectTab(TAB_HOME)
                } else {
                    // Preserve Android's normal root behavior (return to launcher) only
                    // after the user is already on StonicX Home.
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("CURRENT_TAB", currentTab)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSettings()
        val prefs = com.stonicx.assistant.StonicXApp.instance.preferences
        if (appliedThemeKey != prefs.appTheme || appliedCustomHex != prefs.customThemeColor) {
            appliedThemeKey = prefs.appTheme
            appliedCustomHex = prefs.customThemeColor
            recreate()
        }
    }

    private fun setupNavigationDock() {
        binding.tabHome.setOnClickListener { selectTab(TAB_HOME) }
        binding.tabChat.setOnClickListener { selectTab(TAB_CHAT) }
        binding.tabSettings.setOnClickListener { selectTab(TAB_SETTINGS) }
        binding.tabCamera.setOnClickListener { selectTab(TAB_CAMERA) }

        binding.tabMic.setOnClickListener {
            if (currentTab == TAB_CAMERA) cameraFragment.capturePhoto() else handleMicOrOrbClick()
        }

        binding.tabMic.setOnLongClickListener {
            if (currentTab == TAB_CAMERA) cameraFragment.capturePhoto() else handleHoldMicOrOrb()
            true
        }
    }

    fun selectTab(tab: Int) {
        val previousTab = currentTab
        currentTab = tab

        val tabs = listOf(
            TAB_HOME to homeFragment,
            TAB_CHAT to chatFragment,
            TAB_SETTINGS to settingsFragment,
            TAB_UPDATES to updatesFragment,
            TAB_CAMERA to cameraFragment
        )

        val trans = supportFragmentManager.beginTransaction()
        tabs.forEach { (tabId, frag) ->
            if (frag.isAdded) {
                if (tabId == tab) {
                    trans.show(frag)
                } else {
                    trans.hide(frag)
                }
            }
        }
        trans.commitNowAllowingStateLoss()

        tabs.forEach { (tabId, frag) ->
            val v = frag.view
            if (v != null) {
                if (tabId == tab) {
                    v.visibility = View.VISIBLE
                    com.stonicx.assistant.util.ThemeManager.applyDynamicBackground(v)
                    if (previousTab != tab) {
                        v.alpha = 0f
                        v.translationX = if (tab > previousTab) 18f else -18f
                        v.animate().cancel()
                        v.animate()
                            .alpha(1f)
                            .translationX(0f)
                            .setDuration(190L)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                    } else {
                        v.alpha = 1f
                        v.translationX = 0f
                    }
                } else {
                    v.animate().cancel()
                    v.visibility = View.GONE
                }
            }
        }

        updateDockUI(tab)
        renderMicSlot(viewModel.isMicMuted.value, viewModel.conversationState.value, viewModel.isSessionOn.value)
    }

    private fun updateDockUI(tab: Int) {
        val tvAccent = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeAccent, tvAccent, true)
        val activeColor = if (tvAccent.data != 0) tvAccent.data else ContextCompat.getColor(this, R.color.accent_sapphire)

        val tvMuted = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextMuted, tvMuted, true)
        val inactiveColor = if (tvMuted.data != 0) tvMuted.data else ContextCompat.getColor(this, R.color.text_subtle)

        val tvPrimary = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextPrimary, tvPrimary, true)
        val activeTextColor = if (tvPrimary.data != 0) tvPrimary.data else android.graphics.Color.WHITE

        val navItems = listOf(
            Triple(TAB_HOME, binding.pillTabHome, Pair(binding.ivTabHome, binding.tvTabHome)),
            Triple(TAB_CHAT, binding.pillTabChat, Pair(binding.ivTabChat, binding.tvTabChat)),
            Triple(TAB_CAMERA, binding.pillTabCamera, Pair(binding.ivTabCamera, binding.tvTabCamera)),
            Triple(TAB_SETTINGS, binding.pillTabSettings, Pair(binding.ivTabSettings, binding.tvTabSettings))
        )

        navItems.forEach { (tabId, pill, views) ->
            val isSelected = tabId == tab
            val (iv, tv) = views
            if (isSelected) {
                pill.setBackgroundResource(R.drawable.bg_dock_item_active)
                pill.alpha = 0f
                pill.scaleX = 0.88f
                pill.scaleY = 0.88f
                pill.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                    .start()
                iv.imageTintList = ColorStateList.valueOf(activeTextColor)
                tv.setTextColor(activeTextColor)
                tv.alpha = 1f
            } else {
                pill.animate().cancel()
                pill.setBackgroundResource(0)
                pill.alpha = 1f
                pill.scaleX = 1f
                pill.scaleY = 1f
                iv.imageTintList = ColorStateList.valueOf(inactiveColor)
                tv.setTextColor(inactiveColor)
                tv.alpha = 0.75f
            }
        }
    }

    fun handleMicOrOrbClick() {
        if (!StonicXConversationService.isSessionOn.value) {
            checkPermissionsAndStart()
        } else if (StonicXConversationService.conversationState.value == ConversationState.SPEAKING) {
            StonicXConversationService.interruptPlayback()
        } else {
            viewModel.toggleMicMute()
        }
    }

    fun handleHoldMicOrOrb() {
        binding.tabMic.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        if (StonicXConversationService.isSessionOn.value) {
            viewModel.turnOffSession()
        } else {
            checkPermissionsAndStart()
        }
    }

    fun handleTurnOff() {
        handleHoldMicOrOrb()
    }

    fun handlePowerClick() {
        handleHoldMicOrOrb()
    }

    private fun checkPermissionsAndStart() {
        if (!PermissionHelper.hasRecordAudioPermission(this)) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!PermissionHelper.hasNotificationPermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        if (!PermissionHelper.hasBatteryOptimizationPermission(this)) {
            showBatteryPermissionDialog()
            return
        }

        viewModel.toggleSession()
    }

    private fun showBatteryPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Unrestricted Battery Required")
            .setMessage("StonicX requires Unrestricted Battery access so it can stay active in the background and respond to wake words.\n\nPlease allow it or open Settings.")
            .setPositiveButton("ALLOW") { _, _ ->
                PermissionHelper.requestBatteryOptimization(this)
            }
            .setNeutralButton("OPEN SETTINGS") { _, _ ->
                selectTab(TAB_SETTINGS)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }


    /** Mic button in the dock; on the Camera tab it becomes the photo shutter. */
    private fun renderMicSlot(isMuted: Boolean, state: ConversationState, isOn: Boolean) {
        if (currentTab == TAB_CAMERA) {
            binding.ivTabMic.setImageResource(R.drawable.ic_camera_capture)
            binding.ivTabMic.imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
            binding.pillTabMic.setBackgroundResource(R.drawable.bg_dock_mic_highlight)
            binding.pillTabMic.animate().cancel()
            binding.pillTabMic.alpha = 1f
            binding.pillTabMic.scaleX = 1f
            binding.pillTabMic.scaleY = 1f
            return
        }
            val tvAccent = android.util.TypedValue()
            theme.resolveAttribute(R.attr.themeAccent, tvAccent, true)
            val accentColor = if (tvAccent.data != 0) tvAccent.data else ContextCompat.getColor(this, R.color.accent_sapphire)

            val tvMuted = android.util.TypedValue()
            theme.resolveAttribute(R.attr.themeTextMuted, tvMuted, true)
            val inactiveColor = if (tvMuted.data != 0) tvMuted.data else ContextCompat.getColor(this, R.color.text_subtle)

            if (!isOn) {
                binding.ivTabMic.setImageResource(R.drawable.ic_mic_stonicx)
                binding.ivTabMic.imageTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#94A3B8"))
                binding.tvTabMic.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                binding.pillTabMic.setBackgroundResource(R.drawable.bg_dock_mic_off)
                binding.pillTabMic.animate().cancel()
                binding.pillTabMic.alpha = 0.85f
                binding.pillTabMic.scaleX = 1f
                binding.pillTabMic.scaleY = 1f
            } else if (isMuted) {
                binding.ivTabMic.setImageResource(R.drawable.ic_mic_mute_stonicx)
                binding.ivTabMic.imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
                binding.tvTabMic.setTextColor(android.graphics.Color.WHITE)
                binding.pillTabMic.setBackgroundResource(R.drawable.bg_dock_mic_muted)
                binding.pillTabMic.animate().cancel()
                binding.pillTabMic.alpha = 1f
                binding.pillTabMic.scaleX = 1f
                binding.pillTabMic.scaleY = 1f
            } else {
                binding.ivTabMic.setImageResource(R.drawable.ic_mic_stonicx)
                binding.ivTabMic.imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
                binding.tvTabMic.setTextColor(android.graphics.Color.WHITE)
                binding.pillTabMic.setBackgroundResource(R.drawable.bg_dock_mic_highlight)
                binding.pillTabMic.alpha = 1f
                if (state == ConversationState.LISTENING || state == ConversationState.SPEAKING) {
                    binding.pillTabMic.animate()
                        .scaleX(1.08f)
                        .scaleY(1.08f)
                        .setDuration(300)
                        .start()
                } else {
                    binding.pillTabMic.animate().cancel()
                    binding.pillTabMic.scaleX = 1f
                    binding.pillTabMic.scaleY = 1f
                }
            }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.isMicMuted, viewModel.conversationState, viewModel.isSessionOn) { isMuted, state, isOn ->
                        Triple(isMuted, state, isOn)
                    }.collect { (isMuted, state, isOn) ->
                        renderMicSlot(isMuted, state, isOn)
                    }
                }

                launch {
                    viewModel.eventFlow.collect { message ->
                        activeSnackbar?.dismiss()
                        activeSnackbar = Snackbar.make(binding.rootMainLayout, message, Snackbar.LENGTH_SHORT).apply {
                            setAnchorView(binding.bottomDock)
                            show()
                        }
                    }
                }
            }
        }
    }
}
