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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 音频管理器
 * 协调录音、编码、发送和接收、播放的完整音频流程
 */
class AudioManager(private val context: Context, private val udpClient: UdpClient) {

    companion object {
        private const val TAG = "AudioManager"
        private const val RECEIVE_TIMEOUT_MS = 3000L
    }

    private val recorder = AudioRecorder(context)
    private val player = AudioPlayer(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val g711Codec = G711Codec()
    private val opusCodec = OpusCodec()

    // 设置线程写、录音/接收线程读，必须保证可见性
    @Volatile
    private var codec = AudioCodec.G711

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    val currentPlayingClipId: StateFlow<String?> = player.currentPlayingClipId

    private val _lastReceivedCallsign = MutableStateFlow<String>("")
    val lastReceivedCallsign: StateFlow<String> = _lastReceivedCallsign.asStateFlow()

    // 接收线程写、超时监护协程读，必须保证可见性
    @Volatile
    private var lastAudioTime = 0L
    private var receiveTimeoutJob: Job? = null
    private var playerReady = false

    private val recordListener = object : AudioRecorder.AudioDataListener {
        override fun onAudioData(pcmData: ByteArray) {
            if (!_isTransmitting.value) return
            if (pcmData.size >= AudioRecorder.BYTES_PER_FRAME) {
                // 长度恰好为一帧时直接使用，避免每帧一次数组拷贝
                val frameData = if (pcmData.size == AudioRecorder.BYTES_PER_FRAME) {
                    pcmData
                } else {
                    pcmData.copyOf(AudioRecorder.BYTES_PER_FRAME)
                }

                val encodedData = when (codec) {
                    AudioCodec.G711 -> g711Codec.encodePcmToAlaw(frameData)
                    AudioCodec.OPUS -> opusCodec.encode(frameData)
                }

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

    fun setGain(gain: Float) {
        player.setGain(gain)
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

        // 语音包每秒多达 50 个：仅在无活动监护协程时启动，
        // 动态计算剩余超时毫秒，保证在静默恰好达到阈值时立即解除 RX 高亮
        if (receiveTimeoutJob?.isActive != true) {
            receiveTimeoutJob = scope.launch {
                while (isActive) {
                    val elapsed = System.currentTimeMillis() - lastAudioTime
                    val remaining = RECEIVE_TIMEOUT_MS - elapsed
                    if (remaining <= 0) {
                        _isReceiving.value = false
                        break
                    }
                    delay(remaining)
                }
            }
        }

        val pcmData = decodeToPcm(data, type) ?: return
        player.playAudio(pcmData)
    }

    /**
     * 将一帧网络语音数据解码为 PCM（8kHz/16bit/单声道小端）。
     * 用于语音回放时缓存会话音频，不依赖是否为活跃服务器。
     */
    fun decodeToPcm(data: ByteArray, type: Int): ByteArray? {
        return when (type) {
            Nrl21Protocol.TYPE_VOICE -> g711Codec.decodeAlawToPcm(data)
            Nrl21Protocol.TYPE_OPUS -> opusCodec.decode(data)
            else -> null
        }
    }

    /** 回放一段已缓存的 PCM 语音（语音回放） */
    fun playClip(pcm: ByteArray, clipId: String = "") {
        if (_isTransmitting.value) return
        player.playClip(pcm, clipId)
    }

    fun stopClip() {
        player.stopClip()
    }

    fun clearReceivingState() {
        receiveTimeoutJob?.cancel()
        receiveTimeoutJob = null
        _isReceiving.value = false
    }

    fun release() {
        receiveTimeoutJob?.cancel()
        recorder.release()
        player.release()
        scope.cancel()
    }
}
