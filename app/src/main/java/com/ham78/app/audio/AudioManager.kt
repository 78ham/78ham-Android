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
    // 按需初始化：默认 G711 时不必做 Opus 初始化开销
    private val opusCodec by lazy { OpusCodec() }

    // 设置线程写、录音/接收线程读，必须保证可见性
    @Volatile
    private var codec = AudioCodec.G711

    /** 发射路径上残留的不足一帧的 PCM（Opus 帧比录音帧大，需要攒两帧再发） */
    private val frameCarry = java.io.ByteArrayOutputStream()
    private val frameCarryLock = Any()

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
            if (pcmData.isEmpty()) return

            // 录音固定 20ms/320B（8kHz），Opus 帧 20ms/640B（16kHz）：
            // 攒够一个完整编码帧才编码发送，剩余零头留给下一块
            synchronized(frameCarryLock) {
                frameCarry.write(pcmData, 0, pcmData.size)
                val buf = frameCarry.toByteArray()
                val frameBytes = currentEncodeFrameBytes()
                var offset = 0
                while (offset + frameBytes <= buf.size) {
                    sendEncodedFrame(buf, offset, frameBytes)
                    offset += frameBytes
                }
                frameCarry.reset()
                if (offset < buf.size) {
                    frameCarry.write(buf, offset, buf.size - offset)
                }
            }
        }

        override fun onError(error: String) {
            Log.e(TAG, "Record error: $error")
            _isTransmitting.value = false
        }
    }

    /** 当前编码一帧需要的 PCM 字节数（G711=320@8k，Opus=640@16k） */
    private fun currentEncodeFrameBytes(): Int = when (codec) {
        AudioCodec.G711 -> AudioRecorder.BYTES_PER_FRAME
        AudioCodec.OPUS -> OpusCodec.FRAME_SAMPLES * 2
    }

    /** 编码并发送一个整帧 PCM */
    private fun sendEncodedFrame(buf: ByteArray, offset: Int, frameBytes: Int) {
        val frameData = if (offset == 0 && buf.size == frameBytes) {
            buf
        } else {
            buf.copyOfRange(offset, offset + frameBytes)
        }
        val encodedData = when (codec) {
            AudioCodec.G711 -> g711Codec.encodePcmToAlaw(frameData)
            AudioCodec.OPUS -> opusCodec.encode(frameData)
        }
        udpClient.sendAudioData(encodedData, codec == AudioCodec.OPUS)
    }

    init {
        recorder.audioDataListener = recordListener
    }

    fun setCodec(newCodec: AudioCodec) {
        if (codec == newCodec) return
        codec = newCodec
        // 帧长变了（320↔640），发射残留的零头按新编码也没法拼帧，直接丢掉
        synchronized(frameCarryLock) { frameCarry.reset() }
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
     * 信号源一律是 8kHz PCM；G.711 直接切帧编码，Opus 先重采样到 16kHz 再编码。
     *
     * 与语音发射互斥：PTT 正在按住时拒绝信号发送，避免两路音频叠加。
     *
     * 【关键】按真实时间节奏逐帧发，而不是一口气把整段塞进 UDP：
     * 老写法是一个 for 循环把全部帧瞬间发出，SSTV 一张图就是 2880 个 UDP 包
     * 挤在一起，服务端 socket 缓冲区直接溢出丢包，接收端听到的是断断续续的碎音。
     * 现在每帧之间按 20ms 的真实间隔等待，收发两侧的节奏才对得上。
     */
    suspend fun transmitPcm(pcm8k: ByteArray): Boolean {
        if (_isTransmitting.value) return false

        // Opus 需要 16kHz 帧：整段重采样一次（信号音都不是实时流，一次性处理没问题）
        val pcm = if (codec == AudioCodec.OPUS) resample8kTo16k(pcm8k) else pcm8k
        val frameBytes = currentEncodeFrameBytes()

        var offset = 0
        var frameIndex = 0
        val totalFrames = (pcm.size + frameBytes - 1) / frameBytes
        val startedAt = System.nanoTime()

        while (offset < pcm.size) {
            // 帧调度：等到"这一帧该发的时间点"再发。
            // 用累计基准而不是每帧 delay(20)，避免累积漂移导致信号整体变调/变慢。
            val dueAt = startedAt + frameIndex * AudioRecorder.FRAME_MS * 1_000_000L
            val waitNs = dueAt - System.nanoTime()
            if (waitNs > 0) {
                kotlinx.coroutines.delay(waitNs)
            }

            val end = minOf(offset + frameBytes, pcm.size)
            val encodedData = when (codec) {
                AudioCodec.G711 -> {
                    // 最后一帧不足 20ms：补零对齐，保证编码器拿到完整帧
                    val frameData = if (end - offset == frameBytes) {
                        pcm.copyOfRange(offset, end)
                    } else {
                        val padded = ByteArray(frameBytes)
                        System.arraycopy(pcm, offset, padded, 0, end - offset)
                        padded
                    }
                    g711Codec.encodePcmToAlaw(frameData)
                }
                AudioCodec.OPUS -> opusCodec.encode(pcm.copyOfRange(offset, end))   // 内部补零对齐
            }
            udpClient.sendAudioData(encodedData, codec == AudioCodec.OPUS)

            offset = end
            frameIndex++
        }

        Log.d(TAG, "Signal transmitted: $totalFrames frames (${pcm.size} bytes)")
        return true
    }

    /**
     * 8kHz → 16kHz 线性插值重采样（发射 Opus 信号用）。
     * 信号源（CW/DTMF/尾音/TTS）都是 8kHz 生成的。
     */
    private fun resample8kTo16k(src: ByteArray): ByteArray {
        val sampleCount = src.size / 2
        if (sampleCount == 0) return ByteArray(0)
        val out = ByteArray(sampleCount * 4)   // 2x 采样数 * 2 字节
        for (i in 0 until sampleCount) {
            val s = ((src[i * 2 + 1].toInt() shl 8) or (src[i * 2].toInt() and 0xFF)).toShort().toInt()
            // 每个输入样本产出两个输出样本：第一个原样，第二个取相邻样本的均值
            out[i * 4] = (s and 0xFF).toByte()
            out[i * 4 + 1] = ((s shr 8) and 0xFF).toByte()
            val next = if (i + 1 < sampleCount) {
                ((src[(i + 1) * 2 + 1].toInt() shl 8) or (src[(i + 1) * 2].toInt() and 0xFF)).toShort().toInt()
            } else s
            val avg = (s + next) / 2
            out[i * 4 + 2] = (avg and 0xFF).toByte()
            out[i * 4 + 3] = ((avg shr 8) and 0xFF).toByte()
        }
        return out
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
     * Opus 网络包是 16kHz 编码的（与小程序/桌面端一致），解码后降采样到 8kHz，
     * 与 G.711 分支共用同一套 8kHz 播放器/回放缓存。
     * 用于语音回放时缓存会话音频，不依赖是否为活跃服务器。
     */
    fun decodeToPcm(data: ByteArray, type: Int): ByteArray? {
        return when (type) {
            Nrl21Protocol.TYPE_VOICE -> g711Codec.decodeAlawToPcm(data)
            Nrl21Protocol.TYPE_OPUS -> downsample16kTo8k(opusCodec.decode(data))
            else -> null
        }
    }

    /** 16kHz → 8kHz 降采样（取第偶数个样本，Opus 解码输出每帧 320 样本 → 160） */
    private fun downsample16kTo8k(src: ByteArray): ByteArray {
        val sampleCount = src.size / 2
        if (sampleCount == 0) return ByteArray(0)
        val out = ByteArray(sampleCount / 2 * 2)
        for (i in 0 until sampleCount / 2) {
            out[i * 2] = src[i * 4]
            out[i * 2 + 1] = src[i * 4 + 1]
        }
        return out
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
