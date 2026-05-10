package com.ham78.app.protocol

import android.util.Log

class AprsHandler : ProtocolHandler {
    
    companion object {
        private const val TAG = "AprsHandler"
        private const val APRS_HEADER = 0x3C
        private const val APRS_FOOTER = 0x3E
    }
    
    override fun encode(data: String): ByteArray? {
        return try {
            val payload = data.toByteArray(Charsets.UTF_8)
            val result = ByteArray(payload.size + 2)
            result[0] = APRS_HEADER.toByte()
            System.arraycopy(payload, 0, result, 1, payload.size)
            result[result.size - 1] = APRS_FOOTER.toByte()
            result
        } catch (e: Exception) {
            Log.e(TAG, "APRS encode error", e)
            null
        }
    }
    
    override fun decode(data: ByteArray): String? {
        return try {
            if (data.size < 2) return null
            if (data[0] != APRS_HEADER.toByte() || data[data.size - 1] != APRS_FOOTER.toByte()) {
                return null
            }
            String(data, 1, data.size - 2, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "APRS decode error", e)
            null
        }
    }
    
    override fun getType(): ProtocolType = ProtocolType.APRS
}
