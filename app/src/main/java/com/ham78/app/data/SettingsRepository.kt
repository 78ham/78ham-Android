package com.ham78.app.data

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()
    
    fun loadSettings(): UserSettings {
        return UserSettings(
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            password = prefs.getString(KEY_PASSWORD, "") ?: "",
            serverAddress = prefs.getString(KEY_SERVER_ADDRESS, "js.nrlptt.com") ?: "js.nrlptt.com",
            serverPort = prefs.getInt(KEY_SERVER_PORT, 60050),
            dmrId = prefs.getInt(KEY_DMR_ID, 0),
            callsign = prefs.getString(KEY_CALLSIGN, "") ?: "",
            ssid = prefs.getInt(KEY_SSID, 78),
            codec = AudioCodec.valueOf(prefs.getString(KEY_CODEC, AudioCodec.G711.name) ?: AudioCodec.G711.name),
            volume = prefs.getInt(KEY_VOLUME, 100),
            screenOffPtt = prefs.getBoolean(KEY_SCREEN_OFF_PTT, true),
            pttKeyCode = prefs.getInt(KEY_PTT_KEY, KeyEvent.KEYCODE_VOLUME_UP),
            autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, true)
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
            putBoolean(KEY_SCREEN_OFF_PTT, settings.screenOffPtt)
            putInt(KEY_PTT_KEY, settings.pttKeyCode)
            putBoolean(KEY_AUTO_CONNECT, settings.autoConnect)
            apply()
        }
        _settings.value = settings
    }
    
    fun clearSettings() {
        prefs.edit().clear().apply()
        _settings.value = UserSettings()
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
        private const val KEY_SCREEN_OFF_PTT = "screen_off_ptt"
        private const val KEY_PTT_KEY = "ptt_key"
        private const val KEY_AUTO_CONNECT = "auto_connect"
    }
}
