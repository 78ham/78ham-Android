package com.ham78.app.ui

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.ham78.app.ui.screens.SettingsScreen
import com.ham78.app.ui.theme.*

/**
 * 设置 Activity（独立入口，可从通知或其他地方打开）
 * 实际设置 UI 使用 SettingsScreen composable
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Background)
            ) {
                SettingsScreen()
            }
        }
    }
}
