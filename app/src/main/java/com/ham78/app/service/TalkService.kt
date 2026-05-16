package com.ham78.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.ham78.app.R
import com.ham78.app.audio.AudioManager
import com.ham78.app.data.AudioCodec
import com.ham78.app.data.SettingsRepository
import com.ham78.app.network.ConnectionState
import com.ham78.app.network.ApiClient
import com.ham78.app.network.Nrl21Protocol
import com.ham78.app.network.UdpClient
import com.ham78.app.protocol.ProtocolManager
import com.ham78.app.protocol.ProtocolType
import com.ham78.app.ptt.PttController
import com.ham78.app.ptt.DeviceKeyProfiles
import com.ham78.app.ptt.PttButtonReceiver
import com.ham78.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job

/**
 * 对讲前台服务
 * 保持应用在后台运行，处理对讲功能
 * 
 * 流程：
 * 1. HTTP 登录获取用户信息和 Token
 * 2. 使用获取的 DMR ID 和呼号连接 UDP 语音服务
 * 3. 发送心跳包维持连接
 */
class TalkService : Service() {
    
    companion object {
        private const val TAG = "TalkService"
        private const val NOTIFICATION_CHANNEL_ID = "ham78_talk_channel"
        private const val NOTIFICATION_ID = 1
        private const val SERVICE_NAME = "78HAM 对讲服务"
        
        @Volatile
        var isRunning = false
    }
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var udpClient: UdpClient
    private lateinit var audioManager: AudioManager
    private lateinit var pttController: PttController
    private lateinit var protocolManager: ProtocolManager
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _lastReceivedCallsign = MutableStateFlow("")
    val lastReceivedCallsign: StateFlow<String> = _lastReceivedCallsign.asStateFlow()
    
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _receivedMessages = MutableSharedFlow<VoiceMessage>(extraBufferCapacity = 64)
    val receivedMessages: SharedFlow<VoiceMessage> = _receivedMessages

    private val _roomList = MutableStateFlow<List<ApiClient.RoomInfo>>(emptyList())
    val roomList: StateFlow<List<ApiClient.RoomInfo>> = _roomList.asStateFlow()

    private val _currentRoomId = MutableStateFlow(0)
    val currentRoomId: StateFlow<Int> = _currentRoomId.asStateFlow()

    private val _onlineCount = MutableStateFlow(0)
    val onlineCount: StateFlow<Int> = _onlineCount.asStateFlow()

    private val _currentGroupName = MutableStateFlow("")
    val currentGroupName: StateFlow<String> = _currentGroupName.asStateFlow()

    private var refreshJob: Job? = null
    data class VoiceMessage(
        val callsign: String,
        val ssid: Int,
        val content: String,
        val timestamp: String,
        val type: Int
    )

    private var loginToken: String? = null
    private var currentUserInfo: ApiClient.UserInfo? = null
    private var currentDeviceData: ApiClient.DeviceData? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): TalkService = this@TalkService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        settingsRepository = SettingsRepository(this)
        udpClient = UdpClient()
        audioManager = AudioManager(this, udpClient)
        pttController = PttController(this)
        protocolManager = ProtocolManager()

        setupNetworkListener()
        setupPttController()
        setupPttButtonReceiver()
        createNotificationChannel()
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        
        startForeground(NOTIFICATION_ID, createNotification())
        isRunning = true
        
        // 自动连接（如果有保存的用户信息）
        val settings = settingsRepository.loadSettings()
        if (settings.autoConnect && settings.username.isNotEmpty() && settings.password.isNotEmpty()) {
            serviceScope.launch {
                loginAndConnect()
            }
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        disconnect()
        audioManager.release()
        pttController.release()
        udpClient.disconnect()
        serviceScope.cancel()

        PttButtonReceiver.listener = null
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val componentName = android.content.ComponentName(this, PttButtonReceiver::class.java)
            audioManager.unregisterMediaButtonEventReceiver(componentName)
            Log.d(TAG, "Media button receiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister media button receiver", e)
        }

        isRunning = false
    }
    
    private fun setupNetworkListener() {
        udpClient.packetListener = object : UdpClient.PacketListener {
            override fun onPacketReceived(packet: Nrl21Protocol.Packet) {
                serviceScope.launch {
                    _connectionState.value = ConnectionState.CONNECTED
                    
                    when (packet.type) {
                        Nrl21Protocol.TYPE_VOICE, Nrl21Protocol.TYPE_OPUS -> {
                            audioManager.handleReceivedAudio(packet.data, packet.type, packet.callSign)
                            _lastReceivedCallsign.value = packet.callSign

                            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                .format(java.util.Date())
                            _receivedMessages.emit(
                                VoiceMessage(
                                    callsign = packet.callSign,
                                    ssid = packet.ssid,
                                    content = "[语音]",
                                    timestamp = timestamp,
                                    type = packet.type
                                )
                            )
                        }
                    }
                }
            }
            
            override fun onError(error: String) {
                Log.e(TAG, "Network error: $error")
                updateNotification("网络错误: $error")
            }
            
            override fun onConnectionLost() {
                Log.w(TAG, "Connection lost, attempting to reconnect...")
                _connectionState.value = ConnectionState.DISCONNECTED
                updateNotification("连接已断开，正在重新连接...")
                
                serviceScope.launch {
                    if (_isLoggedIn.value && currentUserInfo != null) {
                        connect()
                    }
                }
            }
        }
        
        serviceScope.launch {
            udpClient.connectionState.collect { state ->
                _connectionState.value = state
                when (state) {
                    ConnectionState.CONNECTED -> updateNotification("已连接 - ${currentUserInfo?.callsign ?: "未知"}")
                    ConnectionState.CONNECTING -> updateNotification("连接中...")
                    ConnectionState.DISCONNECTED -> updateNotification("未连接")
                }
            }
        }
    }
    
    private fun setupPttController() {
        val settings = settingsRepository.loadSettings()

        // 自动识别设备按键方案
        val deviceProfile = DeviceKeyProfiles.detect()
        val effectivePttKey = if (settings.pttKeyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            // 用户未自定义，使用设备方案
            deviceProfile.pttKeyCode
        } else {
            settings.pttKeyCode
        }

        Log.i(TAG, "Device profile: ${deviceProfile.name}, PTT keyCode: 0x${effectivePttKey.toString(16)}, broadcast: ${deviceProfile.useBroadcastPtt}")

        pttController.initialize(
            listener = object : PttController.PttListener {
                override fun onPttPressed() {
                    startTransmitting()
                }

                override fun onPttReleased() {
                    stopTransmitting()
                }

                override fun onPttLongPress() {
                    Log.d(TAG, "PTT long press detected")
                    if (!_isLoggedIn.value) {
                        serviceScope.launch {
                            loginAndConnect()
                        }
                    }
                }
            },
            pttKey = effectivePttKey,
            screenOffEnabled = settings.screenOffPtt
        )

        // 注册设备方案中的额外广播（如 MTK PTT 广播）
        if (deviceProfile.useBroadcastPtt) {
            deviceProfile.broadcastActions.forEach { action ->
                try {
                    val filter = android.content.IntentFilter(action)
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent?) {
                            when (intent?.action) {
                                "android.intent.action.PTT.down" -> {
                                    Log.d(TAG, "Device broadcast PTT down")
                                    pttController.onKeyEvent(android.view.KeyEvent(
                                        android.view.KeyEvent.ACTION_DOWN,
                                        deviceProfile.pttKeyCode
                                    ))
                                }
                                "android.intent.action.PTT.up" -> {
                                    Log.d(TAG, "Device broadcast PTT up")
                                    pttController.onKeyEvent(android.view.KeyEvent(
                                        android.view.KeyEvent.ACTION_UP,
                                        deviceProfile.pttKeyCode
                                    ))
                                }
                            }
                        }
                    }
                    registerReceiver(receiver, filter)
                    Log.i(TAG, "Registered broadcast: $action")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register broadcast: $action", e)
                }
            }
        }
    }
    
    private fun setupPttButtonReceiver() {
        PttButtonReceiver.listener = object : PttButtonReceiver.PttButtonListener {
            override fun onPttButtonPressed() {
                startTransmitting()
            }

            override fun onPttButtonReleased() {
                stopTransmitting()
            }
        }

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val componentName = android.content.ComponentName(this, PttButtonReceiver::class.java)
            audioManager.registerMediaButtonEventReceiver(componentName)
            Log.d(TAG, "Media button receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register media button receiver", e)
        }
    }

    /**
     * 登录并连接
     * 流程：HTTP 登录 -> 获取用户信息 -> 连接 UDP
     */
    suspend fun loginAndConnect(): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsRepository.loadSettings()
        
        if (settings.username.isEmpty() || settings.password.isEmpty()) {
            showToast("请先设置用户名和密码")
            return@withContext false
        }
        
        _connectionState.value = ConnectionState.CONNECTING
        
        val serverHost = settings.serverAddress
        val loginResult = ApiClient.login(
            serverHost = serverHost,
            username = settings.username,
            password = settings.password
        )
        
        loginResult.fold(
            onSuccess = { userInfo ->
                loginToken = ApiClient.token
                currentUserInfo = userInfo
                _isLoggedIn.value = true

                // 获取设备信息以获取真实 ssid/devModel/dmrId
                try {
                    val deviceResult = ApiClient.getDevice(serverHost, userInfo.callsign, 100)
                    currentDeviceData = deviceResult.getOrNull()
                    if (currentDeviceData != null) {
                        Log.d(TAG, "Device loaded: ssid=${currentDeviceData!!.ssid}, devModel=${currentDeviceData!!.devModel}, dmrId=${currentDeviceData!!.dmrId}")
                    } else {
                        Log.w(TAG, "Failed to load device data, using defaults")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "获取设备信息失败", e)
                }

                Log.d(TAG, "Login success: ${userInfo.callsign} (DMR:${userInfo.dmrId}), token=${ApiClient.token}")

                connectUdp(userInfo)
                startRefreshData()
                true
            },
            onFailure = { error ->
                Log.e(TAG, "Login failed", error)
                showToast("登录失败: ${error.message}")
                _connectionState.value = ConnectionState.DISCONNECTED
                false
            }
        )
    }
    
    /**
     * 直接连接 UDP（如果已登录过）
     */
    fun connect(): Boolean {
        val settings = settingsRepository.loadSettings()
        
        return if (_isLoggedIn.value && currentUserInfo != null) {
            // 已登录，直接连 UDP
            connectUdp(currentUserInfo!!)
        } else {
            // 未登录，先登录
            serviceScope.launch {
                loginAndConnect()
            }
            true
        }
    }
    
    private fun connectUdp(userInfo: ApiClient.UserInfo): Boolean {
        val settings = settingsRepository.loadSettings()
        
        // 使用服务器返回的地址，或默认配置
        val serverHost = userInfo.server ?: settings.serverAddress
        val port = userInfo.serverPort ?: settings.serverPort
        
        audioManager.setCodec(settings.codec)
        audioManager.setVolume(settings.volume)
        
        val device = currentDeviceData
        // 用户设置的 SSID 优先（非0时），否则用 API 返回的值，最终 fallback 100
        val ssid = if (settings.ssid != 0) settings.ssid else (device?.ssid ?: 78)
        val devModel = device?.devModel ?: 101  // Android 默认设备型号 101
        val dmrId = device?.dmrId ?: userInfo.dmrId

        Log.d(TAG, "connectUdp: ssid=$ssid (settings=${settings.ssid}, device=${device?.ssid}), devModel=$devModel, dmrId=$dmrId")

        val success = udpClient.connect(
            serverHost = serverHost,
            port = port,
            id = dmrId,
            call = userInfo.callsign,
            ssidVal = ssid,
            devModelVal = devModel
        )
        
        if (success) {
            updateNotification("已连接 - ${userInfo.callsign}")
        }
        
        return success
    }
    
    fun disconnect() {
        stopRefreshData()
        udpClient.disconnect()
        _isLoggedIn.value = false
        loginToken = null
        currentUserInfo = null
        currentDeviceData = null
        audioManager.stopTransmitting()
        updateNotification("服务运行中")
    }
    
    fun logout() {
        disconnect()
    }
    
    fun joinRoom(roomId: Int) {
        val settings = settingsRepository.loadSettings()
        val userInfo = currentUserInfo ?: return

        _currentRoomId.value = roomId

        serviceScope.launch {
            try {
                if (ApiClient.token.isNotEmpty()) {
                    val ssid = currentDeviceData?.ssid ?: 78
                    val deviceResult = ApiClient.getDevice(settings.serverAddress, userInfo.callsign, ssid)
                    val device = deviceResult.getOrNull()
                    if (device != null) {
                        currentDeviceData = device
                        val result = ApiClient.updateDevice(settings.serverAddress, device, roomId)
                        result.fold(
                            onSuccess = {
                                Log.d(TAG, "切换频道成功: $roomId")
                                refreshData()
                            },
                            onFailure = { e ->
                                Log.e(TAG, "切换频道失败: ${e.message}")
                            }
                        )
                    } else {
                        Log.e(TAG, "获取设备信息失败，无法切换频道")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Join room failed: ${e.message}")
            }
        }
    }

    fun loadRoomList() {
        val settings = settingsRepository.loadSettings()

        Log.d(TAG, "loadRoomList called, ApiClient.token=${ApiClient.token}, isLoggedIn=${_isLoggedIn.value}")

        if (ApiClient.token.isEmpty()) {
            Log.w(TAG, "Token is empty, cannot load room list")
            return
        }

        serviceScope.launch {
            try {
                val result = ApiClient.getRoomList(settings.serverAddress)
                result.fold(
                    onSuccess = { rooms ->
                        _roomList.value = rooms
                        Log.d(TAG, "获取频道列表成功: ${rooms.size}个频道")
                    },
                    onFailure = { e ->
                        Log.e(TAG, "获取频道列表失败: ${e.message}")
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Load room list failed: ${e.message}")
            }
        }
    }
    
    fun startTransmitting(): Boolean {
        if (!_isLoggedIn.value || _connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot transmit: not connected")
            return false
        }

        return audioManager.startTransmitting()
    }
    
    fun stopTransmitting() {
        audioManager.stopTransmitting()
    }
    
    fun isTransmitting(): Boolean = audioManager.isTransmitting.value
    
    fun isReceiving(): Boolean = audioManager.isReceiving.value
    
    fun isConnected(): Boolean = udpClient.isConnected()
    
    fun handleKeyEvent(event: android.view.KeyEvent): Boolean {
        return pttController.onKeyEvent(event)
    }
    
    fun getCurrentUser(): ApiClient.UserInfo? = currentUserInfo
    
    fun setProtocol(type: ProtocolType) {
        protocolManager.setProtocol(type)
        Log.d(TAG, "Protocol set to: $type")
    }
    
    fun getCurrentProtocol(): ProtocolType = protocolManager.getCurrentProtocol()
    
    fun encodeWithProtocol(data: String, type: ProtocolType): ByteArray? {
        return protocolManager.encode(data, type)
    }
    
    fun decodeWithProtocol(data: ByteArray, type: ProtocolType): String? {
        return protocolManager.decode(data, type)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                SERVICE_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持对讲服务在后台运行"
                setSound(null, null)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("78HAM 对讲")
            .setContentText("服务运行中，按PTT开始对讲")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    fun updateNotification(content: String) {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("78HAM 对讲")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@TalkService, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRefreshData() {
        stopRefreshData()
        refreshJob = serviceScope.launch {
            while (true) {
                ensureActive()
                refreshData()
                delay(5000)
            }
        }
    }

    private fun stopRefreshData() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private suspend fun refreshData() {
        val settings = settingsRepository.loadSettings()
        val userInfo = currentUserInfo ?: return

        try {
            val ssid = currentDeviceData?.ssid ?: 78
            val deviceResult = ApiClient.getDevice(settings.serverAddress, userInfo.callsign, ssid)
            deviceResult.fold(
                onSuccess = { device ->
                    currentDeviceData = device
                    Log.d(TAG, "refreshData: device groupId=${device.groupId}, isOnline=${device.isOnline}, ssid=${device.ssid}, devModel=${device.devModel}")
                    if (device.groupId > 0) {
                        _currentRoomId.value = device.groupId
                        val groupResult = ApiClient.getGroup(settings.serverAddress, device.groupId)
                        groupResult.fold(
                            onSuccess = { group ->
                                _onlineCount.value = group.onlineCount
                                _currentGroupName.value = group.name
                                Log.d(TAG, "在线人数: ${group.onlineCount}, 群组: ${group.name}, 总设备: ${group.deviceCount}")
                            },
                            onFailure = { e ->
                                Log.e(TAG, "获取群组信息失败: ${e.message}")
                            }
                        )
                    } else {
                        Log.w(TAG, "refreshData: device groupId=0, user has not joined any group yet")
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "获取设备信息失败: ${e.message}")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "refreshData异常: ${e.message}")
        }
    }
}
