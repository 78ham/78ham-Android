package com.ham78.app.data

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置管理仓库
 * 使用加密 SharedPreferences 安全存储用户配置
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    fun loadSettings(): UserSettings {
        val stored = prefs
        return UserSettings(
            username = stored.getString(KEY_USERNAME, "") ?: "",
            password = stored.getString(KEY_PASSWORD, "") ?: "",
            serverAddress = stored.getString(KEY_SERVER_ADDRESS, "js.nrlptt.com") ?: "js.nrlptt.com",
            serverPort = stored.getInt(KEY_SERVER_PORT, 60050),
            dmrId = stored.getInt(KEY_DMR_ID, 0),
            callsign = stored.getString(KEY_CALLSIGN, "") ?: "",
            ssid = stored.getInt(KEY_SSID, 179),
            codec = parseCodec(stored.getString(KEY_CODEC, null)),
            volume = stored.getInt(KEY_VOLUME, 100),
            gain = stored.getFloat(KEY_GAIN, 1.0f),
            screenOffPtt = stored.getBoolean(KEY_SCREEN_OFF_PTT, true),
            pttKeyCode = stored.getInt(KEY_PTT_KEY, KeyEvent.KEYCODE_VOLUME_UP),
            autoConnect = stored.getBoolean(KEY_AUTO_CONNECT, true),
            servers = loadServerList()
        )
    }

    fun saveSettings(settings: UserSettings) {
        prefs.edit().apply {
            putString(KEY_USERNAME, settings.username)
            putString(KEY_PASSWORD, settings.password)
            putString(KEY_SERVER_ADDRESS, settings.serverAddress)
            putInt(KEY_SERVER_PORT, settings.serverPort)
            putInt(KEY_DMR_ID, settings.dmrId)
            putString(KEY_CALLSIGN, settings.callsign)
            putInt(KEY_SSID, settings.ssid)
            putString(KEY_CODEC, settings.codec.name)
            putInt(KEY_VOLUME, settings.volume)
            putFloat(KEY_GAIN, settings.gain)
            putBoolean(KEY_SCREEN_OFF_PTT, settings.screenOffPtt)
            putInt(KEY_PTT_KEY, settings.pttKeyCode)
            putBoolean(KEY_AUTO_CONNECT, settings.autoConnect)
            putString(KEY_SERVERS, gson.toJson(settings.servers))
            apply()
        }
        _settings.value = settings
    }

    fun clearSettings() {
        prefs.edit().clear().apply()
        _settings.value = UserSettings.DEFAULT
    }

    fun loadServerList(): List<ServerConfig> {
        val json = prefs.getString(KEY_SERVERS, "[]") ?: "[]"
        return parseServerList(json)
    }

    fun saveServerList(servers: List<ServerConfig>) {
        prefs.edit().putString(KEY_SERVERS, gson.toJson(servers)).apply()
        _settings.value = _settings.value.copy(servers = servers)
    }

    fun addServer(server: ServerConfig) {
        val servers = loadServerList().toMutableList()
        servers.removeAll { it.id == server.id }
        servers.add(server)
        saveServerList(servers)
    }

    fun removeServer(serverId: String) {
        val servers = loadServerList().toMutableList()
        servers.removeAll { it.id == serverId }
        saveServerList(servers)
    }

    fun updateServer(server: ServerConfig) {
        val servers = loadServerList().toMutableList()
        val index = servers.indexOfFirst { it.id == server.id }
        if (index >= 0) {
            servers[index] = server
            saveServerList(servers)
        }
    }

    private fun parseCodec(value: String?): AudioCodec = try {
        value?.let { AudioCodec.valueOf(it) } ?: AudioCodec.G711
    } catch (_: IllegalArgumentException) {
        AudioCodec.G711
    }

    private fun parseServerList(json: String): List<ServerConfig> = try {
        val type = object : TypeToken<List<ServerConfig>>() {}.type
        gson.fromJson<List<ServerConfig>>(json, type) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        private const val PREFS_NAME = "ham78_settings"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SERVER_ADDRESS = "server_address"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_DMR_ID = "dmr_id"
        private const val KEY_CALLSIGN = "callsign"
        private const val KEY_SSID = "ssid"
        private const val KEY_CODEC = "codec"
        private const val KEY_VOLUME = "volume"
        private const val KEY_GAIN = "gain"
        private const val KEY_SCREEN_OFF_PTT = "screen_off_ptt"
        private const val KEY_PTT_KEY = "ptt_key"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_SERVERS = "servers"
    }
}
