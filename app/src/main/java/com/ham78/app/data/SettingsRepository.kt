package com.ham78.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.KeyEvent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
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
        val masterKey = MasterKey.Builder(appContext)
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

    init {
        // EncryptedSharedPreferences 创建（MasterKey 生成 + 解密）耗时可达数百 ms，
        // 构造函数可能在主线程（LoginActivity / TalkService / BootReceiver），
        // 所以"建 prefs + 读磁盘"全部放 IO 协程：构造立即返回，UI 先看到共享 flow 的当前值，
        // 磁盘读完后更新 flow，观察者自动拿到真实数据。
        // 首个实例负责异步加载；后续实例共享同一份 flow，任意实例 saveSettings
        // 后所有观察者（UI/Service）都能收到更新。
        synchronized(SettingsRepository::class.java) {
            if (!globalFlowInitialized) {
                globalFlowInitialized = true
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        val loaded = readSettingsFromDisk()
                        // 异步读盘期间用户可能已保存过设置（读前 flow 还是默认值）：
                        // 已被覆盖就不回写磁盘旧值，否则刚保存的设置会被"回退"
                        synchronized(SettingsRepository::class.java) {
                            if (_sharedSettings.value == UserSettings.DEFAULT) {
                                _sharedSettings.value = loaded
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SettingsRepository", "Failed to read settings from disk", e)
                    } finally {
                        // 读失败也放行等待者（BootReceiver 自动连接等），否则永远等不到
                        _ready.value = true
                    }
                }
            }
        }
    }

    private val _settings get() = _sharedSettings
    val settings: StateFlow<UserSettings> = _sharedSettings.asStateFlow()

    /** 磁盘设置是否已读入共享 flow；主线程入口（自启/自动连接/登录跳转）据此等待 */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * 读"当前已知"的设置：flow 里已有磁盘数据就用它，还没读完就用默认值。
     * 主线程入口（BootReceiver/LoginActivity 的启动分支）用它替代直接读盘，
     * 不阻塞；需要"确定读盘后"最新值的调用方观察 settings flow。
     */
    fun loadSettings(): UserSettings = _sharedSettings.value

    /** 从磁盘读全部设置（含解密开销），只在 IO 线程调用；结果由 init 写进共享 flow */
    private fun readSettingsFromDisk(): UserSettings {
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
            servers = loadServerList(),
            rogerBeep = parseRogerBeep(stored.getString(KEY_ROGER_BEEP, null)),
            broadcastSchedules = loadSchedules()
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
            putString(KEY_ROGER_BEEP, settings.rogerBeep.name)
            putString(KEY_SCHEDULES, gson.toJson(settings.broadcastSchedules))
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

    private fun parseRogerBeep(value: String?): RogerBeepStyle = try {
        value?.let { RogerBeepStyle.valueOf(it) } ?: RogerBeepStyle.NONE
    } catch (_: IllegalArgumentException) {
        RogerBeepStyle.NONE
    }

    // --- 定时播报任务的增删改查：和服务器列表同一套 Gson 持久化套路 ---

    fun loadSchedules(): List<BroadcastSchedule> {
        val json = prefs.getString(KEY_SCHEDULES, "[]") ?: "[]"
        return parseSchedules(json)
    }

    fun saveSchedules(schedules: List<BroadcastSchedule>) {
        prefs.edit().putString(KEY_SCHEDULES, gson.toJson(schedules)).apply()
        _settings.value = _settings.value.copy(broadcastSchedules = schedules)
    }

    fun addSchedule(schedule: BroadcastSchedule) {
        val schedules = loadSchedules().toMutableList()
        schedules.removeAll { it.id == schedule.id }
        schedules.add(schedule)
        saveSchedules(schedules)
    }

    fun removeSchedule(scheduleId: String) {
        saveSchedules(loadSchedules().filter { it.id != scheduleId })
    }

    fun updateSchedule(schedule: BroadcastSchedule) {
        addSchedule(schedule)   // addSchedule 已按 id 去重，等价于更新
    }

    private fun parseSchedules(json: String): List<BroadcastSchedule> = try {
        val type = object : TypeToken<List<BroadcastSchedule>>() {}.type
        gson.fromJson<List<BroadcastSchedule>>(json, type) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
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
        private const val KEY_ROGER_BEEP = "roger_beep"
        private const val KEY_SCHEDULES = "broadcast_schedules"

        // 所有 SettingsRepository 实例共享的设置流
        private val _sharedSettings = MutableStateFlow(UserSettings.DEFAULT)
        private val _ready = MutableStateFlow(false)
        @Volatile
        private var globalFlowInitialized = false
    }
}
