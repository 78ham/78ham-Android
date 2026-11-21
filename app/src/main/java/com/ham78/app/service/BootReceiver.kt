package com.ham78.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ham78.app.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed, checking auto start settings")

        // 设置读取（加密 prefs 解密）在 IO 协程异步进行，onReceive 不能阻塞主线程；
        // goAsync 拿到约 10 秒窗口，等 ready 后再判断自启。
        // 直接 loadSettings() 会拿到"磁盘还没读完"的默认值，漏掉已配置用户的自启。
        val pending = goAsync()
        val appContext = context.applicationContext
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val repo = SettingsRepository(appContext)
                repo.ready.first { it }
                val settings = repo.loadSettings()
                // 用"有没有账号"判断是否自启；原来的 dmrId != 0 判断有漏洞：
                // dmrId 只在登录成功后写入，跳过登录直接进主界面的用户永远无法开机自启
                if (settings.autoConnect && settings.username.isNotEmpty()) {
                    Log.d(TAG, "Auto starting TalkService")
                    val serviceIntent = Intent(appContext, TalkService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appContext.startForegroundService(serviceIntent)
                    } else {
                        appContext.startService(serviceIntent)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto start check failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
