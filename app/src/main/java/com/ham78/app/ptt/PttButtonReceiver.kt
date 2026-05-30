package com.ham78.app.ptt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

class PttButtonReceiver : BroadcastReceiver() {

    companion object {
        @JvmStatic
        var listener: PttButtonListener? = null
            @Synchronized set
    }

    interface PttButtonListener {
        fun onPttButtonPressed()
        fun onPttButtonReleased()
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return

        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> listener?.onPttButtonPressed()
                    KeyEvent.ACTION_UP -> listener?.onPttButtonReleased()
                }
            }
        }
    }
}
