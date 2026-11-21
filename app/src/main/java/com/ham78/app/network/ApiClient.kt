package com.ham78.app.network

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
    // 多台服务器可能在不同 IO 线程并发登录，必须使用线程安全的 Map
    private val serverTokens = java.util.concurrent.ConcurrentHashMap<String, String>()

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
                avatar = data["avatar"] as? String ?: "",
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
     * 当前服务器是否打开了 OIDC。失败或未开启时登录页不显示按钮。
     */
    suspend fun getOidcConfig(serverHost: String): Result<OidcConfig> = apiCall(serverHost) { host ->
        val url = "${normalizeUrl(host)}/user/oidc/config"
        val response = makeRequest(url, "GET")
        val map = parseJson(response)
        val data = if (getCode(map) == SUCCESS_CODE) {
            map["data"] as? Map<*, *> ?: emptyMap<String, Any>()
        } else map
        OidcConfig(
            enabled = data["enabled"] as? Boolean ?: false,
            buttonName = data["button_name"] as? String ?: ""
        )
    }

    /**
     * 注册。执照照片走 multipart 字段 license，其余是表单字段。
     * 成功后要等管理员审核，这里不自动登录。
     */
    suspend fun register(
        serverHost: String,
        fields: Map<String, String>,
        license: File
    ): Result<String> = apiCall(serverHost) { host ->
        val url = "${normalizeUrl(host)}/user/reg/create"
        val response = makeMultipart(url, fields, "license", license)
        val map = parseJson(response)
        if (getCode(map) == SUCCESS_CODE) getMessage(map, "注册成功，请等待管理员审核")
        else throw ApiException(getMessage(map, "手机号或者呼号已经存在，请直接登录试试"))
    }

    /** 把头像地址写回当前用户。没有头像时界面用默认头像。 */
    suspend fun updateAvatar(
        serverHost: String,
        user: UserInfo,
        avatar: String
    ): Result<UserInfo> = apiCall(serverHost) { host ->
        val url = "${normalizeUrl(host)}/user/update/avatar"
        val body = mapOf(
            "id" to user.id,
            "username" to user.username,
            "callsign" to user.callsign,
            "dmr_id" to user.dmrId,
            "mdcid" to user.mdcid,
            "avatar" to avatar
        )
        val response = makeRequest(url, "POST", body = body, serverHost = host)
        val map = parseJson(response)
        if (getCode(map) == SUCCESS_CODE) user.copy(avatar = avatar)
        else throw ApiException(getMessage(map, "头像更新失败"))
    }

    /** 当前账号名下的设备。items 是 "呼号-SSID" 到设备的映射。 */
    suspend fun getMyDevices(serverHost: String): Result<List<ManagedDevice>> = apiCall(serverHost) { host ->
        val url = "${normalizeUrl(host)}/device/mydevlist"
        val response = makeRequest(url, "GET", serverHost = host)
        val map = parseJson(response)
        if (getCode(map) != SUCCESS_CODE) throw ApiException(getMessage(map, "获取设备列表失败"))
        val data = map["data"] as? Map<*, *> ?: emptyMap<String, Any>()
        val items = (data["items"] as? Map<*, *>) ?: data
        items.values.mapNotNull { item ->
            (item as? Map<*, *>)?.let { parseManagedDevice(it) }
        }.sortedByDescending { if (it.isOnline) 1 else 0 }
    }

    suspend fun getManagedDevice(
        serverHost: String,
        callsign: String,
        ssid: Int
    ): Result<ManagedDevice> = apiCall(serverHost) { host ->
        val url = "${normalizeUrl(host)}/device/get"
        val response = makeRequest(
            url, "POST",
            body = mapOf("callsign" to callsign, "ssid" to ssid),
            serverHost = host
        )
        unwrapDevice(parseJson(response), "获取设备信息失败")
    }

    /**
     * 经服务器把 AT 指令中继到设备。
     * 服务器很快就返回，回复可能还是上一次的；调用方要再轮询 getManagedDevice。
     * type 1 查询，2 写入。
     */
    suspend fun sendDeviceAt(
        serverHost: String,
        callsign: String,
        ssid: Int,
        type: Int,
        command: String,
        data: String
    ): Result<ManagedDevice> = apiCall(serverHost) { host ->
        val url = "${normalizeUrl(host)}/device/at"
        val response = makeRequest(
            url, "POST",
            body = mapOf(
                "callsign" to callsign,
                "ssid" to ssid,
                "type" to type,
                "atcommand" to command,
                "data" to data
            ),
            serverHost = host
        )
        unwrapDevice(parseJson(response), "AT 指令发送失败")
    }

    /** 老设备的寄存器参数。body 是已经拼好的表单，和网页端同一条接口。 */
    suspend fun changeDeviceParm(serverHost: String, form: String): Result<String> = apiCall(serverHost) { host ->
        val url = "${normalizeUrl(host)}/device/change"
        val response = makeRaw(url, form, "application/x-www-form-urlencoded", host)
        val map = parseJson(response)
        if (getCode(map) == SUCCESS_CODE) getMessage(map, "已保存")
        else throw ApiException(getMessage(map, "保存失败"))
    }

    suspend fun changeDevice1w(serverHost: String, parm: Map<String, Any?>): Result<String> = apiCall(serverHost) { host ->
        val url = "${normalizeUrl(host)}/device/change1w"
        val response = makeRequest(url, "POST", body = parm, serverHost = host)
        val map = parseJson(response)
        if (getCode(map) == SUCCESS_CODE) getMessage(map, "1W 参数已保存")
        else throw ApiException(getMessage(map, "保存失败"))
    }

    /** 把一台设备改到另一个群组。只提交服务器更新用得到的字段。 */
    suspend fun moveDeviceToGroup(
        serverHost: String,
        device: ManagedDevice,
        groupId: Int
    ): Result<Boolean> = apiCall(serverHost) { host ->
        val url = "${normalizeUrl(host)}/device/update"
        val data = mapOf(
            "id" to device.id,
            "callsign" to device.callsign,
            "ssid" to device.ssid,
            "name" to device.name,
            "gird" to device.grid,
            "dmrid" to device.dmrId,
            "dev_type" to device.devType,
            "dev_model" to device.devModel,
            "group_id" to groupId,
            "status" to device.status,
            "priority" to device.priority,
            "chan_name" to device.chanName,
            "rf_type" to device.rfType,
            "note" to device.note
        )
        val response = makeRequest(url, "POST", body = data, serverHost = host)
        if (getCode(parseJson(response)) == SUCCESS_CODE) true
        else throw ApiException("切换失败，群组可能不允许加入")
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

    private suspend inline fun <T> apiCall(
        serverHost: String? = null,
        crossinline block: suspend (String) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block(serverHost ?: ""))
        } catch (e: ApiException) {
            Log.w(TAG, "API error: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "API exception", e)
            Result.failure(e)
        }
    }

    /**
     * 归一化服务器地址：裸域名/裸 IP 一律按 https 处理；
     * 只有用户在"添加服务器"或登录页显式输入 http:// 并确认后，才允许明文连接
     * （登录响应里的 token 和密码会明文过网，仅限自建/内网服务器使用）。
     */
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

        var reusable = false
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
            // 只打状态码与长度，避免把含 token 的登录响应泄露到 logcat
            if (response.length < 500) Log.d(TAG, "Response length: ${response.length}")

            if (statusCode !in 200..299) {
                throw ApiException("HTTP $statusCode")
            }

            // 完整读取响应后不断开底层连接：平台 keep-alive 池可复用 TLS 会话，
            // 5 秒周期的设备/群组轮询省去重复 TCP+TLS 握手，延迟显著降低
            reusable = true
            return response
        } finally {
            if (!reusable) connection.disconnect()
        }
    }

    private fun parseJson(json: String): Map<*, *> =
        gson.fromJson(json, Map::class.java) as Map<*, *>

    private fun getCode(map: Map<*, *>): Int =
        (map["code"] as? Number)?.toInt() ?: 0

    private fun getMessage(map: Map<*, *>, default: String): String =
        map["message"] as? String ?: default

    private fun unwrapDevice(map: Map<*, *>, fallback: String): ManagedDevice {
        if (getCode(map) != SUCCESS_CODE) throw ApiException(getMessage(map, fallback))
        val data = map["data"] as? Map<*, *> ?: throw ApiException(fallback)
        val device = (data["items"] as? Map<*, *>) ?: data
        return parseManagedDevice(device)
    }

    private fun parseManagedDevice(dev: Map<*, *>): ManagedDevice {
        val at = dev["last_atcommand"] as? Map<*, *>
        val atmap = parseStringMap(at?.get("atmap"))
        val parm = dev["device_parm"] as? Map<*, *> ?: emptyMap<Any, Any>()
        return ManagedDevice(
            id = asInt(dev["id"]),
            callsign = dev["callsign"] as? String ?: "",
            ssid = asInt(dev["ssid"]),
            name = dev["name"] as? String ?: "",
            grid = dev["gird"] as? String ?: "",
            dmrId = asInt(dev["dmrid"]).takeIf { it != 0 } ?: asInt(dev["dmr_id"]),
            devType = asInt(dev["dev_type"]),
            devModel = asInt(dev["dev_model"]),
            groupId = asInt(dev["group_id"]),
            status = asInt(dev["status"]),
            priority = asInt(dev["priority"]),
            chanName = dev["chan_name"] as? String ?: "",
            rfType = asInt(dev["rf_type"]),
            note = dev["note"] as? String ?: "",
            isOnline = dev["is_online"] as? Boolean ?: false,
            qth = dev["qth"] as? String ?: "",
            version = at?.get("version")?.let { asText(it) } ?: "",
            atmap = atmap,
            hasAt = at != null,
            parm = parm.entries.mapNotNull { (k, v) ->
                val key = k as? String ?: return@mapNotNull null
                key to v
            }.toMap()
        )
    }

    private fun parseStringMap(raw: Any?): Map<String, String> {
        val map = raw as? Map<*, *> ?: return emptyMap()
        return map.entries.mapNotNull { (k, v) ->
            val key = k as? String ?: return@mapNotNull null
            key to asText(v)
        }.toMap()
    }

    private fun asInt(value: Any?): Int = (value as? Number)?.toInt() ?: 0

    private fun asText(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Number -> if (value.toDouble() % 1.0 == 0.0) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    private fun makeRaw(url: String, body: String, contentType: String, serverHost: String): String {
        val connection = open(url, "POST", serverHost)
        connection.setRequestProperty("Content-Type", contentType)
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        return readResponse(connection)
    }

    private fun makeMultipart(
        url: String,
        fields: Map<String, String>,
        fileField: String,
        file: File,
        contentType: String = "image/jpeg"
    ): String {
        val boundary = "----78HAM${System.currentTimeMillis()}"
        val connection = open(url, "POST", null)
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.doOutput = true
        connection.outputStream.use { out ->
            val writer = java.io.OutputStreamWriter(out, StandardCharsets.UTF_8)
            fields.forEach { (key, value) ->
                writer.write("--$boundary\r\n")
                writer.write("Content-Disposition: form-data; name=\"$key\"\r\n\r\n")
                writer.write(value)
                writer.write("\r\n")
            }
            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"$fileField\"; filename=\"${file.name}\"\r\n")
            writer.write("Content-Type: $contentType\r\n\r\n")
            writer.flush()
            file.inputStream().use { it.copyTo(out) }
            writer.write("\r\n--$boundary--\r\n")
            writer.flush()
        }
        return readResponse(connection)
    }

    private fun open(url: String, method: String, serverHost: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            serverHost?.let { getTokenForServer(it) }?.takeIf { it.isNotEmpty() }?.let {
                setRequestProperty("x-token", it)
            }
        }

    private fun readResponse(connection: HttpURLConnection): String {
        var reusable = false
        try {
            val statusCode = connection.responseCode
            val response = if (statusCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            if (statusCode !in 200..299) throw ApiException("HTTP $statusCode")
            reusable = true
            return response
        } finally {
            if (!reusable) connection.disconnect()
        }
    }

    // ============== 数据类 ==============

    data class UserInfo(
        val id: Int,
        val username: String,
        val callsign: String,
        val dmrId: Int,
        val mdcid: String = "",
        val avatar: String = "",
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

    data class OidcConfig(
        val enabled: Boolean,
        val buttonName: String
    )

    /** 我的设备 / AT 回复里的一台设备。parm 是老型号寄存器，原样留给设置页提交。 */
    data class ManagedDevice(
        val id: Int = 0,
        val callsign: String = "",
        val ssid: Int = 0,
        val name: String = "",
        val grid: String = "",
        val dmrId: Int = 0,
        val devType: Int = 0,
        val devModel: Int = 0,
        val groupId: Int = 0,
        val status: Int = 0,
        val priority: Int = 0,
        val chanName: String = "",
        val rfType: Int = 0,
        val note: String = "",
        val isOnline: Boolean = false,
        val qth: String = "",
        val version: String = "",
        val atmap: Map<String, String> = emptyMap(),
        val hasAt: Boolean = false,
        val parm: Map<String, Any?> = emptyMap()
    )

    class ApiException(message: String) : Exception(message)
}
