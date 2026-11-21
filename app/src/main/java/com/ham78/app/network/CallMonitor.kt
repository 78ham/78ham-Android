package com.ham78.app.network

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.ham78.app.audio.G711Codec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 实时监听。
 *
 * 连当前服务器的 /ws/calls。文本帧是房间快照和谁在说话，
 * 二进制帧是已订阅房间的 G.711 混流，没有包头，解出来单独播放。
 * 这路声音不进对讲会话，离开页面就断开。
 */
class CallMonitor(
    private val serverHost: String,
    private val token: String,
    private val myCallsign: String,
    private val currentGroupId: Int,
    private val onState: (connected: Boolean, rooms: List<MonitorRoom>, stats: MonitorStats) -> Unit
) {
    data class MonitorRoom(
        val roomKey: String,
        val roomId: Int,
        val name: String,
        val typeName: String,
        val online: Int,
        val speakers: String,
        val active: Boolean,
        val subscribed: Boolean,
        val isCurrent: Boolean
    )

    data class MonitorStats(val devices: Int = 0, val subs: Int = 0, val clients: Int = 0)

    private val g711 = G711Codec()
    private val rooms = linkedMapOf<String, JSONObject>()
    private val subscribed = mutableSetOf<String>()
    private var stats = MonitorStats()
    private var socket: WebSocket? = null
    private var track: AudioTrack? = null
    private val audioLock = Any()
    @Volatile private var closed = false

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun start() {
        if (serverHost.isBlank()) {
            onState(false, emptyList(), MonitorStats())
            return
        }
        val base = when {
            serverHost.startsWith("http://", ignoreCase = true) ->
                "ws://" + serverHost.removePrefix("http://").removePrefix("HTTP://")
            serverHost.startsWith("https://", ignoreCase = true) ->
                "wss://" + serverHost.removePrefix("https://").removePrefix("HTTPS://")
            else -> "wss://$serverHost"
        }.trimEnd('/')
        val url = if (token.isNotEmpty()) {
            "$base/ws/calls?token=${java.net.URLEncoder.encode(token, "UTF-8")}"
        } else {
            "$base/ws/calls"
        }
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                publish(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleJson(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                play(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                publish(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "monitor ws: ${t.message}")
                publish(false)
                if (!closed) {
                    webSocket.cancel()
                }
            }
        })
    }

    fun subscribe(roomKey: String, on: Boolean) {
        val body = JSONObject()
            .put("action", if (on) "subscribe" else "unsubscribe")
            .put("room_keys", org.json.JSONArray().put(roomKey))
        socket?.send(body.toString())
    }

    fun stop() {
        closed = true
        socket?.close(1000, "leave")
        socket = null
        synchronized(audioLock) {
            track?.let {
                try { it.pause(); it.flush(); it.release() } catch (_: Exception) {}
            }
            track = null
        }
        client.dispatcher.executorService.shutdown()
    }

    private fun handleJson(text: String) {
        val msg = try { JSONObject(text) } catch (_: Exception) { return }
        when (msg.optString("type")) {
            "snapshot" -> {
                rooms.clear()
                msg.optJSONArray("rooms")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val room = arr.optJSONObject(i) ?: continue
                        rooms[room.optString("room_key")] = room
                    }
                }
                subscribed.clear()
                msg.optJSONArray("subscriptions")?.let { arr ->
                    for (i in 0 until arr.length()) subscribed.add(arr.optString(i))
                }
                stats = MonitorStats(
                    devices = msg.optInt("online_devices"),
                    subs = msg.optInt("total_subs"),
                    clients = msg.optInt("connected_clients")
                )
                publish(true)
            }
            "rooms" -> {
                rooms.clear()
                msg.optJSONArray("rooms")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val room = arr.optJSONObject(i) ?: continue
                        rooms[room.optString("room_key")] = room
                    }
                }
                publish(true)
            }
            "room_state" -> {
                val room = msg.optJSONObject("room") ?: return
                val key = room.optString("room_key")
                if (key.isEmpty()) return
                val prev = rooms[key]
                if (prev == null) rooms[key] = room
                else {
                    val keys = room.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        prev.put(k, room.get(k))
                    }
                }
                publish(true)
            }
            "subscriptions" -> {
                subscribed.clear()
                msg.optJSONArray("subscriptions")?.let { arr ->
                    for (i in 0 until arr.length()) subscribed.add(arr.optString(i))
                }
                publish(true)
            }
            "stats" -> {
                stats = MonitorStats(
                    devices = msg.optInt("online_devices"),
                    subs = msg.optInt("total_subs"),
                    clients = msg.optInt("connected_clients")
                )
                publish(true)
            }
        }
    }

    private fun publish(connected: Boolean) {
        val prefix = "private:${myCallsign.uppercase()}:"
        val list = rooms.values.map { room ->
            val key = room.optString("room_key")
            val roomId = room.optInt("room_id")
            val isPrivate = key.startsWith("private:")
            val isCurrent = roomId == currentGroupId && (!isPrivate || key.startsWith(prefix))
            MonitorRoom(
                roomKey = key,
                roomId = roomId,
                name = room.optString("room_name").ifEmpty { "频道 $roomId" },
                typeName = ROOM_TYPES[room.optInt("room_type")] ?: "群组",
                online = room.optInt("online_dev_number"),
                speakers = speakersOf(room),
                active = room.optBoolean("active"),
                subscribed = key in subscribed,
                isCurrent = isCurrent
            )
        }.sortedWith(compareBy({ it.roomId }, { it.roomKey }))
        onState(connected, list, stats)
    }

    private fun speakersOf(room: JSONObject): String {
        val arr = room.optJSONArray("speakers")
        if (arr != null && arr.length() > 0) {
            return (0 until arr.length()).joinToString(" / ") { i ->
                val s = arr.optJSONObject(i)
                "${s?.optString("callsign")}-${s?.optInt("ssid")}"
            }
        }
        val call = room.optString("callsign")
        return if (call.isNotEmpty()) "$call-${room.optInt("ssid")}" else ""
    }

    private fun play(alaw: ByteArray) {
        if (alaw.isEmpty() || closed) return
        val pcm = g711.decodeAlawToPcm(alaw)
        synchronized(audioLock) {
            if (closed) return
            val audio = track ?: openTrack().also { track = it }
            audio.write(pcm, 0, pcm.size)
        }
    }

    private fun openTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(8000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(min.coerceAtLeast(1600))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    companion object {
        private const val TAG = "CallMonitor"
        private val ROOM_TYPES = mapOf(
            0 to "公共",
            1 to "中继互联",
            2 to "设备互联",
            4 to "数模互联",
            5 to "俱乐部",
            6 to "车友会",
            7 to "会议组",
            8 to "私密房间",
            100 to "其他"
        )
    }
}
