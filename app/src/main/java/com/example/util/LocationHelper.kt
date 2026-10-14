package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class LocationHelper(private val context: Context) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Location? = suspendCancellableCoroutine { continuation ->
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    continuation.resume(location)
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        } catch (_: Exception) {
            continuation.resume(null)
        }
    }

    fun isWithinRadius(
        currentLat: Double,
        currentLng: Double,
        targetLat: Double,
        targetLng: Double,
        radiusMeters: Double
    ): Boolean {
        val results = FloatArray(1)
        Location.distanceBetween(currentLat, currentLng, targetLat, targetLng, results)
        return results[0] <= radiusMeters
    }
}
