package com.ham78.app.protocol

import android.util.Log

class ProtocolManager {
    
    companion object {
        private const val TAG = "ProtocolManager"
    }
    
    private val handlers = mapOf(
        ProtocolType.APRS to AprsHandler(),
        ProtocolType.AX25 to Ax25Handler(),
        ProtocolType.MDC1200 to Mdc1200Handler()
    )
    
    private var currentProtocol = ProtocolType.APRS
    
    fun setProtocol(type: ProtocolType) {
        currentProtocol = type
        Log.d(TAG, "Protocol switched to: $type")
    }
    
    fun getCurrentProtocol(): ProtocolType = currentProtocol
    
    fun getHandler(type: ProtocolType): ProtocolHandler? = handlers[type]
    
    fun encode(data: String, type: ProtocolType = currentProtocol): ByteArray? {
        return handlers[type]?.encode(data)
    }
    
    fun decode(data: ByteArray, type: ProtocolType = currentProtocol): String? {
        return handlers[type]?.decode(data)
    }
    
    fun encodeWithAllProtocols(data: String): Map<ProtocolType, ByteArray?> {
        return handlers.mapValues { (_, handler) ->
            handler.encode(data)
        }
    }
}
