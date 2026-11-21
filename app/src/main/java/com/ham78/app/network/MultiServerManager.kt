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

        /** 同一发送者位置包入库的最小间隔（毫秒）：防止坐标刷屏 */
        private const val LOC_THROTTLE_MS = 60_000L
        private val timestampFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    }

    private val _transmittingState = MutableStateFlow(false)
    val transmittingState: StateFlow<Boolean> = _transmittingState.asStateFlow()

    private val _receivingState = MutableStateFlow(false)
    val receivingState: StateFlow<Boolean> = _receivingState.asStateFlow()

    // 每台服务器同时只有一个人在说话。身份没变时不写这份状态，避免每个语音包都刷新界面
    private val _liveSpeakers = MutableStateFlow<Map<String, LiveSpeaker>>(emptyMap())
    val liveSpeakers: StateFlow<Map<String, LiveSpeaker>> = _liveSpeakers.asStateFlow()

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

    // 收听中的房间：serverId → 房间 ID。收听=只收不播，与"当前房间"（可发可收）并存；
    // 所有已收听房间的语音都会解码播放和计入消息流，不只限当前选中服务器
    private val _listenRooms = MutableStateFlow<Map<String, Int>>(emptyMap())
    val listenRooms: StateFlow<Map<String, Int>> = _listenRooms.asStateFlow()

    private val _activeServerId = MutableStateFlow("")
    val activeServerId: StateFlow<String> = _activeServerId.asStateFlow()

    var onTextMessageReceived: ((serverId: String, callsign: String, ssid: Int, content: String, timestamp: String, devModel: Int, dmrId: Int) -> Unit)? = null
    var onVoiceReceived: ((serverId: String, callsign: String, ssid: Int, clipId: String, durationMs: Long, devModel: Int, dmrId: Int, opus: Boolean, mdcLabel: String) -> Unit)? = null

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

        // 密码登录优先。OIDC 只留下 token，没有密码，用 token 直接取用户信息
        val loginResult = if (config.password.isEmpty() && !config.authToken.isNullOrEmpty()) {
            ApiClient.setTokenForServer(config.host, config.authToken)
            ApiClient.getUserInfo(config.host)
        } else {
            ApiClient.login(config.host, config.username, config.password)
        }
        return loginResult.fold(
            onSuccess = { userInfo ->
                resources.loginToken = ApiClient.getTokenForServer(config.host)
                resources.userInfo = userInfo

                updateState(serverId) {
                    it.copy(
                        isLoggedIn = true,
                        callsign = userInfo.callsign,
                        dmrId = userInfo.dmrId,
                        userInfo = userInfo,
                        connectionState = ConnectionState.CONNECTING
                    )
                }

                var deviceData: ApiClient.DeviceData? = null
                try {
                    // 先用默认 SSID 查一次设备；查到后以设备真实 SSID 为准
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

                    // 记住上次的房间（按服务器记忆）：连接成功后重新加入服务器端记录的 group_id。
                    // 多房间模式下各服务器独立记忆自己的房间，互不干扰
                    val lastRoomId = if (device != null && device.groupId != 0) device.groupId
                        else resources.deviceData?.groupId ?: 0
                    if (lastRoomId > 0) {
                        updateState(serverId) { it.copy(currentRoomId = lastRoomId) }
                        withContext(Dispatchers.IO) { udpClient.sendJoinRoom(lastRoomId) }
                        Log.d(TAG, "Rejoined last room $lastRoomId on $serverId")
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
        // 连接已移除，其互斥锁无并发竞争者，直接回收避免 map 无界增长
        connectMutexes.remove(serverId)
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
        // 连接已移除，其互斥锁无并发竞争者，直接回收避免 map 无界增长
        connectMutexes.remove(serverId)
        _listenRooms.value = _listenRooms.value - serverId   // 断开的服务器不再有收听记录
        clearLiveSpeaker(serverId)

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

    /**
     * 把一段已生成的 PCM 信号（CW/DTMF/SSTV/尾音/TTS/录音）经活跃连接发送。
     * 返回 false = 没有活跃连接、未进房间或正在 PTT 发射。
     *
     * 挂起函数：信号按真实时间节奏逐帧发出（SSTV 一张图要发 36 秒），
     * 调用方需要在协程里等它发完。
     */
    suspend fun transmitPcmToActive(pcm: ByteArray): Boolean {
        val activeId = _activeServerId.value
        if (activeId.isEmpty()) return false
        val resources = connections[activeId] ?: return false
        if (connectionStates[activeId]?.currentRoomId == 0) return false   // 不在房间里无处可发
        return resources.audioManager.transmitPcm(pcm)
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
     * 加入房间：发送端（可发可收）。
     * 多房间模式：允许同时收听多个房间，但"当前房间"每台服务器只有一个；
     * 加入新房间只替换本服务器的当前房间，不再清空其他服务器的房间。
     *
     * @param asListen true = 只收听模式（纯接收，不作为发送目标）
     */
    suspend fun joinRoom(serverId: String, roomId: Int, asListen: Boolean = false) {
        val resources = connections[serverId] ?: run {
            Log.e(TAG, "joinRoom: no connection for serverId=$serverId")
            return
        }
        val udp = resources.udpClient
        if (!udp.isConnected()) {
            Log.e(TAG, "joinRoom: UDP not connected for serverId=$serverId")
            return
        }

        if (asListen) {
            // --- 收听模式：加入另一个服务器的房间，不动"当前房间" ---
            _listenRooms.value = _listenRooms.value + (serverId to roomId)
        } else {
            // --- 正式加入：本服务器切为活跃（发送目标跟随当前房间）---
            if (_activeServerId.value != serverId) {
                switchActiveServer(serverId)
            }
            _listenRooms.value = _listenRooms.value - serverId   // 本服务器的纯收听记录被正式加入取代
        }

        withContext(Dispatchers.IO) {
            udp.sendJoinRoom(roomId)

            // 同步更新服务器端设备分组，避免 5 秒后的定时刷新把 currentRoomId 拉回旧频道
            // （只对正式加入做：收听不写服务器端分组，避免覆盖本台设备在服务器上的归属）
            if (!asListen) {
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
        }

        if (!asListen) {
            updateState(serverId) { it.copy(currentRoomId = roomId) }
        }
        emitState()
        Log.d(TAG, "joinRoom: serverId=$serverId roomId=$roomId listen=$asListen")
    }

    /** 停止收听某台服务器的房间（正式加入的房间不受影响） */
    fun stopListening(serverId: String) {
        _listenRooms.value = _listenRooms.value - serverId
        Log.d(TAG, "stopListening: serverId=$serverId")
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

    /**
     * 活跃连接此刻是否真的在发射（直接问 AudioManager，不经过 StateFlow）。
     *
     * _transmittingState 是靠 collect 异步同步过来的，按键刚松开的瞬间
     * 它可能还没更新，用它判断会漏掉尾音。这里直接读源头，拿到的是真值。
     */
    fun isActivelyTransmitting(): Boolean =
        getActiveConnection()?.audioManager?.isTransmitting() == true

    fun release() {
        connections.keys.toList().forEach { disconnectFromServer(it) }
        scope.cancel()
    }

    private fun setupPacketListener(serverId: String, resources: ServerResources) {
        val voiceLock = Any()
        var sessionCallsign = ""
        var sessionSsid = 0
        var sessionDevModel = 0
        var sessionDmrId = 0
        var sessionOpus = false
        var sessionMdcLabel = ""
        var sessionMdc: com.ham78.app.audio.Mdc1200Decoder? = null
        var sessionDurationMs = 0L
        val sessionPcm = java.io.ByteArrayOutputStream()
        // 最后一个语音包时间戳；0 表示无进行中的会话
        val lastVoicePacketTime = java.util.concurrent.atomic.AtomicLong(0)

        // [loc] 位置包节流：桌面端等客户端按固定周期广播坐标，逐条入库会刷屏。
        // 同一发送者 60 秒内只记一条（小程序对 loc 直接丢弃，这里保留低频展示）。
        var lastLocCallSign = ""
        var lastLocSsid = -1
        var lastLocAt = 0L

        fun flushVoiceSession() {
            if (sessionCallsign.isEmpty()) return
            val callsign = sessionCallsign
            val ssid = sessionSsid
            val devModel = sessionDevModel
            val dmrId = sessionDmrId
            val opus = sessionOpus
            val mdcLabel = sessionMdcLabel
            val pcm = sessionPcm.toByteArray()
            sessionPcm.reset()
            sessionCallsign = ""
            sessionSsid = 0
            sessionDevModel = 0
            sessionDmrId = 0
            sessionOpus = false
            sessionMdcLabel = ""
            sessionMdc = null
            sessionDurationMs = 0
            lastVoicePacketTime.set(0)
            clearLiveSpeaker(serverId)

            var clipId = ""
            var durationMs = 0L
            if (pcm.isNotEmpty()) {
                clipId = java.util.UUID.randomUUID().toString()
                com.ham78.app.audio.VoiceClipStore.put(clipId, pcm)
                durationMs = com.ham78.app.audio.VoiceClipStore.durationMs(pcm)
            }
            onVoiceReceived?.invoke(serverId, callsign, ssid, clipId, durationMs, devModel, dmrId, opus, mdcLabel)
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
                        // 每个语音包只解码一次：解码结果同时用于播放和会话缓存，
                        // 原来活跃服务器每个包解两次（Opus 解码较重，白费一半 CPU）
                        val pcm = resources.audioManager.decodeToPcm(packet.data, packet.type)

                        // 多房间收听：每个连接只播放自己收听/所在的房间的语音，
                        // 不再要求"必须是当前选中服务器"（原单房间策略的限制）
                        val listeningThisServer =
                            serverId == _activeServerId.value ||
                            _listenRooms.value[serverId] != null
                        if (listeningThisServer) {
                            resources.audioManager.handleReceivedPcm(pcm, packet.callSign)
                        }

                        // 解码已在锁外执行：Opus 解码较重，避免阻塞 flush 检查与会话拼接
                        var shownDuration = 0L
                        var shownDmr = 0
                        var shownOpus = false
                        var shownMdc = ""
                        synchronized(voiceLock) {
                            if (sessionCallsign.isNotEmpty() &&
                                (packet.callSign != sessionCallsign || packet.ssid != sessionSsid)) {
                                flushVoiceSession()
                            }
                            if (sessionCallsign.isEmpty()) {
                                sessionCallsign = packet.callSign
                                sessionSsid = packet.ssid
                                sessionDevModel = packet.devModel
                                sessionOpus = packet.type == Nrl21Protocol.TYPE_OPUS
                                sessionMdc = com.ham78.app.audio.Mdc1200Decoder()
                                sessionDurationMs = 0
                            }
                            if (sessionDmrId == 0 && packet.dmrId != 0) sessionDmrId = packet.dmrId
                            pcm?.let {
                                sessionPcm.write(it)
                                // 8kHz 16bit：每 16 字节是 1 毫秒。Opus 解出来也已降到 8kHz
                                if (it.size >= 16) sessionDurationMs += it.size / 16
                                // 信令多在开头或结尾，解出来就留在这次通话上
                                val label = sessionMdc?.feedPcm16Le(it)
                                if (!label.isNullOrEmpty()) sessionMdcLabel = label
                            }
                            shownDuration = sessionDurationMs
                            shownDmr = sessionDmrId
                            shownOpus = sessionOpus
                            shownMdc = sessionMdcLabel
                        }
                        lastVoicePacketTime.set(System.currentTimeMillis())
                        noteLiveSpeaker(serverId, packet, shownDuration, shownDmr, shownOpus, shownMdc)
                    }
                    Nrl21Protocol.TYPE_TEXT -> {
                        val textContent = Nrl21Protocol.TextContent.parse(packet.data)

                        // 位置包：按发送者节流入库，避免坐标刷屏
                        if (textContent.subType == "loc") {
                            val now = System.currentTimeMillis()
                            synchronized(voiceLock) {
                                if (packet.callSign != lastLocCallSign ||
                                    packet.ssid != lastLocSsid ||
                                    now - lastLocAt > LOC_THROTTLE_MS
                                ) {
                                    lastLocCallSign = packet.callSign
                                    lastLocSsid = packet.ssid
                                    lastLocAt = now
                                    val timestamp = synchronized(timestampFormat) {
                                        timestampFormat.format(java.util.Date())
                                    }
                                    onTextMessageReceived?.invoke(
                                        serverId, packet.callSign, packet.ssid,
                                        "[loc]${textContent.body}", timestamp, packet.devModel, packet.dmrId
                                    )
                                }
                            }
                            return
                        }

                        val timestamp = synchronized(timestampFormat) {
                            timestampFormat.format(java.util.Date())
                        }
                        onTextMessageReceived?.invoke(
                            serverId, packet.callSign, packet.ssid, textContent.body, timestamp, packet.devModel, packet.dmrId
                        )
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
                // 0 号是公共大厅，同样有在线名单。只听的房间不在这里轮询
                updateState(serverId) {
                    it.copy(currentRoomId = device.groupId, deviceData = device)
                }
                val groupResult = ApiClient.getGroup(serverHost, device.groupId)
                groupResult.getOrNull()?.let { group ->
                    val devices = group.devmap.values
                        .filter { it.isOnline }
                        .map { OnlineDevice(it.callsign, it.ssid, it.devModel, it.dmrId) }
                        .sortedBy { it.callsign }
                    updateState(serverId) {
                        it.copy(
                            onlineCount = group.onlineCount,
                            currentGroupName = group.name.ifEmpty { if (device.groupId == 0) "公共大厅" else "" },
                            onlineDevices = devices
                        )
                    }
                    emitState()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Refresh failed for $serverId: ${e.message}")
        }
    }

    // 同一个人连续说话时，时长最多 200ms 推一次界面。语音包每秒几十个，不能每个都刷
    private val speakerUiAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 语音包里的呼号和设备型号就是正在说话的人。
     * 自己的回声不算。时长用已经解码的 PCM 累计，语音条按这个变长。
     */
    private fun noteLiveSpeaker(
        serverId: String,
        packet: Nrl21Protocol.Packet,
        durationMs: Long,
        dmrId: Int,
        opus: Boolean,
        mdcLabel: String
    ) {
        val mine = connectionStates[serverId] ?: return
        if (packet.callSign.equals(mine.callsign, ignoreCase = true) && packet.ssid == mine.ssid) return
        val roomId = _listenRooms.value[serverId] ?: mine.currentRoomId
        val now = System.currentTimeMillis()
        _liveSpeakers.update { current ->
            val prev = current[serverId]
            val same = prev != null &&
                prev.callsign == packet.callSign &&
                prev.ssid == packet.ssid &&
                prev.roomId == roomId &&
                prev.devModel == packet.devModel &&
                prev.opus == opus &&
                prev.dmrId == dmrId &&
                prev.mdcLabel == mdcLabel
            if (same && now - (speakerUiAt[serverId] ?: 0L) < 200L) {
                current
            } else {
                speakerUiAt[serverId] = now
                current + (serverId to LiveSpeaker(
                    serverId, roomId, packet.callSign, packet.ssid, packet.devModel,
                    durationMs, opus, dmrId, mdcLabel
                ))
            }
        }
    }

    private fun clearLiveSpeaker(serverId: String) {
        speakerUiAt.remove(serverId)
        _liveSpeakers.update { current ->
            if (serverId !in current) current else current - serverId
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
