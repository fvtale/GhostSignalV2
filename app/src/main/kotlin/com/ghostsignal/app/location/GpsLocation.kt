package com.ghostsignal.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.ghostsignal.core.geo.GeoFix
import com.ghostsignal.core.geo.LatLon
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** Phone GPS through the fused provider: 1 Hz, high accuracy. */
class GpsLocation(context: Context, private val onFix: (GeoFix) -> Unit) {

  private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
  private var isRunning = false

  private val locationCallback = object : LocationCallback() {
    override fun onLocationResult(result: LocationResult) {
      val location = result.lastLocation ?: return
      onFix(location.toFix())
    }
  }

  private fun Location.toFix(): GeoFix {
    val isMoving = hasSpeed() && speed > 0.6f
    return GeoFix(
        position = LatLon(latitude, longitude),
        accuracyM = if (hasAccuracy()) accuracy.toDouble() else 30.0,
        timeMs = System.currentTimeMillis(),
        speedMps = if (hasSpeed()) speed.toDouble() else null,
        courseDeg = if (hasBearing() && isMoving) bearing.toDouble() else null,
    )
  }

  @SuppressLint("MissingPermission")
  fun start() {
    if (isRunning) return
    isRunning = true
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
        .setMinUpdateIntervalMillis(500)
        .setWaitForAccurateLocation(false)
        .build()
    fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
  }

  fun stop() {
    if (!isRunning) return
    isRunning = false
    fusedClient.removeLocationUpdates(locationCallback)
  }
}
