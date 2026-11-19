package com.ham78.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager

/*
 * 录音回放：录制一段本机语音，经编码后直接发射到当前频道。
 * 与 PTT 讲话的区别：先录完整段再发送，适合"想清楚再发"或避开现场噪音。
 *
 * 采样参数与 AudioRecorder 完全一致（8kHz/16bit/单声道），录出来的 PCM
 * 可以直接走 AudioManager.transmitPcm 编码发射。
 *
 * 调用示例（TalkService 内）：
 *   val memo = MemoRecorder(context)
 *   memo.start()
 *   delay(10_000)                 // 录 10 秒，或由用户点击"完成"提前结束
 *   val pcm = memo.stopAndTake()  // 拿到 PCM；null = 没录上
 *   if (pcm != null) multiServerManager.transmitPcmToActive(pcm)
 */
class MemoRecorder(private val context: Context) {

    companion object {
        private const val TAG = "MemoRecorder"
        private const val MAX_MS = 60_000   // 最长 60 秒：语音信道是共享资源，防止误留录音占用频道
    }

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null

    @Volatile
    private var isRecording = false
    private val buffer = java.io.ByteArrayOutputStream()

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    /** 开始录音；已有录音在进行时先丢弃旧录音重新开始 */
    fun start(): Boolean {
        if (!hasPermission) return false
        if (isRecording) stopInternal(discard = true)

        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                AudioRecorder.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AudioRecorder.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf.coerceAtLeast(AudioRecorder.BYTES_PER_FRAME * 5)
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            buffer.reset()
            isRecording = true
            audioRecord?.startRecording()

            recordThread = Thread({
                recordingLoop()
            }, "MemoRecorder").apply { start() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动录音失败", e)
            stopInternal(discard = true)
            false
        }
    }

    /**
     * 停止录音并返回已录的 PCM 数据。
     * null = 没录上任何内容（未开始过 / 权限丢失 / 录满前出错）。
     */
    fun stopAndTake(): ByteArray? {
        stopInternal(discard = false)
        val pcm = buffer.toByteArray()
        buffer.reset()
        return pcm.takeIf { it.isNotEmpty() }
    }

    /** 丢弃当前录音并停止 */
    fun cancel() {
        stopInternal(discard = true)
        buffer.reset()
    }

    // --- 内部：录音循环与资源清理 ---

    private fun recordingLoop() {
        val chunk = ByteArray(AudioRecorder.BYTES_PER_FRAME)
        val startAt = System.currentTimeMillis()

        while (isRecording) {
            // 60 秒硬上限：到点自动停，防止忘记结束把信道一直占着
            if (System.currentTimeMillis() - startAt >= MAX_MS) break

            val read = audioRecord?.read(chunk, 0, chunk.size) ?: 0
            if (read > 0) {
                synchronized(buffer) { buffer.write(chunk, 0, read) }
            }
        }
    }

    private fun stopInternal(discard: Boolean) {
        isRecording = false
        try { recordThread?.join(500) } catch (_: Exception) {}
        recordThread = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }
}
