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

class UdpClient {

    companion object {
        private const val TAG = "UdpClient"
        private const val BUFFER_SIZE = 2048
        private const val HEARTBEAT_INTERVAL = 2000L
        private const val CONNECTION_TIMEOUT = 30000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 2000L
    }

    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var serverPort: Int = 0
    private var serverHost: String = ""

    private val _connectionState: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _receivedPackets: MutableStateFlow<Nrl21Protocol.Packet?> = MutableStateFlow(null)
    val receivedPackets: StateFlow<Nrl21Protocol.Packet?> = _receivedPackets.asStateFlow()

    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null
    private var monitorJob: Job? = null
    private var reconnectJob: Job? = null

    private var dmrId: Int = 0
    private var callsign: String = ""
    private var ssid: Int = 78
    private var devModel: Int = 101  // Android 默认设备型号 101

    private var lastPacketTime = 0L
    private var lastHeartbeatTime = 0L
    private var reconnectAttempts = 0

    interface PacketListener {
        fun onPacketReceived(packet: Nrl21Protocol.Packet)
        fun onError(error: String)
        fun onConnectionLost()
    }

    var packetListener: PacketListener? = null

    fun connect(serverHost: String, port: Int, id: Int, call: String, ssidVal: Int = 78, devModelVal: Int = 101): Boolean {
        return try {
            dmrId = id
            callsign = call
            ssid = ssidVal
            devModel = devModelVal
            serverPort = port
            this.serverHost = serverHost

            _connectionState.value = ConnectionState.CONNECTING

            serverAddress = InetAddress.getByName(serverHost)
            socket = DatagramSocket().apply {
                soTimeout = 5000
            }

            isRunning.set(true)
            lastPacketTime = System.currentTimeMillis()
            lastHeartbeatTime = System.currentTimeMillis()
            reconnectAttempts = 0

            startReceiving()
            startHeartbeat()
            startConnectionMonitor()

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

    fun disconnect() {
        isRunning.set(false)

        receiveJob?.cancel()
        heartbeatJob?.cancel()
        monitorJob?.cancel()
        reconnectJob?.cancel()

        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }

        socket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.d(TAG, "Disconnected")
    }

    fun sendPacket(packet: ByteArray): Boolean {
        return try {
            val address = serverAddress ?: return false
            val datagramPacket = DatagramPacket(packet, packet.size, address, serverPort)
            socket?.send(datagramPacket)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            packetListener?.onError("发送失败: ${e.message}")
            false
        }
    }

    fun sendAudioData(audioData: ByteArray, isOpus: Boolean = false) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "sendAudioData: not connected, state=${_connectionState.value}")
            return
        }

        val type: Int = if (isOpus) Nrl21Protocol.TYPE_OPUS else Nrl21Protocol.TYPE_VOICE
        val packet: ByteArray = Nrl21Protocol.createPacket(type, callsign, ssid, devModel, dmrId, audioData)

        val sent: Boolean = sendPacket(packet)
        if (!sent) {
            Log.e(TAG, "sendAudioData: sendPacket failed, size=${packet.size}")
        }
    }

    fun sendJoinRoom(roomId: Int) {
        if (_connectionState.value != ConnectionState.CONNECTED) return

        // Type=7 加入群组指令，data[0]=0x01 (subtype=切换组), data[1:5]=group_id (BigEndian)
        // 参考 PC 客户端 nrl_protocol.py: packet.data = b'\x01' + struct.pack('>I', group_id)
        val data = ByteArray(5)
        data[0] = 0x01
        data[1] = ((roomId shr 24) and 0xFF).toByte()
        data[2] = ((roomId shr 16) and 0xFF).toByte()
        data[3] = ((roomId shr 8) and 0xFF).toByte()
        data[4] = (roomId and 0xFF).toByte()

        val packet: ByteArray = Nrl21Protocol.createPacket(Nrl21Protocol.TYPE_JOIN_GROUP, callsign, ssid, devModel, dmrId, data)
        sendPacket(packet)
    }

    private fun startReceiving() {
        receiveJob = scope.launch {
            val buffer = ByteArray(BUFFER_SIZE)

            while (isRunning.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)

                    lastPacketTime = System.currentTimeMillis()

                    val data: ByteArray = packet.data.copyOf(packet.length)
                    val nrlPacket: Nrl21Protocol.Packet? = Nrl21Protocol.decodePacket(data)

                    if (nrlPacket != null) {
                        _receivedPackets.value = nrlPacket
                        packetListener?.onPacketReceived(nrlPacket)
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Receive error", e)
                    }
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isRunning.get()) {
                try {
                    val heartbeat: ByteArray = Nrl21Protocol.createPacket(Nrl21Protocol.TYPE_HEARTBEAT, callsign, ssid, devModel, dmrId)
                    sendPacket(heartbeat)
                    lastHeartbeatTime = System.currentTimeMillis()
                    delay(HEARTBEAT_INTERVAL)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error", e)
                }
            }
        }
    }

    private fun startConnectionMonitor() {
        monitorJob = scope.launch {
            while (isRunning.get()) {
                try {
                    val now = System.currentTimeMillis()

                    if (now - lastPacketTime > CONNECTION_TIMEOUT) {
                        Log.w(TAG, "Connection timeout - no packets for ${now - lastPacketTime}ms")
                        _connectionState.value = ConnectionState.DISCONNECTED
                        packetListener?.onConnectionLost()

                        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                            reconnectAttempts++
                            Log.d(TAG, "Attempting reconnect ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
                            delay(RECONNECT_DELAY_MS)
                            reconnect()
                        } else {
                            Log.e(TAG, "Max reconnect attempts reached")
                            disconnect()
                        }
                        break
                    }

                    delay(5000)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Monitor error", e)
                }
            }
        }
    }

    private fun reconnect() {
        reconnectJob = scope.launch {
            if (connect(serverHost, serverPort, dmrId, callsign, ssid, devModel)) {
                Log.d(TAG, "Reconnected successfully")
                reconnectAttempts = 0
            }
        }
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED
}