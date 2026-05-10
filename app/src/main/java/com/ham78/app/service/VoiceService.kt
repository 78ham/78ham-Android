package com.ham78.app.service

import android.util.Log
import com.ham78.app.network.Nrl21Protocol
import com.ham78.app.audio.AudioManager
import com.ham78.app.audio.G711Codec
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceService(
    private val audioManager: AudioManager
) {
    companion object {
        private const val TAG = "VoiceService"
    }
    
    private val g711Codec = G711Codec()
    private var incomingVoiceBuffer = mutableListOf<ByteArray>()
    private var accumulatedDuration = 0L
    private var durationUpdateTimer: Job? = null
    
    private var currentReceiving = ReceivingState()
    
    data class ReceivingState(
        var isReceiving: Boolean = false,
        var callSign: String = "",
        var ssid: Int = 0,
        var dmrId: Int = 0,
        var devModel: Int = 0,
        var devModelName: String = "",
        var startTime: Long = 0,
        var lastReceiveTime: Long = 0
    )
    
    fun handleMessage(data: ByteArray) {
        try {
            val packet = Nrl21Protocol.decodePacket(data) ?: return

            when (packet.type) {
                Nrl21Protocol.TYPE_VOICE -> {
                    audioManager.handleReceivedAudio(packet.data, Nrl21Protocol.TYPE_VOICE, packet.callSign)
                    processIncomingVoice(packet, packet.data)
                }
                Nrl21Protocol.TYPE_HEARTBEAT -> {
                    Log.d(TAG, "心跳包收到")
                }
                Nrl21Protocol.TYPE_OPUS -> {
                    audioManager.handleReceivedAudio(packet.data, Nrl21Protocol.TYPE_OPUS, packet.callSign)
                    processIncomingVoice(packet, packet.data)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理消息失败", e)
        }
    }
    
    private fun processIncomingVoice(packet: Nrl21Protocol.Packet, linearData: ByteArray) {
        val now = System.currentTimeMillis()
        
        val packetCallSign = packet.callSign.ifEmpty { "未知" }
        val packetSSID = packet.ssid
        val packetDevModel = packet.devModel
        
        if (currentReceiving.isReceiving) {
            val isDifferentSender = 
                currentReceiving.callSign != packetCallSign || 
                currentReceiving.ssid != packetSSID
            
            val timeSinceLastPacket = now - currentReceiving.lastReceiveTime
            val isTooLongInterval = timeSinceLastPacket > 1000
            
            if (isDifferentSender) {
                Log.w(TAG, "不同的发送者! 当前: ${currentReceiving.callSign}-${currentReceiving.ssid}, 新: $packetCallSign-$packetSSID")
                finishIncomingVoice()
            } else if (isTooLongInterval) {
                Log.w(TAG, "间隔过长(${timeSinceLastPacket}ms), 作为新传输处理")
                finishIncomingVoice()
            }
        }
        
        if (!currentReceiving.isReceiving) {
            Log.d(TAG, "开始接收来自 $packetCallSign-$packetSSID 的语音")
            currentReceiving = ReceivingState(
                isReceiving = true,
                callSign = packetCallSign,
                ssid = packetSSID,
                dmrId = packet.dmrId,
                devModel = packetDevModel,
                devModelName = "",
                startTime = now,
                lastReceiveTime = now
            )
            incomingVoiceBuffer.clear()
            accumulatedDuration = 0
            startDurationUpdateTimer()
        }
        
        currentReceiving.lastReceiveTime = now
        incomingVoiceBuffer.add(linearData)
        
        val packetSize = packet.data.size
        val packetDurationMs = when (packetSize) {
            160 -> 20L
            500 -> 62L
            else -> (packetSize * 0.125).toLong()
        }
        accumulatedDuration += packetDurationMs
    }
    
    private fun finishIncomingVoice() {
        if (!currentReceiving.isReceiving) return
        
        Log.d(TAG, "结束接收语音 ${currentReceiving.callSign}-${currentReceiving.ssid}, 时长: ${accumulatedDuration}ms")
        
        currentReceiving.isReceiving = false
        incomingVoiceBuffer.clear()
        accumulatedDuration = 0
        durationUpdateTimer?.cancel()
    }
    
    private fun startDurationUpdateTimer() {
        durationUpdateTimer?.cancel()
        durationUpdateTimer = GlobalScope.launch(Dispatchers.Main) {
            while (isActive && currentReceiving.isReceiving) {
                delay(100)
                if (currentReceiving.isReceiving) {
                    val elapsed = System.currentTimeMillis() - currentReceiving.startTime
                    Log.d(TAG, "接收时长: ${elapsed}ms")
                }
            }
        }
    }
    
    fun stopReceiving() {
        finishIncomingVoice()
    }
}
