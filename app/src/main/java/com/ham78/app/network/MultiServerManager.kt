package com.ham78.app.network

import android.util.Log
import com.ham78.app.data.ServerConfig
import com.ham78.app.audio.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 多服务器连接管理器
 * 支持同时连接多个服务器，管理活跃服务器切换
 */
class MultiServerManager(private val audioManagerFactory: (UdpClient) -> AudioManager) {

    companion object {
        private const val TAG = "MultiServerManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 每个服务器的连接资源
    data class ServerResources(
        val udpClient: UdpClient,
        val audioManager: AudioManager,
        var refreshJob: Job? = null,
        var loginToken: String = "",
        var userInfo: ApiClient.UserInfo? = null,
        var deviceData: ApiClient.DeviceData? = null
    )

    private val connections = mutableMapOf<String, ServerResources>()
    private val connectionStates = mutableMapOf<String, ServerConnection>()

    private val _serverConnections = MutableStateFlow<List<ServerConnection>>(emptyList())
    val serverConnections: StateFlow<List<ServerConnection>> = _serverConnections.asStateFlow()

    private val _activeServerId = MutableStateFlow("")
    val activeServerId: StateFlow<String> = _activeServerId.asStateFlow()

    // 文本消息回调
    var onTextMessageReceived: ((serverId: String, callsign: String, ssid: Int, content: String, timestamp: String) -> Unit)? = null
    // 语音消息回调
    var onVoiceReceived: ((serverId: String, callsign: String, ssid: Int) -> Unit)? = null

    fun getActiveConnection(): ServerResources? {
        val activeId = _activeServerId.value
        return if (activeId.isNotEmpty()) connections[activeId] else null
    }

    fun getActiveServerConnection(): ServerConnection? {
        val activeId = _activeServerId.value
        return if (activeId.isNotEmpty()) connectionStates[activeId] else null
    }

    fun getConnection(serverId: String): ServerResources? = connections[serverId]

    /**
     * 连接到服务器
     */
    suspend fun connectToServer(config: ServerConfig): Boolean {
        val serverId = config.id.ifEmpty { "${config.host}:${config.port}" }

        // 如果已连接，先断开
        if (connections.containsKey(serverId)) {
            disconnectFromServer(serverId)
        }

        Log.d(TAG, "Connecting to server: ${config.name} (${config.host}:${config.port})")

        // 创建 UDP 客户端
        val udpClient = UdpClient()
        val audioManager = audioManagerFactory(udpClient)

        val resources = ServerResources(
            udpClient = udpClient,
            audioManager = audioManager
        )

        // 设置网络监听
        setupPacketListener(serverId, resources)

        connections[serverId] = resources

        updateState(serverId) {
            it.copy(
                serverId = serverId,
                name = config.name.ifEmpty { config.host },
                serverHost = config.host,
                serverPort = config.port,
                connectionState = ConnectionState.CONNECTING
            )
        }

        // 登录
        val loginResult = ApiClient.login(config.host, config.username, config.password)
        loginResult.fold(
            onSuccess = { userInfo ->
                resources.loginToken = ApiClient.token
                resources.userInfo = userInfo

                updateState(serverId) {
                    it.copy(
                        isLoggedIn = true,
                        callsign = userInfo.callsign,
                        dmrId = userInfo.dmrId,
                        connectionState = ConnectionState.CONNECTING
                    )
                }

                // 获取设备信息
                try {
                    val deviceResult = ApiClient.getDevice(config.host, userInfo.callsign, 179)
                    deviceResult.getOrNull()?.let { device ->
                        resources.deviceData = device
                        updateState(serverId) {
                            it.copy(
                                deviceData = device,
                                ssid = device.ssid
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load device data: ${e.message}")
                }

                // 连接 UDP
                val serverHost = userInfo.server ?: config.host
                val port = userInfo.serverPort ?: config.port
                val device = resources.deviceData
                val ssid = if (device?.ssid != null && device.ssid != 0) device.ssid else 179
                val devModel = device?.devModel ?: 101
                val dmrId = device?.dmrId ?: userInfo.dmrId

                val success = udpClient.connect(
                    serverHost = serverHost,
                    port = port,
                    id = dmrId,
                    call = userInfo.callsign,
                    ssidVal = ssid,
                    devModelVal = devModel
                )

                if (success) {
                    updateState(serverId) {
                        it.copy(connectionState = ConnectionState.CONNECTED)
                    }
                    // 如果没有活跃服务器，设为活跃
                    if (_activeServerId.value.isEmpty()) {
                        switchActiveServer(serverId)
                    }
                    startRefreshData(serverId, resources, config.host, userInfo)
                    emitState()
                    return@fold true
                } else {
                    updateState(serverId) {
                        it.copy(connectionState = ConnectionState.DISCONNECTED)
                    }
                    emitState()
                    return@fold false
                }
            },
            onFailure = { error ->
                Log.e(TAG, "Login failed for ${config.host}: ${error.message}")
                updateState(serverId) {
                    it.copy(connectionState = ConnectionState.DISCONNECTED)
                }
                emitState()
                return false
            }
        )

        return true
    }

    /**
     * 断开服务器
     */
    fun disconnectFromServer(serverId: String) {
        val resources = connections[serverId] ?: return
        resources.refreshJob?.cancel()
        resources.udpClient.disconnect()
        resources.audioManager.release()
        connections.remove(serverId)
        connectionStates.remove(serverId)

        if (_activeServerId.value == serverId) {
            // 切换到另一个已连接的服务器
            val nextActive = connections.keys.firstOrNull()
            _activeServerId.value = nextActive ?: ""
        }

        emitState()
        Log.d(TAG, "Disconnected from server: $serverId")
    }

    /**
     * 切换活跃服务器
     */
    fun switchActiveServer(serverId: String) {
        // 停止旧活跃服务器的音频播放
        val oldActive = _activeServerId.value
        if (oldActive.isNotEmpty()) {
            connections[oldActive]?.audioManager?.stopReceiving()
        }

        _activeServerId.value = serverId

        // 更新所有连接的 isActive 状态
        connectionStates.keys.forEach { id ->
            connectionStates[id] = connectionStates[id]?.copy(isActive = id == serverId)
        }

        emitState()
        Log.d(TAG, "Switched active server to: $serverId")
    }

    /**
     * 发送语音
     */
    fun sendAudioData(audioData: ByteArray, isOpus: Boolean = false) {
        val activeId = _activeServerId.value
        if (activeId.isEmpty()) return
        connections[activeId]?.udpClient?.sendAudioData(audioData, isOpus)
    }

    /**
     * 发送文本消息
     */
    fun sendTextMessage(serverId: String, callsign: String, text: String, ssid: Int = 179, dmrId: Int = 0) {
        val resources = connections[serverId] ?: return
        val packet = Nrl21Protocol.createTextPacket(callsign, text, "text", ssid, 101, dmrId)
        resources.udpClient.sendPacket(packet)
    }

    /**
     * 发送位置
     */
    fun sendLocation(serverId: String, callsign: String, latitude: Double, longitude: Double, ssid: Int = 179, dmrId: Int = 0) {
        val resources = connections[serverId] ?: return
        val packet = Nrl21Protocol.createLocationPacket(callsign, latitude, longitude, ssid, 101, dmrId)
        resources.udpClient.sendPacket(packet)
    }

    /**
     * 加入频道
     */
    fun joinRoom(serverId: String, roomId: Int) {
        val resources = connections[serverId] ?: return
        resources.udpClient.sendJoinRoom(roomId)
        updateState(serverId) { it.copy(currentRoomId = roomId) }
        emitState()
    }

    /**
     * 加载频道列表
     */
    suspend fun loadRoomList(serverId: String): List<ApiClient.RoomInfo> {
        val state = connectionStates[serverId] ?: return emptyList()
        return try {
            val result = ApiClient.getRoomList(state.serverHost)
            result.getOrNull() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load room list: ${e.message}")
            emptyList()
        }
    }

    /**
     * 开始发射
     */
    fun startTransmitting(): Boolean {
        val activeId = _activeServerId.value
        if (activeId.isEmpty()) return false
        val resources = connections[activeId] ?: return false
        if (connectionStates[activeId]?.connectionState != ConnectionState.CONNECTED) return false
        return resources.audioManager.startTransmitting()
    }

    /**
     * 停止发射
     */
    fun stopTransmitting() {
        val activeId = _activeServerId.value
        if (activeId.isEmpty()) return
        connections[activeId]?.audioManager?.stopTransmitting()
    }

    fun isTransmitting(): Boolean {
        val activeId = _activeServerId.value
        return connections[activeId]?.audioManager?.isTransmitting?.value ?: false
    }

    fun isReceiving(): Boolean {
        val activeId = _activeServerId.value
        return connections[activeId]?.audioManager?.isReceiving?.value ?: false
    }

    fun release() {
        connections.keys.toList().forEach { disconnectFromServer(it) }
        scope.cancel()
    }

    private fun setupPacketListener(serverId: String, resources: ServerResources) {
        resources.udpClient.packetListener = object : UdpClient.PacketListener {
            override fun onPacketReceived(packet: Nrl21Protocol.Packet) {
                when (packet.type) {
                    Nrl21Protocol.TYPE_VOICE, Nrl21Protocol.TYPE_OPUS -> {
                        // 只播放活跃服务器的音频
                        if (serverId == _activeServerId.value) {
                            resources.audioManager.handleReceivedAudio(packet.data, packet.type, packet.callSign)
                        }
                        updateState(serverId) { it }
                        onVoiceReceived?.invoke(serverId, packet.callSign, packet.ssid)
                    }
                    Nrl21Protocol.TYPE_TEXT -> {
                        val textContent = Nrl21Protocol.TextContent.parse(packet.data)
                        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date())
                        onTextMessageReceived?.invoke(serverId, packet.callSign, packet.ssid, textContent.body, timestamp)
                    }
                }
            }

            override fun onError(error: String) {
                Log.e(TAG, "Server $serverId error: $error")
            }

            override fun onConnectionLost() {
                Log.w(TAG, "Server $serverId connection lost")
                updateState(serverId) { it.copy(connectionState = ConnectionState.DISCONNECTED) }
                emitState()
            }
        }
    }

    private fun startRefreshData(serverId: String, resources: ServerResources, serverHost: String, userInfo: ApiClient.UserInfo) {
        resources.refreshJob?.cancel()
        resources.refreshJob = scope.launch {
            while (true) {
                ensureActive()
                refreshServerData(serverId, resources, serverHost, userInfo)
                delay(5000)
            }
        }
    }

    private suspend fun refreshServerData(serverId: String, resources: ServerResources, serverHost: String, userInfo: ApiClient.UserInfo) {
        try {
            val ssid = resources.deviceData?.ssid ?: 179
            val deviceResult = ApiClient.getDevice(serverHost, userInfo.callsign, ssid)
            deviceResult.fold(
                onSuccess = { device ->
                    resources.deviceData = device
                    if (device.groupId > 0) {
                        updateState(serverId) {
                            it.copy(currentRoomId = device.groupId, deviceData = device)
                        }
                        val groupResult = ApiClient.getGroup(serverHost, device.groupId)
                        groupResult.fold(
                            onSuccess = { group ->
                                updateState(serverId) {
                                    it.copy(onlineCount = group.onlineCount, currentGroupName = group.name)
                                }
                                emitState()
                            },
                            onFailure = { }
                        )
                    }
                },
                onFailure = { }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Refresh failed for $serverId: ${e.message}")
        }
    }

    private fun updateState(serverId: String, update: (ServerConnection) -> ServerConnection) {
        val current = connectionStates[serverId] ?: ServerConnection()
        connectionStates[serverId] = update(current)
    }

    private fun emitState() {
        val activeId = _activeServerId.value
        val list = connectionStates.values.map { state ->
            state.copy(isActive = state.serverId == activeId)
        }
        _serverConnections.value = list
    }
}
