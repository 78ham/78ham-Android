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

    /** 此刻是否正在发射（同步真值，不经过 StateFlow 的异步同步） */
    fun isTransmitting(): Boolean = _isTransmitting.value

    fun stopTransmitting() {
        recorder.stopRecording()
        _isTransmitting.value = false

        player.ensurePlayerReady()

        Log.d(TAG, "Stopped transmitting")
    }

    /**
     * 直接发送一段已生成的 PCM 信号（信号音/CW/DTMF/SSTV/尾音/TTS/录音回放）。
     *
     * 与语音发射互斥：PTT 正在按住时拒绝信号发送，避免两路音频叠加。
     * 按 20ms 一帧切片，每帧独立编码发送，与麦克风路径走完全相同的链路。
     *
     * 【关键】按真实时间节奏逐帧发，而不是一口气把整段塞进 UDP：
     * 老写法是一个 for 循环把全部帧瞬间发出，SSTV 一张图就是 2880 个 UDP 包
     * 挤在一起，服务端 socket 缓冲区直接溢出丢包，接收端听到的是断断续续的碎音。
     * 现在每帧之间按 20ms 的真实间隔等待，收发两侧的节奏才对得上。
     */
    suspend fun transmitPcm(pcm: ByteArray): Boolean {
        if (_isTransmitting.value) return false

        var offset = 0
        var frameIndex = 0
        val totalFrames = (pcm.size + AudioRecorder.BYTES_PER_FRAME - 1) / AudioRecorder.BYTES_PER_FRAME
        val startedAt = System.nanoTime()

        while (offset < pcm.size) {
            // 帧调度：等到"这一帧该发的时间点"再发。
            // 用累计基准而不是每帧 delay(20)，避免累积漂移导致信号整体变调/变慢。
            val dueAt = startedAt + frameIndex * AudioRecorder.FRAME_MS * 1_000_000L
            val waitNs = dueAt - System.nanoTime()
            if (waitNs > 0) {
                delay(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
            }

            val end = minOf(offset + AudioRecorder.BYTES_PER_FRAME, pcm.size)
            val frameData = if (end - offset == AudioRecorder.BYTES_PER_FRAME) {
                pcm.copyOfRange(offset, end)   // 整帧
            } else {
                // 最后一帧不足 20ms：补零对齐，保证编码器拿到完整帧
                val padded = ByteArray(AudioRecorder.BYTES_PER_FRAME)
                System.arraycopy(pcm, offset, padded, 0, end - offset)
                padded
            }

            val encodedData = when (codec) {
                AudioCodec.G711 -> g711Codec.encodePcmToAlaw(frameData)
                AudioCodec.OPUS -> opusCodec.encode(frameData)
            }
            udpClient.sendAudioData(encodedData, codec == AudioCodec.OPUS)

            offset = end
            frameIndex++
        }

        Log.d(TAG, "Signal transmitted: $totalFrames frames (${pcm.size} bytes)")
        return true
    }

    /**
     * 播放一个已解码的接收语音帧，并维护 RX 状态与超时监护。
     * 语音包的解码在 MultiServerManager 统一做（每包一次），这里只管播放。
     */
    fun handleReceivedPcm(pcmData: ByteArray?, callsign: String) {
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

        if (pcmData != null && pcmData.isNotEmpty()) {
            player.playAudio(pcmData)
        }
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
