package com.ham78.app.network

import android.util.Log
import com.ham78.app.data.ServerConfig
import com.ham78.app.audio.AudioManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private val _playingVoiceClipId = MutableStateFlow<String?>(null)
    val playingVoiceClipId: StateFlow<String?> = _playingVoiceClipId.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioStateJob: Job? = null

    data class ServerResources(
        val udpClient: UdpClient,
        val audioManager: AudioManager,
        var refreshJob: Job? = null,
        var voiceSessionJob: Job? = null,
        var loginToken: String = "",
        var userInfo: ApiClient.UserInfo? = null,
        var deviceData: ApiClient.DeviceData? = null
    )

    private val connections = java.util.concurrent.ConcurrentHashMap<String, ServerResources>()
    private val connectionStates = java.util.concurrent.ConcurrentHashMap<String, ServerConnection>()
    private val connectMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    // 当前音频编码，新连接与已有连接共用
    @Volatile
    private var currentCodec = com.ham78.app.data.AudioCodec.G711
    @Volatile
    private var currentVolume = 100
    @Volatile
    private var currentGain = 1.0f

    /** 应用新的音频编码到所有连接（含后续新建的连接） */
    fun setAudioCodec(codec: com.ham78.app.data.AudioCodec) {
        if (currentCodec == codec) return
        currentCodec = codec
        connections.values.forEach { it.audioManager.setCodec(codec) }
        Log.d(TAG, "Audio codec switched to $codec for ${connections.size} connections")
    }

    /** 应用新的音量到所有连接 */
    fun setAudioVolume(volume: Int) {
        currentVolume = volume
        connections.values.forEach { it.audioManager.setVolume(volume) }
    }

    /** 应用新的音频增益到所有连接 */
    fun setAudioGain(gain: Float) {
        currentGain = gain
        connections.values.forEach { it.audioManager.setGain(gain) }
    }

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
        // 同一服务器同时只允许一个连接流程（自动重连/手动点击/服务启动可能并发触发）
        val mutex = connectMutexes.computeIfAbsent(serverId) { Mutex() }
        return mutex.withLock { connectToServerLocked(serverId, config) }
    }

    private suspend fun connectToServerLocked(serverId: String, config: ServerConfig): Boolean {
        if (connections.containsKey(serverId)) {
            disconnectFromServer(serverId)
        }

        Log.d(TAG, "Connecting to server: ${config.name} (${config.host}:${config.port})")

        val udpClient = UdpClient()
        val audioManager = audioManagerFactory(udpClient)
        audioManager.setCodec(currentCodec)
        audioManager.setVolume(currentVolume)
        audioManager.setGain(currentGain)
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
        emitState()

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

                // DNS 解析 + socket 创建涉及网络操作，必须在 IO 线程执行，
                // 否则从主线程协程（UI 点击）发起时会抛 NetworkOnMainThreadException
                val success = withContext(Dispatchers.IO) {
                    udpClient.connect(
                        serverHost = serverHost, port = port,
                        id = dmrId, call = userInfo.callsign,
                        ssidVal = ssid, devModelVal = devModel
                    )
                }

                if (success) {
                    updateState(serverId) { it.copy(connectionState = ConnectionState.CONNECTED) }
                    if (_activeServerId.value.isEmpty()) {
                        switchActiveServer(serverId)
                    }
                    startRefreshData(serverId, resources, config.host, userInfo)

                    // 记住上次的房间（全局唯一）：连接成功后重新加入服务器端记录的 group_id；
                    // 若其他服务器已占用房间，则清掉本台的记录，保证同一时间只有一个房间
                    val lastRoomId = if (device != null && device.groupId != 0) device.groupId
                        else resources.deviceData?.groupId ?: 0
                    if (lastRoomId > 0) {
                        val roomOccupied = connectionStates.values.any { it.currentRoomId > 0 }
                        if (roomOccupied) {
                            updateState(serverId) { it.copy(currentRoomId = 0, currentGroupName = "") }
                            withContext(Dispatchers.IO) {
                                udpClient.sendJoinRoom(0)
                                try {
                                    val dev = resources.deviceData
                                    val host = config.host
                                    if (dev != null && dev.id > 0) {
                                        ApiClient.updateDevice(host, dev, 0).getOrNull()?.let {
                                            resources.deviceData = dev.copy(groupId = 0)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Clear stale room failed: ${e.message}")
                                }
                            }
                            Log.d(TAG, "Room $lastRoomId on $serverId cleared (room occupied elsewhere)")
                        } else {
                            updateState(serverId) { it.copy(currentRoomId = lastRoomId) }
                            withContext(Dispatchers.IO) { udpClient.sendJoinRoom(lastRoomId) }
                            Log.d(TAG, "Rejoined last room $lastRoomId on $serverId")
                        }
                    }

                    emitState()
                    true
                } else {
                    Log.e(TAG, "UDP connect failed for ${config.host}:$port")
                    cleanupFailedConnection(serverId)
                    false
                }
            },
            onFailure = { error ->
                Log.e(TAG, "Login failed for ${config.host}: ${error.message}")
                cleanupFailedConnection(serverId)
                false
            }
        )
    }

    /**
     * 连接失败时清理残留资源与状态，
     * 使该服务器回退到"未连接"列表，用户可以直接重试。
     */
    private fun cleanupFailedConnection(serverId: String) {
        connections.remove(serverId)?.let { res ->
            res.refreshJob?.cancel()
            res.voiceSessionJob?.cancel()
            try { res.udpClient.release() } catch (_: Exception) {}
            try { res.audioManager.release() } catch (_: Exception) {}
        }
        connectionStates.remove(serverId)
        emitState()
    }

    fun disconnectFromServer(serverId: String) {
        val resources = connections[serverId] ?: return
        resources.refreshJob?.cancel()
        resources.voiceSessionJob?.cancel()
        resources.udpClient.release()
        resources.audioManager.release()
        connectionStates[serverId]?.serverHost?.takeIf { it.isNotEmpty() }?.let {
            ApiClient.clearTokenForServer(it)
        }
        connections.remove(serverId)
        connectionStates.remove(serverId)

        if (_activeServerId.value == serverId) {
            _activeServerId.value = connections.keys.firstOrNull() ?: ""
            watchActiveAudioStates()
        }

        emitState()
        Log.d(TAG, "Disconnected from server: $serverId")
    }

    /**
     * 切换选中（活跃）服务器：语音收发只跟随选中服务器。
     */
    fun switchActiveServer(serverId: String) {
        val oldActive = _activeServerId.value
        if (oldActive.isNotEmpty() && oldActive != serverId) {
            connections[oldActive]?.audioManager?.clearReceivingState()
        }

        _activeServerId.value = serverId
        watchActiveAudioStates()

        // isActive 由 emitState 统一按 activeId 重算，无需逐个 copy
        emitState()
        Log.d(TAG, "Switched active server to: $serverId")
    }

    /**
     * 收发状态只跟随当前选中（活跃）连接：
     * 同一时间只与选中服务器加入的房间通话。
     */
    private fun watchActiveAudioStates() {
        audioStateJob?.cancel()
        val activeId = _activeServerId.value
        val am = connections[activeId]?.audioManager
        if (am == null) {
            _transmittingState.value = false
            _receivingState.value = false
            _playingVoiceClipId.value = null
            return
        }
        _transmittingState.value = am.isTransmitting.value
        _receivingState.value = am.isReceiving.value
        _playingVoiceClipId.value = am.currentPlayingClipId.value
        audioStateJob = scope.launch {
            launch { am.isTransmitting.collect { _transmittingState.value = it } }
            launch { am.isReceiving.collect { _receivingState.value = it } }
            launch { am.currentPlayingClipId.collect { _playingVoiceClipId.value = it } }
        }
    }

    fun sendAudioData(audioData: ByteArray, isOpus: Boolean = false) {
        val activeId = _activeServerId.value
        if (activeId.isEmpty()) return
        connections[activeId]?.udpClient?.sendAudioData(audioData, isOpus)
    }

    fun replayVoiceClip(pcm: ByteArray, clipId: String = "") {
        getActiveConnection()?.audioManager?.playClip(pcm, clipId)
    }

    fun stopVoiceClip() {
        getActiveConnection()?.audioManager?.stopClip()
    }

    suspend fun sendTextMessage(serverId: String, callsign: String, text: String, ssid: Int = 179, dmrId: Int = 0): Boolean {
        val resources = connections[serverId] ?: run {
            Log.e(TAG, "sendTextMessage: no connection for serverId=$serverId")
            return false
        }
        val udp = resources.udpClient
        if (!udp.isConnected()) {
            Log.e(TAG, "sendTextMessage: UDP not connected for serverId=$serverId")
            return false
        }
        val devModel = resources.deviceData?.devModel ?: 101
        val packet = Nrl21Protocol.createTextPacket(callsign, text, "text", ssid, devModel, dmrId)
        // UDP 发包是网络操作，必须在 IO 线程执行，否则主线程调用会抛 NetworkOnMainThreadException
        val sent = withContext(Dispatchers.IO) { udp.sendPacket(packet) }
        if (!sent) {
            Log.e(TAG, "sendTextMessage: sendPacket failed for serverId=$serverId")
        }
        return sent
    }

    suspend fun sendLocation(serverId: String, callsign: String, latitude: Double, longitude: Double, ssid: Int = 179, dmrId: Int = 0) {
        val resources = connections[serverId] ?: run {
            Log.e(TAG, "sendLocation: no connection for serverId=$serverId")
            return
        }
        val packet = Nrl21Protocol.createLocationPacket(callsign, latitude, longitude, ssid, 101, dmrId)
        if (!withContext(Dispatchers.IO) { resources.udpClient.sendPacket(packet) }) {
            Log.e(TAG, "sendLocation: sendPacket failed for serverId=$serverId")
        }
    }

    /**
     * 加入房间（全局唯一房间策略）：
     * 无论连着多少台服务器，同一时间只能加入一个房间。
     * 选中某台服务器的房间后，自动把该服务器切换为选中服务器，
     * 并退出其他服务器上已加入的房间。
     */
    suspend fun joinRoom(serverId: String, roomId: Int) {
        val resources = connections[serverId] ?: run {
            Log.e(TAG, "joinRoom: no connection for serverId=$serverId")
            return
        }
        val udp = resources.udpClient
        if (!udp.isConnected()) {
            Log.e(TAG, "joinRoom: UDP not connected for serverId=$serverId")
            return
        }

        // 切换选中服务器到目标服务器
        if (_activeServerId.value != serverId) {
            switchActiveServer(serverId)
        }

        // 退出其他服务器上已加入的房间，保证全局只有一个房间
        connectionStates.entries
            .filter { it.key != serverId && it.value.currentRoomId > 0 }
            .toList()
            .forEach { leaveRoom(it.key) }

        withContext(Dispatchers.IO) {
            udp.sendJoinRoom(roomId)

            // 同步更新服务器端设备分组，避免 5 秒后的定时刷新把 currentRoomId 拉回旧频道
            val device = resources.deviceData
            val host = connectionStates[serverId]?.serverHost.orEmpty()
            if (device != null && device.id > 0 && host.isNotEmpty() && device.groupId != roomId) {
                try {
                    ApiClient.updateDevice(host, device, roomId).getOrNull()?.let {
                        resources.deviceData = device.copy(groupId = roomId)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "joinRoom: updateDevice failed: ${e.message}")
                }
            }
        }

        updateState(serverId) { it.copy(currentRoomId = roomId) }
        emitState()
        Log.d(TAG, "joinRoom: serverId=$serverId roomId=$roomId")
    }

    /**
     * 退出某台服务器上已加入的房间：
     * UDP 侧发送房间号 0，同时把服务器端设备分组清零，
     * 避免 5 秒定时刷新又把 currentRoomId 拉回旧频道。
     */
    private suspend fun leaveRoom(serverId: String) {
        val resources = connections[serverId] ?: return

        withContext(Dispatchers.IO) {
            if (resources.udpClient.isConnected()) {
                resources.udpClient.sendJoinRoom(0)
            }
            val device = resources.deviceData
            val host = connectionStates[serverId]?.serverHost.orEmpty()
            if (device != null && device.id > 0 && host.isNotEmpty()) {
                try {
                    if (ApiClient.updateDevice(host, device, 0).getOrNull() == true) {
                        resources.deviceData = device.copy(groupId = 0)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "leaveRoom: updateDevice failed: ${e.message}")
                }
            }
        }

        updateState(serverId) { it.copy(currentRoomId = 0, currentGroupName = "") }
        emitState()
        Log.d(TAG, "leaveRoom: serverId=$serverId")
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

    /**
     * 开始发射：语音只发送到当前选中（活跃）服务器加入的房间。
     */
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
        // 最后一个语音包时间戳；0 表示无进行中的会话
        val lastVoicePacketTime = java.util.concurrent.atomic.AtomicLong(0)

        fun flushVoiceSession() {
            if (sessionCallsign.isEmpty()) return
            val callsign = sessionCallsign
            val ssid = sessionSsid
            val pcm = sessionPcm.toByteArray()
            sessionPcm.reset()
            sessionCallsign = ""
            sessionSsid = 0
            lastVoicePacketTime.set(0)

            var clipId = ""
            var durationMs = 0L
            if (pcm.isNotEmpty()) {
                clipId = java.util.UUID.randomUUID().toString()
                com.ham78.app.audio.VoiceClipStore.put(clipId, pcm)
                durationMs = com.ham78.app.audio.VoiceClipStore.durationMs(pcm)
            }
            onVoiceReceived?.invoke(serverId, callsign, ssid, clipId, durationMs)
        }

        // 静默超时监护：单一协程定期检查，替代原"每包取消并重启延迟协程"的防抖
        // （语音每秒可达 50 包，原实现每秒约 100 次协程调度；现在仅每 600ms 一次检查）。
        // 会话 flush 后若连接已移除则自动退出，避免空转协程累积。
        resources.voiceSessionJob = scope.launch {
            while (isActive) {
                delay(VOICE_SESSION_GAP_MS / 2)
                if (connections[serverId] !== resources) break
                val last = lastVoicePacketTime.get()
                if (last > 0 && System.currentTimeMillis() - last >= VOICE_SESSION_GAP_MS) {
                    synchronized(voiceLock) { flushVoiceSession() }
                }
            }
        }

        resources.udpClient.packetListener = object : UdpClient.PacketListener {
            override fun onPacketReceived(packet: Nrl21Protocol.Packet) {
                when (packet.type) {
                    Nrl21Protocol.TYPE_VOICE, Nrl21Protocol.TYPE_OPUS -> {
                        // 只播放当前选中（活跃）服务器的语音
                        if (serverId == _activeServerId.value) {
                            resources.audioManager.handleReceivedAudio(packet.data, packet.type, packet.callSign)
                        }

                        // 解码在锁外执行：Opus 解码较重，避免阻塞 flush 检查与会话拼接
                        val pcm = resources.audioManager.decodeToPcm(packet.data, packet.type)

                        synchronized(voiceLock) {
                            if (sessionCallsign.isNotEmpty() &&
                                (packet.callSign != sessionCallsign || packet.ssid != sessionSsid)) {
                                flushVoiceSession()
                            }
                            if (sessionCallsign.isEmpty()) {
                                sessionCallsign = packet.callSign
                                sessionSsid = packet.ssid
                            }
                            pcm?.let { sessionPcm.write(it) }
                        }
                        lastVoicePacketTime.set(System.currentTimeMillis())
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
