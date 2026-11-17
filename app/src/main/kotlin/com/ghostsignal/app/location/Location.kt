package com.ghostsignal.app.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.ghostsignal.core.geo.Geo
import com.ghostsignal.core.geo.GeoFix
import com.ghostsignal.core.geo.LatLon
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A fake runner for desk tests and demos: teleport, nudge, or walk toward
 * a point at walking pace. Fixes look like a decent GPS (5 m accuracy) and
 * carry a course, so heading fallback works too.
 */
class SimulatedLocation(
    private val scope: CoroutineScope,
    private val onFix: (GeoFix) -> Unit,
) {

  private val mutablePosition = MutableStateFlow(START_POSITION)
  val position: StateFlow<LatLon> = mutablePosition

  private var walkJob: Job? = null
  private var tickerJob: Job? = null
  private var courseDeg: Double? = null
  private var speedMps: Double = 0.0

  fun start() {
    if (tickerJob != null) return
    tickerJob = scope.launch {
      while (isActive) {
        publishFix()
        delay(1_000)
      }
    }
  }

  fun stop() {
    tickerJob?.cancel()
    tickerJob = null
    walkJob?.cancel()
  }

  fun teleport(destination: LatLon) {
    walkJob?.cancel()
    speedMps = 0.0
    courseDeg = null
    mutablePosition.value = destination
    publishFix()
  }

  fun nudge(bearingDeg: Double, meters: Double) {
    walkJob?.cancel()
    courseDeg = bearingDeg
    speedMps = 1.2
    mutablePosition.value = Geo.destination(mutablePosition.value, bearingDeg, meters)
    publishFix()
  }

  fun walkTo(target: LatLon, metersPerSecond: Double = 1.4, stopShortM: Double = 3.0) {
    walkJob?.cancel()
    walkJob = scope.launch {
      while (isActive) {
        val here = mutablePosition.value
        val remainingM = Geo.distanceM(here, target)
        if (remainingM <= stopShortM) break
        val bearing = Geo.bearingDeg(here, target)
        courseDeg = bearing
        speedMps = metersPerSecond
        mutablePosition.value = Geo.destination(here, bearing, minOf(metersPerSecond / 2, remainingM))
        delay(500)
      }
      speedMps = 0.0
      publishFix()
    }
  }

  private fun publishFix() {
    onFix(
        GeoFix(
            position = mutablePosition.value,
            accuracyM = 5.0,
            timeMs = System.currentTimeMillis(),
            speedMps = speedMps,
            courseDeg = courseDeg,
            simulated = true,
        ),
    )
  }

  companion object {
    /** Bryant Park, where the bundled mission starts. */
    val START_POSITION = LatLon(40.753742, -73.983559)
  }
}

/**
 * Phone compass, corrected to true north. Used for calibration and as the
 * heading source while the runner is looking at the phone.
 */
class PhoneCompass(
    context: Context,
    private val onAzimuth: (Double) -> Unit,
) : SensorEventListener {

  private val sensorManager = context.getSystemService(SensorManager::class.java)
  private val rotationSensor: Sensor? =
      sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

  private val rotationMatrix = FloatArray(9)
  private val remappedMatrix = FloatArray(9)
  private val orientationAngles = FloatArray(3)

  /** Magnetic declination; Midtown Manhattan until a GPS fix refines it. */
  @Volatile
  var declination: Float = -12.9f

  @Volatile
  var lastAzimuth: Double? = null
    private set

  val available: Boolean
    get() = rotationSensor != null

  fun start() {
    rotationSensor?.let { sensor ->
      sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }
  }

  fun stop() {
    sensorManager.unregisterListener(this)
  }

  fun updateDeclination(position: LatLon) {
    val field = GeomagneticField(
        position.lat.toFloat(),
        position.lon.toFloat(),
        10f,
        System.currentTimeMillis(),
    )
    declination = field.declination
  }

  override fun onSensorChanged(event: SensorEvent) {
    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
    SensorManager.getOrientation(rotationMatrix, orientationAngles)

    // Held upright to read, the direction the phone's back faces is the
    // useful one, not the top edge: remap when pitched far from flat.
    val pitchDegrees = Math.toDegrees(orientationAngles[1].toDouble())
    if (abs(pitchDegrees) > 45) {
      SensorManager.remapCoordinateSystem(
          rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remappedMatrix,
      )
      SensorManager.getOrientation(remappedMatrix, orientationAngles)
    }

    val azimuthDeg = Geo.normalizeDeg(
        Math.toDegrees(orientationAngles[0].toDouble()) + declination,
    )
    lastAzimuth = azimuthDeg
    onAzimuth(azimuthDeg)
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
