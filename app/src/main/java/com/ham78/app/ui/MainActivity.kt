package com.ham78.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ham78.app.service.TalkService
import com.ham78.app.ui.theme.*

class MainActivity : ComponentActivity() {

    private var talkService: TalkService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TalkService.LocalBinder
            talkService = binder.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            talkService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MainScreenWrapper()
        }

        Intent(this, TalkService::class.java).also { intent ->
            startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null) {
            android.util.Log.d("MainActivity", "dispatchKeyEvent: keyCode=${event.keyCode} action=${event.action}")
        }
        val service = talkService
        if (service != null && event != null && service.handleKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Composable
    fun MainScreenWrapper() {
        val service = talkService

        if (service == null || !serviceBound) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Background),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = BrandPurple)
            }
            return
        }

        MainApp(
            talkService = service,
            onLogout = {
                stopService(Intent(this, TalkService::class.java))
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        )
    }
}
