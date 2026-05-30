package com.ham78.app.network

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 统一 API 客户端
 * 封装所有 HTTP API 调用
 */
object ApiClient {
    private const val TAG = "ApiClient"
    private val gson = Gson()

    private const val TIMEOUT_MS = 15000
    private const val USER_AGENT = "78HAM-Android/1.0"

    // Token 管理（按服务器存储，支持多服务器同时登录）
    private val serverTokens = mutableMapOf<String, String>()

    var token: String = ""
        get() = ""  // 不再使用全局 token
        private set

    fun getTokenForServer(serverHost: String): String {
        return serverTokens[normalizeUrl(serverHost)] ?: ""
    }

    fun setTokenForServer(serverHost: String, newToken: String) {
        serverTokens[normalizeUrl(serverHost)] = newToken
    }

    fun clearTokenForServer(serverHost: String) {
        serverTokens.remove(normalizeUrl(serverHost))
    }

    fun clearAllTokens() {
        serverTokens.clear()
    }

    // ============== API 接口 ==============

    /**
     * 用户登录
     */
    suspend fun login(serverHost: String, username: String, password: String): Result<UserInfo> =
        withContext(Dispatchers.IO) {
            try {
                val baseUrl: String = normalizeUrl(serverHost)
                val url: String = "$baseUrl/user/login"

                Log.d(TAG, "POST $url")

                val requestBody: Map<String, String> = mapOf("username" to username, "password" to password)
                val response: String = makeRequest(url, "POST", body = requestBody, serverHost = serverHost)
                val map: Map<*, *> = parseJson(response)

                val code: Int = getCode(map)
                if (code == 20000 || code == 60204) {
                    val data: Map<*, *>? = map["data"] as? Map<*, *>
                    if (data != null) {
                        val newToken: String = data["token"] as? String ?: ""
                        if (newToken.isNotEmpty()) {
                            setTokenForServer(serverHost, newToken)
                            // 登录成功后获取用户信息
                            getUserInfo(serverHost)
                        } else {
                            failure<UserInfo>(getMessage(map, "登录失败"))
                        }
                    } else {
                        failure<UserInfo>("登录返回数据为空")
                    }
                } else {
                    failure<UserInfo>(getMessage(map, "登录失败"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "登录异常", e)
                Result.failure(e)
            }
        }

    /**
     * 获取当前用户信息
     */
    suspend fun getUserInfo(serverHost: String): Result<UserInfo> = withContext(Dispatchers.IO) {
        try {
            val baseUrl: String = normalizeUrl(serverHost)
            val url: String = "$baseUrl/user/info"

            val response: String = makeRequest(url, "POST", body = emptyMap<String, Any>(), serverHost = serverHost)
            val map: Map<*, *> = parseJson(response)

            val code: Int = getCode(map)
            if (code == 20000) {
                val data: Map<*, *>? = map["data"] as? Map<*, *>
                if (data != null) {
                    val userInfo = UserInfo(
                        id = (data["id"] as? Number)?.toInt() ?: 0,
                        username = data["username"] as? String ?: "",
                        callsign = data["callsign"] as? String ?: "",
                        dmrId = (data["dmr_id"] as? Number)?.toInt() ?: 0,
                        mdcid = data["mdcid"] as? String ?: "",
                        server = data["server"] as? String,
                        serverPort = (data["server_port"] as? Number)?.toInt(),
                        serverUdpPort = (data["server_udp_port"] as? Number)?.toInt()
                    )
                    Result.success(userInfo)
                } else {
                    failure<UserInfo>("用户信息为空")
                }
            } else {
                failure<UserInfo>(getMessage(map, "获取用户信息失败"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取用户信息异常", e)
            Result.failure(e)
        }
    }

    /**
     * 获取频道列表
     */
    suspend fun getRoomList(serverHost: String): Result<List<RoomInfo>> = withContext(Dispatchers.IO) {
        try {
            val baseUrl: String = normalizeUrl(serverHost)
            val url: String = "$baseUrl/group/list/mini"

            val response: String = makeRequest(url, "POST", body = emptyMap<String, Any>(), serverHost = serverHost)
            val map: Map<*, *> = parseJson(response)

            val code: Int = getCode(map)
            if (code == 20000) {
                val data: Any? = map["data"]
                if (data is List<*>) {
                    val rooms: List<RoomInfo> = data.mapNotNull { item: Any? ->
                        if (item is Map<*, *>) {
                            RoomInfo(
                                id = (item["id"] as? Number)?.toInt() ?: 0,
                                name = item["name"] as? String ?: "",
                                roomKey = item["room_key"] as? String,
                                memberCount = (item["member_count"] as? Number)?.toInt() ?: 0
                            )
                        } else {
                            null
                        }
                    }
                    Result.success(rooms)
                } else {
                    Result.success(emptyList<RoomInfo>())
                }
            } else {
                failure<List<RoomInfo>>(getMessage(map, "获取频道列表失败"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取频道列表异常", e)
            Result.failure(e)
        }
    }

    /**
     * 获取群组详情
     */
    suspend fun getGroup(serverHost: String, groupId: Int): Result<GroupInfo> = withContext(Dispatchers.IO) {
        try {
            val baseUrl: String = normalizeUrl(serverHost)
            val url: String = "$baseUrl/group/get"

            val data: Map<String, Any> = mapOf("group_id" to groupId)
            val response: String = makeRequest(url, "POST", body = data, serverHost = serverHost)
            val map: Map<*, *> = parseJson(response)

            val code: Int = getCode(map)
            if (code == 20000) {
                val groupData: Map<*, *>? = map["data"] as? Map<*, *>
                if (groupData != null) {
                    // 服务端 devmap 是 []*deviceInfo 数组，不是 Map
                    val devmapList: List<*> = groupData["devmap"] as? List<*> ?: emptyList<Any>()
                    val devmap: MutableMap<String, DeviceInGroup> = mutableMapOf()
                    var online: Int = 0
                    for (item: Any? in devmapList) {
                        val v: Map<*, *>? = item as? Map<*, *>
                        if (v != null) {
                            val isOnline: Boolean = v["is_online"] as? Boolean ?: false
                            if (isOnline) online++
                            val callsign = v["callsign"] as? String ?: ""
                            val ssid = (v["ssid"] as? Number)?.toInt() ?: 0
                            val key = "$callsign-$ssid"
                            devmap[key] = DeviceInGroup(
                                callsign = callsign,
                                ssid = ssid,
                                isOnline = isOnline,
                                dmrId = (v["dmrid"] as? Number)?.toInt() ?: 0,
                                devModel = (v["dev_model"] as? Number)?.toInt() ?: 0
                            )
                        }
                    }
                    val group = GroupInfo(
                        id = (groupData["id"] as? Number)?.toInt() ?: 0,
                        name = groupData["name"] as? String ?: "",
                        devmap = devmap,
                        onlineCount = online,
                        deviceCount = devmap.size
                    )
                    Result.success(group)
                } else {
                    failure<GroupInfo>("群组信息为空")
                }
            } else {
                failure<GroupInfo>(getMessage(map, "获取群组信息失败"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取群组信息异常", e)
            Result.failure(e)
        }
    }

    /**
     * 获取设备信息
     */
    suspend fun getDevice(serverHost: String, callsign: String, ssid: Int): Result<DeviceData> =
        withContext(Dispatchers.IO) {
            try {
                val baseUrl: String = normalizeUrl(serverHost)
                val url: String = "$baseUrl/device/get"

                val data: Map<String, Any> = mapOf("callsign" to callsign, "ssid" to ssid)
                val response: String = makeRequest(url, "POST", body = data, serverHost = serverHost)
                val map: Map<*, *> = parseJson(response)

                val code: Int = getCode(map)
                if (code == 20000) {
                    val devData: Map<*, *>? = map["data"] as? Map<*, *>
                    if (devData != null) {
                        val device = DeviceData(
                            id = (devData["id"] as? Number)?.toInt() ?: 0,
                            callsign = devData["callsign"] as? String ?: "",
                            ssid = (devData["ssid"] as? Number)?.toInt() ?: 0,
                            groupId = (devData["group_id"] as? Number)?.toInt() ?: 0,
                            dmrId = (devData["dmr_id"] as? Number)?.toInt() ?: 0,
                            isOnline = devData["is_online"] as? Boolean ?: false,
                            devModel = (devData["dev_model"] as? Number)?.toInt() ?: 100,
                            lastVoiceBeginTime = devData["last_voice_begin_time"] as? String ?: "0001-01-01T00:00:00Z",
                            lastVoiceEndTime = devData["last_voice_end_time"] as? String ?: "0001-01-01T00:00:00Z"
                        )
                        Result.success(device)
                    } else {
                        failure<DeviceData>("设备信息为空")
                    }
                } else {
                    failure<DeviceData>(getMessage(map, "获取设备信息失败"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取设备信息异常", e)
                Result.failure(e)
            }
        }

    /**
     * 更新设备（切换群组等）
     */
    suspend fun updateDevice(serverHost: String, device: DeviceData, newGroupId: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val baseUrl: String = normalizeUrl(serverHost)
                val url: String = "$baseUrl/device/update"

                val data: Map<String, Any> = mapOf(
                    "id" to device.id,
                    "callsign" to device.callsign,
                    "ssid" to device.ssid,
                    "dmr_id" to device.dmrId,
                    "group_id" to newGroupId,
                    "is_online" to device.isOnline,
                    "dev_model" to device.devModel,
                    "last_voice_begin_time" to "0001-01-01T00:00:00Z",
                    "last_voice_end_time" to "0001-01-01T00:00:00Z"
                )

                val response: String = makeRequest(url, "POST", body = data, serverHost = serverHost)
                val map: Map<*, *> = parseJson(response)

                val code: Int = getCode(map)
                if (code == 20000) {
                    Result.success(true)
                } else {
                    failure<Boolean>(getMessage(map, "更新设备失败"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "更新设备异常", e)
                Result.failure(e)
            }
        }

    /**
     * 获取服务器列表
     */
    suspend fun getServerList(): Result<List<ServerInfo>> = withContext(Dispatchers.IO) {
        try {
            val url: String = "https://m.nrlptt.com/platform/list"
            val response: String = makeRequest(url, "GET")
            val map: Map<*, *> = parseJson(response)

            val code: Int = getCode(map)
            if (code == 20000) {
                val data: Map<*, *>? = map["data"] as? Map<*, *>
                val items: List<*>? = data?.get("items") as? List<*>
                if (items != null) {
                    val servers: List<ServerInfo> = items.mapNotNull { item: Any? ->
                        if (item is Map<*, *>) {
                            ServerInfo(
                                name = item["name"] as? String ?: "",
                                host = item["host"] as? String ?: "",
                                port = (item["port"] as? Number)?.toInt() ?: 60050
                            )
                        } else {
                            null
                        }
                    }
                    Result.success(servers)
                } else {
                    Result.success(emptyList<ServerInfo>())
                }
            } else {
                failure<List<ServerInfo>>(getMessage(map, "获取服务器列表失败"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取服务器列表异常", e)
            Result.failure(e)
        }
    }

    // ============== 内部方法 ==============

    private fun normalizeUrl(host: String): String {
        return when {
            host.startsWith("http://") -> host
            host.startsWith("https://") -> host
            else -> "https://$host"
        }
    }

    private fun makeRequest(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: Any? = null,
        serverHost: String? = null
    ): String {
        val connection: HttpURLConnection = URL(url).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            // 使用对应服务器的 token
            val tokenForRequest = serverHost?.let { getTokenForServer(it) } ?: ""
            if (tokenForRequest.isNotEmpty()) {
                setRequestProperty("x-token", tokenForRequest)
            }
            headers.forEach { (key: String, value: String) ->
                setRequestProperty(key, value)
            }
            if (body != null && method == "POST") {
                doOutput = true
                val bodyJson: String = if (body is String) body else gson.toJson(body)
                outputStream.use { os ->
                    os.write(bodyJson.toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                }
            }
        }

        val statusCode: Int = connection.responseCode
        val response: String = if (statusCode in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        }

        Log.d(TAG, "$method $url - Status: $statusCode")
        if (response.length < 500) {
            Log.d(TAG, "Response: $response")
        }

        if (statusCode !in 200..299) {
            throw Exception("HTTP $statusCode")
        }

        return response
    }

    private fun parseJson(json: String): Map<*, *> {
        @Suppress("UNCHECKED_CAST")
        return gson.fromJson(json, Map::class.java) as Map<*, *>
    }

    private fun getCode(map: Map<*, *>): Int {
        return (map["code"] as? Number)?.toInt() ?: 0
    }

    private fun getMessage(map: Map<*, *>, default: String): String {
        return map["message"] as? String ?: default
    }

    private fun <T> failure(message: String): Result<T> {
        return Result.failure(Exception(message))
    }

    // ============== 数据类 ==============

    data class UserInfo(
        val id: Int,
        val username: String,
        val callsign: String,
        val dmrId: Int,
        val mdcid: String = "",
        val server: String? = null,
        val serverPort: Int? = null,
        val serverUdpPort: Int? = null
    )

    data class RoomInfo(
        val id: Int,
        val name: String,
        val roomKey: String? = null,
        val memberCount: Int = 0
    )

    data class GroupInfo(
        val id: Int,
        val name: String,
        val devmap: Map<String, DeviceInGroup>,
        val onlineCount: Int = 0,
        val deviceCount: Int = 0
    )

    data class DeviceInGroup(
        val callsign: String,
        val ssid: Int,
        val isOnline: Boolean,
        val dmrId: Int = 0,
        val devModel: Int = 0
    )

    data class DeviceData(
        val id: Int,
        val callsign: String,
        val ssid: Int,
        val groupId: Int,
        val dmrId: Int,
        val isOnline: Boolean,
        val devModel: Int = 100,
        val lastVoiceBeginTime: String = "0001-01-01T00:00:00Z",
        val lastVoiceEndTime: String = "0001-01-01T00:00:00Z"
    )

    data class ServerInfo(
        val name: String,
        val host: String,
        val port: Int = 60050
    )
}