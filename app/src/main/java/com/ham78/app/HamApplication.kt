package com.ham78.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

class HamApplication : Application() {

    companion object {
        private const val TAG = "HamApplication"
        @Volatile
        lateinit var instance: HamApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "78HAM Application started")
    }

    /**
     * 是否已在电池优化白名单里。
     * 对讲服务靠 PARTIAL_WAKE_LOCK + 2 秒 UDP 心跳保活，掉白名单的机器
     * 熄屏几分钟 Doze 就把 socket 掐了（"到后台会掉"的主因）。
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** 跳出系统"忽略电池优化"确认框；用户取消也无妨，下次启动会再问 */
    fun requestIgnoreBatteryOptimizations() {
        if (isIgnoringBatteryOptimizations()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        } catch (e: Exception) {
            // 个别 ROM 不支持该 action：退到设置列表页让用户手动加
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Log.w(TAG, "Battery optimization request failed", e2)
            }
        }
    }
}
