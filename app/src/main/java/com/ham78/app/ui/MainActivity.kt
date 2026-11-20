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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ham78.app.HamApplication
import com.ham78.app.service.TalkService
import com.ham78.app.ui.theme.Background
import com.ham78.app.ui.theme.BrandPurple

class MainActivity : ComponentActivity() {

    private var talkService by mutableStateOf<TalkService?>(null)
    private var serviceBound by mutableStateOf(false)

    // 掉后台期间最后可见时间：回前台时用于判断 UDP 心跳是否已经断了很久
    private var lastVisibleAt = 0L

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
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // 对讲服务必须常驻：未在电池优化白名单时弹一次系统确认框（用户可拒绝）
        val app = application as? HamApplication
        if (app != null && !app.isIgnoringBatteryOptimizations()) {
            // 延后 1 秒弹：等界面先渲染完成，避免和首帧绘制抢焦点
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                app.requestIgnoreBatteryOptimizations()
            }, 1000)
        }
    }

    override fun onStart() {
        super.onStart()
        lastVisibleAt = System.currentTimeMillis()
        // 回前台：多服务器 5 秒定时刷新协程一直在跑，UI 状态会自己跟上；
        // 这里只需要把 UDP 心跳的新鲜度交给它——服务里 15 秒无包会自动重连
    }

    override fun onStop() {
        super.onStop()
        lastVisibleAt = System.currentTimeMillis()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        android.util.Log.d("MainActivity", "dispatchKeyEvent: keyCode=${event.keyCode} action=${event.action}")
        val service = talkService
        if (service != null && service.handleKeyEvent(event)) {
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
