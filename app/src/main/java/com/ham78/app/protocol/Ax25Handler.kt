package com.ham78.app.protocol

import android.util.Log

class Ax25Handler : ProtocolHandler {
    
    companion object {
        private const val TAG = "Ax25Handler"
        private const val AX25_FLAG = 0x7E
        private const val AX25_CONTROL = 0x03
        private const val AX25_PID = 0xF0
    }
    
    override fun encode(data: String): ByteArray? {
        return try {
            val payload = data.toByteArray(Charsets.UTF_8)
            val result = ByteArray(payload.size + 4)
            result[0] = AX25_FLAG.toByte()
            result[1] = AX25_CONTROL.toByte()
            result[2] = AX25_PID.toByte()
            System.arraycopy(payload, 0, result, 3, payload.size)
            result[result.size - 1] = AX25_FLAG.toByte()
            result
        } catch (e: Exception) {
            Log.e(TAG, "AX.25 encode error", e)
            null
        }
    }
    
    override fun decode(data: ByteArray): String? {
        return try {
            if (data.size < 4) return null
            if (data[0] != AX25_FLAG.toByte() || data[data.size - 1] != AX25_FLAG.toByte()) {
                return null
            }
            String(data, 3, data.size - 4, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "AX.25 decode error", e)
            null
        }
    }
    
    override fun getType(): ProtocolType = ProtocolType.AX25
}
