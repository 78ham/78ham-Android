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
import com.ham78.app.data.ServerConfig
import com.ham78.app.data.SettingsRepository
import com.ham78.app.location.LocationManager
import com.ham78.app.network.ApiClient
import com.ham78.app.network.ConnectionState
import com.ham78.app.network.MessageStore
import com.ham78.app.network.MultiServerManager
import com.ham78.app.network.Nrl21Protocol
import com.ham78.app.network.ServerConnection
import com.ham78.app.network.UdpClient
import com.ham78.app.ptt.PttController
import com.ham78.app.ptt.DeviceKeyProfiles
import com.ham78.app.ptt.PttButtonReceiver
import com.ham78.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 对讲前台服务（多服务器版）
 * 支持同时连接多个服务器，文本消息收发，位置上传
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
    private lateinit var pttController: PttController
    private lateinit var locationManager: LocationManager
    private lateinit var multiServerManager: MultiServerManager
    private lateinit var messageStore: MessageStore

    // 状态暴露
    private val _serverConnections = MutableStateFlow<List<ServerConnection>>(emptyList())
    val serverConnections: StateFlow<List<ServerConnection>> = _serverConnections.asStateFlow()

    val activeServerId: StateFlow<String> get() = multiServerManager.activeServerId

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _receivedMessages = MutableSharedFlow<VoiceMessage>(extraBufferCapacity = 64)
    val receivedMessages: SharedFlow<VoiceMessage> = _receivedMessages

    // 文本消息
    val textMessages: StateFlow<List<MessageStore.TextMessage>> get() = messageStore.allMessages

    inner class LocalBinder : Binder() {
        fun getService(): TalkService = this@TalkService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        settingsRepository = SettingsRepository(this)
        locationManager = LocationManager(this)
        messageStore = MessageStore.getInstance()

        // 创建多服务器管理器，提供 AudioManager 工厂
        multiServerManager = MultiServerManager { udpClient ->
            AudioManager(this, udpClient)
        }

        // 设置消息回调
        multiServerManager.onTextMessageReceived = { serverId, callsign, ssid, content, timestamp ->
            val serverName = multiServerManager.serverConnections.value
                .find { it.serverId == serverId }?.name ?: serverId

            // 根据内容判断消息类型
            val msgType = when {
                content.startsWith("[loc]") -> MessageStore.MessageType.LOCATION
                content.startsWith("[语音]") -> MessageStore.MessageType.VOICE
                else -> MessageStore.MessageType.TEXT
            }

            val textContent = if (content.startsWith("[loc]")) {
                "📍 位置: ${content.removePrefix("[loc]")}"
            } else {
                content
            }

            messageStore.addMessage(
                MessageStore.TextMessage(
                    id = UUID.randomUUID().toString(),
                    serverId = serverId,
                    serverName = serverName,
                    callsign = callsign,
                    ssid = ssid,
                    content = textContent,
                    timestamp = timestamp,
                    timestampMs = System.currentTimeMillis(),
                    isSelf = false,
                    type = msgType
                )
            )
        }

        multiServerManager.onVoiceReceived = { serverId, callsign, ssid, clipId, durationMs ->
            val serverName = multiServerManager.serverConnections.value
                .find { it.serverId == serverId }?.name ?: serverId

            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())

            val durationLabel = if (durationMs > 0) "%.1f″".format(durationMs / 1000.0) else ""
            val content = if (durationLabel.isNotEmpty()) "[语音] $durationLabel" else "[语音]"

            messageStore.addMessage(
                MessageStore.TextMessage(
                    id = UUID.randomUUID().toString(),
                    serverId = serverId,
                    serverName = serverName,
                    callsign = callsign,
                    ssid = ssid,
                    content = content,
                    timestamp = timestamp,
                    timestampMs = System.currentTimeMillis(),
                    isSelf = false,
                    type = MessageStore.MessageType.VOICE,
                    voiceClipId = clipId,
                    voiceDurationMs = durationMs
                )
            )

            serviceScope.launch {
                _receivedMessages.emit(VoiceMessage(callsign, ssid, content, timestamp, 1))
            }
        }

        // 监听多服务器状态
        serviceScope.launch {
            multiServerManager.serverConnections.collect { connections ->
                _serverConnections.value = connections
                _isLoggedIn.value = connections.any { it.isLoggedIn }
                updateNotification(buildNotificationText(connections))
            }
        }

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

        // 自动连接保存的服务器
        val settings = settingsRepository.loadSettings()
        if (settings.autoConnect && settings.username.isNotEmpty() && settings.password.isNotEmpty()) {
            serviceScope.launch {
                val serverConfig = ServerConfig(
                    id = "${settings.serverAddress}:${settings.serverPort}",
                    name = settings.serverAddress,
                    host = settings.serverAddress,
                    port = settings.serverPort,
                    username = settings.username,
                    password = settings.password,
                    autoConnect = true
                )
                connectToServer(serverConfig)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        multiServerManager.release()
        pttController.release()
        serviceScope.cancel()

        PttButtonReceiver.listener = null
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val componentName = android.content.ComponentName(this, PttButtonReceiver::class.java)
            audioManager.unregisterMediaButtonEventReceiver(componentName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister media button receiver", e)
        }

        isRunning = false
    }

    // ============== 多服务器操作 ==============

    suspend fun connectToServer(config: ServerConfig): Boolean {
        return multiServerManager.connectToServer(config)
    }

    fun disconnectFromServer(serverId: String) {
        multiServerManager.disconnectFromServer(serverId)
    }

    fun switchActiveServer(serverId: String) {
        multiServerManager.switchActiveServer(serverId)
    }

    fun getServerConnections(): List<ServerConnection> = _serverConnections.value

    // ============== 频道操作 ==============

    fun joinRoom(serverId: String, roomId: Int) {
        multiServerManager.joinRoom(serverId, roomId)
    }

    suspend fun loadRoomList(serverId: String): List<ApiClient.RoomInfo> {
        return multiServerManager.loadRoomList(serverId)
    }

    // ============== 语音操作 ==============

    fun startTransmitting(): Boolean {
        return multiServerManager.startTransmitting()
    }

    fun stopTransmitting() {
        multiServerManager.stopTransmitting()
    }

    fun isTransmitting(): Boolean = multiServerManager.isTransmitting()
    fun isReceiving(): Boolean = multiServerManager.isReceiving()

    /** 回放某条语音消息（语音回放） */
    fun replayVoice(clipId: String) {
        if (clipId.isEmpty()) return
        val pcm = com.ham78.app.audio.VoiceClipStore.get(clipId)
        if (pcm == null) {
            Log.w(TAG, "replayVoice: clip not found or expired, id=$clipId")
            return
        }
        multiServerManager.replayVoiceClip(pcm)
    }

    fun handleKeyEvent(event: android.view.KeyEvent): Boolean {
        return pttController.onKeyEvent(event)
    }

    // ============== 文本消息 ==============

    /**
     * 发送文本消息到指定服务器
     */
    fun sendTextMessage(serverId: String, text: String) {
        val connection = multiServerManager.getConnection(serverId)
        if (connection == null) {
            Log.e(TAG, "sendTextMessage: no connection for serverId=$serverId")
            return
        }
        val userInfo = connection.userInfo
        if (userInfo == null) {
            Log.e(TAG, "sendTextMessage: no userInfo for serverId=$serverId (not logged in?)")
            return
        }
        val deviceData = connection.deviceData
        val udpClient = connection.udpClient

        if (!udpClient.isConnected()) {
            Log.e(TAG, "sendTextMessage: UDP not connected for serverId=$serverId (state=${udpClient.connectionState.value})")
            return
        }

        val ssid = deviceData?.ssid ?: 179
        val dmrId = deviceData?.dmrId ?: userInfo.dmrId

        Log.d(TAG, "sendTextMessage: serverId=$serverId, callsign=${userInfo.callsign}, ssid=$ssid, dmrId=$dmrId, text=${text.take(20)}")
        val sent = multiServerManager.sendTextMessage(serverId, userInfo.callsign, text, ssid, dmrId)

        // 添加到消息存储（只在发送成功时）
        if (sent) {
            val serverName = _serverConnections.value.find { it.serverId == serverId }?.name ?: serverId
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())

            messageStore.addMessage(
                MessageStore.TextMessage(
                    id = UUID.randomUUID().toString(),
                    serverId = serverId,
                    serverName = serverName,
                    callsign = userInfo.callsign,
                    ssid = ssid,
                    content = text,
                    timestamp = timestamp,
                    timestampMs = System.currentTimeMillis(),
                    isSelf = true,
                    type = MessageStore.MessageType.TEXT
                )
            )
        } else {
            Log.e(TAG, "sendTextMessage: failed to send message for serverId=$serverId")
        }
    }

    /**
     * 发送文本到活跃服务器
     */
    fun sendTextMessageToActive(text: String) {
        val activeId = multiServerManager.activeServerId.value
        if (activeId.isNotEmpty()) {
            sendTextMessage(activeId, text)
        }
    }

    // ============== 位置上传 ==============

    /**
     * 上传当前位置到指定服务器
     */
    suspend fun uploadLocation(serverId: String): Boolean {
        if (!locationManager.hasLocationPermission()) {
            showToast("请先授予位置权限")
            return false
        }

        val locationResult = locationManager.getCurrentLocation()
        locationResult.fold(
            onSuccess = { (lat, lng) ->
                val connection = multiServerManager.getConnection(serverId) ?: return false
                val userInfo = connection.userInfo ?: return false
                val deviceData = connection.deviceData

                val ssid = deviceData?.ssid ?: 179
                val dmrId = deviceData?.dmrId ?: userInfo.dmrId

                multiServerManager.sendLocation(serverId, userInfo.callsign, lat, lng, ssid, dmrId)

                val serverName = _serverConnections.value.find { it.serverId == serverId }?.name ?: serverId
                val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())

                messageStore.addMessage(
                    MessageStore.TextMessage(
                        id = UUID.randomUUID().toString(),
                        serverId = serverId,
                        serverName = serverName,
                        callsign = userInfo.callsign,
                        ssid = ssid,
                        content = "📍 已上传位置: ${"%.4f".format(lat)}, ${"%.4f".format(lng)}",
                        timestamp = timestamp,
                        timestampMs = System.currentTimeMillis(),
                        isSelf = true,
                        type = MessageStore.MessageType.LOCATION
                    )
                )

                showToast("位置已上传")
                return true
            },
            onFailure = { error ->
                showToast("获取位置失败: ${error.message}")
                return false
            }
        )
    }

    /**
     * 上传位置到活跃服务器
     */
    suspend fun uploadLocationToActive(): Boolean {
        val activeId = multiServerManager.activeServerId.value
        return if (activeId.isNotEmpty()) {
            uploadLocation(activeId)
        } else {
            showToast("没有活跃的服务器连接")
            false
        }
    }

    // ============== 内部方法 ==============

    private fun setupPttController() {
        val settings = settingsRepository.loadSettings()

        val deviceProfile = DeviceKeyProfiles.detect()
        val effectivePttKey = if (settings.pttKeyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            deviceProfile.pttKeyCode
        } else {
            settings.pttKeyCode
        }

        pttController = PttController(this)
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
                }
            },
            pttKey = effectivePttKey,
            screenOffEnabled = settings.screenOffPtt
        )

        // 注册设备方案中的额外广播
        if (deviceProfile.useBroadcastPtt) {
            deviceProfile.broadcastActions.forEach { action ->
                try {
                    val filter = android.content.IntentFilter(action)
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent?) {
                            when (intent?.action) {
                                "android.intent.action.PTT.down" -> {
                                    pttController.onKeyEvent(android.view.KeyEvent(
                                        android.view.KeyEvent.ACTION_DOWN,
                                        deviceProfile.pttKeyCode
                                    ))
                                }
                                "android.intent.action.PTT.up" -> {
                                    pttController.onKeyEvent(android.view.KeyEvent(
                                        android.view.KeyEvent.ACTION_UP,
                                        deviceProfile.pttKeyCode
                                    ))
                                }
                            }
                        }
                    }
                    registerReceiver(receiver, filter)
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register media button receiver", e)
        }
    }

    private fun buildNotificationText(connections: List<ServerConnection>): String {
        if (connections.isEmpty()) return "服务运行中"
        val online = connections.count { it.isOnline }
        val total = connections.size
        val active = connections.find { it.isActive }
        return if (active != null && active.isOnline) {
            "已连接 $online/$total - ${active.callsign}"
        } else {
            "在线: $online/$total 服务器"
        }
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
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("78HAM 对讲")
            .setContentText("服务运行中，按PTT开始对讲")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
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

    data class VoiceMessage(
        val callsign: String,
        val ssid: Int,
        val content: String,
        val timestamp: String,
        val type: Int
    )
}
