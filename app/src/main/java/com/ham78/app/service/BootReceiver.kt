package com.ham78.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ham78.app.data.SettingsRepository

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed, checking auto start settings")

        val settings = SettingsRepository(context).loadSettings()
        // 用"有没有账号"判断是否自启；原来的 dmrId != 0 判断有漏洞：
        // dmrId 只在登录成功后写入，跳过登录直接进主界面的用户永远无法开机自启
        if (settings.autoConnect && settings.username.isNotEmpty()) {
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
