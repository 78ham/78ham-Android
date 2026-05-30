package com.ham78.app.audio

import android.content.Context
import android.util.Log
import com.ham78.app.data.AudioCodec
import com.ham78.app.network.Nrl21Protocol
import com.ham78.app.network.UdpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AudioManager(private val context: Context, private val udpClient: UdpClient) {

    companion object {
        private const val TAG = "AudioManager"
        private const val RECEIVE_TIMEOUT_MS = 3000L
    }

    private val recorder = AudioRecorder(context)
    private val player = AudioPlayer(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val g711Codec = G711Codec()

    private var codec = AudioCodec.G711

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    private val _lastReceivedCallsign = MutableStateFlow<String>("")
    val lastReceivedCallsign: StateFlow<String> = _lastReceivedCallsign.asStateFlow()

    private var lastAudioTime = 0L
    private var receiveTimeoutJob: Job? = null

    private var playerReady = false

    private val recordListener = object : AudioRecorder.AudioDataListener {
        override fun onAudioData(pcmData: ByteArray) {
            if (!_isTransmitting.value) return
            if (pcmData.size >= AudioRecorder.BYTES_PER_FRAME) {
                val frameData = pcmData.copyOf(AudioRecorder.BYTES_PER_FRAME)

                val encodedData = when (codec) {
                    AudioCodec.G711 -> {
                        val samples = ShortArray(frameData.size / 2)
                        java.nio.ByteBuffer.wrap(frameData)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().get(samples)
                        g711Codec.encode(samples)
                    }
                    AudioCodec.OPUS -> frameData
                }

                Log.d(TAG, "Sending audio frame: ${encodedData.size} bytes, codec=$codec")
                udpClient.sendAudioData(encodedData, codec == AudioCodec.OPUS)
            }
        }

        override fun onError(error: String) {
            Log.e(TAG, "Record error: $error")
            _isTransmitting.value = false
        }
    }

    init {
        recorder.audioDataListener = recordListener
    }

    fun setCodec(newCodec: AudioCodec) {
        codec = newCodec
    }

    fun setVolume(volume: Int) {
        player.setVolume(volume / 100f)
    }

    fun preparePlayer() {
        if (!playerReady) {
            playerReady = player.startPlayback()
        }
    }

    fun startTransmitting(): Boolean {
        if (_isTransmitting.value) return true

        player.pausePlayback()

        val success = recorder.startRecording()
        if (success) {
            _isTransmitting.value = true
            Log.d(TAG, "Started transmitting")
        } else {
            Log.e(TAG, "Failed to start recording")
            player.ensurePlayerReady()
        }
        return success
    }

    fun stopTransmitting() {
        recorder.stopRecording()
        _isTransmitting.value = false

        player.ensurePlayerReady()

        Log.d(TAG, "Stopped transmitting")
    }

    fun handleReceivedAudio(data: ByteArray, type: Int, callsign: String) {
        if (_isTransmitting.value) return

        _lastReceivedCallsign.value = callsign
        _isReceiving.value = true
        lastAudioTime = System.currentTimeMillis()

        receiveTimeoutJob?.cancel()
        receiveTimeoutJob = scope.launch {
            delay(RECEIVE_TIMEOUT_MS)
            if (System.currentTimeMillis() - lastAudioTime >= RECEIVE_TIMEOUT_MS) {
                _isReceiving.value = false
            }
        }

        val pcmData = when (type) {
            Nrl21Protocol.TYPE_VOICE -> {
                val samples = ShortArray(data.size)
                for (i in data.indices) {
                    samples[i] = g711Codec.alaw2linear(data[i].toInt() and 0xFF).toShort()
                }
                val buf = java.nio.ByteBuffer.allocate(samples.size * 2)
                buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buf.asShortBuffer().put(samples)
                buf.array()
            }
            Nrl21Protocol.TYPE_OPUS -> data
            else -> return
        }

        player.playAudio(pcmData)
    }

    /**
     * 将一帧网络语音数据解码为 PCM（8kHz/16bit/单声道小端）。
     * 用于语音回放时缓存会话音频，不依赖是否为活跃服务器。
     */
    fun decodeToPcm(data: ByteArray, type: Int): ByteArray? {
        return when (type) {
            Nrl21Protocol.TYPE_VOICE -> {
                val samples = ShortArray(data.size)
                for (i in data.indices) {
                    samples[i] = g711Codec.alaw2linear(data[i].toInt() and 0xFF).toShort()
                }
                val buf = java.nio.ByteBuffer.allocate(samples.size * 2)
                buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buf.asShortBuffer().put(samples)
                buf.array()
            }
            Nrl21Protocol.TYPE_OPUS -> data
            else -> null
        }
    }

    /** 回放一段已缓存的 PCM 语音（语音回放） */
    fun playClip(pcm: ByteArray) {
        if (_isTransmitting.value) return
        player.playClip(pcm)
    }

    fun clearReceivingState() {
        _isReceiving.value = false
    }

    fun release() {
        receiveTimeoutJob?.cancel()
        recorder.release()
        player.release()
        scope.cancel()
    }
}
