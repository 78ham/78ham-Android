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
        private const val LONG_PRESS_THRESHOLD = 1000L

        const val KEYCODE_PTT = 0x106
        const val KEYCODE_D12_PTT = 0x107
        const val KEYCODE_D12_MENU = 0x108
        const val KEYCODE_D12_UP = 0x109
        const val KEYCODE_D12_DOWN = 0x10A
        const val KEYCODE_D12_OK = 0x10B

        private val PTT_KEYCODES = setOf(
            KEYCODE_PTT,
            KEYCODE_D12_PTT,
            113, 368, 270, 531, 532, 217,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_RECORD,
            KeyEvent.KEYCODE_FUNCTION,
            KeyEvent.KEYCODE_PROG_RED,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_C,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_Z,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR
        )

        private val BUTTON_RANGE = KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16
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
    private var isReleased = false

    private var pttPressTime = 0L

    fun initialize(listener: PttListener, pttKey: Int, screenOffEnabled: Boolean) {
        pttListener = listener
        pttKeyCode = pttKey
        screenOffPtt = screenOffEnabled
        createWakeLock()
        registerReceivers()
    }

    fun setPttKeyCode(keyCode: Int) { pttKeyCode = keyCode }
    fun setScreenOffPtt(enabled: Boolean) {
        screenOffPtt = enabled
        if (!enabled) releaseWakeLock()
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in PTT_KEYCODES || event.keyCode in BUTTON_RANGE || event.keyCode == pttKeyCode) {
            if (isPotentialPttKey(event.keyCode)) {
                Log.d(TAG, "KeyEvent: keyCode=${event.keyCode} action=${event.action} source=${event.source}")
            }
            handlePttEvent(event.action)
            return true
        }
        return false
    }

    private fun isPotentialPttKey(keyCode: Int): Boolean =
        keyCode in PTT_KEYCODES || keyCode in BUTTON_RANGE || keyCode == pttKeyCode

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (_: Exception) { }
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
                }
            }
            KeyEvent.ACTION_UP -> {
                if (_isPttPressed.value) {
                    val pressDuration = System.currentTimeMillis() - pttPressTime
                    if (pressDuration >= LONG_PRESS_THRESHOLD) {
                        pttListener?.onPttLongPress()
                    }
                    _isPttPressed.value = false
                    pttListener?.onPttReleased()
                    releaseWakeLock()
                }
            }
        }
    }

    fun pressPtt() {
        if (!_isPttPressed.value) {
            _isPttPressed.value = true
            acquireWakeLock()
            pttListener?.onPttPressed()
        }
    }

    fun releasePtt() {
        if (_isPttPressed.value) {
            _isPttPressed.value = false
            pttListener?.onPttReleased()
            releaseWakeLock()
        }
    }

    private fun createWakeLock() {
        if (wakeLock == null && !isReleased) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                WAKE_LOCK_TAG
            ).apply { setReferenceCounted(false) }
        }
    }

    private fun acquireWakeLock() {
        if (screenOffPtt && !isReleased && wakeLock?.isHeld == false) {
            try { wakeLock?.acquire(WAKE_LOCK_TIMEOUT) } catch (_: Exception) { }
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            try { wakeLock?.release() } catch (_: Exception) { }
        }
    }

    private fun registerReceivers() {
        mediaButtonReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
                    intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        ?.let { onKeyEvent(it) }
                }
            }
        }

        volumeChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { }
        }

        try {
            context.registerReceiver(mediaButtonReceiver, IntentFilter(Intent.ACTION_MEDIA_BUTTON))
        } catch (_: Exception) { }

        mtkPttReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "android.intent.action.PTT.down" -> handlePttEvent(KeyEvent.ACTION_DOWN)
                    "android.intent.action.PTT.up" -> handlePttEvent(KeyEvent.ACTION_UP)
                }
            }
        }

        try {
            val pttFilter = IntentFilter().apply {
                addAction("android.intent.action.PTT.down")
                addAction("android.intent.action.PTT.up")
            }
            context.registerReceiver(mtkPttReceiver, pttFilter)
        } catch (_: Exception) { }
    }

    fun release() {
        isReleased = true
        releaseWakeLock()
        wakeLock = null

        listOfNotNull(mediaButtonReceiver, mtkPttReceiver, volumeChangeReceiver).forEach {
            try { context.unregisterReceiver(it) } catch (_: Exception) { }
        }

        mediaButtonReceiver = null
        mtkPttReceiver = null
        volumeChangeReceiver = null
        pttListener = null
    }
}
