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
        val devModel: Int = 0,
        val dmrId: Int = 0,
        // 旧消息没有这两个字段。Gson 不走构造默认值，缺字段时是 null/false，界面按空处理
        val opus: Boolean = false,
        val mdcLabel: String? = null,
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
            ?: Log.w("MessageStore", "No context provided — persistence disabled").let { null }
    }

    private val _allMessages = MutableStateFlow<List<TextMessage>>(emptyList())
    val allMessages: StateFlow<List<TextMessage>> = _allMessages.asStateFlow()

    private val _serverMessages = MutableStateFlow<Map<String, List<TextMessage>>>(emptyMap())
    val serverMessages: StateFlow<Map<String, List<TextMessage>>> = _serverMessages.asStateFlow()

    init {
        // Gson 反序列化整包消息可能较大，避免阻塞构造线程（可能在主线程）
        saveScope.launch { loadFromDisk() }
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

    private fun emitState() {
        // 单次快照：所有服务器消息只做一遍加锁拷贝，all 与 byServer 共享同一份快照。
        // 各服务器列表本身按到达序追加（timestampMs 单调），归并替代全量排序；
        // 归并结果仅取最近 500 条。
        val snapshot = messages.mapValues { (_, msgs) ->
            synchronized(msgs) { msgs.toList() }
        }
        _allMessages.value = mergeSorted(snapshot.values).takeLast(500)
        _serverMessages.value = snapshot
    }

    private fun mergeSorted(lists: Collection<List<TextMessage>>): List<TextMessage> {
        val queue = java.util.PriorityQueue<List<TextMessage>>(java.util.Comparator.comparing { l: List<TextMessage> -> l.firstOrNull()?.timestampMs ?: Long.MAX_VALUE })
        lists.forEach { if (it.isNotEmpty()) queue.offer(it) }
        val result = ArrayList<TextMessage>()
        while (queue.isNotEmpty()) {
            val smallest = queue.poll()
            result.add(smallest.first())
            if (smallest.size > 1) queue.offer(smallest.subList(1, smallest.size))
        }
        return result
    }

    private fun loadFromDisk() {
        val json = prefs?.getString(KEY_MESSAGES, null) ?: return
        try {
            val type = object : TypeToken<Map<String, List<TextMessage>>>() {}.type
            val loaded: Map<String, List<TextMessage>> = gson.fromJson(json, type) ?: return
            // 异步加载与启动初期的实时消息存在竞态：加载完成时内存里可能已经有
            // 新收到的消息，直接 put 会把它们覆盖掉。按 id 去重合并，再按时间排序。
            loaded.forEach { (serverId, msgs) ->
                val existing = messages.putIfAbsent(serverId, ArrayList(msgs))
                if (existing != null) {
                    synchronized(existing) {
                        val knownIds = existing.mapTo(HashSet()) { it.id }
                        existing.addAll(msgs.filter { it.id !in knownIds })
                        existing.sortBy { it.timestampMs }
                        while (existing.size > maxMessagesPerServer) {
                            existing.removeAt(0)
                        }
                    }
                }
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
