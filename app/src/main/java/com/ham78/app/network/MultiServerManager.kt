package com.ham78.app.network

import android.util.Log
import com.ham78.app.data.ServerConfig
import com.ham78.app.audio.AudioManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 多服务器连接管理器
 * 支持同时连接多个服务器，管理活跃服务器切换
 */
class MultiServerManager(private val audioManagerFactory: (UdpClient) -> AudioManager) {

    companion object {
        private const val TAG = "MultiServerManager"
        private const val VOICE_SESSION_GAP_MS = 1200L
        private const val REFRESH_INTERVAL_MS = 5000L
        private val timestampFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    }

    private val _transmittingState = MutableStateFlow(false)
    val transmittingState: StateFlow<Boolean> = _transmittingState.asStateFlow()

    private val _receivingState = MutableStateFlow(false)
    val receivingState: StateFlow<Boolean> = _receivingState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioStateJob: Job? = null

    data class ServerResources(
        val udpClient: UdpClient,
        val audioManager: AudioManager,
        var refreshJob: Job? = null,
        var loginToken: String = "",
        var userInfo: ApiClient.UserInfo? = null,
        var deviceData: ApiClient.DeviceData? = null
    )

    private val connections = java.util.concurrent.ConcurrentHashMap<String, ServerResources>()
    private val connectionStates = java.util.concurrent.ConcurrentHashMap<String, ServerConnection>()

    private val _serverConnections = MutableStateFlow<List<ServerConnection>>(emptyList())
    val serverConnections: StateFlow<List<ServerConnection>> = _serverConnections.asStateFlow()

    private val _activeServerId = MutableStateFlow("")
    val activeServerId: StateFlow<String> = _activeServerId.asStateFlow()

    var onTextMessageReceived: ((serverId: String, callsign: String, ssid: Int, content: String, timestamp: String) -> Unit)? = null
    var onVoiceReceived: ((serverId: String, callsign: String, ssid: Int, clipId: String, durationMs: Long) -> Unit)? = null

    fun getActiveConnection(): ServerResources? {
        val activeId = _activeServerId.value
        return if (activeId.isNotEmpty()) connections[activeId] else null
    }

    fun getActiveServerConnection(): ServerConnection? {
        val activeId = _activeServerId.value
        return if (activeId.isNotEmpty()) connectionStates[activeId] else null
    }

    fun getConnection(serverId: String): ServerResources? = connections[serverId]

    suspend fun connectToServer(config: ServerConfig): Boolean {
        val serverId = config.id.ifEmpty { "${config.host}:${config.port}" }

        if (connections.containsKey(serverId)) {
            disconnectFromServer(serverId)
        }

        Log.d(TAG, "Connecting to server: ${config.name} (${config.host}:${config.port})")

        val udpClient = UdpClient()
        val audioManager = audioManagerFactory(udpClient)
        val resources = ServerResources(udpClient = udpClient, audioManager = audioManager)

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

        val loginResult = ApiClient.login(config.host, config.username, config.password)
        return loginResult.fold(
            onSuccess = { userInfo ->
                resources.loginToken = ApiClient.getTokenForServer(config.host)
                resources.userInfo = userInfo

                updateState(serverId) {
                    it.copy(
                        isLoggedIn = true,
                        callsign = userInfo.callsign,
                        dmrId = userInfo.dmrId,
                        connectionState = ConnectionState.CONNECTING
                    )
                }

                var deviceData: ApiClient.DeviceData? = null
                try {
                    val deviceResult = ApiClient.getDevice(config.host, userInfo.callsign, 179)
                    deviceResult.getOrNull()?.let { device ->
                        resources.deviceData = device
                        deviceData = device
                        updateState(serverId) {
                            it.copy(deviceData = device, ssid = device.ssid)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load device data: ${e.message}")
                }

                val device = deviceData ?: resources.deviceData
                val serverHost = userInfo.server ?: config.host
                val port = userInfo.serverPort ?: config.port
                val ssid = if (device != null && device.ssid != 0) device.ssid else 179
                val devModel = device?.devModel ?: 101
                val dmrId = device?.dmrId ?: userInfo.dmrId

                val success = udpClient.connect(
                    serverHost = serverHost, port = port,
                    id = dmrId, call = userInfo.callsign,
                    ssidVal = ssid, devModelVal = devModel
                )

                if (success) {
                    updateState(serverId) { it.copy(connectionState = ConnectionState.CONNECTED) }
                    if (_activeServerId.value.isEmpty()) {
                        switchActiveServer(serverId)
                    }
                    startRefreshData(serverId, resources, config.host, userInfo)
                    emitState()
                    true
                } else {
                    updateState(serverId) { it.copy(connectionState = ConnectionState.DISCONNECTED) }
                    emitState()
                    false
                }
            },
            onFailure = { error ->
                Log.e(TAG, "Login failed for ${config.host}: ${error.message}")
                updateState(serverId) { it.copy(connectionState = ConnectionState.DISCONNECTED) }
                emitState()
                false
            }
        )
    }

    fun disconnectFromServer(serverId: String) {
        val resources = connections[serverId] ?: return
        resources.refreshJob?.cancel()
        resources.udpClient.disconnect()
        resources.audioManager.release()
        connectionStates[serverId]?.serverHost?.takeIf { it.isNotEmpty() }?.let {
            ApiClient.clearTokenForServer(it)
        }
        connections.remove(serverId)
        connectionStates.remove(serverId)

        if (_activeServerId.value == serverId) {
            _activeServerId.value = connections.keys.firstOrNull() ?: ""
        }

        emitState()
        Log.d(TAG, "Disconnected from server: $serverId")
    }

    fun switchActiveServer(serverId: String) {
        val oldActive = _activeServerId.value
        if (oldActive.isNotEmpty()) {
            connections[oldActive]?.audioManager?.clearReceivingState()
        }
        audioStateJob?.cancel()

        _activeServerId.value = serverId
        connections[serverId]?.audioManager?.let { am ->
            _transmittingState.value = am.isTransmitting.value
            _receivingState.value = am.isReceiving.value
                audioStateJob = scope.launch {
                    launch { am.isTransmitting.collect { _transmittingState.value = it } }
                    launch { am.isReceiving.collect { _receivingState.value = it } }
                }
        }

        connectionStates.keys.forEach { id ->
            connectionStates[id]?.let { conn ->
                connectionStates[id] = conn.copy(isActive = id == serverId)
            }
        }

        emitState()
        Log.d(TAG, "Switched active server to: $serverId")
    }

    fun sendAudioData(audioData: ByteArray, isOpus: Boolean = false) {
        val activeId = _activeServerId.value
        if (activeId.isEmpty()) return
        connections[activeId]?.udpClient?.sendAudioData(audioData, isOpus)
    }

    fun replayVoiceClip(pcm: ByteArray) {
        getActiveConnection()?.audioManager?.playClip(pcm)
    }

    fun sendTextMessage(serverId: String, callsign: String, text: String, ssid: Int = 179, dmrId: Int = 0): Boolean {
        val resources = connections[serverId] ?: run {
            Log.e(TAG, "sendTextMessage: no connection for serverId=$serverId")
            return false
        }
        val udp = resources.udpClient
        if (!udp.isConnected()) {
            Log.e(TAG, "sendTextMessage: UDP not connected for serverId=$serverId")
            return false
        }
        val packet = Nrl21Protocol.createTextPacket(callsign, text, "text", ssid, 101, dmrId)
        val sent = udp.sendPacket(packet)
        if (!sent) {
            Log.e(TAG, "sendTextMessage: sendPacket failed for serverId=$serverId")
        }
        return sent
    }

    fun sendLocation(serverId: String, callsign: String, latitude: Double, longitude: Double, ssid: Int = 179, dmrId: Int = 0) {
        val resources = connections[serverId] ?: run {
            Log.e(TAG, "sendLocation: no connection for serverId=$serverId")
            return
        }
        val packet = Nrl21Protocol.createLocationPacket(callsign, latitude, longitude, ssid, 101, dmrId)
        if (!resources.udpClient.sendPacket(packet)) {
            Log.e(TAG, "sendLocation: sendPacket failed for serverId=$serverId")
        }
    }

    fun joinRoom(serverId: String, roomId: Int) {
        val resources = connections[serverId] ?: return
        resources.udpClient.sendJoinRoom(roomId)
        updateState(serverId) { it.copy(currentRoomId = roomId) }
        emitState()
    }

    suspend fun loadRoomList(serverId: String): List<ApiClient.RoomInfo> {
        val state = connectionStates[serverId] ?: return emptyList()
        return try {
            ApiClient.getRoomList(state.serverHost).getOrNull() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load room list: ${e.message}")
            emptyList()
        }
    }

    fun startTransmitting(): Boolean {
        val activeId = _activeServerId.value
        if (activeId.isEmpty()) return false
        val resources = connections[activeId] ?: return false
        if (connectionStates[activeId]?.connectionState != ConnectionState.CONNECTED) return false
        return resources.audioManager.startTransmitting()
    }

    fun stopTransmitting() {
        val activeId = _activeServerId.value
        if (activeId.isEmpty()) return
        connections[activeId]?.audioManager?.stopTransmitting()
    }

    fun isTransmitting(): Boolean = _transmittingState.value
    fun isReceiving(): Boolean = _receivingState.value

    fun release() {
        connections.keys.toList().forEach { disconnectFromServer(it) }
        scope.cancel()
    }

    private fun setupPacketListener(serverId: String, resources: ServerResources) {
        val voiceLock = Any()
        var sessionCallsign = ""
        var sessionSsid = 0
        val sessionPcm = java.io.ByteArrayOutputStream()
        var flushJob: Job? = null

        fun flushVoiceSession() {
            if (sessionCallsign.isEmpty()) return
            val callsign = sessionCallsign
            val ssid = sessionSsid
            val pcm = sessionPcm.toByteArray()
            sessionPcm.reset()
            sessionCallsign = ""
            sessionSsid = 0

            var clipId = ""
            var durationMs = 0L
            if (pcm.isNotEmpty()) {
                clipId = java.util.UUID.randomUUID().toString()
                com.ham78.app.audio.VoiceClipStore.put(clipId, pcm)
                durationMs = com.ham78.app.audio.VoiceClipStore.durationMs(pcm)
            }
            onVoiceReceived?.invoke(serverId, callsign, ssid, clipId, durationMs)
        }

        resources.udpClient.packetListener = object : UdpClient.PacketListener {
            override fun onPacketReceived(packet: Nrl21Protocol.Packet) {
                when (packet.type) {
                    Nrl21Protocol.TYPE_VOICE, Nrl21Protocol.TYPE_OPUS -> {
                        if (serverId == _activeServerId.value) {
                            resources.audioManager.handleReceivedAudio(packet.data, packet.type, packet.callSign)
                        }

                        synchronized(voiceLock) {
                            if (sessionCallsign.isNotEmpty() &&
                                (packet.callSign != sessionCallsign || packet.ssid != sessionSsid)) {
                                flushVoiceSession()
                            }
                            if (sessionCallsign.isEmpty()) {
                                sessionCallsign = packet.callSign
                                sessionSsid = packet.ssid
                            }
                            resources.audioManager.decodeToPcm(packet.data, packet.type)?.let {
                                sessionPcm.write(it)
                            }
                        }

                        flushJob?.cancel()
                        flushJob = scope.launch {
                            delay(VOICE_SESSION_GAP_MS)
                            synchronized(voiceLock) { flushVoiceSession() }
                        }
                    }
                    Nrl21Protocol.TYPE_TEXT -> {
                        val textContent = Nrl21Protocol.TextContent.parse(packet.data)
                        val timestamp = synchronized(timestampFormat) {
                            timestampFormat.format(java.util.Date())
                        }
                        onTextMessageReceived?.invoke(serverId, packet.callSign, packet.ssid, textContent.body, timestamp)
                    }
                }
            }

            override fun onError(error: String) {
                Log.e(TAG, "Server $serverId error: $error")
            }

            override fun onConnectionLost() {
                Log.w(TAG, "Server $serverId connection lost")
                updateState(serverId) { it.copy(connectionState = ConnectionState.RECONNECTING) }
                emitState()
            }
        }
    }

    private fun startRefreshData(serverId: String, resources: ServerResources, serverHost: String, userInfo: ApiClient.UserInfo) {
        resources.refreshJob?.cancel()
        resources.refreshJob = scope.launch {
            while (isActive) {
                refreshServerData(serverId, resources, serverHost, userInfo)
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshServerData(serverId: String, resources: ServerResources, serverHost: String, userInfo: ApiClient.UserInfo) {
        try {
            val ssid = resources.deviceData?.ssid ?: 179
            val deviceResult = ApiClient.getDevice(serverHost, userInfo.callsign, ssid)
            deviceResult.getOrNull()?.let { device ->
                resources.deviceData = device
                if (device.groupId > 0) {
                    updateState(serverId) {
                        it.copy(currentRoomId = device.groupId, deviceData = device)
                    }
                    val groupResult = ApiClient.getGroup(serverHost, device.groupId)
                    groupResult.getOrNull()?.let { group ->
                        updateState(serverId) {
                            it.copy(onlineCount = group.onlineCount, currentGroupName = group.name)
                        }
                        emitState()
                    }
                }
            }
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
