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
    const val DEFAULT_SSID = 179
    const val DEFAULT_DEVMODEL = 101   // Android 客户端设备型号 (100=小程序, 101=Android, 102=iOS, 103=Win)

    // 协议头部偏移量
    private const val OFF_HEADER = 0
    private const val OFF_LENGTH = 4
    private const val OFF_DMR_ID = 6
    private const val OFF_TYPE = 20
    private const val OFF_STATUS = 21
    private const val OFF_COUNT = 22
    private const val OFF_CALLSIGN = 24
    private const val OFF_SSID = 30
    private const val OFF_DEVMODEL = 31
    private const val HEADER_LEN = 4
    private const val CALLSIGN_LEN = 6

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
     * 通用包创建方法（零额外包装对象，直接构建原生字节数组）
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
        val totalLength = FIXED_BUFFER_SIZE + dataSize
        val packet = ByteArray(totalLength)

        // 写入固定头部 "NRL2"
        packet[0] = 'N'.code.toByte()
        packet[1] = 'R'.code.toByte()
        packet[2] = 'L'.code.toByte()
        packet[3] = '2'.code.toByte()

        // 长度 (大端 16bit)
        packet[OFF_LENGTH] = ((totalLength shr 8) and 0xFF).toByte()
        packet[OFF_LENGTH + 1] = (totalLength and 0xFF).toByte()

        // DMR ID (大端 24bit)
        packet[OFF_DMR_ID] = ((dmrId shr 16) and 0xFF).toByte()
        packet[OFF_DMR_ID + 1] = ((dmrId shr 8) and 0xFF).toByte()
        packet[OFF_DMR_ID + 2] = (dmrId and 0xFF).toByte()

        // type / status / count
        packet[OFF_TYPE] = type.toByte()
        packet[OFF_STATUS] = 1
        packet[OFF_COUNT] = 0
        packet[OFF_COUNT + 1] = 0

        // callSign (6字节)
        val csLen = minOf(callSign.length, CALLSIGN_LEN)
        for (i in 0 until csLen) {
            packet[OFF_CALLSIGN + i] = callSign[i].code.toByte()
        }

        // ssid / devModel
        packet[OFF_SSID] = ssid.toByte()
        packet[OFF_DEVMODEL] = devModel.toByte()

        // 数据载荷
        if (data != null && dataSize > 0) {
            System.arraycopy(data, 0, packet, FIXED_BUFFER_SIZE, dataSize)
        }

        return packet
    }

    /**
     * 解析接收到的数据包（极速无锁解析，无 ByteBuffer/StringBuilder 中间分配）
     */
    fun decodePacket(data: ByteArray): Packet? {
        if (data.size < FIXED_BUFFER_SIZE) {
            return null
        }

        // 快速校验头部 "NRL2"
        if (data[0] != 'N'.code.toByte() ||
            data[1] != 'R'.code.toByte() ||
            data[2] != 'L'.code.toByte() ||
            data[3] != '2'.code.toByte()) {
            return null
        }

        // DMR ID (24bit 大端)
        val dmrId = ((data[OFF_DMR_ID].toInt() and 0xFF) shl 16) or
                    ((data[OFF_DMR_ID + 1].toInt() and 0xFF) shl 8) or
                    (data[OFF_DMR_ID + 2].toInt() and 0xFF)

        // 呼号解析（直接提取有效 ASCII 字符，避免每次接收分配 StringBuilder）
        var csLen = 0
        while (csLen < CALLSIGN_LEN && data[OFF_CALLSIGN + csLen] != 0.toByte()) {
            csLen++
        }
        val callSign = if (csLen > 0) {
            String(data, OFF_CALLSIGN, csLen, Charsets.US_ASCII).trim()
        } else ""

        val type = data[OFF_TYPE].toInt() and 0xFF
        val ssid = data[OFF_SSID].toInt() and 0xFF
        val devModel = data[OFF_DEVMODEL].toInt() and 0xFF
        val status = data[OFF_STATUS].toInt() and 0xFF
        val count = ((data[OFF_COUNT].toInt() and 0xFF) shl 8) or (data[OFF_COUNT + 1].toInt() and 0xFF)

        val payloadData = if (data.size > FIXED_BUFFER_SIZE) {
            val pSize = data.size - FIXED_BUFFER_SIZE
            val out = ByteArray(pSize)
            System.arraycopy(data, FIXED_BUFFER_SIZE, out, 0, pSize)
            out
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
     * 解析并返回包类型（直接读取偏移，无需全包解析）
     */
    fun getPacketType(data: ByteArray): Int? {
        if (data.size < FIXED_BUFFER_SIZE) return null
        return data[OFF_TYPE].toInt() and 0xFF
    }

    /**
     * 判断是否为语音包（直接读取偏移，无对象分配）
     */
    fun isVoicePacket(data: ByteArray): Boolean {
        if (data.size < FIXED_BUFFER_SIZE) return false
        val type = data[OFF_TYPE].toInt() and 0xFF
        return type == TYPE_VOICE || type == TYPE_OPUS
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
