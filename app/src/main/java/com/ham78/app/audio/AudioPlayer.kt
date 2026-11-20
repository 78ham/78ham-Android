package com.ham78.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager as AndroidAudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音频播放器
 * 使用 AudioTrack 实时播放接收到的音频数据
 */
class AudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "AudioPlayer"

        const val SAMPLE_RATE = 8000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_MS = 20
        const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000
        const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2

        private const val MAX_QUEUE_SIZE = 50
        private const val JITTER_BUFFER_FRAMES = 4
        private const val JITTER_HIGH_THRESHOLD = 15

        /** 预缓冲期间的等待间隔：够短保证低延迟，又不至于空转烧 CPU */
        private const val PRIMING_POLL_MS = 5L

        /** 回放 AudioTrack 单次写入块：320ms 数据，写入次数少、播放平滑 */
        private const val REPLAY_CHUNK_BYTES = SAMPLE_RATE / 1000 * 320 * 2
    }

    private var audioTrack: AudioTrack? = null
    private var audioManager: AndroidAudioManager? = null

    // 播放器初始化加锁：网络线程可能同时送来多帧，
    // 不加锁会重复创建 AudioTrack（旧的泄漏）并把队列里的数据清掉
    private val initLock = Any()

    // 语音回放专用 AudioTrack：与实时流完全隔离，
    // 之前回放和实时收音共用一个队列互相灌包——回放时实时语音消失，
    // 实时语音会把正在回放的片段打断（"回放异常"的根因）
    private var replayTrack: AudioTrack? = null
    private var replayThread: Thread? = null

    private val isPlaying = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var playJob: Job? = null

    // 有界阻塞队列：写线程 poll 阻塞等待，替代 delay 轮询，显著降低空闲 CPU 占用
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile
    private var gainMultiplier = 1.0f

    private val _currentPlayingClipId = MutableStateFlow<String?>(null)
    val currentPlayingClipId: StateFlow<String?> = _currentPlayingClipId.asStateFlow()

    fun ensurePlayerReady(): Boolean = synchronized(initLock) {
        if (isPlaying.get()) return true

        audioTrack?.let { track ->
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                resumePlayback()
                return true
            }
        }

        startPlayback()
    }

    fun startPlayback(): Boolean = synchronized(initLock) {
        if (isPlaying.get()) return true

        return try {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AndroidAudioManager
            // 扬声器/音频模式属于"锦上添花"：这些接口需要 MODIFY_AUDIO_SETTINGS 权限，
            // 拿不到时只影响外放路由，不该连累 AudioTrack 整个建不起来。
            // （早期版本在这里直接抛 SecurityException，导致接收端完全没声音）
            try {
                audioManager?.isSpeakerphoneOn = true
                audioManager?.mode = AndroidAudioManager.MODE_NORMAL
            } catch (e: Exception) {
                Log.w(TAG, "Speakerphone setup skipped: ${e.message}")
            }

            val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AUDIO_FORMAT)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack not initialized")
                audioTrack?.release()
                audioTrack = null
                return false
            }

            audioQueue.clear()
            audioTrack?.play()
            isPlaying.set(true)

            playJob = scope.launch {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                playbackLoop()
            }

            Log.d(TAG, "Playback started, speaker on")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Start playback failed", e)
            isPlaying.set(false)
            try { audioTrack?.release() } catch (_: Exception) {}
            audioTrack = null
            false
        }
    }

    fun pausePlayback() = synchronized(initLock) {
        try {
            audioTrack?.pause()
            isPlaying.set(false)
            playJob?.cancel()
            playJob = null
            _currentPlayingClipId.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Pause playback error", e)
        }
    }

    fun resumePlayback() = synchronized(initLock) {
        try {
            if (audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioQueue.clear()
                audioTrack?.play()
                isPlaying.set(true)

                playJob = scope.launch {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    playbackLoop()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Resume playback error", e)
        }
    }

    fun stopPlayback() = synchronized(initLock) {
        isPlaying.set(false)
        playJob?.cancel()
        playJob = null
        stopReplayTrack()

        try {
            audioTrack?.apply {
                if (state == AudioTrack.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop playback error", e)
        }
        audioTrack = null
        audioQueue.clear()

        Log.d(TAG, "Playback stopped")
    }

    fun playAudio(pcmData: ByteArray) {
        if (!isPlaying.get()) {
            ensurePlayerReady()
        }

        if (audioQueue.size >= MAX_QUEUE_SIZE) {
            audioQueue.poll()
        }

        audioQueue.offer(pcmData)
    }

    /**
     * 回放一段完整的 PCM 语音片段（语音回放）。
     *
     * 用独立的 AudioTrack 线程同步播放，与实时接收流的队列完全隔离：
     * 回放不会挤掉实时语音，实时语音也不会打断回放。
     * 点击正在播放的同一条语音 = 停止回放。
     */
    fun playClip(pcm: ByteArray, clipId: String = "") {
        if (pcm.isEmpty()) return

        // 如果用户点击了正在播放的同一条语音，则执行停止
        if (clipId.isNotEmpty() && _currentPlayingClipId.value == clipId) {
            stopClip()
            return
        }

        stopReplayTrack()   // 换片段前先停掉上一个，避免两条回放叠音

        val track = try {
            buildTrack()
        } catch (e: Exception) {
            Log.e(TAG, "Replay track create failed", e)
            null
        } ?: return

        replayTrack = track
        val playingId = clipId.ifEmpty { "replay" }
        _currentPlayingClipId.value = playingId

        replayThread = Thread({
            try {
                track.play()
                var offset = 0
                while (offset < pcm.size && replayTrack === track) {
                    val end = minOf(offset + REPLAY_CHUNK_BYTES, pcm.size)
                    val written = track.write(pcm, offset, end - offset)
                    if (written <= 0) break   // track 释放/错误
                    offset += written
                }
                // 写完等播完（缓冲最长约 100ms），再释放
                val tailMs = track.bufferSizeInFrames * 1000L / SAMPLE_RATE
                try { Thread.sleep(tailMs.coerceAtMost(150)) } catch (_: InterruptedException) {}
            } catch (e: Exception) {
                Log.e(TAG, "Replay playback error", e)
            } finally {
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
                if (replayTrack === track) {
                    replayTrack = null
                    replayThread = null
                    if (_currentPlayingClipId.value == playingId) {
                        _currentPlayingClipId.value = null
                    }
                }
            }
        }, "VoiceReplay").apply {
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
    }

    /** 构建一个 8kHz 单声道 AudioTrack（回放专用） */
    private fun buildTrack(): AudioTrack {
        val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(SAMPLE_RATE / 10 * 2)   // 至少 100ms，回放不怕延迟大
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AUDIO_FORMAT)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("Replay AudioTrack not initialized")
        }
        return track
    }

    /** 停止并释放回放 AudioTrack 与线程 */
    private fun stopReplayTrack() {
        val thread = replayThread
        replayThread = null
        val track = replayTrack
        replayTrack = null
        // 先置空引用让写循环退出，再 join，最后兜底 stop/release
        try { thread?.join(300) } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        _currentPlayingClipId.value = null
    }

    fun stopClip() {
        stopReplayTrack()
    }

    fun setVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        try {
            audioTrack?.setVolume(clampedVolume)
        } catch (e: Exception) {
            Log.e(TAG, "Set volume failed", e)
        }
    }

    fun setGain(gain: Float) {
        gainMultiplier = gain.coerceIn(0.5f, 4.0f)
    }

    /**
     * 就地增益：手写小端字节序运算，零中间分配。
     * data 来自播放队列，为调用方独占数组，可安全原地修改。
     */
    private fun applyGain(data: ByteArray): ByteArray {
        if (gainMultiplier == 1.0f) return data
        val gain = gainMultiplier
        for (i in 0 until data.size / 2) {
            val lo = data[i * 2].toInt() and 0xFF
            val hi = data[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            val amplified = (sample * gain).toInt().coerceIn(-32768, 32767)
            data[i * 2] = (amplified and 0xFF).toByte()
            data[i * 2 + 1] = (amplified shr 8).toByte()
        }
        return data
    }

    private suspend fun playbackLoop() {
        // 预缓冲只在冷启动做一次。旧写法 poll(5ms) 空手而归就把 primed 复位，
        // 结果每播 1 帧就重新攒 4 帧（语音 20ms 一帧），实际输出约 1/5，一顿一停。
        var primed = false

        while (isPlaying.get()) {
            try {
                if (!primed) {
                    if (audioQueue.size >= JITTER_BUFFER_FRAMES) {
                        primed = true
                    } else {
                        // 预缓冲：等队列攒够几帧再开播。
                        // 这里只能"看"不能"取"——旧代码用 poll(20ms) 等新数据，
                        // 而 poll() 会把队头那帧取出来直接丢掉：
                        // 网络每 20ms 送一帧、这里每 20ms 丢一帧，
                        // 队列永远攒不满 4 帧，于是接收到的语音几乎全被吞掉。
                        // 现在改成 delay 等一会儿，绝不消费队列。
                        delay(PRIMING_POLL_MS)
                        continue
                    }
                }

                // 有界等待：语音包 20ms 一个，200ms 等不到说明真正的静默间隙，
                // 保持 primed 不复位，下一帧来了立刻继续播（不重新攒缓冲）。
                val data = audioQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue

                if (data.isNotEmpty()) {
                    val outputData = applyGain(data)
                    audioTrack?.write(outputData, 0, outputData.size)

                    while (audioQueue.size > JITTER_HIGH_THRESHOLD) {
                        audioQueue.poll()
                    }
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (isPlaying.get()) {
                    Log.e(TAG, "Playback error", e)
                }
            }
        }
    }

    fun isPlaying(): Boolean = isPlaying.get()

    fun release() {
        stopPlayback()
        scope.cancel()
    }
}
