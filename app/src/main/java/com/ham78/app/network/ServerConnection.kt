package com.ham78.app.network

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
        ConnectionState.DISCONNECTED -> "未连接"
    }

    val isOnline: Boolean get() = connectionState == ConnectionState.CONNECTED
}
