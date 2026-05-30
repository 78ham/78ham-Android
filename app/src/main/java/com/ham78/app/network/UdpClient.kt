package com.ham78.app.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
    }

    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var serverPort: Int = 0
    private var serverHost: String = ""

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _receivedPackets = MutableStateFlow<Nrl21Protocol.Packet?>(null)
    val receivedPackets: StateFlow<Nrl21Protocol.Packet?> = _receivedPackets.asStateFlow()

    private val isRunning = AtomicBoolean(false)
    private var scope: CoroutineScope? = null

    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null
    private var monitorJob: Job? = null
    private var reconnectJob: Job? = null

    private var dmrId: Int = 0
    private var callsign: String = ""
    private var ssid: Int = 179
    private var devModel: Int = 101

    private var lastPacketTime = 0L
    private var reconnectAttempts = 0

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
            socket = DatagramSocket().apply { soTimeout = RECEIVE_TIMEOUT_MS }

            isRunning.set(true)
            lastPacketTime = System.currentTimeMillis()
            reconnectAttempts = 0

            val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope = newScope

            startReceiving(newScope)
            startHeartbeat(newScope)
            startConnectionMonitor(newScope)

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

        receiveJob = null
        heartbeatJob = null
        monitorJob = null
        reconnectJob = null

        try { socket?.close() } catch (_: Exception) { }
        socket = null
        serverAddress = null

        scope?.cancel()
        scope = null

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

    fun sendAudioData(audioData: ByteArray, isOpus: Boolean = false) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "sendAudioData: not connected")
            return
        }
        val type = if (isOpus) Nrl21Protocol.TYPE_OPUS else Nrl21Protocol.TYPE_VOICE
        val packet = Nrl21Protocol.createPacket(type, callsign, ssid, devModel, dmrId, audioData)
        if (!sendPacket(packet)) {
            Log.e(TAG, "sendAudioData: sendPacket failed, size=${packet.size}")
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
                    socket?.receive(packet) ?: break

                    lastPacketTime = System.currentTimeMillis()

                    val data = packet.data.copyOf(packet.length)
                    Nrl21Protocol.decodePacket(data)?.let { nrlPacket ->
                        _receivedPackets.value = nrlPacket
                        packetListener?.onPacketReceived(nrlPacket)
                    }
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
            Log.e(TAG, "Max reconnect attempts reached")
            _connectionState.value = ConnectionState.DISCONNECTED
            disconnect()
        }
    }

    private fun reconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope?.launch {
            if (connect(serverHost, serverPort, dmrId, callsign, ssid, devModel)) {
                Log.d(TAG, "Reconnected successfully")
            }
        }
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED
}
