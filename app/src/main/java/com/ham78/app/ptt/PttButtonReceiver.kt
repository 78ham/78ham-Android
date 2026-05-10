package com.ham78.app.ptt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent

/**
 * PTT 按键广播接收器
 * 用于接收系统媒体按钮事件（耳机PTT键）
 */
class PttButtonReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "PttButtonReceiver"
        var listener: PttButtonListener? = null
    }
    
    interface PttButtonListener {
        fun onPttButtonPressed()
        fun onPttButtonReleased()
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
        
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        event?.let { keyEvent ->
            Log.d(TAG, "Media button event: ${keyEvent.keyCode}, action: ${keyEvent.action}")
            
            when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    when (keyEvent.action) {
                        KeyEvent.ACTION_DOWN -> listener?.onPttButtonPressed()
                        KeyEvent.ACTION_UP -> listener?.onPttButtonReleased()
                    }
                }
            }
        }
    }
}
