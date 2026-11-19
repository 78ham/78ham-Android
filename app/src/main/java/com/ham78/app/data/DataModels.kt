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
    val servers: List<ServerConfig> = emptyList(),
    val rogerBeep: RogerBeepStyle = RogerBeepStyle.NONE,
    val broadcastSchedules: List<BroadcastSchedule> = emptyList()
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

/**
 * 尾音（Roger Beep）样式：松开 PTT 后自动追加的结束提示音。
 * 业余无线电通联习惯：一头讲完发一声短促提示音，告知对方"我说完了"。
 */
enum class RogerBeepStyle(val label: String) {
    NONE("关闭"),
    CLASSIC("经典 (1000Hz 单音)"),          // 250ms 1000Hz 正弦，最常见
    K("K 字 (─ · ─)"),                     // 摩尔斯 K：对讲机"over"的谐音
    TWO_TONE("双音 (1500→500Hz)"),         // 滑降双音，MDC 风格
    QUINDAR("太空腔 (2525Hz 短音)")        // NASA Quindar tone，2500Hz 附近
}

/**
 * 一条定时播报任务。
 *
 * mode 决定 hour/minute 的含义：
 * - EVERY_MINUTE / EVERY_5_MIN / EVERY_15_MIN：hour/minute 无意义，固定为 0
 * - DAILY：每天的 hour:minute 整点触发一次
 *
 * enabled 由 UI 开关控制； TalkService 的调度协程每分钟对表触发。
 * content 是要播报的文本（TTS），播完自动追加当前尾音。
 */
data class BroadcastSchedule(
    val id: String = "",
    val name: String = "",
    val mode: ScheduleMode = ScheduleMode.DAILY,
    val hour: Int = 12,
    val minute: Int = 0,
    val content: String = "",
    val enabled: Boolean = true
) {
    /** 下次对表匹配用的分钟序号（0..1439），EVERY_* 模式在 intervalMinutes() 里单独判断 */
    fun dailyMinuteOfDay(): Int = hour * 60 + minute
}

enum class ScheduleMode(val label: String) {
    EVERY_MINUTE("每分钟"),
    EVERY_5_MIN("每 5 分钟"),
    EVERY_15_MIN("每 15 分钟"),
    DAILY("每天定时")
}

