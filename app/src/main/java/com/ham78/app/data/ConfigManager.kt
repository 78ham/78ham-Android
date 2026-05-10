package com.ham78.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * 配置管理器
 * 支持从外部配置文件读取，方便 ADB 修改
 */
class ConfigManager(private val context: Context) {

    companion object {
        private const val TAG = "ConfigManager"
        private const val CONFIG_FILE_NAME = "ham78_config.json"
        private const val PREFS_NAME = "ham78_prefs"
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 获取配置（优先级：外部文件 > SharedPreferences > 默认值）
     */
    fun getSettings(): UserSettings {
        // 1. 尝试从外部配置文件读取
        val externalConfig = readExternalConfig()
        if (externalConfig != null) {
            Log.d(TAG, "使用外部配置文件")
            // 同时保存到 SharedPreferences
            saveToPrefs(externalConfig)
            return externalConfig
        }

        // 2. 从 SharedPreferences 读取
        val prefsSettings = readFromPrefs()
        if (prefsSettings != null) {
            Log.d(TAG, "使用 SharedPreferences 配置")
            return prefsSettings
        }

        // 3. 返回默认配置
        Log.d(TAG, "使用默认配置")
        return UserSettings()
    }

    /**
     * 保存配置到 SharedPreferences（通过 UI 修改时用）
     */
    fun saveSettings(settings: UserSettings) {
        saveToPrefs(settings)
        // 同时尝试写入外部文件（如果有权限）
        try {
            writeExternalConfig(settings)
        } catch (e: Exception) {
            Log.w(TAG, "写入外部配置失败: ${e.message}")
        }
    }

    /**
     * 从外部配置文件读取
     * 文件位置：/sdcard/Android/data/com.ham78.app/files/ham78_config.json
     * 或：/sdcard/ham78_config.json
     */
    private fun readExternalConfig(): UserSettings? {
        // 尝试多个位置
        val possiblePaths = listOf(
            File(context.getExternalFilesDir(null), CONFIG_FILE_NAME),
            File("/sdcard/$CONFIG_FILE_NAME"),
            File("/storage/emulated/0/$CONFIG_FILE_NAME"),
            File(context.filesDir, CONFIG_FILE_NAME)
        )

        for (file in possiblePaths) {
            if (file.exists()) {
                try {
                    val json = file.readText()
                    val settings = gson.fromJson(json, UserSettings::class.java)
                    Log.d(TAG, "从 ${file.absolutePath} 读取配置成功")
                    return settings
                } catch (e: Exception) {
                    Log.e(TAG, "读取配置文件失败 ${file.absolutePath}: ${e.message}")
                }
            }
        }
        return null
    }

    /**
     * 写入外部配置文件
     */
    private fun writeExternalConfig(settings: UserSettings) {
        val file = File(context.getExternalFilesDir(null), CONFIG_FILE_NAME)
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(settings))
        Log.d(TAG, "配置已写入 ${file.absolutePath}")
    }

    /**
     * 从 SharedPreferences 读取
     */
    private fun readFromPrefs(): UserSettings? {
        if (!prefs.contains("serverAddress")) {
            return null
        }
        return UserSettings(
            username = prefs.getString("username", "") ?: "",
            password = prefs.getString("password", "") ?: "",
            serverAddress = prefs.getString("serverAddress", "js.nrlptt.com") ?: "js.nrlptt.com",
            serverPort = prefs.getInt("serverPort", 60050),
            dmrId = prefs.getInt("dmrId", 0),
            callsign = prefs.getString("callsign", "") ?: "",
            ssid = prefs.getInt("ssid", 100),
            codec = if (prefs.getString("codec", "G711") == "OPUS") AudioCodec.OPUS else AudioCodec.G711,
            volume = prefs.getInt("volume", 100),
            screenOffPtt = prefs.getBoolean("screenOffPtt", true),
            pttKeyCode = prefs.getInt("pttKeyCode", android.view.KeyEvent.KEYCODE_VOLUME_UP),
            autoConnect = prefs.getBoolean("autoConnect", true)
        )
    }

    /**
     * 保存到 SharedPreferences
     */
    private fun saveToPrefs(settings: UserSettings) {
        prefs.edit().apply {
            putString("username", settings.username)
            putString("password", settings.password)
            putString("serverAddress", settings.serverAddress)
            putInt("serverPort", settings.serverPort)
            putInt("dmrId", settings.dmrId)
            putString("callsign", settings.callsign)
            putInt("ssid", settings.ssid)
            putString("codec", settings.codec.name)
            putInt("volume", settings.volume)
            putBoolean("screenOffPtt", settings.screenOffPtt)
            putInt("pttKeyCode", settings.pttKeyCode)
            putBoolean("autoConnect", settings.autoConnect)
            apply()
        }
    }

    /**
     * 导出配置模板到外部存储（方便用户修改）
     */
    fun exportConfigTemplate(): File? {
        return try {
            val file = File(context.getExternalFilesDir(null), CONFIG_FILE_NAME)
            file.parentFile?.mkdirs()
            val template = UserSettings() // 默认配置作为模板
            file.writeText(gson.toJson(template))
            Log.d(TAG, "配置模板已导出到 ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "导出配置模板失败: ${e.message}")
            null
        }
    }

    /**
     * 获取配置文件路径（用于显示给用户）
     */
    fun getConfigFilePath(): String {
        return File(context.getExternalFilesDir(null), CONFIG_FILE_NAME).absolutePath
    }
}

/**
 * 用户设置数据类
 */
data class UserSettings(
    val username: String = "",           // 账号（通过 ADB 或 UI 配置）
    val password: String = "",           // 密码
    val serverAddress: String = "js.nrlptt.com",
    val serverPort: Int = 60050,
    val dmrId: Int = 0,
    val callsign: String = "",
    val ssid: Int = 100,                 // 设备 SSID（呼号后缀，协议帧 offset 30）
    val codec: AudioCodec = AudioCodec.G711,
    val volume: Int = 100,
    val screenOffPtt: Boolean = true,
    val pttKeyCode: Int = android.view.KeyEvent.KEYCODE_VOLUME_UP,
    val autoConnect: Boolean = false     // 默认不自动连接，等配置好账号
)

enum class AudioCodec {
    G711, OPUS
}

data class DeviceStatus(
    val isOnline: Boolean = false,
    val lastHeartbeat: Long = 0,
    val batteryLevel: Int = 0,
    val temperature: Float = 0f,
    val voltage: Float = 0f,
    val currentGroup: Int = 0
)
