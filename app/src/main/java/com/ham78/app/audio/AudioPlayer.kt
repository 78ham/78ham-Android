package com.ham78.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager as AndroidAudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

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
        private const val INITIAL_BUFFER_FRAMES = 3
    }

    private var audioTrack: AudioTrack? = null
    private var audioManager: AndroidAudioManager? = null

    private val isPlaying = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var playJob: Job? = null

    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var initialBufferCount = 0

    private var replayJob: Job? = null

    fun ensurePlayerReady(): Boolean {
        if (audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
            if (!isPlaying.get()) {
                resumePlayback()
            }
            return true
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
            initialBufferCount = 0
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
        } catch (e: Exception) {
            Log.e(TAG, "Pause playback error", e)
        }
    }

    fun resumePlayback() {
        try {
            if (audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioQueue.clear()
                initialBufferCount = 0
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
    fun playClip(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        replayJob?.cancel()
        replayJob = scope.launch {
            if (!ensurePlayerReady()) return@launch
            var offset = 0
            while (offset < pcm.size && isPlaying.get()) {
                // 队列接近满时等待消费，保持实时节奏
                while (audioQueue.size >= MAX_QUEUE_SIZE - 2 && isPlaying.get()) {
                    delay(10)
                }
                val end = minOf(offset + BYTES_PER_FRAME, pcm.size)
                audioQueue.offer(pcm.copyOfRange(offset, end))
                offset = end
            }
        }
    }

    fun setVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        try {
            audioTrack?.setVolume(clampedVolume)
        } catch (e: Exception) {
            Log.e(TAG, "Set volume failed", e)
        }
    }

    private suspend fun playbackLoop() {
        while (isPlaying.get()) {
            try {
                val data = audioQueue.poll()

                if (data != null && data.isNotEmpty()) {
                    initialBufferCount++
                    audioTrack?.write(data, 0, data.size)
                } else {
                    delay(2)
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
