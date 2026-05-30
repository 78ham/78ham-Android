package com.ham78.app.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 文本消息存储
 * 按服务器分组保存消息历史，优化内存使用
 */
class MessageStore {

    data class TextMessage(
        val id: String = "",
        val serverId: String = "",
        val serverName: String = "",
        val callsign: String = "",
        val ssid: Int = 0,
        val content: String = "",
        val timestamp: String = "",
        val timestampMs: Long = 0,
        val isSelf: Boolean = false,
        val type: MessageType = MessageType.TEXT,
        // 语音回放：缓存的 PCM 片段标识与时长（仅 VOICE 类型有效）
        val voiceClipId: String = "",
        val voiceDurationMs: Long = 0
    )

    enum class MessageType {
        TEXT, VOICE, LOCATION
    }

    // 使用CopyOnWriteArrayList提高线程安全性和读取性能
    private val messages = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayList<TextMessage>>()
    private val maxMessagesPerServer = 200

    private val _allMessages = MutableStateFlow<List<TextMessage>>(emptyList())
    val allMessages: StateFlow<List<TextMessage>> = _allMessages.asStateFlow()

    private val _serverMessages = MutableStateFlow<Map<String, List<TextMessage>>>(emptyMap())
    val serverMessages: StateFlow<Map<String, List<TextMessage>>> = _serverMessages.asStateFlow()

    fun addMessage(message: TextMessage) {
        val serverMessages = messages.getOrPut(message.serverId) { java.util.ArrayList() }

        // 线程安全地添加消息
        synchronized(serverMessages) {
            serverMessages.add(message)

            // 限制消息数量
            while (serverMessages.size > maxMessagesPerServer) {
                serverMessages.removeAt(0)
            }
        }

        // 语音已在上游按会话合并，消息频率已很低，这里始终发射，
        // 避免之前“节流窗口内丢弃最后一条更新”导致消息不显示的问题。
        emitState()
    }

    fun getMessagesForServer(serverId: String): List<TextMessage> {
        return messages[serverId]?.let { msgs ->
            synchronized(msgs) { msgs.toList() }
        } ?: emptyList()
    }

    fun getAllMessages(): List<TextMessage> {
        return messages.values.flatMap { msgs ->
            synchronized(msgs) { msgs.toList() }
        }.sortedBy { it.timestampMs }
    }

    fun clearServerMessages(serverId: String) {
        messages.remove(serverId)
        emitState()
    }

    fun clearAll() {
        messages.clear()
        emitState()
    }

    private fun emitState() {
        val all = messages.values.flatMap { msgs ->
            synchronized(msgs) { msgs.toList() }
        }.sortedBy { it.timestampMs }.takeLast(500)

        _allMessages.value = all

        val byServer = messages.mapValues { (_, msgs) ->
            synchronized(msgs) { msgs.toList() }
        }
        _serverMessages.value = byServer
    }

    companion object {
        private var instance: MessageStore? = null

        fun getInstance(): MessageStore {
            if (instance == null) {
                instance = MessageStore()
            }
            return instance!!
        }
    }
}
