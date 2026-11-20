package com.ham78.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.ham78.app.R
import com.ham78.app.audio.AudioManager
import com.ham78.app.audio.CwEncoder
import com.ham78.app.audio.DtmfEncoder
import com.ham78.app.audio.MemoRecorder
import com.ham78.app.audio.RogerBeep
import com.ham78.app.audio.SstvEncoder
import com.ham78.app.audio.TtsAnnouncer
import com.ham78.app.data.BroadcastSchedule
import com.ham78.app.data.ScheduleMode
import com.ham78.app.data.ServerConfig
import com.ham78.app.data.SettingsRepository
import com.ham78.app.location.LocationManager
import com.ham78.app.network.ApiClient
import com.ham78.app.network.MessageStore
import com.ham78.app.network.MultiServerManager
import com.ham78.app.network.NetworkMonitor
import com.ham78.app.network.ServerConnection
import com.ham78.app.ptt.PttController
import com.ham78.app.ptt.DeviceKeyProfiles
import com.ham78.app.ptt.PttButtonReceiver
import com.ham78.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        private const val DEFAULT_SSID = 179

        @Volatile
        var isRunning = false
    }

    private val timestampFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var pttController: PttController
    private lateinit var locationManager: LocationManager
    private lateinit var multiServerManager: MultiServerManager
    private lateinit var messageStore: MessageStore
    private lateinit var networkMonitor: NetworkMonitor
    private val pttBroadcastReceivers = mutableListOf<BroadcastReceiver>()

    // 发送工具：TTS 播报与录音回放（懒创建，只在第一次用时初始化）
    private var ttsAnnouncer: TtsAnnouncer? = null
    private var memoRecorder: MemoRecorder? = null

    // 后台保活：防止 CPU 休眠和 Wi-Fi 休眠导致 UDP 心跳中断被服务器踢下线
    private var keepAliveWakeLock: PowerManager.WakeLock? = null
    private var keepAliveWifiLock: WifiManager.WifiLock? = null

    // 状态暴露
    private val _serverConnections = MutableStateFlow<List<ServerConnection>>(emptyList())
    val serverConnections: StateFlow<List<ServerConnection>> = _serverConnections.asStateFlow()

    val activeServerId: StateFlow<String> get() = multiServerManager.activeServerId

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    val transmittingState: StateFlow<Boolean> get() = multiServerManager.transmittingState
    val receivingState: StateFlow<Boolean> get() = multiServerManager.receivingState
    val playingVoiceClipId: StateFlow<String?> get() = multiServerManager.playingVoiceClipId

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

        multiServerManager = MultiServerManager { udpClient ->
            AudioManager(this, udpClient)
        }

        setupMessageCallbacks()
        setupServerStateListener()
        setupSettingsListener()
        setupNetworkMonitor()
        setupPttController()
        setupPttButtonReceiver()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        // manifest 声明了 microphone 类型：Android 14+ 上 RECORD_AUDIO 未授权时
        // startForeground 会抛 SecurityException，这里兜底避免启动闪退
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed, stopping service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        acquireKeepAliveLocks()

        autoConnectAllServers()
        startScheduleWatcher()

        return START_STICKY
    }

    /**
     * 同时自动连接所有已保存的服务器；
     * 列表为空时回退到默认设置中的服务器
     */
    private fun autoConnectAllServers() {
        val settings = settingsRepository.loadSettings()
        if (settings.username.isEmpty() || settings.password.isEmpty()) return

        val serversToConnect = if (settings.servers.isNotEmpty()) {
            settings.servers
        } else {
            listOf(
                ServerConfig(
                    id = "${settings.serverAddress}:${settings.serverPort}",
                    name = settings.serverAddress,
                    host = settings.serverAddress,
                    port = settings.serverPort,
                    username = settings.username,
                    password = settings.password,
                    autoConnect = true
                )
            )
        }

        serversToConnect.forEach { config ->
            val alreadyOnline = _serverConnections.value.any {
                it.serverId == config.id && it.isOnline
            }
            if (alreadyOnline) {
                Log.d(TAG, "Auto-connect skipped, ${config.id} already online")
            } else {
                serviceScope.launch { connectToServer(config) }
            }
        }
    }

    /**
     * 获取常驻保活锁（服务生命周期内持有）：
     * - PARTIAL_WAKE_LOCK：熄屏后 CPU 继续运行，心跳定时器不被推迟
     * - WifiLock HIGH_PERF：Wi-Fi 不进入省电休眠，UDP 链路保持畅通
     */
    private fun acquireKeepAliveLocks() {
        if (keepAliveWakeLock?.isHeld == true) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        keepAliveWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "78HAM:KeepAliveWakeLock"
        ).apply {
            setReferenceCounted(false)
            try { acquire() } catch (e: Exception) { Log.e(TAG, "acquireWakeLock failed", e) }
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        keepAliveWifiLock = wifiManager.createWifiLock(
            wifiMode,
            "78HAM:KeepAliveWifiLock"
        ).apply {
            setReferenceCounted(false)
            try { acquire() } catch (e: Exception) { Log.e(TAG, "acquireWifiLock failed", e) }
        }

        Log.d(TAG, "Keep-alive locks acquired")
    }

    private fun releaseKeepAliveLocks() {
        try { keepAliveWakeLock?.release() } catch (_: Exception) {}
        try { keepAliveWifiLock?.release() } catch (_: Exception) {}
        keepAliveWakeLock = null
        keepAliveWifiLock = null
        Log.d(TAG, "Keep-alive locks released")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        releaseKeepAliveLocks()
        multiServerManager.release()
        pttController.release()
        networkMonitor.stop()
        ttsAnnouncer?.release()
        ttsAnnouncer = null
        memoRecorder?.cancel()
        memoRecorder = null
        serviceScope.cancel()

        PttButtonReceiver.listener = null
        pttBroadcastReceivers.forEach { receiver ->
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
        }
        pttBroadcastReceivers.clear()

        try {
            @Suppress("DEPRECATION")
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val componentName = android.content.ComponentName(this, PttButtonReceiver::class.java)
            @Suppress("DEPRECATION")
            audioManager.unregisterMediaButtonEventReceiver(componentName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister media button receiver", e)
        }

        isRunning = false
    }

    // ============== 多服务器操作 ==============

    suspend fun connectToServer(config: ServerConfig): Boolean =
        multiServerManager.connectToServer(config)

    fun disconnectFromServer(serverId: String) =
        multiServerManager.disconnectFromServer(serverId)

    fun switchActiveServer(serverId: String) =
        multiServerManager.switchActiveServer(serverId)

    fun getServerConnections(): List<ServerConnection> = _serverConnections.value

    // ============== 频道操作 ==============

    suspend fun joinRoom(serverId: String, roomId: Int) =
        multiServerManager.joinRoom(serverId, roomId)

    /** 只收听模式：接收该服务器的房间语音，不影响发送目标 */
    suspend fun listenRoom(serverId: String, roomId: Int) =
        multiServerManager.joinRoom(serverId, roomId, asListen = true)

    /** 停止收听某台服务器 */
    fun stopListening(serverId: String) = multiServerManager.stopListening(serverId)

    val listenRooms: StateFlow<Map<String, Int>> get() = multiServerManager.listenRooms

    suspend fun loadRoomList(serverId: String): List<ApiClient.RoomInfo> =
        multiServerManager.loadRoomList(serverId)

    // ============== 语音操作 ==============

    fun startTransmitting(): Boolean = multiServerManager.startTransmitting()

    /**
     * 松开 PTT：停录 + 自动追加尾音。
     *
     * 尾音必须挂在这里发——之前它只在"发信号"（CW/DTMF/SSTV/TTS/录音）之后追加，
     * 普通语音讲话结束时反而一声不吭，这与业余无线电"讲完给个结束音"的习惯相反。
     */
    fun stopTransmitting() {
        // 先记下"刚才确实在发话"：没发过话就不该有结束音
        val wasTransmitting = multiServerManager.isActivelyTransmitting()
        multiServerManager.stopTransmitting()
        if (wasTransmitting) {
            // 独立协程：stopTransmitting 来自按键回调，不能是挂起函数
            serviceScope.launch { appendRogerBeep() }
        }
    }

    fun isTransmitting(): Boolean = multiServerManager.isTransmitting()
    fun isReceiving(): Boolean = multiServerManager.isReceiving()

    fun replayVoice(clipId: String) {
        if (clipId.isEmpty()) return
        val pcm = com.ham78.app.audio.VoiceClipStore.get(clipId)
        if (pcm == null) {
            Log.w(TAG, "replayVoice: clip not found or expired, id=$clipId")
            return
        }
        multiServerManager.replayVoiceClip(pcm, clipId)
    }

    fun stopReplayVoice() {
        multiServerManager.stopVoiceClip()
    }

    // ============== 发送工具（CW/DTMF/SSTV/TTS/录音） ==============
    // 所有入口统一走 transmitPcmToActive → AudioManager.transmitPcm：
    // 编码、切帧、发 UDP 都在那一条链路上，这里只负责"生成 PCM"。

    /** 发送 CW（摩尔斯电码）文本 */
    suspend fun sendCw(text: String, wpm: Int = 15): Boolean {
        if (text.isBlank()) return false
        // 编码是纯 CPU 计算（长文本能到几百毫秒），放到 IO 线程，别卡住界面
        val pcm = withContext(Dispatchers.Default) { CwEncoder.encode(text, wpm) }
        return sendPcmAndLog(pcm, "CW", text.take(30))
    }

    /** 发送 DTMF 拨号音序列（中继台控制命令等） */
    suspend fun sendDtmf(digits: String): Boolean {
        if (digits.isBlank()) return false
        val pcm = withContext(Dispatchers.Default) { DtmfEncoder.encode(digits) }
        return sendPcmAndLog(pcm, "DTMF", digits)
    }

    /**
     * 发送 SSTV 图片（Robot36 制式）。
     * pixels 是已缩放到 320×240 的 ARGB 像素数组。
     */
    suspend fun sendSstv(pixels: IntArray, width: Int = 320, height: Int = 240): Boolean {
        // Robot36 要生成 36 秒音频（约 576KB），必须在后台线程做
        val pcm = withContext(Dispatchers.Default) {
            SstvEncoder.encodeRobot36(pixels, width, height)
        }
        return sendPcmAndLog(pcm, "SSTV", "Robot36 ${width}x$height")
    }

    /** TTS 文字播报：合成语音后发射，播完自动追加尾音 */
    suspend fun sendTts(text: String): Boolean {
        if (text.isBlank()) return false
        val announcer = ttsAnnouncer ?: TtsAnnouncer(this).also { ttsAnnouncer = it }
        val pcm = announcer.synthesize(text) ?: run {
            showToast("TTS 合成失败（引擎未就绪或不支持中文）")
            return false
        }
        return sendPcmAndLog(pcm, "TTS", text.take(30))
    }

    // --- 录音回放状态：UI 需要知道"正在录音"来显示计时器 ---

    private val _isMemoRecording = MutableStateFlow(false)
    val isMemoRecording: StateFlow<Boolean> = _isMemoRecording.asStateFlow()

    private val _memoRecordSeconds = MutableStateFlow(0)
    val memoRecordSeconds: StateFlow<Int> = _memoRecordSeconds.asStateFlow()

    /** 开始本机录音（最长 60 秒，UI 显示计时） */
    suspend fun startMemoRecording(): Boolean {
        val recorder = memoRecorder ?: MemoRecorder(this).also { memoRecorder = it }
        val started = recorder.start()
        if (!started) {
            showToast("没有录音权限")
            return false
        }

        _isMemoRecording.value = true
        _memoRecordSeconds.value = 0
        // 计时协程：每秒 +1，录音结束后自动退出
        serviceScope.launch {
            while (_isMemoRecording.value) {
                delay(1000)
                if (_isMemoRecording.value) _memoRecordSeconds.value += 1
            }
        }
        return true
    }

    /** 结束录音并把整段录音发射出去 */
    suspend fun stopMemoRecordingAndSend(): Boolean {
        val recorder = memoRecorder ?: return false
        _isMemoRecording.value = false
        val pcm = recorder.stopAndTake() ?: run {
            showToast("没有录到内容")
            return false
        }
        return sendPcmAndLog(pcm, "录音", "${pcm.size / 16}ms")
    }

    /** 取消录音（丢弃） */
    fun cancelMemoRecording() {
        _isMemoRecording.value = false
        memoRecorder?.cancel()
    }

    /**
     * 统一的 PCM 发送入口：发出去 → 记一条消息 → 追加尾音。
     *
     * 【关键】用 serviceScope 而不是调用方的协程作用域来发：
     * 调用方是 UI（rememberCoroutineScope），用户一发 SSTV 就把对话框关掉的话，
     * UI 作用域被取消 → 正在发的那一半图片直接没了（服务端那边听到的是半张图）。
     * 信号发射属于"已经承诺要做的事"，不该被界面关闭打断。
     */
    private suspend fun sendPcmAndLog(pcm: ByteArray, label: String, summary: String): Boolean {
        val activeId = multiServerManager.activeServerId.value
        // 发射动作放到 serviceScope 里跑：它的生命周期跟着服务走，
        // 不受 UI 作用域（rememberCoroutineScope）取消的影响。
        // 等待时用 NonCancellable，保证"已经开始发"的信号一定发完。
        val job = serviceScope.async { multiServerManager.transmitPcmToActive(pcm) }
        val sent = withContext(NonCancellable) { job.await() }
        if (!sent) {
            Log.w(TAG, "send$label failed: no active room or busy")
            showToast("发送失败：请先加入频道（或 PTT 正在发射）")
            return false
        }

        // 信号发送也进消息流，用户能看到发过什么
        val callsign = _serverConnections.value.find { it.serverId == activeId }?.callsign ?: ""
        addOwnMessage(
            activeId, callsign, DEFAULT_SSID,
            "[$label] $summary",
            MessageStore.MessageType.TEXT
        )

        // 非尾音来源的信号发送完都自动追加当前设置的尾音
        appendRogerBeep()
        return true
    }

    // ============== 尾音（Roger Beep） ==============

    /**
     * 把当前设置的尾音发送到活跃房间（NONE 时静默跳过）。
     * 两个调用来源：松开 PTT（讲完话）、发送信号之后（CW/DTMF/SSTV/TTS/录音）。
     */
    private suspend fun appendRogerBeep() {
        val style = settingsRepository.settings.value.rogerBeep
        if (style == com.ham78.app.data.RogerBeepStyle.NONE) return

        val pcm = RogerBeep.encode(style)
        if (pcm.isEmpty()) return
        multiServerManager.transmitPcmToActive(pcm)
        Log.d(TAG, "Roger beep sent: $style")
    }

    // ============== 定时播报 ==============

    /**
     * 定时播报调度协程：每 30 秒对表一次，命中分钟边界时触发任务。
     *
     * 触发去重：EVERY_* 模式用"上一个已触发的分钟序号"防重复；
     * DAILY 用"今天已触发的任务 ID 集合"。两者都存内存，重启后重新计时无妨。
     */
    private var lastScheduleMinute = -1
    private val firedDailyToday = mutableSetOf<String>()
    private var firedDailyDate = ""

    fun startScheduleWatcher() {
        serviceScope.launch {
            while (isActive) {
                runScheduledBroadcasts()
                delay(30_000)   // 30 秒对表一次：分钟级任务，延迟最多半分钟，可接受
            }
        }
    }

    private suspend fun runScheduledBroadcasts() {
        val schedules = settingsRepository.settings.value.broadcastSchedules
        if (schedules.isEmpty()) return

        val now = java.time.LocalDateTime.now()
        val minuteOfDay = now.hour * 60 + now.minute
        val today = now.toLocalDate().toString()

        // 日期变化时清掉"今天已触发"记录
        if (today != firedDailyDate) {
            firedDailyDate = today
            firedDailyToday.clear()
        }
        // 分钟序号倒退（跨午夜/手动改时间）时重置去重
        if (minuteOfDay < lastScheduleMinute) lastScheduleMinute = -1

        for (schedule in schedules) {
            if (!schedule.enabled) continue
            val due = when (schedule.mode) {
                ScheduleMode.EVERY_MINUTE -> true
                ScheduleMode.EVERY_5_MIN -> minuteOfDay % 5 == 0
                ScheduleMode.EVERY_15_MIN -> minuteOfDay % 15 == 0
                ScheduleMode.DAILY -> minuteOfDay == schedule.dailyMinuteOfDay()
            }
            if (!due) continue

            // 去重：DAILY 每天一次（firedDailyToday 记录今天已触发的任务 ID）；
            // EVERY_* 每分钟一次（lastScheduleMinute 记录上一轮跑到的分钟）
            if (schedule.mode == ScheduleMode.DAILY) {
                if (!firedDailyToday.add(schedule.id)) continue   // 今天已触发过
            } else if (lastScheduleMinute == minuteOfDay) {
                continue                                          // 本分钟已经跑过一轮
            }

            Log.d(TAG, "Scheduled broadcast: ${schedule.name} (mode=${schedule.mode})")
            sendTts(schedule.content)
        }

        lastScheduleMinute = minuteOfDay
    }

    /** 手动立刻执行一条定时播报（UI"试播"按钮用） */
    suspend fun testBroadcast(schedule: BroadcastSchedule) {
        sendTts(schedule.content)
    }

    fun handleKeyEvent(event: android.view.KeyEvent): Boolean =
        pttController.onKeyEvent(event)

    // ============== 文本消息 ==============

    suspend fun sendTextMessage(serverId: String, text: String) {
        val connection = multiServerManager.getConnection(serverId) ?: run {
            Log.e(TAG, "sendTextMessage: no connection for serverId=$serverId")
            return
        }
        val userInfo = connection.userInfo ?: run {
            Log.e(TAG, "sendTextMessage: no userInfo for serverId=$serverId")
            return
        }
        val udpClient = connection.udpClient

        if (!udpClient.isConnected()) {
            Log.e(TAG, "sendTextMessage: UDP not connected for serverId=$serverId")
            return
        }

        val deviceData = connection.deviceData
        val ssid = deviceData?.ssid ?: DEFAULT_SSID
        val dmrId = deviceData?.dmrId ?: userInfo.dmrId

        Log.d(TAG, "sendTextMessage: serverId=$serverId, callsign=${userInfo.callsign}, text=${text.take(20)}")
        val sent = multiServerManager.sendTextMessage(serverId, userInfo.callsign, text, ssid, dmrId)

        if (sent) {
            addOwnMessage(serverId, userInfo.callsign, ssid, text, MessageStore.MessageType.TEXT)
        } else {
            Log.e(TAG, "sendTextMessage: failed to send message for serverId=$serverId")
        }
    }

    suspend fun sendTextMessageToActive(text: String) {
        val activeId = multiServerManager.activeServerId.value
        if (activeId.isNotEmpty()) {
            sendTextMessage(activeId, text)
        }
    }

    // ============== 位置上传 ==============

    suspend fun uploadLocation(serverId: String): Boolean {
        if (!locationManager.hasLocationPermission()) {
            showToast("请先授予位置权限")
            return false
        }

        val location = locationManager.getCurrentLocation().getOrElse { error ->
            showToast("获取位置失败: ${error.message}")
            return false
        }
        val (lat, lng) = location

        val connection = multiServerManager.getConnection(serverId) ?: return false
        val userInfo = connection.userInfo ?: return false
        val deviceData = connection.deviceData

        val ssid = deviceData?.ssid ?: DEFAULT_SSID
        val dmrId = deviceData?.dmrId ?: userInfo.dmrId

        multiServerManager.sendLocation(serverId, userInfo.callsign, lat, lng, ssid, dmrId)

        addOwnMessage(
            serverId, userInfo.callsign, ssid,
            "📍 已上传位置: ${"%.4f".format(lat)}, ${"%.4f".format(lng)}",
            MessageStore.MessageType.LOCATION
        )

        showToast("位置已上传")
        return true
    }

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

    private fun setupMessageCallbacks() {
        multiServerManager.onTextMessageReceived = { serverId, callsign, ssid, content, timestamp ->
            val serverName = multiServerManager.serverConnections.value
                .find { it.serverId == serverId }?.name ?: serverId

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

            val timestamp = formatTimestamp()
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
        }
    }

    private fun setupSettingsListener() {
        serviceScope.launch {
            // StateFlow 首值立即发出 → 服务启动时应用当前编码与音频/PTT参数；
            // 设置页保存后（共享 flow）自动热切换所有连接的编码与音量/增益/按键设置
            settingsRepository.settings.collect { settings ->
                multiServerManager.setAudioCodec(settings.codec)
                multiServerManager.setAudioVolume(settings.volume)
                multiServerManager.setAudioGain(settings.gain)

                if (::pttController.isInitialized) {
                    val deviceProfile = DeviceKeyProfiles.detect()
                    val effectivePttKey = if (settings.pttKeyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                        deviceProfile.pttKeyCode
                    } else {
                        settings.pttKeyCode
                    }
                    pttController.setPttKeyCode(effectivePttKey)
                    pttController.setScreenOffPtt(settings.screenOffPtt)
                }
            }
        }
    }

    private fun setupServerStateListener() {
        serviceScope.launch {
            multiServerManager.serverConnections.collect { connections ->
                _serverConnections.value = connections
                _isLoggedIn.value = connections.any { it.isLoggedIn }
                updateNotification(buildNotificationText(connections))
            }
        }
    }

    private fun setupNetworkMonitor() {
        networkMonitor = NetworkMonitor(this)
        networkMonitor.start()
        serviceScope.launch {
            networkMonitor.networkAvailable.collect { available ->
                if (available) {
                    val savedServers = settingsRepository.loadServerList()
                    _serverConnections.value
                        .filter { !it.isOnline }
                        .forEach { conn ->
                            savedServers.find { it.id == conn.serverId }?.let { config ->
                                Log.d(TAG, "Network restored, reconnecting: ${config.name}")
                                connectToServer(config)
                            }
                        }
                }
            }
        }
    }

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

                override fun onPttReleased() = stopTransmitting()
                override fun onPttLongPress() {
                    Log.d(TAG, "PTT long press detected")
                }
            },
            pttKey = effectivePttKey,
            screenOffEnabled = settings.screenOffPtt
        )

        if (deviceProfile.useBroadcastPtt) {
            deviceProfile.broadcastActions.forEach { action ->
                try {
                    val filter = android.content.IntentFilter(action)
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent?) {
                            when (intent?.action) {
                                "android.intent.action.PTT.down" -> {
                                    pttController.onKeyEvent(
                                        android.view.KeyEvent(
                                            android.view.KeyEvent.ACTION_DOWN,
                                            deviceProfile.pttKeyCode
                                        )
                                    )
                                }
                                "android.intent.action.PTT.up" -> {
                                    pttController.onKeyEvent(
                                        android.view.KeyEvent(
                                            android.view.KeyEvent.ACTION_UP,
                                            deviceProfile.pttKeyCode
                                        )
                                    )
                                }
                            }
                        }
                    }
                    // Android 14+ 注册非系统广播必须显式指定 export 标志，否则抛 SecurityException
                    androidx.core.content.ContextCompat.registerReceiver(
                        this, receiver, filter,
                        androidx.core.content.ContextCompat.RECEIVER_EXPORTED
                    )
                    pttBroadcastReceivers.add(receiver)
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

            override fun onPttButtonReleased() = stopTransmitting()
        }

        try {
            @Suppress("DEPRECATION")
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val componentName = android.content.ComponentName(this, PttButtonReceiver::class.java)
            @Suppress("DEPRECATION")
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

    private fun formatTimestamp(): String = synchronized(timestampFormat) {
        timestampFormat.format(Date())
    }

    /** 统一构造本台发出的消息（文本/位置），消除重复的 Message 构建代码 */
    private fun addOwnMessage(
        serverId: String,
        callsign: String,
        ssid: Int,
        content: String,
        type: MessageStore.MessageType
    ) {
        val serverName = _serverConnections.value.find { it.serverId == serverId }?.name ?: serverId
        messageStore.addMessage(
            MessageStore.TextMessage(
                id = UUID.randomUUID().toString(),
                serverId = serverId,
                serverName = serverName,
                callsign = callsign,
                ssid = ssid,
                content = content,
                timestamp = formatTimestamp(),
                timestampMs = System.currentTimeMillis(),
                isSelf = true,
                type = type
            )
        )
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@TalkService, message, Toast.LENGTH_SHORT).show()
        }
    }
}
