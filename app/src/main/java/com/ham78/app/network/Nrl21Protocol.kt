package com.ham78.app.network

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * NRL21 协议实现
 * 对讲通信协议，包含语音、心跳、文本、位置等消息类型
 */
object Nrl21Protocol {
    const val TAG = "Nrl21Protocol"
    const val HEADER = "NRL2"
    const val FIXED_BUFFER_SIZE = 48
    const val PACKET_SIZE = FIXED_BUFFER_SIZE
    const val DEFAULT_SSID = 78
    const val DEFAULT_DEVMODEL = 101   // Android 客户端设备型号 (100=小程序, 101=Android, 102=iOS, 103=Win)

    // 包类型
    const val TYPE_VOICE = 1          // 语音数据 (G711)
    const val TYPE_HEARTBEAT = 2      // 心跳包
    const val TYPE_TEXT = 5           // 文本消息
    const val TYPE_JOIN_GROUP = 7     // 加入/切换房间
    const val TYPE_OPUS = 8           // OPUS 语音

    /**
     * NRL21 数据包
     */
    data class Packet(
        val type: Int,
        val callSign: String,
        val ssid: Int,
        val devModel: Int,
        val dmrId: Int,
        val status: Int = 1,
        val count: Int = 0,
        val data: ByteArray = ByteArray(0)
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Packet
            return type == other.type && callSign == other.callSign && ssid == other.ssid
        }

        override fun hashCode(): Int {
            var result = type
            result = 31 * result + callSign.hashCode()
            result = 31 * result + ssid
            return result
        }
    }

    /**
     * 文本消息内容封装
     */
    data class TextContent(
        val subType: String,  // text, loc, json, xml, html, bin, img, video, audio
        val body: String
    ) {
        fun toBytes(): ByteArray {
            val prefix = "[$subType]"
            val textBytes = prefix.toByteArray(Charsets.UTF_8)
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            return textBytes + bodyBytes
        }

        companion object {
            fun parse(data: ByteArray): TextContent {
                val text = decodeUtf8(data)
                val regex = Regex("^\\[(text|loc|json|xml|html|bin|img|video|audio)\\](.*)$", RegexOption.DOT_MATCHES_ALL)
                val match = regex.find(text)
                return if (match != null) {
                    TextContent(match.groupValues[1], match.groupValues[2])
                } else {
                    TextContent("text", text)
                }
            }
        }
    }

    /**
     * 创建语音包
     */
    fun createVoicePacket(
        callSign: String,
        ssid: Int = DEFAULT_SSID,
        devModel: Int = DEFAULT_DEVMODEL,
        dmrId: Int = 0,
        audioData: ByteArray? = null
    ): ByteArray {
        return createPacket(TYPE_VOICE, callSign, ssid, devModel, dmrId, audioData)
    }

    /**
     * 创建心跳包
     */
    fun createHeartbeatPacket(
        callSign: String,
        ssid: Int = DEFAULT_SSID,
        devModel: Int = DEFAULT_DEVMODEL,
        dmrId: Int = 0
    ): ByteArray {
        return createPacket(TYPE_HEARTBEAT, callSign, ssid, devModel, dmrId, null)
    }

    /**
     * 创建文本消息包
     */
    fun createTextPacket(
        callSign: String,
        text: String,
        subType: String = "text",
        ssid: Int = DEFAULT_SSID,
        devModel: Int = DEFAULT_DEVMODEL,
        dmrId: Int = 0
    ): ByteArray {
        val content = TextContent(subType, text)
        return createPacket(TYPE_TEXT, callSign, ssid, devModel, dmrId, content.toBytes())
    }

    /**
     * 创建位置消息包
     */
    fun createLocationPacket(
        callSign: String,
        latitude: Double,
        longitude: Double,
        ssid: Int = DEFAULT_SSID,
        devModel: Int = DEFAULT_DEVMODEL,
        dmrId: Int = 0
    ): ByteArray {
        // 位置格式: [loc]lat,lng
        val locationText = "$latitude,$longitude"
        return createTextPacket(callSign, locationText, "loc", ssid, devModel, dmrId)
    }

    /**
     * 通用包创建方法
     */
    fun createPacket(
        type: Int,
        callSign: String,
        ssid: Int = DEFAULT_SSID,
        devModel: Int = DEFAULT_DEVMODEL,
        dmrId: Int = 0,
        data: ByteArray? = null
    ): ByteArray {
        val dataSize = data?.size ?: 0
        val buffer = ByteBuffer.allocate(PACKET_SIZE + dataSize)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // 写入固定头部 "NRL2"
        writeString(buffer, 0, HEADER, 4)
        // 长度 (头部 + 数据总长度，与服务端 encodeNRL21 一致)
        buffer.putShort(4, (FIXED_BUFFER_SIZE + dataSize).toShort())
        // DMR ID (3字节)
        writeUint24(buffer, 6, dmrId)
        // 密码字段 (9-19 共11字节，ByteBuffer.allocate 已初始化为0)

        // type
        buffer.put(20, type.toByte())
        // status
        buffer.put(21, 1)
        // count
        buffer.putShort(22, 0)

        // callSign (6字节)
        writeString(buffer, 24, callSign, 6)
        // ssid
        buffer.put(30, ssid.toByte())
        // devModel
        buffer.put(31, devModel.toByte())

        // 数据部分，从 FIXED_BUFFER_SIZE (48) 开始写入，避免覆盖头部
        if (data != null) {
            System.arraycopy(data, 0, buffer.array(), FIXED_BUFFER_SIZE, data.size)
        }

        return buffer.array()
    }

    /**
     * 解析接收到的数据包
     */
    fun decodePacket(data: ByteArray): Packet? {
        if (data.size < FIXED_BUFFER_SIZE) {
            return null
        }

        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.BIG_ENDIAN)

        val header = readString(buffer, 0, 4)
        if (header != HEADER) {
            return null
        }

        val callSign = readString(buffer, 24, 6).trim()
        val dmrId = readUint24(buffer, 6)
        val type = data[20].toInt() and 0xFF
        val ssid = data[30].toInt() and 0xFF
        val devModel = data[31].toInt() and 0xFF
        val status = data[21].toInt() and 0xFF
        val count = ((data[22].toInt() and 0xFF) shl 8) or (data[23].toInt() and 0xFF)

        val payloadData = if (data.size > FIXED_BUFFER_SIZE) {
            data.sliceArray(FIXED_BUFFER_SIZE until data.size)
        } else {
            ByteArray(0)
        }

        return Packet(
            type = type,
            callSign = callSign,
            ssid = ssid,
            devModel = devModel,
            dmrId = dmrId,
            status = status,
            count = count,
            data = payloadData
        )
    }

    /**
     * 解析并返回包类型
     */
    fun getPacketType(data: ByteArray): Int? {
        return decodePacket(data)?.type
    }

    /**
     * 判断是否为语音包
     */
    fun isVoicePacket(data: ByteArray): Boolean {
        return getPacketType(data) == TYPE_VOICE
    }

    /**
     * 判断是否为自己发送的包
     */
    fun isOwnPacket(data: ByteArray, callSign: String, ssid: Int = DEFAULT_SSID): Boolean {
        val packet = decodePacket(data) ?: return false
        return packet.callSign == callSign && packet.ssid == ssid
    }

    // ============== 辅助方法 ==============

    private fun writeString(buffer: ByteBuffer, offset: Int, str: String, length: Int) {
        for (i in 0 until length) {
            val charCode = if (i < str.length) str[i].code else 0
            buffer.put(offset + i, charCode.toByte())
        }
    }

    private fun readString(buffer: ByteBuffer, offset: Int, length: Int): String {
        val sb = StringBuilder()
        for (i in 0 until length) {
            val charCode = buffer.get(offset + i).toInt() and 0xFF
            if (charCode != 0) {
                sb.append(charCode.toChar())
            }
        }
        return sb.toString()
    }

    private fun writeUint24(buffer: ByteBuffer, offset: Int, value: Int) {
        buffer.put(offset, ((value shr 16) and 0xFF).toByte())
        buffer.put(offset + 1, ((value shr 8) and 0xFF).toByte())
        buffer.put(offset + 2, (value and 0xFF).toByte())
    }

    private fun readUint24(buffer: ByteBuffer, offset: Int): Int {
        val b0 = buffer.get(offset).toInt() and 0xFF
        val b1 = buffer.get(offset + 1).toInt() and 0xFF
        val b2 = buffer.get(offset + 2).toInt() and 0xFF
        return (b0 shl 16) + (b1 shl 8) + b2
    }

    /**
     * UTF-8 解码
     */
    private fun decodeUtf8(data: ByteArray): String {
        if (data.isEmpty()) return ""
        // 查找第一个 null 字节并截断
        val nullIndex = data.indexOf(0)
        val validData = if (nullIndex >= 0) data.sliceArray(0 until nullIndex) else data
        return try {
            String(validData, Charsets.UTF_8)
        } catch (e: Exception) {
            String(validData, Charsets.ISO_8859_1)
        }
    }
}
