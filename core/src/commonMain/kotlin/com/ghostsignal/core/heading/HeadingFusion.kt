package com.ghostsignal.core.heading

import com.ghostsignal.core.geo.Geo
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
  fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z

  fun norm() = sqrt(dot(this))

  operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)

  operator fun times(k: Double) = Vec3(x * k, y * k, z * k)

  operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)

  fun unit(): Vec3 = norm().let { if (it < 1e-9) this else this * (1.0 / it) }

  companion object {
    val ZERO = Vec3(0.0, 0.0, 0.0)
  }
}

/** w + xi + yj + zk */
data class Quat(val x: Double, val y: Double, val z: Double, val w: Double)

/** One IMU sample from the glasses. Any field can be missing. */
data class ImuSample(
    val timestampNs: Long,
    /** m/s^2, device frame. At rest it points away from the ground. */
    val accel: Vec3?,
    /** rad/s, device frame. */
    val gyro: Vec3?,
    val orientation: Quat?,
)

enum class HeadingSource {
  GLASSES,
  PHONE,
  COURSE,
  NONE,
}

data class HeadingEstimate(val deg: Double?, val source: HeadingSource, val glassesAligned: Boolean)

/**
 * Estimates where the runner's head is pointing, in degrees clockwise from true north.
 *
 * The glasses IMU tells us how the head turns, quickly and smoothly, but not which way is north
 * (and we don't know the glasses' axis conventions). So yaw is integrated from the gyroscope
 * around the measured gravity axis, which works whatever the frame, and an offset ties it to the
 * world. The offset comes from the phone compass (when the runner is looking at the phone, or on
 * an explicit calibrate) and is nudged by the GPS course while walking, on the assumption that
 * people mostly look where they walk.
 *
 * Without fresh glasses data it falls back to the phone compass (phone in hand), then the GPS
 * course (walking), then nothing, in which case the game drops to non-directional cues.
 */
class HeadingFusion(
    var mode: YawMode = YawMode.GYRO,
    /** Flip if turning right moves the in-app needle left. Checked during the field test. */
    var invertYaw: Boolean = false,
) {
  enum class YawMode {
    /** Integrate gyro around gravity. Frame-agnostic; drifts slowly, corrected by GPS course. */
    GYRO,
    /** Use the fused orientation quaternion's yaw, assuming a Z-up world frame. */
    QUATERNION,
  }

  private var relYawDeg = 0.0
  private var lastImuNs: Long? = null
  private var lastImuWallMs: Long? = null
  private var gravity: Vec3? = null
  private var gyroBias = Vec3.ZERO
  private var stillSinceNs: Long? = null

  private var offsetDeg: Double? = null

  private var phoneAzimuthDeg: Double? = null
  private var phoneAzimuthMs: Long = 0
  private var phoneInHand = false

  private var courseDeg: Double? = null
  private var courseMs: Long = 0
  private var speedMps = 0.0

  val glassesAligned: Boolean
    get() = offsetDeg != null

  fun onImu(sample: ImuSample, wallMs: Long) {
    val yaw = when (mode) {
      YawMode.GYRO -> integrateGyro(sample)
      YawMode.QUATERNION -> sample.orientation?.let { quaternionYawDeg(it) }
    } ?: return
    relYawDeg = yaw
    lastImuWallMs = wallMs
    // First chance to align: whichever absolute reference is fresh right now.
    if (offsetDeg == null) {
      absoluteReference(wallMs)?.let { offsetDeg = Geo.normalizeDeg(it - signed(relYawDeg)) }
    }
  }

  private fun integrateGyro(sample: ImuSample): Double? {
    sample.accel?.let { a ->
      gravity = gravity?.let { g -> g * 0.92 + a * 0.08 } ?: a
    }
    val g = gravity ?: return null
    val w = sample.gyro ?: return null
    val prev = lastImuNs
    lastImuNs = sample.timestampNs
    if (prev == null) return relYawDeg
    val dt = (sample.timestampNs - prev) / 1e9
    if (dt <= 0.0 || dt > 0.5) return relYawDeg

    // Learn gyro bias while the head is still.
    if (w.norm() < 0.03) {
      val since = stillSinceNs ?: sample.timestampNs.also { stillSinceNs = it }
      if (sample.timestampNs - since > 1_000_000_000L) gyroBias = gyroBias * 0.98 + w * 0.02
    } else {
      stillSinceNs = null
    }

    val up = g.unit()
    // Positive rotation about "up" is counter-clockwise seen from above; heading runs clockwise.
    val yawRateCcw = (w - gyroBias).dot(up)
    return relYawDeg - (yawRateCcw * dt) * 180.0 / PI
  }

  /** Yaw of a Z-up orientation quaternion, as a clockwise heading. */
  fun quaternionYawDeg(q: Quat): Double {
    val sinyCosp = 2 * (q.w * q.z + q.x * q.y)
    val cosyCosp = 1 - 2 * (q.y * q.y + q.z * q.z)
    return -atan2(sinyCosp, cosyCosp) * 180.0 / PI
  }

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
    // Drift correction: pull the glasses offset gently toward the walking direction.
    val offset = offsetDeg
    if (offset != null && glassesFresh(wallMs)) {
      val headNow = Geo.normalizeDeg(signed(relYawDeg) + offset)
      val err = Geo.deltaDeg(headNow, courseDeg)
      if (abs(err) < 60) offsetDeg = Geo.normalizeDeg(offset + err * COURSE_GAIN)
    }
  }

  /** Ties glasses yaw to the phone compass. The runner faces the way the phone's top points. */
  fun calibrateToPhone(wallMs: Long): Boolean {
    val az = phoneAzimuthDeg ?: return false
    if (wallMs - phoneAzimuthMs > FRESH_MS || !glassesFresh(wallMs)) return false
    offsetDeg = Geo.normalizeDeg(az - signed(relYawDeg))
    return true
  }

  fun resetAlignment() {
    offsetDeg = null
  }

  fun estimate(wallMs: Long): HeadingEstimate {
    val offset = offsetDeg
    if (offset != null && glassesFresh(wallMs)) {
      return HeadingEstimate(Geo.normalizeDeg(signed(relYawDeg) + offset), HeadingSource.GLASSES, true)
    }
    if (phoneInHand && wallMs - phoneAzimuthMs <= FRESH_MS) {
      return HeadingEstimate(phoneAzimuthDeg, HeadingSource.PHONE, glassesAligned)
    }
    if (wallMs - courseMs <= COURSE_FRESH_MS && speedMps >= WALKING_MPS) {
      return HeadingEstimate(courseDeg, HeadingSource.COURSE, glassesAligned)
    }
    return HeadingEstimate(null, HeadingSource.NONE, glassesAligned)
  }

  private fun absoluteReference(wallMs: Long): Double? =
      when {
        phoneInHand && wallMs - phoneAzimuthMs <= FRESH_MS -> phoneAzimuthDeg
        wallMs - courseMs <= COURSE_FRESH_MS && speedMps >= WALKING_MPS -> courseDeg
        else -> null
      }

  private fun glassesFresh(wallMs: Long) = lastImuWallMs?.let { wallMs - it <= FRESH_MS } ?: false

  private fun signed(yaw: Double) = if (invertYaw) -yaw else yaw

  companion object {
    const val FRESH_MS = 1_500L
    const val COURSE_FRESH_MS = 4_000L
    const val WALKING_MPS = 0.8
    const val COURSE_GAIN = 0.04
  }
}
