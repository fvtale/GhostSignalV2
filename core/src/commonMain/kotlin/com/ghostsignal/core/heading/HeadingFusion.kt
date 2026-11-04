package com.ghostsignal.core.heading

import com.ghostsignal.core.geo.Geo
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/** Minimal 3-vector for IMU work. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
  fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z
  fun norm(): Double = sqrt(dot(this))
  operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
  operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
  operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)

  fun unit(): Vec3 {
    val length = norm()
    return if (length < 1e-9) this else this * (1.0 / length)
  }

  companion object {
    val ZERO = Vec3(0.0, 0.0, 0.0)
  }
}

/** Quaternion in w + xi + yj + zk form (components stored x, y, z, w). */
data class Quat(val x: Double, val y: Double, val z: Double, val w: Double)

/** One glasses IMU sample. Any channel may be missing on a given frame. */
data class ImuSample(
    val timestampNs: Long,
    /** Acceleration in m/s², device frame. At rest it points away from ground. */
    val accel: Vec3?,
    /** Angular velocity in rad/s, device frame. */
    val gyro: Vec3?,
    val orientation: Quat?,
)

enum class HeadingSource { GLASSES, PHONE, COURSE, NONE }

data class HeadingEstimate(
    val deg: Double?,
    val source: HeadingSource,
    val glassesAligned: Boolean,
    /** Head pitch in degrees: positive looks up. Glasses-derived. */
    val pitchDeg: Double? = null,
    /** Head roll in degrees: positive tilts toward the right shoulder. */
    val rollDeg: Double? = null,
)

/**
 * Estimates head heading (degrees clockwise from true north) from whatever
 * the hardware offers, best source first.
 *
 * The glasses IMU measures head *turns* beautifully but has no north, and
 * its axis conventions are unknown. So yaw is integrated from the gyroscope
 * about the measured gravity axis — frame-agnostic by construction — and a
 * single offset anchors that relative yaw to the world. The anchor comes
 * from the phone compass (calibration, or looking at the phone) and is
 * continuously nudged by GPS course while walking, on the theory that
 * people mostly look where they walk.
 *
 * Fallback chain when glasses data goes stale: phone compass in hand, then
 * GPS course, then nothing (the game switches to non-directional cues).
 */
class HeadingFusion(
    var mode: YawMode = YawMode.GYRO,
    /** Flip when a right turn moves the rendered needle left. Field-checked. */
    var invertYaw: Boolean = false,
    /** Flip when nodding down reads as pitch up. Field-checked. */
    var invertPitch: Boolean = false,
) {

  enum class YawMode {
    /** Integrate gyro about gravity. Slow drift, corrected by GPS course. */
    GYRO,
    /** Take yaw from the fused orientation quaternion (assumes Z-up world). */
    QUATERNION,
  }

  // Gyro integration state.
  private var relativeYawDeg: Double = 0.0
  private var lastImuNs: Long? = null
  private var lastImuWallMs: Long? = null
  private var gravity: Vec3? = null
  private var gyroBias: Vec3 = Vec3.ZERO
  private var stillSinceNs: Long? = null

  // World anchor for the relative yaw.
  private var offsetDeg: Double? = null

  // Phone compass state.
  private var phoneAzimuthDeg: Double? = null
  private var phoneAzimuthMs: Long = 0L
  private var phoneInHand: Boolean = false

  // GPS course state.
  private var courseDeg: Double? = null
  private var courseMs: Long = 0L
  private var speedMps: Double = 0.0

  // Pitch/roll state.
  private var lastPitchDeg: Double? = null
  private var lastRollDeg: Double? = null
  private var pitchGravity: Vec3? = null

  val glassesAligned: Boolean
    get() = offsetDeg != null

  fun onImu(sample: ImuSample, wallMs: Long) {
    updatePitchAndRoll(sample)
    lastImuWallMs = wallMs
    val yaw = when (mode) {
      YawMode.GYRO -> integrateGyro(sample)
      YawMode.QUATERNION -> sample.orientation?.let { quat -> quaternionYawDeg(quat) }
    } ?: return
    relativeYawDeg = yaw
    lastImuWallMs = wallMs
    // First chance to anchor: adopt any fresh absolute reference.
    if (offsetDeg == null) {
      absoluteReference(wallMs)?.let { reference ->
        offsetDeg = Geo.normalizeDeg(reference - signed(relativeYawDeg))
      }
    }
  }

  private fun integrateGyro(sample: ImuSample): Double? {
    sample.accel?.let { accel ->
      val previous = gravity
      gravity = if (previous == null) accel else previous * 0.92 + accel * 0.08
    }
    val gravityNow = gravity ?: return null
    val angular = sample.gyro ?: return null

    val previousNs = lastImuNs
    lastImuNs = sample.timestampNs
    if (previousNs == null) return relativeYawDeg
    val dtSeconds = (sample.timestampNs - previousNs) / 1e9
    if (dtSeconds <= 0.0 || dtSeconds > 0.5) return relativeYawDeg

    trackGyroBias(angular, sample.timestampNs)

    // Rotation about "up" is counter-clockwise from above; headings run
    // clockwise, hence the subtraction.
    val yawRateCcw = (angular - gyroBias).dot(gravityNow.unit())
    return relativeYawDeg - yawRateCcw * dtSeconds * 180.0 / PI
  }

  /** Learns the gyro's resting bias while the head is held still. */
  private fun trackGyroBias(angular: Vec3, timestampNs: Long) {
    if (angular.norm() < 0.03) {
      val since = stillSinceNs ?: timestampNs.also { stillSinceNs = it }
      if (timestampNs - since > 1_000_000_000L) {
        gyroBias = gyroBias * 0.98 + angular * 0.02
      }
    } else {
      stillSinceNs = null
    }
  }

  /** Yaw of a Z-up orientation quaternion, as a clockwise heading. */
  fun quaternionYawDeg(q: Quat): Double {
    val sinTerm = 2.0 * (q.w * q.z + q.x * q.y)
    val cosTerm = 1.0 - 2.0 * (q.y * q.y + q.z * q.z)
    return -atan2(sinTerm, cosTerm) * 180.0 / PI
  }

  /**
   * Pitch and roll from the same frame. Assumed device convention: X runs
   * ear-to-ear, Y up out of the frame top, Z forward out of the face. The
   * orientation quaternion wins when present; otherwise smoothed gravity
   * gives horizon tilt, which needs no world anchor and never drifts.
   */
  private fun updatePitchAndRoll(sample: ImuSample) {
    val orientation = sample.orientation
    if (orientation != null) {
      val (pitch, roll) = quaternionPitchRollDeg(orientation)
      lastPitchDeg = pitch
      lastRollDeg = roll
      return
    }
    val accel = sample.accel ?: return
    val previous = pitchGravity
    val smoothed = if (previous == null) accel else previous * 0.92 + accel * 0.08
    pitchGravity = smoothed
    if (smoothed.norm() < 1e-9) return
    val up = smoothed.unit()
    lastPitchDeg = -atan2(up.z, up.y) * 180.0 / PI
    lastRollDeg = atan2(up.x, up.y) * 180.0 / PI
  }

  /** Pitch/roll of a fused orientation quaternion (world up is +Y there). */
  fun quaternionPitchRollDeg(q: Quat): Pair<Double, Double> {
    val worldUp = Vec3(0.0, 1.0, 0.0)
    val forward = rotate(q, Vec3(0.0, 0.0, 1.0))
    val headUp = rotate(q, worldUp)

    val pitch = asin(forward.dot(worldUp).coerceIn(-1.0, 1.0)) * 180.0 / PI

    val forwardFlat = forward.flattenAgainst(worldUp)
    val upFlat = headUp.flattenAgainst(worldUp)
    val roll = atan2(
        cross(upFlat, headUp).dot(forwardFlat),
        upFlat.dot(headUp).coerceIn(-1.0, 1.0),
    ) * 180.0 / PI
    return pitch to roll
  }

  private fun Vec3.flattenAgainst(axis: Vec3): Vec3 {
    val projected = this - axis * dot(axis)
    return if (projected.norm() < 1e-9) projected else projected.unit()
  }

  private fun rotate(q: Quat, v: Vec3): Vec3 {
    val vectorPart = Vec3(q.x, q.y, q.z)
    val uv = cross(vectorPart, v)
    val uuv = cross(vectorPart, uv)
    return v + (uv * q.w + uuv) * 2.0
  }

  private fun cross(a: Vec3, b: Vec3): Vec3 = Vec3(
      a.y * b.z - a.z * b.y,
      a.z * b.x - a.x * b.z,
      a.x * b.y - a.y * b.x,
  )

  fun onPhoneAzimuth(deg: Double, wallMs: Long, inHand: Boolean) {
    phoneAzimuthDeg = Geo.normalizeDeg(deg)
    phoneAzimuthMs = wallMs
    phoneInHand = inHand
  }

  fun onCourse(courseDeg: Double?, speedMps: Double?, wallMs: Long) {
    this.speedMps = speedMps ?: 0.0
    if (courseDeg == null || (speedMps ?: 0.0) < WALKING_MPS) return
    this.courseDeg = Geo.normalizeDeg(courseDeg)
    courseMs = wallMs
    // Walking correction: ease the glasses anchor toward travel direction.
    val offset = offsetDeg ?: return
    if (!glassesFresh(wallMs)) return
    val headNow = Geo.normalizeDeg(signed(relativeYawDeg) + offset)
    val error = Geo.deltaDeg(headNow, courseDeg)
    if (abs(error) < 60.0) {
      offsetDeg = Geo.normalizeDeg(offset + error * COURSE_GAIN)
    }
  }

  /** Anchors glasses yaw to the phone compass (runner faces the phone top). */
  fun calibrateToPhone(wallMs: Long): Boolean {
    val azimuth = phoneAzimuthDeg ?: return false
    if (wallMs - phoneAzimuthMs > FRESH_MS || !glassesFresh(wallMs)) return false
    offsetDeg = Geo.normalizeDeg(azimuth - signed(relativeYawDeg))
    return true
  }

  fun resetAlignment() {
    offsetDeg = null
  }

  fun estimate(wallMs: Long): HeadingEstimate {
    val pitch = pitchNow(wallMs)
    val roll = rollNow(wallMs)
    val offset = offsetDeg
    if (offset != null && glassesFresh(wallMs)) {
      val fused = Geo.normalizeDeg(signed(relativeYawDeg) + offset)
      return HeadingEstimate(fused, HeadingSource.GLASSES, glassesAligned = true, pitch, roll)
    }
    if (phoneInHand && wallMs - phoneAzimuthMs <= FRESH_MS) {
      return HeadingEstimate(phoneAzimuthDeg, HeadingSource.PHONE, glassesAligned, pitch, roll)
    }
    if (wallMs - courseMs <= COURSE_FRESH_MS && speedMps >= WALKING_MPS) {
      return HeadingEstimate(courseDeg, HeadingSource.COURSE, glassesAligned, pitch, roll)
    }
    return HeadingEstimate(null, HeadingSource.NONE, glassesAligned, pitch, roll)
  }

  private fun absoluteReference(wallMs: Long): Double? = when {
    phoneInHand && wallMs - phoneAzimuthMs <= FRESH_MS -> phoneAzimuthDeg
    wallMs - courseMs <= COURSE_FRESH_MS && speedMps >= WALKING_MPS -> courseDeg
    else -> null
  }

  private fun glassesFresh(wallMs: Long): Boolean =
      lastImuWallMs?.let { stamp -> wallMs - stamp <= FRESH_MS } ?: false

  private fun pitchNow(wallMs: Long): Double? =
      if (glassesFresh(wallMs)) lastPitchDeg?.let { pitch -> if (invertPitch) -pitch else pitch } else null

  private fun rollNow(wallMs: Long): Double? =
      if (glassesFresh(wallMs)) lastRollDeg else null

  private fun signed(yaw: Double): Double = if (invertYaw) -yaw else yaw

  companion object {
    const val FRESH_MS: Long = 1_500L
    const val COURSE_FRESH_MS: Long = 4_000L
    const val WALKING_MPS: Double = 0.8
    const val COURSE_GAIN: Double = 0.04
  }
}
