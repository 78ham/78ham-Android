package com.ham78.app.protocol

enum class ProtocolType {
    APRS,
    AX25,
    MDC1200
}

interface ProtocolHandler {
    fun encode(data: String): ByteArray?
    fun decode(data: ByteArray): String?
    fun getType(): ProtocolType
}
