package com.ham78.app.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP 客户端
 * 负责与服务器的 UDP 通信，包括心跳、语音收发、连接监控和自动重连
 */
class UdpClient {

    companion object {
        private const val TAG = "UdpClient"
        private const val BUFFER_SIZE = 2048
        private const val HEARTBEAT_INTERVAL_MS = 2000L
        private const val CONNECTION_TIMEOUT_MS = 15000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 16000L
        private const val RECEIVE_TIMEOUT_MS = 5000

        /** 退避重连全失败后的低频探测间隔：Doze 里省电，回前台最多 1 分钟自动恢复 */
        private const val SLOW_PROBE_INTERVAL_MS = 60_000L
    }

    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var serverPort: Int = 0
    private var serverHost: String = ""

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val isRunning = AtomicBoolean(false)
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null
    private var monitorJob: Job? = null
    private var reconnectJob: Job? = null
    private var slowProbeJob: Job? = null

    private var dmrId: Int = 0
    private var callsign: String = ""
    private var ssid: Int = 179
    private var devModel: Int = 101

    // 语音包 16bit 序号：逐包递增，服务器靠它识别一段连续的语音流（TALK 会话）。
    // 之前恒 0，接收端（小程序/桌面）和部分服务器把整段语音当成无效流丢弃。
    private var voicePacketCount = 0

    private var lastPacketTime = 0L
    private var reconnectAttempts = 0
    private var lastSendWarnTime = 0L

    interface PacketListener {
        fun onPacketReceived(packet: Nrl21Protocol.Packet)
        fun onError(error: String)
        fun onConnectionLost()
    }

    var packetListener: PacketListener? = null

    fun connect(
        serverHost: String,
        port: Int,
        id: Int,
        call: String,
        ssidVal: Int = 179,
        devModelVal: Int = 101
    ): Boolean {
        disconnectInternal(clearState = false)

        return try {
            this.dmrId = id
            this.callsign = call
            this.ssid = ssidVal
            this.devModel = devModelVal
            this.serverPort = port
            this.serverHost = serverHost

            _connectionState.value = ConnectionState.CONNECTING

            serverAddress = InetAddress.getByName(serverHost)
            socket = DatagramSocket().apply {
                soTimeout = RECEIVE_TIMEOUT_MS
                try {
                    receiveBufferSize = 256 * 1024
                    sendBufferSize = 256 * 1024
                } catch (_: Exception) {}
            }

            isRunning.set(true)
            lastPacketTime = System.currentTimeMillis()
            reconnectAttempts = 0

            startReceiving(clientScope)
            startHeartbeat(clientScope)
            startConnectionMonitor(clientScope)

            _connectionState.value = ConnectionState.CONNECTED
            Log.d(TAG, "Connected to $serverHost:$port (DMR:$dmrId)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _connectionState.value = ConnectionState.DISCONNECTED
            packetListener?.onError("连接失败: ${e.message}")
            false
        }
    }

    fun disconnect() = disconnectInternal(clearState = true)

    private fun disconnectInternal(clearState: Boolean) {
        isRunning.set(false)

        receiveJob?.cancel()
        heartbeatJob?.cancel()
        monitorJob?.cancel()
        reconnectJob?.cancel()
        slowProbeJob?.cancel()

        receiveJob = null
        heartbeatJob = null
        monitorJob = null
        reconnectJob = null
        slowProbeJob = null

        try { socket?.close() } catch (_: Exception) { }
        socket = null
        serverAddress = null

        if (clearState) {
            _connectionState.value = ConnectionState.DISCONNECTED
            Log.d(TAG, "Disconnected")
        }
    }

    fun sendPacket(packet: ByteArray): Boolean {
        val address = serverAddress ?: return false.also {
            Log.e(TAG, "sendPacket: serverAddress is null")
        }
        val sock = socket ?: return false.also {
            Log.e(TAG, "sendPacket: socket is null")
        }

        return try {
            sock.send(DatagramPacket(packet, packet.size, address, serverPort))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            packetListener?.onError("发送失败: ${e.message}")
            false
        }
    }

    /** 高频路径（语音每秒 50 帧）告警节流：同一告警 2 秒内只记录一次，避免刷爆 logcat */
    private fun warnThrottled(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastSendWarnTime > 2000) {
            lastSendWarnTime = now
            Log.w(TAG, message)
        }
    }

    fun sendAudioData(audioData: ByteArray, isOpus: Boolean = false) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            warnThrottled("sendAudioData: not connected")
            return
        }
        val type = if (isOpus) Nrl21Protocol.TYPE_OPUS else Nrl21Protocol.TYPE_VOICE
        val count = voicePacketCount
        voicePacketCount = (voicePacketCount + 1) and 0xFFFF   // 16bit 回绕
        val packet = Nrl21Protocol.createPacket(type, callsign, ssid, devModel, dmrId, audioData, count)
        if (!sendPacket(packet)) {
            warnThrottled("sendAudioData: sendPacket failed, size=${packet.size}")
        }
    }

    fun sendJoinRoom(roomId: Int) {
        if (_connectionState.value != ConnectionState.CONNECTED) return

        val data = ByteArray(5).apply {
            this[0] = 0x01
            this[1] = ((roomId shr 24) and 0xFF).toByte()
            this[2] = ((roomId shr 16) and 0xFF).toByte()
            this[3] = ((roomId shr 8) and 0xFF).toByte()
            this[4] = (roomId and 0xFF).toByte()
        }

        sendPacket(Nrl21Protocol.createPacket(Nrl21Protocol.TYPE_JOIN_GROUP, callsign, ssid, devModel, dmrId, data))
    }

    private fun startReceiving(scope: CoroutineScope) {
        receiveJob = scope.launch {
            val buffer = ByteArray(BUFFER_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isRunning.get() && isActive) {
                try {
                    // 必须每次重置缓冲与长度，防止上次短包导致后续长语音包被截断
                    packet.setData(buffer, 0, buffer.size)
                    socket?.receive(packet) ?: break

                    lastPacketTime = System.currentTimeMillis()

                    val data = packet.data.copyOf(packet.length)
                    Nrl21Protocol.decodePacket(data)?.let { nrlPacket ->
                        packetListener?.onPacketReceived(nrlPacket)
                    }
                } catch (_: SocketTimeoutException) {
                    // 超时等待下一个数据包，属于正常现象
                    continue
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Receive error", e)
                    }
                }
            }
        }
    }

    private fun startHeartbeat(scope: CoroutineScope) {
        heartbeatJob = scope.launch {
            while (isRunning.get() && isActive) {
                try {
                    val heartbeat = Nrl21Protocol.createPacket(
                        Nrl21Protocol.TYPE_HEARTBEAT, callsign, ssid, devModel, dmrId
                    )
                    sendPacket(heartbeat)
                    delay(HEARTBEAT_INTERVAL_MS)
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error", e)
                }
            }
        }
    }

    private fun startConnectionMonitor(scope: CoroutineScope) {
        monitorJob = scope.launch {
            while (isRunning.get() && isActive) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastPacketTime > CONNECTION_TIMEOUT_MS) {
                        Log.w(TAG, "Connection timeout - no packets for ${now - lastPacketTime}ms")
                        packetListener?.onConnectionLost()
                        handleTimeout()
                        break
                    }
                    delay(5000)
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Monitor error", e)
                }
            }
        }
    }

    private suspend fun handleTimeout() {
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            _connectionState.value = ConnectionState.RECONNECTING
            val backoff = (INITIAL_RECONNECT_DELAY_MS * (1 shl (reconnectAttempts - 1)))
                .coerceAtMost(MAX_RECONNECT_DELAY_MS)
            Log.d(TAG, "Attempting reconnect ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS) in ${backoff}ms")
            delay(backoff)
            reconnect()
        } else {
            // 5 次退避重连全部失败（Doze 期间常见）：不再标记 DISCONNECTED 下线，
            // 转入低频探测——每 60 秒试一次重建 socket，网络恢复（回前台/Wi-Fi 唤醒）
            // 后最多 60 秒内自动回到在线。原逻辑直接 disconnect()，掉后台超过几分钟
            // 就永远离线，必须手动重开 app。
            Log.w(TAG, "Max reconnect attempts reached, entering slow keepalive probing")
            _connectionState.value = ConnectionState.RECONNECTING
            // 慢速探测放独立协程：不占用 monitorJob，便于单独取消与状态可见。
            // 生命周期：connect() 成功后本协程自行退出；disconnect()/reconnect 成功
            // 路径也会显式 cancel（reconnect 里做），保证同一时刻只有一个探测在跑
            slowProbeJob?.cancel()
            slowProbeJob = clientScope.launch {
                while (isRunning.get() && clientScope.isActive) {
                    delay(SLOW_PROBE_INTERVAL_MS)
                    if (!isRunning.get() || !clientScope.isActive) return@launch
                    Log.d(TAG, "Slow keepalive probe")
                    if (connect(serverHost, serverPort, dmrId, callsign, ssid, devModel)) {
                        Log.d(TAG, "Reconnected via slow probe")
                        return@launch
                    }
                }
            }
        }
    }

    private fun reconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = clientScope.launch {
            if (connect(serverHost, serverPort, dmrId, callsign, ssid, devModel)) {
                // 慢探测还挂着的话立刻停：它周期性调 connect()，成功后会把
                // 刚建好的 socket 再关一次重建，语音流直接被打断
                slowProbeJob?.cancel()
                slowProbeJob = null
                Log.d(TAG, "Reconnected successfully")
            }
        }
    }

    fun release() {
        disconnect()
        clientScope.cancel()
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED
}
