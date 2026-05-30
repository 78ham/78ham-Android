package com.ham78.app.network

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class HttpClient {
    
    companion object {
        internal const val TAG = "HttpClient"
        const val CONNECT_TIMEOUT = 15000
        const val READ_TIMEOUT = 15000
        const val DEFAULT_USER_AGENT = "78HAM-Android/1.0"
    }
    
    internal val gson: Gson = Gson()
    
    data class HttpResponse<T>(
        val success: Boolean,
        val data: T? = null,
        val message: String? = null,
        val statusCode: Int = 0
    )
    
    suspend fun <T> get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        clazz: Class<T>
    ): Result<T> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                setRequestProperty("Accept", "application/json")
                headers.forEach { (key, value) ->
                    setRequestProperty(key, value)
                }
            }
            
            val statusCode = connection.responseCode
            Log.d(TAG, "GET $url - Status: $statusCode")
            
            val response = if (statusCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            
            Log.d(TAG, "Response: $response")
            
            if (statusCode == HttpURLConnection.HTTP_OK) {
                val data = gson.fromJson(response, clazz)
                Result.success(data)
            } else {
                Result.failure(Exception("HTTP $statusCode: $response"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET request failed", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
    
    suspend fun <T> post(
        url: String,
        body: Any? = null,
        headers: Map<String, String> = emptyMap(),
        clazz: Class<T>
    ): Result<T> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                setRequestProperty("Accept", "application/json")
                headers.forEach { (key, value) ->
                    setRequestProperty(key, value)
                }
            }
            
            if (body != null) {
                val bodyJson = if (body is String) body else gson.toJson(body)
                connection.outputStream.use { os ->
                    os.write(bodyJson.toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                }
            }
            
            val statusCode = connection.responseCode
            Log.d(TAG, "POST $url - Status: $statusCode")
            
            val response = if (statusCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            
            Log.d(TAG, "Response: $response")
            
            if (statusCode in 200..299) {
                val data = gson.fromJson(response, clazz)
                Result.success(data)
            } else {
                Result.failure(Exception("HTTP $statusCode: $response"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "POST request failed", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
    
    suspend fun <T> postForm(
        url: String,
        params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        clazz: Class<T>
    ): Result<T> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                setRequestProperty("Accept", "application/json")
                headers.forEach { (key, value) ->
                    setRequestProperty(key, value)
                }
            }
            
            val bodyString = params.entries.joinToString("&") { (k, v) ->
                "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
            }
            
            connection.outputStream.use { os ->
                os.write(bodyString.toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }
            
            val statusCode = connection.responseCode
            Log.d(TAG, "POST FORM $url - Status: $statusCode")
            
            val response = if (statusCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            
            Log.d(TAG, "Response: $response")
            
            if (statusCode in 200..299) {
                val data = gson.fromJson(response, clazz)
                Result.success(data)
            } else {
                Result.failure(Exception("HTTP $statusCode: $response"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "POST FORM request failed", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}
