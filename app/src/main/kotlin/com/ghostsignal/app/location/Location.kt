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
 * A fake runner for desk tests and demos: teleport, nudge, or walk to a point at walking pace.
 * Fixes look like a decent GPS (5 m accuracy) and carry a course so heading fallback works too.
 */
class SimulatedLocation(private val scope: CoroutineScope, private val onFix: (GeoFix) -> Unit) {
  private val _position = MutableStateFlow(LatLon(40.753742, -73.983559))
  val position: StateFlow<LatLon> = _position
  private var walkJob: Job? = null
  private var ticker: Job? = null
  private var course: Double? = null
  private var speed = 0.0

  fun start() {
    if (ticker != null) return
    ticker =
        scope.launch {
          while (isActive) {
            emit()
            delay(1_000)
          }
        }
  }

  fun stop() {
    ticker?.cancel()
    ticker = null
    walkJob?.cancel()
  }

  fun teleport(p: LatLon) {
    walkJob?.cancel()
    speed = 0.0
    course = null
    _position.value = p
    emit()
  }

  fun nudge(bearingDeg: Double, meters: Double) {
    walkJob?.cancel()
    course = bearingDeg
    speed = 1.2
    _position.value = Geo.destination(_position.value, bearingDeg, meters)
    emit()
  }

  fun walkTo(target: LatLon, metersPerSecond: Double = 1.4, stopShortM: Double = 3.0) {
    walkJob?.cancel()
    walkJob =
        scope.launch {
          while (isActive) {
            val here = _position.value
            val d = Geo.distanceM(here, target)
            if (d <= stopShortM) break
            val b = Geo.bearingDeg(here, target)
            course = b
            speed = metersPerSecond
            _position.value = Geo.destination(here, b, minOf(metersPerSecond / 2, d))
            delay(500)
          }
          speed = 0.0
          emit()
        }
  }

  private fun emit() {
    onFix(GeoFix(_position.value, 5.0, System.currentTimeMillis(), speed, course, simulated = true))
  }
}

/**
 * Phone compass, corrected to true north. Used for calibration and as the heading source when the
 * runner is looking at the phone.
 */
class PhoneCompass(context: Context, private val onAzimuth: (Double) -> Unit) : SensorEventListener {
  private val sensors = context.getSystemService(SensorManager::class.java)
  private val rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
  private val matrix = FloatArray(9)
  private val remapped = FloatArray(9)
  private val orientation = FloatArray(3)
  @Volatile var declination = -12.9f // Midtown Manhattan, until a GPS fix refines it
  @Volatile var lastAzimuth: Double? = null
    private set

  val available: Boolean
    get() = rotation != null

  fun start() {
    rotation?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
  }

  fun stop() = sensors.unregisterListener(this)

  fun updateDeclination(p: LatLon) {
    declination = GeomagneticField(p.lat.toFloat(), p.lon.toFloat(), 10f, System.currentTimeMillis()).declination
  }

  override fun onSensorChanged(event: SensorEvent) {
    SensorManager.getRotationMatrixFromVector(matrix, event.values)
    SensorManager.getOrientation(matrix, orientation)
    // Held upright to read, use the direction the back of the phone faces instead of the top edge.
    val pitchDeg = Math.toDegrees(orientation[1].toDouble())
    if (abs(pitchDeg) > 45) {
      SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
      SensorManager.getOrientation(remapped, orientation)
    }
    val az = Geo.normalizeDeg(Math.toDegrees(orientation[0].toDouble()) + declination)
    lastAzimuth = az
    onAzimuth(az)
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
