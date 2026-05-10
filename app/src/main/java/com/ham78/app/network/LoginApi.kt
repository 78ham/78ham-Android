package com.ham78.app.network

import android.util.Log
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LoginApi {
    
    companion object {
        private const val TAG = "LoginApi"
    }
    
    private val httpClient = HttpClient()
    
    data class LoginRequest(
        val username: String,
        val password: String
    )
    
    data class LoginResponse(
        @SerializedName("code") val code: Int,
        @SerializedName("data") val data: LoginData?,
        @SerializedName("message") val message: String?
    )
    
    data class LoginData(
        @SerializedName("token") val token: String?
    )
    
    data class UserInfo(
        @SerializedName("id") val id: Int,
        @SerializedName("username") val username: String,
        @SerializedName("callsign") val callsign: String,
        @SerializedName("dmr_id") val dmrId: Int,
        @SerializedName("server") val server: String?,
        @SerializedName("server_port") val serverPort: Int?,
        @SerializedName("server_udp_port") val serverUdpPort: Int?,
        @SerializedName("token") val token: String?
    )
    
    data class UserInfoResponse(
        @SerializedName("code") val code: Int,
        @SerializedName("data") val data: UserInfo?,
        @SerializedName("message") val message: String?
    )
    
    data class RoomInfo(
        @SerializedName("id") val id: Int,
        @SerializedName("name") val name: String,
        @SerializedName("room_key") val roomKey: String? = null,
        @SerializedName("member_count") val memberCount: Int = 0
    )
    
    data class RoomListResponse(
        @SerializedName("code") val code: Int,
        @SerializedName("data") val data: List<RoomInfo>?,
        @SerializedName("message") val message: String?
    )
    
    private var authToken: String = ""
    
    fun setToken(token: String) {
        authToken = token
    }
    
    fun getToken(): String = authToken
    
    suspend fun login(
        serverHost: String,
        username: String,
        password: String
    ): Result<UserInfo> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = normalizeUrl(serverHost)
            val loginUrl = "$baseUrl/user/login"
            
            Log.d(TAG, "尝试登录: $loginUrl")
            
            val requestBody = mapOf(
                "username" to username,
                "password" to password
            )
            
            val result = httpClient.post(loginUrl, requestBody, clazz = LoginResponse::class.java)
            
            result.fold(
                onSuccess = { response ->
                    Log.d(TAG, "登录响应: code=${response.code}, message=${response.message}")
                    
                    if (response.code == 20000 || response.code == 60204) {
                        val token = response.data?.token ?: ""
                        if (token.isNotEmpty()) {
                            authToken = token
                            getUserInfo(serverHost, token)
                        } else {
                            Result.failure(Exception(response.message ?: "登录失败"))
                        }
                    } else {
                        Result.failure(Exception(response.message ?: "登录失败"))
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "登录失败: ${error.message}")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "登录异常", e)
            Result.failure(e)
        }
    }
    
    suspend fun getUserInfo(
        serverHost: String,
        token: String
    ): Result<UserInfo> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = normalizeUrl(serverHost)
            val url = "$baseUrl/user/info"
            
            val headers = mapOf("x-token" to token)
            val result = httpClient.post(url, emptyMap<String, Any>(), headers, clazz = UserInfoResponse::class.java)
            
            result.fold(
                onSuccess = { response ->
                    if (response.code == 20000 && response.data != null) {
                        authToken = token
                        Log.d(TAG, "获取用户信息成功: ${response.data.callsign} (DMR:${response.data.dmrId})")
                        Result.success(response.data)
                    } else {
                        Result.failure(Exception(response.message ?: "获取用户信息失败"))
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "获取用户信息失败: ${error.message}")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取用户信息异常", e)
            Result.failure(e)
        }
    }
    
    suspend fun getRoomList(
        serverHost: String,
        token: String
    ): Result<List<RoomInfo>> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = normalizeUrl(serverHost)
            val url = "$baseUrl/group/list/mini"
            
            val headers = mapOf("x-token" to token)
            val result = httpClient.post(url, emptyMap<String, Any>(), headers, clazz = RoomListResponse::class.java)
            
            result.fold(
                onSuccess = { response ->
                    if (response.code == 20000 && response.data != null) {
                        Log.d(TAG, "获取房间列表成功: ${response.data.size} 个房间")
                        Result.success(response.data)
                    } else {
                        Result.failure(Exception(response.message ?: "获取房间列表失败"))
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "获取房间列表失败: ${error.message}")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取房间列表异常", e)
            Result.failure(e)
        }
    }
    
    suspend fun joinRoom(
        serverHost: String,
        token: String,
        roomId: Int
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = normalizeUrl(serverHost)
            val url = "$baseUrl/device/update"
            
            val headers = mapOf("x-token" to token)
            val data = mapOf("group_id" to roomId)
            val result = httpClient.post(url, data, headers, clazz = DeviceUpdateResponse::class.java)
            
            result.fold(
                onSuccess = { response ->
                    if (response.code == 20000) {
                        Log.d(TAG, "切换频道成功: $roomId")
                        Result.success(true)
                    } else {
                        Result.failure(Exception(response.message ?: "切换频道失败"))
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "切换频道失败: ${error.message}")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "切换频道异常", e)
            Result.failure(e)
        }
    }
    
    suspend fun leaveRoom(
        serverHost: String,
        token: String,
        roomId: Int
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        Result.success(true)
    }
    
    suspend fun getMyDevices(
        serverHost: String,
        token: String
    ): Result<List<DeviceInfo>> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = normalizeUrl(serverHost)
            val url = "$baseUrl/device/mydevlist"
            
            val headers = mapOf("x-token" to token)
            val result = httpClient.get(url, headers, clazz = DeviceListResponse::class.java)
            
            result.fold(
                onSuccess = { response ->
                    if (response.code == 20000 && response.data != null) {
                        Log.d(TAG, "获取设备列表成功: ${response.data.size} 个设备")
                        Result.success(response.data)
                    } else {
                        Result.failure(Exception(response.message ?: "获取设备列表失败"))
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "获取设备列表失败: ${error.message}")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取设备列表异常", e)
            Result.failure(e)
        }
    }
    
    suspend fun getDevice(
        serverHost: String,
        token: String,
        deviceId: Int
    ): Result<DeviceInfo> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = normalizeUrl(serverHost)
            val url = "$baseUrl/device/get"
            
            val headers = mapOf("x-token" to token)
            val data = mapOf("id" to deviceId)
            val result = httpClient.post(url, data, headers, clazz = DeviceGetResponse::class.java)
            
            result.fold(
                onSuccess = { response ->
                    if (response.code == 20000 && response.data != null) {
                        Log.d(TAG, "获取设备成功: ${response.data.name}")
                        Result.success(response.data)
                    } else {
                        Result.failure(Exception(response.message ?: "获取设备失败"))
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "获取设备失败: ${error.message}")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取设备异常", e)
            Result.failure(e)
        }
    }
    
    private fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> "https://$url"
        }
    }
}

data class RoomInfo(
    val id: Int,
    val name: String,
    val roomKey: String? = null,
    val memberCount: Int = 0
)

data class DeviceInfo(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("dmr_id") val dmrId: Int?,
    @SerializedName("callsign") val callsign: String?,
    @SerializedName("group_id") val groupId: Int?,
    @SerializedName("group_name") val groupName: String?,
    @SerializedName("status") val status: Int?,
    @SerializedName("online") val online: Boolean?,
    @SerializedName("last_heartbeat") val lastHeartbeat: Long?
)

data class DeviceListResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("data") val data: List<DeviceInfo>?,
    @SerializedName("message") val message: String?
)

data class DeviceGetResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("data") val data: DeviceInfo?,
    @SerializedName("message") val message: String?
)

data class DeviceUpdateResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("data") val data: Any?,
    @SerializedName("message") val message: String?
)
