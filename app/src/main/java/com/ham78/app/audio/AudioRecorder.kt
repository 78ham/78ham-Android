package com.ham78.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音频录音器
 * 使用 AudioRecord 实时录制麦克风音频并按 20ms 帧切分回调
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorder"

        const val SAMPLE_RATE = 8000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_MS = 20
        const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000
        const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2

        /**
         * AudioRecord 单次 read 的块大小：读 4 帧再逐帧切开回调。
         * 之前按单帧读，部分设备驱动返回的块大于请求值时，
         * 调用方只取前 320 字节、其余整块丢弃 → 实时语音每秒丢一半以上的帧。
         */
        private const val READ_FRAMES = 4

        private fun getBufferSize(): Int {
            return AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                .coerceAtLeast(BYTES_PER_FRAME * READ_FRAMES * 2)
        }
    }

    private var audioRecord: AudioRecord? = null

    private val isRecording = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordJob: Job? = null

    private val _isRecordingState = MutableStateFlow(false)
    val isRecordingState: StateFlow<Boolean> = _isRecordingState.asStateFlow()

    interface AudioDataListener {
        fun onAudioData(pcmData: ByteArray)
        fun onError(error: String)
    }

    var audioDataListener: AudioDataListener? = null

    fun startRecording(): Boolean {
        if (isRecording.get()) return true

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            audioDataListener?.onError("没有录音权限")
            return false
        }

        return try {
            val bufferSize = getBufferSize()

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord初始化失败")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            isRecording.set(true)
            _isRecordingState.value = true

            recordJob = scope.launch(Dispatchers.IO + CoroutineName("AudioRecorder")) {
                recordingLoop()
            }

            Log.d(TAG, "Recording started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Start recording failed", e)
            audioDataListener?.onError("启动录音失败: ${e.message}")
            false
        }
    }

    fun stopRecording() {
        isRecording.set(false)
        _isRecordingState.value = false

        recordJob?.cancel()
        recordJob = null

        try {
            audioRecord?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop recording error", e)
        }
        audioRecord = null

        Log.d(TAG, "Recording stopped")
    }

    private suspend fun recordingLoop() {
        // 一次读 4 帧，然后逐帧 20ms 切开回调：
        // read 返回的字节数可能多于请求值（驱动差异），必须把整块都发出去，
        // 任何截断都是丢语音。不加噪声门——微信小程序/桌面端都没有，
        // 小声说话被 RMS 阈值整帧吞掉比环境噪音更伤对讲。
        val block = ByteArray(BYTES_PER_FRAME * READ_FRAMES)
        val frame = ByteArray(BYTES_PER_FRAME)

        audioRecord?.startRecording()

        while (isRecording.get()) {
            try {
                val read = audioRecord?.read(block, 0, block.size) ?: 0

                var offset = 0
                while (offset + BYTES_PER_FRAME <= read) {
                    System.arraycopy(block, offset, frame, 0, BYTES_PER_FRAME)
                    audioDataListener?.onAudioData(frame.copyOf(BYTES_PER_FRAME))
                    offset += BYTES_PER_FRAME
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (isRecording.get()) {
                    Log.e(TAG, "Recording error", e)
                    audioDataListener?.onError("录音错误: ${e.message}")
                }
            }
        }
    }

    fun isRecording(): Boolean = isRecording.get()

    fun release() {
        stopRecording()
        scope.cancel()
    }
}
