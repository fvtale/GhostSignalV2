package com.ghostsignal.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.ghostsignal.core.geo.GeoFix
import com.ghostsignal.core.geo.LatLon
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** Phone GPS through the fused location provider, at 1 Hz with high accuracy. */
class GpsLocation(context: Context, private val onFix: (GeoFix) -> Unit) {
  private val client = LocationServices.getFusedLocationProviderClient(context)
  private var running = false

  private val callback =
      object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
          val loc = result.lastLocation ?: return
          onFix(
              GeoFix(
                  position = LatLon(loc.latitude, loc.longitude),
                  accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 30.0,
                  timeMs = System.currentTimeMillis(),
                  speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                  courseDeg = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.6f) loc.bearing.toDouble() else null,
              ))
        }
      }

  @SuppressLint("MissingPermission")
  fun start() {
    if (running) return
    running = true
    val request =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
            .setMinUpdateIntervalMillis(500)
            .setWaitForAccurateLocation(false)
            .build()
    client.requestLocationUpdates(request, callback, Looper.getMainLooper())
  }

  fun stop() {
    if (!running) return
    running = false
    client.removeLocationUpdates(callback)
  }
}
