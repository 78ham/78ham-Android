package com.ham78.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioRecorder"
        
        const val SAMPLE_RATE = 8000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_MS = 20
        const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000
        const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2
        
        private fun getBufferSize(): Int {
            return AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                .coerceAtLeast(BYTES_PER_FRAME * 5)
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
            
            recordJob = scope.launch {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
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
        val buffer = ByteArray(BYTES_PER_FRAME)
        
        audioRecord?.startRecording()
        
        while (isRecording.get()) {
            try {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                
                if (read > 0) {
                    val data = buffer.copyOf(read)
                    audioDataListener?.onAudioData(data)
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
