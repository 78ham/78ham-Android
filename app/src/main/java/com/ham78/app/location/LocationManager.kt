package com.ham78.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 位置管理器
 * 获取设备 GPS 位置用于位置上报
 */
class LocationManager(private val context: Context) {

    companion object {
        private const val TAG = "LocationManager"
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    /**
     * 检查位置权限
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 获取当前位置
     */
    suspend fun getCurrentLocation(): Result<Pair<Double, Double>> {
        if (!hasLocationPermission()) {
            return Result.failure(SecurityException("位置权限未授予"))
        }

        return try {
            val cancellationToken = CancellationTokenSource()
            val location: Location? = suspendCancellableCoroutine { cont ->
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationToken.token
                ).addOnSuccessListener { loc -> cont.resume(loc) }
                 .addOnFailureListener { e -> cont.resumeWithException(e) }
            }

            if (location != null) {
                val lat = location.latitude
                val lng = location.longitude
                Log.d(TAG, "获取位置成功: $lat, $lng")
                Result.success(Pair(lat, lng))
            } else {
                // 尝试获取最后已知位置
                val lastLocation = getLastKnownLocation()
                if (lastLocation != null) {
                    Result.success(lastLocation)
                } else {
                    Result.failure(Exception("无法获取位置"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取位置失败: ${e.message}")
            // fallback 到最后已知位置
            val lastLocation = getLastKnownLocation()
            if (lastLocation != null) {
                Result.success(lastLocation)
            } else {
                Result.failure(e)
            }
        }
    }

    /**
     * 获取最后已知位置
     */
    private suspend fun getLastKnownLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null

        return try {
            val location = suspendCancellableCoroutine { cont ->
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { loc -> cont.resume(loc) }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }
            if (location != null) {
                Pair(location.latitude, location.longitude)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取最后已知位置失败: ${e.message}")
            null
        }
    }
}
