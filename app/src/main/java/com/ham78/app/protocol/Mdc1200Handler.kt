package com.ham78.app.protocol

import android.util.Log

class Mdc1200Handler : ProtocolHandler {
    
    companion object {
        private const val TAG = "Mdc1200Handler"
        private const val MDC_SYNC = 0x7A
        private const val MDC_FRAME_SIZE = 112
    }
    
    override fun encode(data: String): ByteArray? {
        return try {
            val payload = data.toByteArray(Charsets.UTF_8)
            if (payload.size > MDC_FRAME_SIZE - 2) {
                Log.w(TAG, "MDC1200 payload too large")
                return null
            }
            
            val result = ByteArray(MDC_FRAME_SIZE)
            result[0] = MDC_SYNC.toByte()
            System.arraycopy(payload, 0, result, 1, payload.size)
            result
        } catch (e: Exception) {
            Log.e(TAG, "MDC1200 encode error", e)
            null
        }
    }
    
    override fun decode(data: ByteArray): String? {
        return try {
            if (data.isEmpty() || data[0] != MDC_SYNC.toByte()) {
                return null
            }
            
            val endIndex = data.indexOfFirst { it == 0.toByte() }
            val length = if (endIndex > 0) endIndex else data.size
            String(data, 1, length - 1, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "MDC1200 decode error", e)
            null
        }
    }
    
    override fun getType(): ProtocolType = ProtocolType.MDC1200
}
