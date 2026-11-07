package com.smartphonex.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Vehicle attitude, in degrees. Roll right, pitch up and yaw right (compass heading) are positive. */
data class Attitude(val roll: Double, val pitch: Double, val yaw: Double)

/** Replaceable per phone: a better filter only has to implement this. */
interface AttitudeEstimator {
    val converged: Boolean
    fun onGyro(x: Double, y: Double, z: Double, dt: Double)
    fun onAccel(x: Double, y: Double, z: Double)
    fun onMag(x: Double, y: Double, z: Double)
    fun attitude(): Attitude
    fun reset()
}

/** Rotations between the phone and the vehicle. Vehicle axes: x forward, y right, z down. */
object Frames {
    /** Row-major matrices taking a phone vector to vehicle axes, one per MOUNT_TYPE. */
    private val MOUNTS = arrayOf(
        doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, -1.0),   // Flat, screen up, top forward.
        doubleArrayOf(0.0, 0.0, -1.0, 1.0, 0.0, 0.0, 0.0, -1.0, 0.0),  // Upright, rear camera forward.
        doubleArrayOf(0.0, 0.0, -1.0, 0.0, -1.0, 0.0, -1.0, 0.0, 0.0), // Sideways, rear camera forward.
        doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0),   // Flat, screen down, top forward.
    )

    fun mount(type: Int, yawDeg: Double): DoubleArray {
        val m = MOUNTS[type.coerceIn(0, MOUNTS.size - 1)]
        if (yawDeg == 0.0) return m
        val c = cos(Math.toRadians(yawDeg))
        val s = sin(Math.toRadians(yawDeg))
        val rz = doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
        return mul(rz, m)
    }

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) for (k in 0..2) r[i * 3 + j] += a[i * 3 + k] * b[k * 3 + j]
        return r
    }

    fun apply(m: DoubleArray, x: Double, y: Double, z: Double) = doubleArrayOf(
        m[0] * x + m[1] * y + m[2] * z,
        m[3] * x + m[4] * y + m[5] * z,
        m[6] * x + m[7] * y + m[8] * z,
    )

    fun transpose(m: DoubleArray) = doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

    /** Vehicle-to-north-east-down matrix to roll, pitch and heading. */
    fun euler(r: DoubleArray): Attitude {
        val pitch = asin((-r[6]).coerceIn(-1.0, 1.0))
        return Attitude(
            Math.toDegrees(atan2(r[7], r[8])),
            Math.toDegrees(pitch),
            norm360(Math.toDegrees(atan2(r[3], r[0]))),
        )
    }

    fun norm360(d: Double): Double { val x = d % 360.0; return if (x < 0) x + 360.0 else x }
    /** Shortest signed angle, -180 to 180. */
    fun wrap180(d: Double): Double { val x = norm360(d); return if (x > 180.0) x - 360.0 else x }

    /**
     * Android's rotation vector, as an independent attitude reference. It describes the phone
     * relative to east-north-up, so convert that to vehicle-to-north-east-down.
     */
    fun fromRotationVector(x: Double, y: Double, z: Double, w: Double, mount: DoubleArray): Attitude {
        val q = sqrt(max(0.0, 1 - x * x - y * y - z * z))
        val qw = if (w.isNaN()) q else w
        val deviceToEnu = doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - qw * z), 2 * (x * z + qw * y),
            2 * (x * y + qw * z), 1 - 2 * (x * x + z * z), 2 * (y * z - qw * x),
            2 * (x * z - qw * y), 2 * (y * z + qw * x), 1 - 2 * (x * x + y * y),
        )
        val enuToNed = doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, -1.0)
        return euler(mul(mul(enuToNed, deviceToEnu), transpose(mount)))
    }
}

/**
 * Complementary filter: the gyro tracks fast motion, gravity corrects roll and pitch slowly, and
 * the compass corrects heading slowly. Gravity is trusted less while the phone is being shaken.
 */
class ComplementaryEstimator(private val gains: () -> Gains) : AttitudeEstimator {
    data class Gains(val kp: Double, val ki: Double, val mag: Double, val declinationDeg: Double)

    private var q0 = 1.0
    private var q1 = 0.0
    private var q2 = 0.0
    private var q3 = 0.0
    private var ix = 0.0
    private var iy = 0.0
    private var iz = 0.0
    private var accel: DoubleArray? = null
    private var mag: DoubleArray? = null
    private var initialized = false
    private var settle = 0.0

    override val converged: Boolean get() = initialized && settle >= SETTLE_SECONDS

    override fun reset() {
        q0 = 1.0; q1 = 0.0; q2 = 0.0; q3 = 0.0
        ix = 0.0; iy = 0.0; iz = 0.0
        initialized = false
        settle = 0.0
    }

    override fun onAccel(x: Double, y: Double, z: Double) { accel = doubleArrayOf(x, y, z) }
    override fun onMag(x: Double, y: Double, z: Double) { mag = doubleArrayOf(x, y, z) }

    private fun initialize() {
        val a = accel ?: return
        val n = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        if (n < 1.0) return
        // A resting accelerometer reads "up", which is -z in vehicle axes.
        val roll = atan2(-a[1], -a[2])
        val pitch = atan2(a[0], sqrt(a[1] * a[1] + a[2] * a[2]))
        var yaw = 0.0
        mag?.let { m -> yaw = Math.toRadians(headingFromMag(m, roll, pitch)) }
        val cr = cos(roll / 2); val sr = sin(roll / 2)
        val cp = cos(pitch / 2); val sp = sin(pitch / 2)
        val cy = cos(yaw / 2); val sy = sin(yaw / 2)
        q0 = cr * cp * cy + sr * sp * sy
        q1 = sr * cp * cy - cr * sp * sy
        q2 = cr * sp * cy + sr * cp * sy
        q3 = cr * cp * sy - sr * sp * cy
        initialized = true
    }

    private fun headingFromMag(m: DoubleArray, roll: Double, pitch: Double): Double {
        val cr = cos(roll); val sr = sin(roll)
        val cp = cos(pitch); val sp = sin(pitch)
        val xh = m[0] * cp + m[1] * sr * sp + m[2] * cr * sp
        val yh = m[1] * cr - m[2] * sr
        return Math.toDegrees(atan2(-yh, xh)) + gains().declinationDeg
    }

    override fun onGyro(x: Double, y: Double, z: Double, dt: Double) {
        if (!initialized) { initialize(); if (!initialized) return }
        val g = gains()
        var ex = 0.0
        var ey = 0.0
        var ez = 0.0

        val a = accel
        if (a != null) {
            val n = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
            // Only trust gravity while total acceleration is close to 1 g.
            val trust = (1.0 - abs(n - GRAVITY) / (GRAVITY * 0.3)).coerceIn(0.0, 1.0)
            if (n > 1.0 && trust > 0.0) {
                val ax = -a[0] / n; val ay = -a[1] / n; val az = -a[2] / n // Measured "down".
                val vx = 2 * (q1 * q3 - q0 * q2)
                val vy = 2 * (q0 * q1 + q2 * q3)
                val vz = q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3 // Predicted "down".
                ex += (ay * vz - az * vy) * trust
                ey += (az * vx - ax * vz) * trust
                ez += (ax * vy - ay * vx) * trust
            }
        }

        var cx = x + g.kp * ex + ix
        var cy = y + g.kp * ey + iy
        var cz = z + g.kp * ez + iz
        if (g.ki > 0) {
            ix = (ix + g.ki * ex * dt).coerceIn(-I_LIMIT, I_LIMIT)
            iy = (iy + g.ki * ey * dt).coerceIn(-I_LIMIT, I_LIMIT)
            iz = (iz + g.ki * ez * dt).coerceIn(-I_LIMIT, I_LIMIT)
        }

        val m = mag
        if (m != null && g.mag > 0) {
            val e = euler()
            val h = headingFromMag(m, Math.toRadians(e.roll), Math.toRadians(e.pitch))
            val err = Math.toRadians(Frames.wrap180(h - e.yaw))
            // Heading error applied about the world's vertical, expressed in vehicle axes.
            val dx = 2 * (q1 * q3 - q0 * q2)
            val dy = 2 * (q0 * q1 + q2 * q3)
            val dz = q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3
            cx += g.mag * err * dx
            cy += g.mag * err * dy
            cz += g.mag * err * dz
        }

        val d0 = 0.5 * (-q1 * cx - q2 * cy - q3 * cz)
        val d1 = 0.5 * (q0 * cx + q2 * cz - q3 * cy)
        val d2 = 0.5 * (q0 * cy - q1 * cz + q3 * cx)
        val d3 = 0.5 * (q0 * cz + q1 * cy - q2 * cx)
        q0 += d0 * dt; q1 += d1 * dt; q2 += d2 * dt; q3 += d3 * dt
        val qn = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        q0 /= qn; q1 /= qn; q2 /= qn; q3 /= qn
        settle = min(SETTLE_SECONDS, settle + dt)
    }

    private fun euler() = Frames.euler(doubleArrayOf(
        1 - 2 * (q2 * q2 + q3 * q3), 2 * (q1 * q2 - q0 * q3), 2 * (q1 * q3 + q0 * q2),
        2 * (q1 * q2 + q0 * q3), 1 - 2 * (q1 * q1 + q3 * q3), 2 * (q2 * q3 - q0 * q1),
        2 * (q1 * q3 - q0 * q2), 2 * (q2 * q3 + q0 * q1), 1 - 2 * (q1 * q1 + q2 * q2),
    ))

    override fun attitude(): Attitude = euler()

    private companion object {
        const val GRAVITY = 9.80665
        const val I_LIMIT = 0.1
        const val SETTLE_SECONDS = 2.0
    }
}
