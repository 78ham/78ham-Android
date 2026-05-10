package com.ham78.app.ptt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PttController(private val context: Context) {
    
    companion object {
        private const val TAG = "PttController"
        private const val WAKE_LOCK_TAG = "78HAM:PttWakeLock"
        private const val WAKE_LOCK_TIMEOUT = 30000L
        
        const val KEYCODE_PTT = 0x106
        
        const val KEYCODE_D12_PTT = 0x107
        const val KEYCODE_D12_MENU = 0x108
        const val KEYCODE_D12_UP = 0x109
        const val KEYCODE_D12_DOWN = 0x10A
        const val KEYCODE_D12_OK = 0x10B
    }
    
    interface PttListener {
        fun onPttPressed()
        fun onPttReleased()
        fun onPttLongPress()
    }
    
    private var pttListener: PttListener? = null
    private var pttKeyCode: Int = KeyEvent.KEYCODE_VOLUME_UP
    private var screenOffPtt: Boolean = true
    
    private val _isPttPressed = MutableStateFlow(false)
    val isPttPressed: StateFlow<Boolean> = _isPttPressed.asStateFlow()
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private var wakeLock: PowerManager.WakeLock? = null
    
    private var mediaButtonReceiver: BroadcastReceiver? = null
    private var mtkPttReceiver: BroadcastReceiver? = null
    private var volumeChangeReceiver: BroadcastReceiver? = null
    
    private var pttPressTime = 0L
    private val LONG_PRESS_THRESHOLD = 1000L
    
    /**
     * 初始化 PTT 控制器
     */
    fun initialize(listener: PttListener, pttKey: Int, screenOffEnabled: Boolean) {
        pttListener = listener
        pttKeyCode = pttKey
        screenOffPtt = screenOffEnabled
        
        createWakeLock()
        registerReceivers()
    }
    
    /**
     * 设置PTT按键
     */
    fun setPttKeyCode(keyCode: Int) {
        pttKeyCode = keyCode
    }
    
    /**
     * 设置息屏启麦
     */
    fun setScreenOffPtt(enabled: Boolean) {
        screenOffPtt = enabled
        if (!enabled) {
            releaseWakeLock()
        }
    }
    
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (isPotentialPttKey(event.keyCode)) {
            Log.d(TAG, "KeyEvent: keyCode=${event.keyCode} action=${event.action} source=${event.source} deviceId=${event.deviceId} name=${KeyEvent.keyCodeToString(event.keyCode)}")
        } else {
            Log.v(TAG, "Non-PTT key: keyCode=${event.keyCode} action=${event.action} name=${KeyEvent.keyCodeToString(event.keyCode)}")
        }

        when (event.keyCode) {
            KEYCODE_PTT,               // 0x106
            KEYCODE_D12_PTT,           // 0x107
            113,                       // KEY_MUTE (D12 PTT)
            368,                       // KEY_HP (直接映射)
            270,                       // KEY_HP2
            531,                       // KEY_PTT_ON
            532,                       // KEY_PTT_OFF
            217,                       // KEY_ASSIST
            KeyEvent.KEYCODE_HEADSETHOOK, // 耳机线控
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_RECORD,
            KeyEvent.KEYCODE_FUNCTION,
            KeyEvent.KEYCODE_PROG_RED,
            KeyEvent.KEYCODE_BUTTON_1,
            KeyEvent.KEYCODE_BUTTON_2,
            KeyEvent.KEYCODE_BUTTON_3,
            KeyEvent.KEYCODE_BUTTON_4,
            KeyEvent.KEYCODE_BUTTON_5,
            KeyEvent.KEYCODE_BUTTON_6,
            KeyEvent.KEYCODE_BUTTON_7,
            KeyEvent.KEYCODE_BUTTON_8,
            KeyEvent.KEYCODE_BUTTON_9,
            KeyEvent.KEYCODE_BUTTON_10,
            KeyEvent.KEYCODE_BUTTON_11,
            KeyEvent.KEYCODE_BUTTON_12,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_C,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_Z,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                handlePttEvent(event.action)
                return true
            }
        }

        if (event.keyCode == pttKeyCode) {
            handlePttEvent(event.action)
            return true
        }

        return false
    }

    private fun isPotentialPttKey(keyCode: Int): Boolean {
        return keyCode == 23 ||
            keyCode == KEYCODE_PTT ||
            keyCode == KEYCODE_D12_PTT ||
            keyCode == 368 ||
            keyCode == 270 ||
            keyCode == 531 ||
            keyCode == 532 ||
            keyCode == 217 ||
            keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_RECORD ||
            keyCode == KeyEvent.KEYCODE_FUNCTION ||
            keyCode == KeyEvent.KEYCODE_PROG_RED ||
            (keyCode in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16) ||
            keyCode == pttKeyCode
    }
    
    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate failed", e)
        }
    }

    private fun handlePttEvent(action: Int) {
        when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (!_isPttPressed.value) {
                    pttPressTime = System.currentTimeMillis()
                    _isPttPressed.value = true
                    acquireWakeLock()
                    vibrate()
                    pttListener?.onPttPressed()
                    Log.d(TAG, "PTT pressed")
                }
            }
            KeyEvent.ACTION_UP -> {
                if (_isPttPressed.value) {
                    val pressDuration = System.currentTimeMillis() - pttPressTime
                    if (pressDuration >= LONG_PRESS_THRESHOLD) {
                        pttListener?.onPttLongPress()
                        Log.d(TAG, "PTT long press (${pressDuration}ms)")
                    }
                    _isPttPressed.value = false
                    pttListener?.onPttReleased()
                    releaseWakeLock()
                    Log.d(TAG, "PTT released")
                }
            }
        }
    }
    
    /**
     * 手动按下PTT（屏幕按钮）
     */
    fun pressPtt() {
        if (!_isPttPressed.value) {
            _isPttPressed.value = true
            acquireWakeLock()
            pttListener?.onPttPressed()
        }
    }
    
    /**
     * 手动释放PTT（屏幕按钮）
     */
    fun releasePtt() {
        if (_isPttPressed.value) {
            _isPttPressed.value = false
            pttListener?.onPttReleased()
            releaseWakeLock()
        }
    }
    
    /**
     * 创建唤醒锁
     */
    private fun createWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                WAKE_LOCK_TAG
            )
        }
    }
    
    /**
     * 获取唤醒锁（息屏时保持CPU运行）
     */
    private fun acquireWakeLock() {
        if (screenOffPtt && wakeLock?.isHeld == false) {
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT)
            Log.d(TAG, "Wake lock acquired")
        }
    }
    
    /**
     * 释放唤醒锁
     */
    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "Wake lock released")
        }
    }
    
    /**
     * 注册广播接收器
     */
    private fun registerReceivers() {
        // 媒体按钮接收器（耳机PTT键）
        mediaButtonReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
                    val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    event?.let { onKeyEvent(it) }
                }
            }
        }
        
        // 音量变化接收器（某些对讲机通过音量键作为PTT）
        volumeChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // 检测特定的PTT音量键组合
            }
        }
        
        try {
            context.registerReceiver(mediaButtonReceiver, IntentFilter(Intent.ACTION_MEDIA_BUTTON))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register media button receiver", e)
        }

        // MTK ROM PTT 广播接收器（PhoneWindowManager.interceptPTTKey 发出）
        mtkPttReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "android.intent.action.PTT.down" -> {
                        Log.d(TAG, "MTK PTT down")
                        handlePttEvent(KeyEvent.ACTION_DOWN)
                    }
                    "android.intent.action.PTT.up" -> {
                        Log.d(TAG, "MTK PTT up")
                        handlePttEvent(KeyEvent.ACTION_UP)
                    }
                }
            }
        }

        try {
            val pttFilter = IntentFilter().apply {
                addAction("android.intent.action.PTT.down")
                addAction("android.intent.action.PTT.up")
            }
            context.registerReceiver(mtkPttReceiver, pttFilter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register MTK PTT receiver", e)
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        releaseWakeLock()
        
        try {
            mediaButtonReceiver?.let { context.unregisterReceiver(it) }
            mtkPttReceiver?.let { context.unregisterReceiver(it) }
            volumeChangeReceiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers", e)
        }

        mediaButtonReceiver = null
        mtkPttReceiver = null
        volumeChangeReceiver = null
        pttListener = null
    }
}
