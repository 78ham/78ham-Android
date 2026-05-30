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

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    suspend fun getCurrentLocation(): Result<Pair<Double, Double>> {
        if (!hasLocationPermission()) {
            return Result.failure(SecurityException("位置权限未授予"))
        }

        val cts = CancellationTokenSource()
        return try {
            val location: Location? = suspendCancellableCoroutine { cont ->
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY, cts.token
                )
                    .addOnSuccessListener { loc -> cont.resume(loc) }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }

                cont.invokeOnCancellation { cts.cancel() }
            }

            when {
                location != null -> {
                    Log.d(TAG, "获取位置成功: ${location.latitude}, ${location.longitude}")
                    Result.success(location.latitude to location.longitude)
                }
                else -> getLastKnownLocation()?.let { Result.success(it) }
                    ?: Result.failure(Exception("无法获取位置"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取位置失败: ${e.message}")
            getLastKnownLocation()?.let { Result.success(it) } ?: Result.failure(e)
        } finally {
            cts.cancel()
        }
    }

    private suspend fun getLastKnownLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null
        return try {
            val location: Location? = suspendCancellableCoroutine { cont ->
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { loc -> cont.resume(loc) }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }
            location?.let { it.latitude to it.longitude }
        } catch (_: Exception) { null }
    }
}
