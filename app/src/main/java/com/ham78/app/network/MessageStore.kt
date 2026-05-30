package com.ham78.app.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * 消息存储
 * 管理文本和语音消息的缓存与持久化
 */
class MessageStore(private val context: Context? = null) {

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
        val voiceClipId: String = "",
        val voiceDurationMs: Long = 0
    )

    enum class MessageType {
        TEXT, VOICE, LOCATION
    }

    private val messages = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayList<TextMessage>>()
    private val maxMessagesPerServer = 200

    private val gson = Gson()
    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pendingSave = AtomicReference<Job?>(null)

    private val prefs: SharedPreferences? by lazy {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _allMessages = MutableStateFlow<List<TextMessage>>(emptyList())
    val allMessages: StateFlow<List<TextMessage>> = _allMessages.asStateFlow()

    private val _serverMessages = MutableStateFlow<Map<String, List<TextMessage>>>(emptyMap())
    val serverMessages: StateFlow<Map<String, List<TextMessage>>> = _serverMessages.asStateFlow()

    init {
        loadFromDisk()
    }

    fun addMessage(message: TextMessage) {
        val serverMsgs = messages.getOrPut(message.serverId) { java.util.ArrayList() }

        synchronized(serverMsgs) {
            serverMsgs.add(message)
            while (serverMsgs.size > maxMessagesPerServer) {
                serverMsgs.removeAt(0)
            }
        }

        emitState()
        scheduleSave()
    }

    fun getMessagesForServer(serverId: String): List<TextMessage> {
        return messages[serverId]?.let { msgs ->
            synchronized(msgs) { msgs.toList() }
        } ?: emptyList()
    }

    fun getAllMessages(): List<TextMessage> {
        return collectAllMessages()
    }

    fun clearServerMessages(serverId: String) {
        messages.remove(serverId)
        emitState()
        saveToDisk()
    }

    fun clearAll() {
        messages.clear()
        emitState()
        saveToDisk()
    }

    private fun collectAllMessages(): List<TextMessage> =
        messages.values.flatMap { msgs ->
            synchronized(msgs) { msgs.toList() }
        }.sortedBy { it.timestampMs }

    private fun emitState() {
        val all = collectAllMessages().takeLast(500)
        _allMessages.value = all

        val byServer = messages.mapValues { (_, msgs) ->
            synchronized(msgs) { msgs.toList() }
        }
        _serverMessages.value = byServer
    }

    private fun loadFromDisk() {
        val json = prefs?.getString(KEY_MESSAGES, null) ?: return
        try {
            val type = object : TypeToken<Map<String, List<TextMessage>>>() {}.type
            val loaded: Map<String, List<TextMessage>> = gson.fromJson(json, type) ?: return
            loaded.forEach { (serverId, msgs) ->
                messages[serverId] = ArrayList(msgs)
            }
            emitState()
        } catch (e: Exception) {
            Log.e("MessageStore", "Failed to load messages", e)
        }
    }

    private fun saveToDisk() {
        val store = prefs ?: return
        try {
            val toSave = messages.mapValues { (_, msgs) ->
                synchronized(msgs) { msgs.takeLast(MAX_PERSISTED) }
            }
            store.edit().putString(KEY_MESSAGES, gson.toJson(toSave)).apply()
        } catch (e: Exception) {
            Log.e("MessageStore", "Failed to save messages", e)
        }
    }

    private fun scheduleSave() {
        pendingSave.getAndSet(saveScope.launch {
            delay(SAVE_DELAY_MS)
            saveToDisk()
        })?.cancel()
    }

    companion object {
        private const val PREFS_NAME = "ham78_messages"
        private const val KEY_MESSAGES = "messages_json"
        private const val MAX_PERSISTED = 100
        private const val SAVE_DELAY_MS = 2000L

        @Volatile
        private var instance: MessageStore? = null

        fun getInstance(context: Context? = null): MessageStore {
            return instance ?: synchronized(this) {
                instance ?: MessageStore(context?.applicationContext).also { instance = it }
            }
        }
    }
}
