package com.stonicx.assistant.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.stonicx.assistant.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        com.stonicx.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<android.view.View>(R.id.settingsContainer)?.let {
            com.stonicx.assistant.util.ThemeManager.applyDynamicBackground(it)
        }
    }
}
