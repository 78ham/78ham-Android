package com.ham78.app.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object NrlApi {
    private const val TAG = "NrlApi"
    private val gson = Gson()

    var token: String = ""

    data class UserInfo(
        val id: Int,
        val username: String,
        val callsign: String,
        val dmrId: Int,
        val server: String?,
        val serverPort: Int?,
        val serverUdpPort: Int?
    )

    data class RoomInfo(
        val id: Int,
        val name: String,
        val roomKey: String? = null,
        val memberCount: Int = 0
    )

    data class DeviceInfo(
        val id: Int,
        val callsign: String,
        val ssid: Int,
        val groupId: Int,
        val dmrId: Int
    )

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
        body: Any? = null
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "78HAM-Android/1.0")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-token", token)
            headers.forEach { (key, value) ->
                setRequestProperty(key, value)
            }
            if (body != null && method == "POST") {
                doOutput = true
                val bodyJson = if (body is String) body else gson.toJson(body)
                outputStream.use { os ->
                    os.write(bodyJson.toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                }
            }
        }

        val statusCode = connection.responseCode
        val response = if (statusCode in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        }

        Log.d(TAG, "$method $url - Status: $statusCode")
        Log.d(TAG, "Response: $response")

        if (statusCode !in 200..299) {
            throw Exception("HTTP $statusCode: $response")
        }

        return response
    }

    private fun checkCode(responseJson: String): Boolean {
        val map = gson.fromJson(responseJson, Map::class.java)
        val code = (map["code"] as? Number)?.toInt() ?: 0
        return code == 20000 || code == 60204
    }

    suspend fun login(
        serverHost: String,
        username: String,
        password: String
    ): Result<UserInfo> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = normalizeUrl(serverHost)
            val url = "$baseUrl/user/login"

            Log.d(TAG, "登录: $url")

            val requestBody = mapOf("username" to username, "password" to password)
            val response = makeRequest(url, "POST", body = requestBody)

            val map = gson.fromJson(response, Map::class.java)
            val code = (map["code"] as? Number)?.toInt() ?: 0

            if (code == 20000 || code == 60204) {
                val data = map["data"] as? Map<*, *>
                if (data != null) {
                    val tok = data["token"] as? String ?: ""
                    if (tok.isNotEmpty()) {
                        token = tok
                        getUserInfo(serverHost)
                    } else {
                        val msg = map["message"] as? String ?: "登录失败"
                        Result.failure(Exception(msg))
                    }
                } else {
                    Result.failure(Exception("登录返回数据为空"))
                }
            } else {
                val msg = map["message"] as? String ?: "登录失败"
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "登录异常", e)
            Result.failure(e)
        }
    }

    suspend fun getUserInfo(serverHost: String): Result<UserInfo> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = normalizeUrl(serverHost)
            val url = "$baseUrl/user/info"

            Log.d(TAG, "获取用户信息: $url")

            val response = makeRequest(url, "GET")
            val map = gson.fromJson(response, Map::class.java)
            val code = (map["code"] as? Number)?.toInt() ?: 0

            if (code == 20000) {
                val data = map["data"] as? Map<*, *>
                if (data != null) {
                    val userInfo = UserInfo(
                        id = (data["id"] as? Number)?.toInt() ?: 0,
                        username = data["username"] as? String ?: "",
                        callsign = data["callsign"] as? String ?: "",
                        dmrId = (data["dmr_id"] as? Number)?.toInt() ?: 0,
                        server = data["server"] as? String,
                        serverPort = (data["server_port"] as? Number)?.toInt(),
                        serverUdpPort = (data["server_udp_port"] as? Number)?.toInt()
                    )
                    Result.success(userInfo)
                } else {
                    Result.failure(Exception("用户信息为空"))
                }
            } else {
                val msg = map["message"] as? String ?: "获取用户信息失败"
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取用户信息异常", e)
            Result.failure(e)
        }
    }

    suspend fun getRoomList(serverHost: String): Result<List<RoomInfo>> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = normalizeUrl(serverHost)
            val url = "$baseUrl/group/list/mini"

            Log.d(TAG, "获取频道列表: $url, token: $token")

            val response = makeRequest(url, "POST", body = emptyMap<String, Any>())
            val map = gson.fromJson(response, Map::class.java)
            val code = (map["code"] as? Number)?.toInt() ?: 0

            Log.d(TAG, "频道列表响应 code: $code")

            if (code == 20000) {
                val data = map["data"]
                if (data is List<*>) {
                    val rooms = data.mapNotNull { item ->
                        if (item is Map<*, *>) {
                            RoomInfo(
                                id = (item["id"] as? Number)?.toInt() ?: 0,
                                name = item["name"] as? String ?: "",
                                roomKey = item["room_key"] as? String,
                                memberCount = (item["member_count"] as? Number)?.toInt() ?: 0
                            )
                        } else null
                    }
                    Log.d(TAG, "获取频道列表成功: ${rooms.size}个频道")
                    Result.success(rooms)
                } else {
                    Log.w(TAG, "频道列表data不是数组: $data")
                    Result.success(emptyList())
                }
            } else {
                val msg = map["message"] as? String ?: "获取频道列表失败"
                Log.e(TAG, "频道列表失败: code=$code, msg=$msg")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取频道列表异常", e)
            Result.failure(e)
        }
    }

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

    suspend fun getGroup(serverHost: String, groupId: Int): Result<GroupInfo> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = normalizeUrl(serverHost)
            val url = "$baseUrl/group/get"

            val data = mapOf("group_id" to groupId)
            val response = makeRequest(url, "POST", body = data)
            val map = gson.fromJson(response, Map::class.java)
            val code = (map["code"] as? Number)?.toInt() ?: 0

            if (code == 20000) {
                val groupData = map["data"] as? Map<*, *>
                if (groupData != null) {
                    val devmapRaw = groupData["devmap"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
                    val devmap = mutableMapOf<String, DeviceInGroup>()
                    var online = 0
                    for ((key, value) in devmapRaw) {
                        val k = key.toString()
                        val v = value as? Map<*, *>
                        if (v != null) {
                            val isOnline = v["is_online"] as? Boolean ?: false
                            if (isOnline) online++
                            devmap[k] = DeviceInGroup(
                                callsign = v["callsign"] as? String ?: "",
                                ssid = (v["ssid"] as? Number)?.toInt() ?: 0,
                                isOnline = isOnline,
                                dmrId = (v["dmr_id"] as? Number)?.toInt() ?: 0,
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
                    Result.failure(Exception("群组信息为空"))
                }
            } else {
                val msg = map["message"] as? String ?: "获取群组信息失败"
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取群组信息异常", e)
            Result.failure(e)
        }
    }

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

    suspend fun getDevice(serverHost: String, callsign: String, ssid: Int): Result<DeviceData> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = normalizeUrl(serverHost)
            val url = "$baseUrl/device/get"

            val data = mapOf("callsign" to callsign, "ssid" to ssid)
            val response = makeRequest(url, "POST", body = data)
            val map = gson.fromJson(response, Map::class.java)
            val code = (map["code"] as? Number)?.toInt() ?: 0

            if (code == 20000) {
                val devData = map["data"] as? Map<*, *>
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
                    Log.d(TAG, "getDevice: id=${device.id}, callsign=${device.callsign}, groupId=${device.groupId}, isOnline=${device.isOnline}")
                    Result.success(device)
                } else {
                    Result.failure(Exception("设备信息为空"))
                }
            } else {
                val msg = map["message"] as? String ?: "获取设备信息失败"
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取设备信息异常", e)
            Result.failure(e)
        }
    }

    suspend fun updateDevice(serverHost: String, device: DeviceData, newGroupId: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = normalizeUrl(serverHost)
            val url = "$baseUrl/device/update"

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
            Log.d(TAG, "updateDevice: $data")
            val response = makeRequest(url, "POST", body = data)
            val map = gson.fromJson(response, Map::class.java)
            val code = (map["code"] as? Number)?.toInt() ?: 0

            if (code == 20000) {
                Log.d(TAG, "切换频道成功: $newGroupId")
                Result.success(true)
            } else {
                val msg = map["message"] as? String ?: "切换频道失败"
                Log.e(TAG, "切换频道失败: code=$code, msg=$msg")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "切换频道异常", e)
            Result.failure(e)
        }
    }
}
