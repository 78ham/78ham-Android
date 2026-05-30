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
    private const val TIMEOUT_MS = 15000
    private const val USER_AGENT = "78HAM-Android/2.0"
    private const val SUCCESS_CODE = 20000
    private const val LOGIN_EXISTS_CODE = 60204

    private val gson = Gson()

    // Token 管理（按服务器存储，支持多服务器同时登录）
    private val serverTokens = mutableMapOf<String, String>()

    fun getTokenForServer(serverHost: String): String =
        serverTokens[normalizeUrl(serverHost)] ?: ""

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
    suspend fun login(
        serverHost: String,
        username: String,
        password: String
    ): Result<UserInfo> = apiCall(serverHost) { host ->
        val url = "${normalizeUrl(host)}/user/login"
        val requestBody = mapOf("username" to username, "password" to password)
        val response = makeRequest(url, "POST", body = requestBody, serverHost = host)
        val map = parseJson(response)

        val code = getCode(map)
        if (code == SUCCESS_CODE || code == LOGIN_EXISTS_CODE) {
            val data = map["data"] as? Map<*, *>
            val token = data?.get("token") as? String
            if (!token.isNullOrEmpty()) {
                setTokenForServer(host, token)
                getUserInfo(host).getOrThrow()
            } else {
                throw ApiException(getMessage(map, "登录失败"))
            }
        } else {
            throw ApiException(getMessage(map, "登录失败"))
        }
    }

    /**
     * 获取当前用户信息
     */
    suspend fun getUserInfo(serverHost: String): Result<UserInfo> = apiCall(serverHost) { host ->
        val url = "${normalizeUrl(host)}/user/info"
        val response = makeRequest(url, "POST", body = emptyMap<String, Any>(), serverHost = host)
        val map = parseJson(response)

        if (getCode(map) == SUCCESS_CODE) {
            val data = map["data"] as? Map<*, *>
                ?: throw ApiException("用户信息为空")
            UserInfo(
                id = (data["id"] as? Number)?.toInt() ?: 0,
                username = data["username"] as? String ?: "",
                callsign = data["callsign"] as? String ?: "",
                dmrId = (data["dmr_id"] as? Number)?.toInt() ?: 0,
                mdcid = data["mdcid"] as? String ?: "",
                server = data["server"] as? String,
                serverPort = (data["server_port"] as? Number)?.toInt(),
                serverUdpPort = (data["server_udp_port"] as? Number)?.toInt()
            )
        } else {
            throw ApiException(getMessage(map, "获取用户信息失败"))
        }
    }

    /**
     * 获取频道列表
     */
    suspend fun getRoomList(serverHost: String): Result<List<RoomInfo>> = apiCall(serverHost) { host ->
        val url = "${normalizeUrl(host)}/group/list/mini"
        val response = makeRequest(url, "POST", body = emptyMap<String, Any>(), serverHost = host)
        val map = parseJson(response)

        if (getCode(map) == SUCCESS_CODE) {
            val data = map["data"]
            if (data is List<*>) {
                data.mapNotNull { item ->
                    (item as? Map<*, *>)?.let {
                        RoomInfo(
                            id = (it["id"] as? Number)?.toInt() ?: 0,
                            name = it["name"] as? String ?: "",
                            roomKey = it["room_key"] as? String,
                            memberCount = (it["member_count"] as? Number)?.toInt() ?: 0
                        )
                    }
                }
            } else emptyList()
        } else {
            throw ApiException(getMessage(map, "获取频道列表失败"))
        }
    }

    /**
     * 获取群组详情
     */
    suspend fun getGroup(serverHost: String, groupId: Int): Result<GroupInfo> = apiCall(serverHost) { host ->
        val url = "${normalizeUrl(host)}/group/get"
        val response = makeRequest(url, "POST", body = mapOf("group_id" to groupId), serverHost = host)
        val map = parseJson(response)

        if (getCode(map) == SUCCESS_CODE) {
            val groupData = map["data"] as? Map<*, *>
                ?: throw ApiException("群组信息为空")

            val devmapList = groupData["devmap"] as? List<*> ?: emptyList<Any>()
            val devmap = mutableMapOf<String, DeviceInGroup>()
            var online = 0

            devmapList.forEach { item ->
                (item as? Map<*, *>)?.let { v ->
                    val isOnline = v["is_online"] as? Boolean ?: false
                    if (isOnline) online++
                    val callsign = v["callsign"] as? String ?: ""
                    val ssid = (v["ssid"] as? Number)?.toInt() ?: 0
                    devmap["$callsign-$ssid"] = DeviceInGroup(
                        callsign = callsign,
                        ssid = ssid,
                        isOnline = isOnline,
                        dmrId = (v["dmrid"] as? Number)?.toInt() ?: 0,
                        devModel = (v["dev_model"] as? Number)?.toInt() ?: 0
                    )
                }
            }

            GroupInfo(
                id = (groupData["id"] as? Number)?.toInt() ?: 0,
                name = groupData["name"] as? String ?: "",
                devmap = devmap,
                onlineCount = online,
                deviceCount = devmap.size
            )
        } else {
            throw ApiException(getMessage(map, "获取群组信息失败"))
        }
    }

    /**
     * 获取设备信息
     */
    suspend fun getDevice(
        serverHost: String,
        callsign: String,
        ssid: Int
    ): Result<DeviceData> = apiCall(serverHost) { host ->
        val url = "${normalizeUrl(host)}/device/get"
        val response = makeRequest(
            url, "POST",
            body = mapOf("callsign" to callsign, "ssid" to ssid),
            serverHost = host
        )
        val map = parseJson(response)

        if (getCode(map) == SUCCESS_CODE) {
            val devData = map["data"] as? Map<*, *>
                ?: throw ApiException("设备信息为空")

            DeviceData(
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
        } else {
            throw ApiException(getMessage(map, "获取设备信息失败"))
        }
    }

    /**
     * 更新设备（切换群组等）
     */
    suspend fun updateDevice(
        serverHost: String,
        device: DeviceData,
        newGroupId: Int
    ): Result<Boolean> = apiCall(serverHost) { host ->
        val url = "${normalizeUrl(host)}/device/update"
        val data = mapOf(
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

        val response = makeRequest(url, "POST", body = data, serverHost = host)
        if (getCode(parseJson(response)) == SUCCESS_CODE) true
        else throw ApiException("更新设备失败")
    }

    /**
     * 获取服务器列表
     */
    suspend fun getServerList(): Result<List<ServerInfo>> = apiCall {
        val url = "https://m.nrlptt.com/platform/list"
        val response = makeRequest(url, "GET")
        val map = parseJson(response)

        if (getCode(map) == SUCCESS_CODE) {
            val items = (map["data"] as? Map<*, *>)?.get("items") as? List<*>
            items?.mapNotNull { item ->
                (item as? Map<*, *>)?.let {
                    ServerInfo(
                        name = it["name"] as? String ?: "",
                        host = it["host"] as? String ?: "",
                        port = (it["port"] as? Number)?.toInt() ?: 60050
                    )
                }
            } ?: emptyList()
        } else {
            throw ApiException(getMessage(map, "获取服务器列表失败"))
        }
    }

    // ============== 内部方法 ==============

    private inline fun <T> apiCall(
        serverHost: String? = null,
        crossinline block: (String) -> T
    ): Result<T> = try {
        Result.success(block(serverHost ?: ""))
    } catch (e: ApiException) {
        Log.w(TAG, "API error: ${e.message}")
        Result.failure(e)
    } catch (e: Exception) {
        Log.e(TAG, "API exception", e)
        Result.failure(e)
    }

    private fun normalizeUrl(host: String): String = when {
        host.startsWith("http://", ignoreCase = true) -> host
        host.startsWith("https://", ignoreCase = true) -> host
        else -> "https://$host"
    }

    private fun makeRequest(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: Any? = null,
        serverHost: String? = null
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")

            serverHost?.let { getTokenForServer(it) }?.takeIf { it.isNotEmpty() }?.let {
                setRequestProperty("x-token", it)
            }
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }

        try {
            if (body != null && method == "POST") {
                connection.doOutput = true
                val bodyJson = if (body is String) body else gson.toJson(body)
                connection.outputStream.use { os ->
                    os.write(bodyJson.toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                }
            }

            val statusCode = connection.responseCode
            val response = if (statusCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            Log.d(TAG, "$method $url - Status: $statusCode")
            if (response.length < 500) Log.d(TAG, "Response: $response")

            if (statusCode !in 200..299) {
                throw ApiException("HTTP $statusCode")
            }

            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJson(json: String): Map<*, *> =
        gson.fromJson(json, Map::class.java) as Map<*, *>

    private fun getCode(map: Map<*, *>): Int =
        (map["code"] as? Number)?.toInt() ?: 0

    private fun getMessage(map: Map<*, *>, default: String): String =
        map["message"] as? String ?: default

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

    class ApiException(message: String) : Exception(message)
}
