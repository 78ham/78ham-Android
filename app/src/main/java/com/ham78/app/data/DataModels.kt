package com.ham78.app.data

/**
 * 用户设置数据类
 */
data class UserSettings(
    val username: String = "",
    val password: String = "",
    val serverAddress: String = "js.nrlptt.com",
    val serverPort: Int = 60050,
    val dmrId: Int = 0,
    val callsign: String = "",
    val ssid: Int = 179,
    val codec: AudioCodec = AudioCodec.G711,
    val volume: Int = 100,
    val gain: Float = 1.0f,
    val screenOffPtt: Boolean = true,
    val pttKeyCode: Int = android.view.KeyEvent.KEYCODE_VOLUME_UP,
    val autoConnect: Boolean = false,
    val servers: List<ServerConfig> = emptyList()
) {
    companion object {
        val DEFAULT = UserSettings()
    }
}

/**
 * 服务器配置数据类
 */
data class ServerConfig(
    val id: String = "",
    val name: String = "",
    val host: String = "js.nrlptt.com",
    val port: Int = 60050,
    val username: String = "",
    val password: String = "",
    val autoConnect: Boolean = false
) {
    val displayName: String
        get() = name.ifEmpty { host }
}

/**
 * 音频编码格式枚举
 */
enum class AudioCodec {
    G711, OPUS
}
