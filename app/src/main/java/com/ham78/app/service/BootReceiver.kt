package com.ham78.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ham78.app.data.SettingsRepository

/**
 * 开机启动接收器
 * 设备重启后自动启动对讲服务
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed, checking auto start settings")
            
            val settingsRepository = SettingsRepository(context)
            val settings = settingsRepository.loadSettings()
            
            if (settings.autoConnect && settings.dmrId != 0) {
                Log.d(TAG, "Auto starting TalkService")
                
                val serviceIntent = Intent(context, TalkService::class.java)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
