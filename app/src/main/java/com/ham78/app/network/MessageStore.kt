package com.ham78.app.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 文本消息存储
 * 按服务器分组保存消息历史
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
        val type: MessageType = MessageType.TEXT
    )

    enum class MessageType {
        TEXT, VOICE, LOCATION
    }

    private val messages = mutableMapOf<String, MutableList<TextMessage>>()
    private val maxMessagesPerServer = 200

    private val _allMessages = MutableStateFlow<List<TextMessage>>(emptyList())
    val allMessages: StateFlow<List<TextMessage>> = _allMessages.asStateFlow()

    private val _serverMessages = MutableStateFlow<Map<String, List<TextMessage>>>(emptyMap())
    val serverMessages: StateFlow<Map<String, List<TextMessage>>> = _serverMessages.asStateFlow()

    fun addMessage(message: TextMessage) {
        val serverMessages = messages.getOrPut(message.serverId) { mutableListOf() }
        serverMessages.add(message)

        // 限制消息数量
        while (serverMessages.size > maxMessagesPerServer) {
            serverMessages.removeAt(0)
        }

        emitState()
    }

    fun getMessagesForServer(serverId: String): List<TextMessage> {
        return messages[serverId]?.toList() ?: emptyList()
    }

    fun getAllMessages(): List<TextMessage> {
        return messages.values.flatten().sortedBy { it.timestampMs }
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
        val all = messages.values.flatten().sortedBy { it.timestampMs }.takeLast(500)
        _allMessages.value = all

        val byServer = messages.mapValues { (_, msgs) -> msgs.toList() }
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
