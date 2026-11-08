package com.smartphonex.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Sits between the GPS and navigation, so navigation never acts on a position it shouldn't trust.
 *
 * - Smooths: a small Kalman filter in local metres, so jitter doesn't steer the vehicle. While the vehicle isn't being
 *   driven, it's held still, which stops a parked vehicle "wandering" with GPS drift.
 * - Rejects jumps: a fix too far from where the vehicle could possibly be is thrown away. If the GPS keeps insisting,
 *   the guard accepts the new place but treats the position as unproven until it has settled again.
 * - Knows its own uncertainty: [Estimate.radiusM] is a 68% radius, like Android's own accuracy figure, but it never
 *   claims to be better than about 60% of what the GPS reports, because GPS errors drift slowly rather than averaging out.
 * - Says how far it can be trusted: [Health]. Navigation only moves on GOOD.
 */
class GpsGuard(
    /** Worst 68% radius still usable for navigation, in metres. */
    private val accLimit: () -> Double,
    /** Seconds without an accepted fix before the position counts as lost. */
    private val timeoutS: () -> Double,
    /** The fastest this vehicle can go, in m/s: sets how far it could have moved between fixes. */
    private val maxSpeed: () -> Double,
) {
    enum class Health { NONE, SETTLING, GOOD, DEGRADED, GLITCH, LOST }

    data class Estimate(
        val lat: Double, val lon: Double, val altM: Double, val radiusM: Double, val speedMps: Double,
        val health: Health, val satellites: Int, val atMs: Long,
    )

    /** One axis of a constant-velocity Kalman filter: position, velocity, and their covariance. */
    private class Axis(var p: Double, var v: Double, var a: Double, var b: Double, var c: Double) {
        fun predict(dt: Double, q: Double) {
            if (dt <= 0) return
            p += v * dt
            a += 2 * b * dt + c * dt * dt + q * dt * dt * dt * dt / 4
            b += c * dt + q * dt * dt * dt / 2
            c += q * dt * dt
        }
        fun innovation(z: Double, r: Double) = (z - p) to (a + r)
        fun update(z: Double, r: Double) {
            val s = a + r; val k1 = a / s; val k2 = b / s; val y = z - p
            p += k1 * y; v += k2 * y
            val a0 = a; val b0 = b
            a = (1 - k1) * a0; b = (1 - k1) * b0; c -= k2 * b0
        }
        /** "It isn't moving": a velocity reading of zero. */
        fun still(r: Double) {
            val s = c + r; val k1 = b / s; val k2 = c / s; val y = -v
            p += k1 * y; v += k2 * y
            val b0 = b; val c0 = c
            a -= k1 * b0; b -= k1 * c0; c -= k2 * c0
        }
    }

    private var lat0 = 0.0
    private var lon0 = 0.0
    private var kLon = M_PER_DEG
    private var e: Axis? = null
    private var n: Axis? = null
    private var lastFixMs = 0L
    private var lastAcceptedMs = 0L
    private var lastAcc = 0.0
    private var lastAlt = 0.0
    private var lastSats = 0
    private var settled = 0
    private var rejectsInRow = 0
    private var firstRejectMs = 0L
    private var glitchUntil = 0L
    var rejected = 0L; private set
    var resets = 0L; private set

    sealed class Verdict {
        data object Accepted : Verdict()
        data class Rejected(val reason: String) : Verdict()
        data class Reset(val reason: String) : Verdict()
    }

    @Synchronized fun reset() {
        e = null; n = null; settled = 0; rejectsInRow = 0; glitchUntil = 0; lastAcceptedMs = 0; lastFixMs = 0
    }

    private fun toLocal(lat: Double, lon: Double) = (lon - lon0) * kLon to (lat - lat0) * M_PER_DEG

    /** Takes one fix. [moving] says whether the vehicle is being driven, which decides how much it may move. */
    @Synchronized fun feed(fix: GpsFix, moving: Boolean): Verdict {
        if (!fix.accuracyM.isFinite() || fix.accuracyM <= 0 || fix.accuracyM > MAX_USABLE_ACC) {
            rejected++
            return Verdict.Rejected("accuracy of ${fix.accuracyM.toInt()} m is no use")
        }
        val sigma = fix.accuracyM / RADIUS_PER_SIGMA
        val r = sigma * sigma
        val ex = e; val nx = n
        if (ex == null || nx == null) {
            start(fix, r)
            return Verdict.Accepted
        }
        val dt = ((fix.atMs - lastFixMs) / 1000.0).coerceIn(0.0, 30.0)
        val vMax = max(maxSpeed(), 0.5)
        // A driven vehicle may speed up or turn hard. A parked one barely moves at all.
        val q = if (moving) (vMax * vMax) else STILL_ACCEL * STILL_ACCEL
        ex.predict(dt, q); nx.predict(dt, q)
        val (ze, zn) = toLocal(fix.lat, fix.lon)
        val (ye, se) = ex.innovation(ze, r)
        val (yn, sn) = nx.innovation(zn, r)
        val d2 = ye * ye / se + yn * yn / sn
        val jump = sqrt(ye * ye + yn * yn)
        // Too far to be real: both statistically, and further than the vehicle could have gone, with the accuracy allowed for.
        val reach = vMax * max(dt, 1.0) * 1.5 + 3 * sqrt(max(ex.a, nx.a)) + 3 * sigma
        lastFixMs = fix.atMs
        if (d2 > GATE && jump > reach) {
            rejected++
            if (rejectsInRow == 0) firstRejectMs = fix.atMs
            rejectsInRow++
            // The GPS keeps saying the same thing: believe it, but don't navigate on it until it has proved itself.
            if (rejectsInRow >= RESET_AFTER_FIXES || fix.atMs - firstRejectMs >= RESET_AFTER_MS) {
                start(fix, r)
                glitchUntil = fix.atMs + GLITCH_HOLD_MS
                resets++
                return Verdict.Reset("position jumped ${jump.toInt()} m and stayed there")
            }
            return Verdict.Rejected("jumped ${jump.toInt()} m, more than it could have moved")
        }
        rejectsInRow = 0
        ex.update(ze, r); nx.update(zn, r)
        if (!moving) { ex.still(STILL_VEL_VAR); nx.still(STILL_VEL_VAR) }
        accept(fix)
        return Verdict.Accepted
    }

    private fun start(fix: GpsFix, r: Double) {
        lat0 = fix.lat; lon0 = fix.lon
        kLon = M_PER_DEG * cos(Math.toRadians(fix.lat)).coerceAtLeast(0.01)
        val vMax = max(maxSpeed(), 0.5)
        e = Axis(0.0, 0.0, r, 0.0, vMax * vMax)
        n = Axis(0.0, 0.0, r, 0.0, vMax * vMax)
        settled = 0
        rejectsInRow = 0
        lastFixMs = fix.atMs
        accept(fix)
    }

    private fun accept(fix: GpsFix) {
        lastAcceptedMs = fix.atMs
        lastAcc = fix.accuracyM
        lastAlt = fix.altM
        lastSats = fix.satellites
        settled++
    }

    /** Where the vehicle is now, how sure, and whether to trust it. Null before the first fix. */
    @Synchronized fun estimate(nowMs: Long): Estimate? {
        val ex = e ?: return null
        val nx = n ?: return null
        val since = ((nowMs - lastAcceptedMs) / 1000.0).coerceAtLeast(0.0)
        // Carry the position forward to now, and widen the uncertainty with the time since the last fix.
        val pe = ex.p + ex.v * since
        val pn = nx.p + nx.v * since
        val varNow = max(ex.a + ex.c * since * since, nx.a + nx.c * since * since)
        val radius = max(RADIUS_PER_SIGMA * sqrt(varNow), lastAcc * FLOOR_OF_REPORTED)
        val health = when {
            since > timeoutS() -> Health.LOST
            nowMs < glitchUntil || rejectsInRow > 0 -> Health.GLITCH
            settled < SETTLE_FIXES -> Health.SETTLING
            radius > accLimit() -> Health.DEGRADED
            else -> Health.GOOD
        }
        return Estimate(lat0 + pn / M_PER_DEG, lon0 + pe / kLon, lastAlt, radius, sqrt(ex.v * ex.v + nx.v * nx.v), health, lastSats, lastAcceptedMs)
    }

    /** Plain words for people. */
    fun describe(est: Estimate?): String = when (est?.health) {
        null, Health.NONE -> "No position yet"
        Health.LOST -> "Position lost"
        Health.GLITCH -> "Position jumped: checking it"
        Health.SETTLING -> "Settling, ±${est.radiusM.toInt()} m"
        Health.DEGRADED -> "Too rough to navigate, ±${est.radiusM.toInt()} m"
        Health.GOOD -> "Good, ±${est.radiusM.toInt()} m"
    }

    companion object {
        private const val M_PER_DEG = 111_320.0
        /** Android's accuracy is a 68% radius. For a 2-D normal error that's about 1.5 standard deviations per axis. */
        const val RADIUS_PER_SIGMA = 1.515
        /** Chi-square, 2 degrees of freedom, 99.9%. */
        private const val GATE = 13.8
        private const val MAX_USABLE_ACC = 500.0
        private const val STILL_ACCEL = 0.05
        private const val STILL_VEL_VAR = 0.01
        private const val FLOOR_OF_REPORTED = 0.6
        const val SETTLE_FIXES = 5
        private const val RESET_AFTER_FIXES = 5
        private const val RESET_AFTER_MS = 5000L
        const val GLITCH_HOLD_MS = 10_000L
    }
}
