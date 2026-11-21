package com.ham78.app.network

/** 群组轮询带回的一台在线设备 */
data class OnlineDevice(
    val callsign: String,
    val ssid: Int,
    val devModel: Int,
    val dmrId: Int = 0
)

/**
 * 某个服务器上正在说话的人。
 * 语音包一到就记上，这台服务器大约 1.2 秒没有新包就清掉。
 * 房间号是这台连接当时加入的房间：正式加入用 currentRoomId，只听用收听房间。
 * durationMs 按已经收到的 PCM 累计，大约每 200 毫秒刷新一次，语音条跟着变长。
 */
data class LiveSpeaker(
    val serverId: String,
    val roomId: Int,
    val callsign: String,
    val ssid: Int,
    val devModel: Int,
    val durationMs: Long = 0,
    val opus: Boolean = false,
    val dmrId: Int = 0,
    val mdcLabel: String = ""
)

/**
 * 单个服务器连接状态
 */
data class ServerConnection(
    val serverId: String = "",          // 唯一标识
    val name: String = "",              // 显示名称
    val serverHost: String = "",        // 服务器地址
    val serverPort: Int = 60050,        // 端口
    val userInfo: ApiClient.UserInfo? = null,
    val deviceData: ApiClient.DeviceData? = null,
    val currentRoomId: Int = 0,
    val currentGroupName: String = "",
    val onlineCount: Int = 0,
    val onlineDevices: List<OnlineDevice> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isLoggedIn: Boolean = false,
    val callsign: String = "",
    val ssid: Int = 179,
    val dmrId: Int = 0,
    val isActive: Boolean = false       // 是否为当前活跃服务器
) {
    val statusText: String get() = when (connectionState) {
        ConnectionState.CONNECTED -> "已连接"
        ConnectionState.CONNECTING -> "连接中..."
        ConnectionState.RECONNECTING -> "重连中..."
        ConnectionState.DISCONNECTED -> "未连接"
    }

    val isOnline: Boolean get() = connectionState == ConnectionState.CONNECTED
}
