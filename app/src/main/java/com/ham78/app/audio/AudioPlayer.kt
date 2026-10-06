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
    }

    private var audioTrack: AudioTrack? = null
    private var audioManager: AndroidAudioManager? = null

    private val isPlaying = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var playJob: Job? = null

    // 有界阻塞队列：写线程 poll 阻塞等待，替代 delay 轮询，显著降低空闲 CPU 占用
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile
    private var gainMultiplier = 1.0f

    private var replayJob: Job? = null
    private val _currentPlayingClipId = MutableStateFlow<String?>(null)
    val currentPlayingClipId: StateFlow<String?> = _currentPlayingClipId.asStateFlow()

    fun ensurePlayerReady(): Boolean {
        audioTrack?.let { track ->
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                if (!isPlaying.get()) {
                    resumePlayback()
                }
                return true
            }
        }

        return startPlayback()
    }

    fun startPlayback(): Boolean {
        if (isPlaying.get()) return true

        return try {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AndroidAudioManager
            audioManager?.isSpeakerphoneOn = true
            audioManager?.mode = AndroidAudioManager.MODE_NORMAL

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

    fun pausePlayback() {
        try {
            audioTrack?.pause()
            isPlaying.set(false)
            playJob?.cancel()
            playJob = null
            replayJob?.cancel()
            replayJob = null
            _currentPlayingClipId.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Pause playback error", e)
        }
    }

    fun resumePlayback() {
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

    fun stopPlayback() {
        isPlaying.set(false)
        playJob?.cancel()
        playJob = null
        replayJob?.cancel()
        replayJob = null
        _currentPlayingClipId.value = null

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
     * 复用现有的播放队列与单一写线程（playbackLoop），按帧投递并在队列接近满时
     * 等待消费，避免覆盖 MAX_QUEUE_SIZE 上限导致片段被截断，也避免多线程同时写
     * AudioTrack。
     */
    fun playClip(pcm: ByteArray, clipId: String = "") {
        if (pcm.isEmpty()) return

        // 如果用户点击了正在播放的同一条语音，则执行暂停/停止切换
        if (clipId.isNotEmpty() && _currentPlayingClipId.value == clipId) {
            stopClip()
            return
        }

        replayJob?.cancel()
        replayJob = scope.launch {
            if (!ensurePlayerReady()) return@launch
            _currentPlayingClipId.value = clipId.ifEmpty { null }
            try {
                var offset = 0
                while (offset < pcm.size && isPlaying.get()) {
                    while (audioQueue.size >= MAX_QUEUE_SIZE - 2 && isPlaying.get()) {
                        delay(10)
                    }
                    val end = minOf(offset + BYTES_PER_FRAME, pcm.size)
                    audioQueue.offer(pcm.copyOfRange(offset, end))
                    offset = end
                }

                // 投递完成后，等待队列中的数据被消费完毕，再把状态切回空闲
                while (audioQueue.isNotEmpty() && isPlaying.get()) {
                    delay(20)
                }
            } finally {
                if (_currentPlayingClipId.value == clipId) {
                    _currentPlayingClipId.value = null
                }
            }
        }
    }

    fun stopClip() {
        replayJob?.cancel()
        replayJob = null
        audioQueue.clear()
        _currentPlayingClipId.value = null
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
        var bufferReady = false

        while (isPlaying.get()) {
            try {
                if (!bufferReady) {
                    if (audioQueue.size >= JITTER_BUFFER_FRAMES) {
                        bufferReady = true
                    } else {
                        // 阻塞等待新数据，避免忙轮询
                        audioQueue.poll(20, TimeUnit.MILLISECONDS)
                        continue
                    }
                }

                val data = audioQueue.poll(5, TimeUnit.MILLISECONDS)

                if (data != null && data.isNotEmpty()) {
                    val outputData = applyGain(data)
                    audioTrack?.write(outputData, 0, outputData.size)

                    while (audioQueue.size > JITTER_HIGH_THRESHOLD) {
                        audioQueue.poll()
                    }
                } else {
                    bufferReady = false
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
