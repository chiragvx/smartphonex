package com.smartphonex.core

import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class FlightMode { MANUAL, STABILIZE, HOLD_HEADING, RETURN_HOME, AUTO, HOLD }

data class GpsFix(
    val lat: Double, val lon: Double, val altM: Double, val speedMps: Double, val courseDeg: Double,
    val accuracyM: Double, val satellites: Int, val atMs: Long,
)

/** Rate PID with a limited I-term and derivative on the measured rate. */
class RatePid {
    var integral = 0.0; private set
    private var lastMeasured: Double? = null
    var lastP = 0.0; private set
    var lastD = 0.0; private set
    fun reset() { integral = 0.0; lastMeasured = null; lastP = 0.0; lastD = 0.0 }
    fun update(target: Double, measured: Double, dt: Double, kp: Double, ki: Double, kd: Double, iMax: Double): Double {
        val error = target - measured
        lastP = kp * error
        lastD = lastMeasured?.let { if (dt > 0) -kd * (measured - it) / dt else 0.0 } ?: 0.0
        lastMeasured = measured
        integral = (integral + ki * error * dt).coerceIn(-iMax, iMax)
        return (lastP + integral + lastD).coerceIn(-1.0, 1.0)
    }
}

object Navigation {
    private const val EARTH_M = 6_371_000.0
    fun distanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_M * atan2(sqrt(h), sqrt(1 - h))
    }
    fun bearingDeg(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val p1 = Math.toRadians(aLat)
        val p2 = Math.toRadians(bLat)
        val dLon = Math.toRadians(bLon - aLon)
        return Frames.norm360(Math.toDegrees(atan2(sin(dLon) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon))))
    }
}

/**
 * The autopilot. It runs from app start whether or not a vehicle is attached. Each input is a
 * pilot request, and each output is a request to the mixer. The vehicle controller stays in charge
 * of arming, authority and latched failsafes.
 *
 * Axis convention of inputs and outputs: throttle forward, steering left, pitch up, roll left.
 */
class FlightController(
    val params: ParamStore,
    private val log: EventLog,
    private val vehicleType: () -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Must match the timebase of sensor timestamps. */
    var nowNs: () -> Long = System::nanoTime,
    estimatorFactory: ((() -> ComplementaryEstimator.Gains) -> AttitudeEstimator)? = null,
) {
    private val lock = Any()
    private var declinationDeg = 0.0
    private val estimator: AttitudeEstimator = estimatorFactory?.invoke(::gains) ?: ComplementaryEstimator(::gains)
    private fun p(name: String) = params.get(name, vehicleType())
    private fun gains() = ComplementaryEstimator.Gains(p("AHRS_KP"), p("AHRS_KI"), p("AHRS_MAG"), declinationDeg)

    private var mount = Frames.mount(0, 0.0)
    private var mountKey = 0.0 to 0.0
    private val rollPid = RatePid()
    private val pitchPid = RatePid()
    private val yawPid = RatePid()

    @Volatile var mode = FlightMode.MANUAL
        private set

    private var gyroBody = doubleArrayOf(0.0, 0.0, 0.0)
    private var attitude = Attitude(0.0, 0.0, 0.0)
    private var reference: Attitude? = null
    private var lastGyroNs = 0L
    private var lastGyroWallMs = 0L
    private var lastAccelWallMs = 0L
    private var lastMagWallMs = 0L
    private var lastRefWallMs = 0L
    private var lastBaroWallMs = 0L
    private var pressureHpa: Double? = null
    private var vibration = 0.0
    private var gps: GpsFix? = null
    private var home: GpsFix? = null
    /** The vehicle's top speed, for telling a real move from a GPS jump. Set by the runtime from the active profile. */
    @Volatile var maxSpeedMps: () -> Double = { 5.0 }
    /** Every fix passes through this before navigation sees it. */
    val gpsGuard = GpsGuard({ p("ARM_GPS_ACC") }, { p("GPS_TIMEOUT_S") }, { maxSpeedMps() })
    private var lastRejectLogMs = 0L
    private var navWaitSince = 0L
    private var lastNavHealth: GpsGuard.Health? = null
    private var arrivedHome = false
    private var batteryPct: Double? = null
    private var batteryKnown = false
    private var accelNorm: Double? = null
    private var magNorm: Double? = null
    private var thermal = 0
    private var thermalWarned = false
    private var calibratingUntil = 0L
    private val calSum = doubleArrayOf(0.0, 0.0, 0.0)
    private var calCount = 0

    private val gyroRate = RateMeter()
    private val accelRate = RateMeter()
    private val magRate = RateMeter()
    private val gpsRate = RateMeter()
    private val loopRate = RateMeter()
    private var delaySumMs = 0.0
    private var delayMaxMs = 0.0
    private var delayCount = 0
    private var delayAvgShown = 0.0
    private var delayMaxShown = 0.0
    private var taskMaxUs = 0.0
    private var taskMaxShown = 0.0
    private var dtMaxMs = 0.0
    private var dtMaxShown = 0.0
    private var windowStartMs = 0L
    private var maxStallMs = 0.0
    private var overruns = 0L
    private var lastTickWallMs = 0L

    private var targetRoll: Double? = null
    private var targetPitch: Double? = null
    private var targetYawRate = 0.0
    private var targetRollRate = 0.0
    private var targetPitchRate = 0.0
    private var targetHeading: Double? = null
    private var lastOutput = ActuatorOutput(0.0, 0.0)
    private var failsafeRequest: String? = null
    private var enabled = true
    private var lastArmed = false

    class RateMeter {
        private var count = 0
        var hz = 0.0; private set
        fun tick() { count++ }
        fun roll(elapsedMs: Long) { hz = count * 1000.0 / elapsedMs; count = 0 }
    }

    // --- Sensor inputs: called from the sensor thread at whatever rate this phone manages.

    fun onGyro(tNs: Long, x: Float, y: Float, z: Float) = synchronized(lock) {
        val now = nowMs()
        val dt = if (lastGyroNs == 0L) 0.0 else ((tNs - lastGyroNs) / 1e9).coerceIn(0.0, 0.1)
        lastGyroNs = tNs
        lastGyroWallMs = now
        gyroRate.tick()
        refreshMount()
        val bx = x - p("GYRO_BX")
        val by = y - p("GYRO_BY")
        val bz = z - p("GYRO_BZ")
        if (now < calibratingUntil) { calSum[0] += x.toDouble(); calSum[1] += y.toDouble(); calSum[2] += z.toDouble(); calCount++ }
        else if (calCount > 0) finishGyroCal()
        val b = Frames.apply(mount, bx, by, bz)
        gyroBody = b
        if (dt > 0) estimator.onGyro(b[0], b[1], b[2], dt)
        attitude = estimator.attitude()
    }

    fun onAccel(x: Float, y: Float, z: Float) = synchronized(lock) {
        lastAccelWallMs = nowMs()
        accelRate.tick()
        val n = sqrt((x * x + y * y + z * z).toDouble())
        accelNorm = n
        vibration += (abs(n - 9.80665) - vibration) * 0.02
        val b = Frames.apply(mount, x.toDouble(), y.toDouble(), z.toDouble())
        estimator.onAccel(b[0], b[1], b[2])
    }

    fun onMag(x: Float, y: Float, z: Float) = synchronized(lock) {
        lastMagWallMs = nowMs()
        magRate.tick()
        magNorm = sqrt((x * x + y * y + z * z).toDouble())
        val b = Frames.apply(mount, x.toDouble(), y.toDouble(), z.toDouble())
        estimator.onMag(b[0], b[1], b[2])
    }

    fun onRotationVector(x: Float, y: Float, z: Float, w: Float) = synchronized(lock) {
        lastRefWallMs = nowMs()
        reference = Frames.fromRotationVector(x.toDouble(), y.toDouble(), z.toDouble(), w.toDouble(), mount)
    }

    fun onBaro(hPa: Float) = synchronized(lock) { lastBaroWallMs = nowMs(); pressureHpa = hPa.toDouble() }

    /**
     * A new position. [fromSatellites] is false for Android's network position (Wi-Fi and cell towers): it's shown,
     * but never steers anything, because it can be hundreds of metres out while claiming far better.
     */
    fun onGps(fix: GpsFix, declination: Double?, fromSatellites: Boolean = true) = synchronized(lock) {
        gps = fix
        gpsRate.tick()
        if (declination != null) declinationDeg = declination
        if (!fromSatellites) return@synchronized
        // Only an armed vehicle that isn't being driven is surely still. Disarmed, it may be carried, as in a walk test.
        val moving = !lastArmed || kotlin.math.abs(lastOutput.throttle) > 0.05
        when (val v = gpsGuard.feed(fix, moving)) {
            is GpsGuard.Verdict.Reset -> log.event("gps", "phone", "jump", "${v.reason}: navigation waits until it settles")
            is GpsGuard.Verdict.Rejected -> if (fix.atMs - lastRejectLogMs > 5000) { lastRejectLogMs = fix.atMs; log.event("gps", "phone", "rejected", "GPS fix ignored: ${v.reason}") }
            else -> {}
        }
    }

    /** The guarded position: smoothed, with jumps removed, and how far to trust it. */
    fun navEstimate(): GpsGuard.Estimate? = synchronized(lock) { gpsGuard.estimate(nowMs()) }

    /** The guarded position as a fix, only when it's good enough to navigate on. Its accuracy is the guard's radius. */
    private fun navFix(): GpsFix? {
        val e = gpsGuard.estimate(nowMs())?.takeIf { it.health == GpsGuard.Health.GOOD } ?: return null
        return GpsFix(e.lat, e.lon, e.altM, e.speedMps, gps?.courseDeg ?: 0.0, e.radiusM, e.satellites, e.atMs)
    }

    // --- Experimental: the propeller heard through the microphone (PropSensor.kt). Never sets throttle.

    private val propTracker = ToneTracker()
    private val propAir = PropAirspeed().also { it.restore(savedPropFit()) }
    private var propTone: PropTone.Tone? = null
    private var propAtMs = 0L
    private var armedAtMs = 0L
    private var lastPropKq = p("PROP_KQ")
    /**
     * Tones heard in the minute before arming, with every output at rest: not the propeller (mains hum, fans,
     * machinery), so skipped once armed. A motor spun on the Test page must never land here, or the real propeller
     * would be skipped in flight at that speed.
     */
    private class Heard(val hz: Double, var atMs: Long)
    private val propBackground = ArrayDeque<Heard>()
    /** When every output last came to rest, or 0 while one is being driven. */
    private var propQuietSinceMs = 0L

    /** Background tones for the analyzer to skip, only while armed: disarmed, everything heard is reported. */
    fun propExclusions(): List<Double> = synchronized(lock) { if (lastArmed) propBackground.map { it.hz } else emptyList() }
    /** Why the microphone isn't listening, set by the app, or null when it is. */
    @Volatile var propUnavailable: String? = null

    /** A tone from the microphone, or null when nothing stood out. Called about ten times a second. */
    fun onPropTone(tone: PropTone.Tone?, outputsAtRest: Boolean = true) = synchronized(lock) {
        val now = nowMs()
        val hz = propTracker.update(tone, now)
        if (tone != null) { propTone = tone; propAtMs = now }
        if (!outputsAtRest) propQuietSinceMs = 0L else if (propQuietSinceMs == 0L) propQuietSinceMs = now
        if (!lastArmed) {
            propBackground.removeAll { now - it.atMs > PROP_BACKGROUND_MS }
            // A motor takes a moment to spin down after a test, so wait before trusting the quiet.
            if (tone != null && propQuietSinceMs > 0 && now - propQuietSinceMs >= PROP_SPINDOWN_MS) {
                val same = propBackground.firstOrNull { abs(it.hz - tone.hz) <= it.hz * 0.03 }
                if (same != null) same.atMs = now else {
                    propBackground.addLast(Heard(tone.hz, now))
                    while (propBackground.size > 6) propBackground.removeFirst()
                }
            }
        }
        // Someone set PROP_KQ to 0 to forget: drop the old samples too, or the next fit would learn them again.
        val kq = p("PROP_KQ")
        if (kq == 0.0 && lastPropKq > 0.0) { propAir.clear(); log.event("prop", "phone", "forgot", "propeller airspeed forgotten") }
        lastPropKq = kq
        if (hz == null || p("MIC_PROP") < 2 || !lastArmed) return@synchronized
        // Learn only from a fresh, trusted satellite fix, so GPS jumps don't teach it wrong.
        val g = gps ?: return@synchronized
        if (now - g.atMs > 1500 || gpsGuard.estimate(now)?.health != GpsGuard.Health.GOOD) return@synchronized
        propAir.add(PropAirspeed.Sample(hz / p("PROP_BLADES"), lastOutput.throttle, minutesArmed(now), g.speedMps, g.courseDeg))
        if (propAir.count % 50 == 0) propAir.refit()?.let { f ->
            if (f.rmsMps <= p("PROP_FIT_RMS")) {
                val first = p("PROP_KQ") == 0.0
                listOf("PROP_K0", "PROP_K1", "PROP_K2", "PROP_K3").forEachIndexed { i, n -> params.set(n, f.k[i]) }
                params.set("PROP_NMIN", f.nMin); params.set("PROP_NMAX", f.nMax); params.set("PROP_KQ", maxOf(f.rmsMps, 0.01))
                if (first) log.event("prop", "phone", "learned", "propeller airspeed learned from ${f.samples} samples, ±${"%.1f".format(f.rmsMps)} m/s; wind ${"%.0f".format(f.windMps)} m/s from ${f.windFromDeg.toInt()}°")
            }
        }
    }


    private fun savedPropFit(): PropAirspeed.Fit? {
        val q = p("PROP_KQ")
        if (q <= 0) return null
        return PropAirspeed.Fit(doubleArrayOf(p("PROP_K0"), p("PROP_K1"), p("PROP_K2"), p("PROP_K3")), q, 0.0, 0.0, p("PROP_NMIN"), p("PROP_NMAX"), 0)
    }

    private fun minutesArmed(now: Long) = if (lastArmed && armedAtMs > 0) (now - armedAtMs) / 60_000.0 else 0.0

    private fun propHz(now: Long) = propTracker.hz?.takeIf { p("MIC_PROP") >= 1 && now - propAtMs < 1000 }

    /** Airspeed from the propeller, when it's been learned and the motor is in the range it learned. */
    private fun propAirspeed(now: Long): Double? {
        if (p("MIC_PROP") < 2 || p("PROP_KQ") <= 0) return null
        if (propAir.fit == null || propAir.fit?.samples == 0) propAir.restore(savedPropFit())
        val hz = propHz(now) ?: return null
        return propAir.airspeed(hz / p("PROP_BLADES"), lastOutput.throttle, minutesArmed(now))
    }

    @Volatile var missions: MissionEngine? = null
    /** Strength of the four best satellites, in dB-Hz. Null when the device doesn't report it. */
    @Volatile var gnssSignal: Double? = null
    fun gpsText(): String? = synchronized(lock) {
        val g = gps ?: return null
        "±${g.accuracyM.toInt()} m, ${g.satellites} satellites" + (gnssSignal?.let { ", signal ${it.toInt()} dB-Hz" } ?: "") +
            ". For navigation: " + gpsGuard.describe(gpsGuard.estimate(nowMs())).replaceFirstChar { it.lowercase() }
    }
    /** Asks the controller for a latched failsafe at its next step. */
    fun requestFailsafe(why: String) = synchronized(lock) { failsafeRequest = why }
    /** A safety system changing mode: not subject to the usual checks. */
    fun force(m: FlightMode, why: String) = synchronized(lock) { enterMode(m, why) }
    fun setModeForce(m: FlightMode) = synchronized(lock) { enterMode(m, "requested") }
    val homeNow: GpsFix? get() = synchronized(lock) { home }
    val batteryNow: Double? get() = synchronized(lock) { batteryPct }
    val accelMagnitude: Double? get() = synchronized(lock) { accelNorm }
    val magStrength: Double? get() = synchronized(lock) { magNorm }
    val overrunCount: Long get() = synchronized(lock) { overruns }
    val worstStepMs: Double get() = synchronized(lock) { dtMaxShown }
    fun batteryText(): String = synchronized(lock) { batteryPct?.let { "${it.toInt()}% battery" } ?: if (batteryKnown) "No battery: mains power" else "Unknown" }

    /** Health of each part: ok, bad, or none when this device doesn't have it. */
    fun healthMap(): Map<String, String> = synchronized(lock) {
        val now = nowMs()
        fun st(at: Long) = if (at == 0L) "none" else if (now - at < SENSOR_STALE_MS) "ok" else "bad"
        val g = gps
        mapOf(
            "gyro" to st(lastGyroWallMs), "accel" to st(lastAccelWallMs), "compass" to st(lastMagWallMs), "baro" to st(lastBaroWallMs),
            "gps" to when { g == null -> "none"; navFix() != null -> "ok"; else -> "bad" },
            "attitude" to if (estimator.converged) "ok" else "bad",
            "timing" to if (dtMaxShown < STALL_MS) "ok" else "bad",
            "battery" to when (val b = batteryPct) { null -> "none"; else -> if (b >= p("FS_BATT_MIN")) "ok" else "bad" },
            "heat" to if (thermal < p("FS_THERMAL")) "ok" else "bad",
        )
    }

    /** [battery] is null on a device without one, which then never fails the battery check. */
    fun onDeviceState(battery: Double?, thermalStatus: Int) = synchronized(lock) { batteryPct = battery; batteryKnown = true; thermal = thermalStatus }

    private fun refreshMount() {
        val key = p("MOUNT_TYPE") to p("MOUNT_YAW")
        if (key != mountKey) {
            mountKey = key
            mount = Frames.mount(key.first.toInt(), key.second)
            estimator.reset()
            log.event("fc", "phone", "mounting", "mounting changed: attitude restarted")
        }
    }

    // --- Actions from the ground station.

    fun calibrateGyro(): String? = synchronized(lock) {
        calibratingUntil = nowMs() + 2000
        calSum.fill(0.0)
        calCount = 0
        log.event("fc", "phone", "calibrate", "gyro calibration: hold still for 2 s")
        null
    }

    private fun finishGyroCal() {
        val s = calSum
        val n = calCount
        calCount = 0
        if (n < 10) { log.event("fc", "phone", "calibrate", "gyro calibration failed: too few samples"); return }
        val bx = s[0] / n; val by = s[1] / n; val bz = s[2] / n
        if (abs(bx) > 0.2 || abs(by) > 0.2 || abs(bz) > 0.2) {
            log.event("fc", "phone", "calibrate", "gyro calibration failed: the phone was moving")
            return
        }
        params.set("GYRO_BX", bx); params.set("GYRO_BY", by); params.set("GYRO_BZ", bz)
        estimator.reset()
        log.event("fc", "phone", "calibrate", "gyro calibrated from $n samples")
    }

    fun setLevel(): String? = synchronized(lock) {
        if (!estimator.converged) return "attitude isn't ready yet"
        val a = estimator.attitude()
        params.set("TRIM_ROLL", a.roll.coerceIn(-45.0, 45.0))
        params.set("TRIM_PITCH", a.pitch.coerceIn(-45.0, 45.0))
        log.event("fc", "phone", "level", "level trim set")
        null
    }

    fun setHomeHere(): String? = synchronized(lock) {
        val g = goodFix() ?: return gpsProblem()
        home = g
        arrivedHome = false
        log.event("fc", "phone", "home", "home set, accuracy ${g.accuracyM.toInt()} m")
        null
    }

    private fun goodFix(): GpsFix? = navFix()

    private fun gpsProblem(): String {
        gps ?: return "no GPS position: turn on Location under Sensors and go outside"
        val e = gpsGuard.estimate(nowMs()) ?: return "no GPS position yet"
        return when (e.health) {
            GpsGuard.Health.LOST -> "GPS position is out of date"
            GpsGuard.Health.GLITCH -> "the GPS position just jumped. Wait a few seconds for it to settle"
            GpsGuard.Health.SETTLING -> "GPS is still settling. Wait a few seconds"
            GpsGuard.Health.DEGRADED -> "GPS is only good to ±${e.radiusM.toInt()} m, needs ${p("ARM_GPS_ACC").toInt()} m. Go somewhere with open sky"
            else -> "GPS isn't good enough"
        }
    }

    /** Why this mode can't be used now, or null when it can. */
    fun modeProblem(m: FlightMode): String? = synchronized(lock) {
        if (m == FlightMode.MANUAL) return null
        if (nowMs() - lastGyroWallMs > SENSOR_STALE_MS) return "no gyroscope data: this phone can't stabilize"
        if (!estimator.converged) return "attitude is still settling"
        if (gyroRate.hz > 0 && gyroRate.hz < p("ARM_RATE_MIN")) return "gyro is only ${gyroRate.hz.toInt()} Hz, needs ${p("ARM_RATE_MIN").toInt()} Hz"
        if (m == FlightMode.AUTO) goodFix() ?: return gpsProblem()
        if (m == FlightMode.RETURN_HOME) {
            goodFix() ?: return gpsProblem()
            if (home == null) return "no home position: use Set home"
        }
        if (m == FlightMode.AUTO) {
            goodFix() ?: return gpsProblem()
            if (missions?.mission == null) return "no mission loaded"
        }
        null
    }

    /**
     * Why a plane looks airborne, or null. Disarming then is refused: it would cut the motor and drop the controls.
     * STOP still works, as the deliberate kill switch. Ground vehicles never count as flying. Without a fresh fix it
     * can't tell, so it doesn't refuse.
     */
    fun flyingReason(): String? = synchronized(lock) {
        if (!isPlane()) return null
        // Airspeed doesn't drop to zero in a headwind the way ground speed can.
        propAirspeed(nowMs())?.let { if (it > p("DISARM_AIR_SPD")) return "airspeed about ${"%.0f".format(it)} m/s, from the propeller" }
        val g = gps ?: return null
        if (nowMs() - g.atMs > p("GPS_TIMEOUT_S") * 1000) return null
        if (g.speedMps > p("DISARM_AIR_SPD")) return "moving at ${"%.0f".format(g.speedMps)} m/s"
        val h = home
        if (h != null && g.altM - h.altM > DISARM_AIR_ALT_M) return "${(g.altM - h.altM).toInt()} m above home"
        null
    }

    fun armProblem(): String? = synchronized(lock) {
        val b = batteryPct
        if (b != null && b < p("FS_BATT_MIN")) return "phone battery is ${b.toInt()}%, needs ${p("FS_BATT_MIN").toInt()}%"
        modeProblem(mode)
    }

    fun setMode(m: FlightMode): String? = synchronized(lock) {
        modeProblem(m)?.let { return it }
        enterMode(m, "requested")
        null
    }

    private fun enterMode(m: FlightMode, why: String) {
        if (m != mode) log.event("flightmode", "phone", m.name, "${mode.name} -> ${m.name}: $why")
        mode = m
        rollPid.reset(); pitchPid.reset(); yawPid.reset()
        targetHeading = null
        arrivedHome = false
    }

    /** What to do when the human link is lost: true means fly home, false means the normal failsafe. */
    fun returnHomeOnLinkLoss(): Boolean = synchronized(lock) {
        if (mode == FlightMode.MANUAL || p("FS_LINK") < 1.0) return false
        if (modeProblem(FlightMode.RETURN_HOME) != null) return false
        enterMode(FlightMode.RETURN_HOME, "link lost")
        true
    }

    fun takeFailsafeRequest(): String? = synchronized(lock) { failsafeRequest.also { failsafeRequest = null } }

    // --- The control step, called from the control loop.

    fun process(input: ActuatorOutput, armed: Boolean, dt: Double): ActuatorOutput = synchronized(lock) {
        enabled = true
        val tStart = System.nanoTime()
        val now = nowMs()
        if (armed && !lastArmed && home == null) goodFix()?.let { home = it; log.event("fc", "phone", "home", "home set at arming") }
        if (armed && !lastArmed) { armedAtMs = now; propBackground.removeAll { now - it.atMs > PROP_BACKGROUND_MS } }
        lastArmed = armed
        monitor(now, armed)
        val out = when (mode) {
            FlightMode.MANUAL -> { clearTargets(); input }
            FlightMode.STABILIZE -> if (isPlane()) planeStabilize(input, dt) else groundRate(input, input.throttle, -input.steering * p("RATE_MAX"), dt)
            FlightMode.HOLD_HEADING -> holdHeading(input, dt)
            FlightMode.RETURN_HOME -> returnHome(input, dt)
            // Navigation only ever moves an armed vehicle.
            FlightMode.AUTO -> if (armed) auto(input, dt) else { clearTargets(); input }
            FlightMode.HOLD -> if (armed) holdHere(input, dt) else { clearTargets(); input }
        }
        lastOutput = out
        meter(now, dt, tStart)
        out
    }

    /** Called instead of process when the simulator drives: the phone's real attitude doesn't apply. */
    fun bypass() = synchronized(lock) { enabled = false; if (mode != FlightMode.MANUAL) enterMode(FlightMode.MANUAL, "simulator selected") }

    private fun isPlane() = vehicleType() == "plane"

    private fun clearTargets() {
        targetRoll = null; targetPitch = null; targetHeading = null
        targetRollRate = 0.0; targetPitchRate = 0.0; targetYawRate = 0.0
    }

    private fun trimmed(): Pair<Double, Double> = (attitude.roll - p("TRIM_ROLL")) to (attitude.pitch - p("TRIM_PITCH"))

    /** Fixed-wing inner loops. Angle targets become rate targets, and rate PIDs drive the surfaces. */
    private fun planeControl(input: ActuatorOutput, rollTarget: Double, pitchTarget: Double, throttle: Double, dt: Double): ActuatorOutput {
        val (roll, pitch) = trimmed()
        val rateMax = p("RATE_MAX")
        targetRoll = rollTarget
        targetPitch = pitchTarget
        targetRollRate = (p("ANG_P") * (rollTarget - roll)).coerceIn(-rateMax, rateMax)
        targetPitchRate = (p("ANG_P") * (pitchTarget - pitch)).coerceIn(-rateMax, rateMax)
        targetYawRate = -input.steering * rateMax
        val rollCmd = rollPid.update(targetRollRate, Math.toDegrees(gyroBody[0]), dt, p("ROLL_P"), p("ROLL_I"), p("ROLL_D"), p("I_MAX"))
        val pitchCmd = pitchPid.update(targetPitchRate, Math.toDegrees(gyroBody[1]), dt, p("PITCH_P"), p("PITCH_I"), p("PITCH_D"), p("I_MAX"))
        val yawCmd = yawPid.update(targetYawRate, Math.toDegrees(gyroBody[2]), dt, p("YAW_P"), p("YAW_I"), p("YAW_D"), p("I_MAX"))
        // Commands are right-positive, while the output axes are left-positive for roll and steering.
        return ActuatorOutput(throttle, -yawCmd, pitchCmd, -rollCmd)
    }

    private fun planeStabilize(input: ActuatorOutput, dt: Double) =
        planeControl(input, -input.roll * p("ANG_MAX_ROLL"), input.pitch * p("ANG_MAX_PITCH"), input.throttle, dt)

    /** Boats and ground vehicles: steering asks for a turn rate, and the gyro holds it. */
    private fun groundRate(input: ActuatorOutput, throttle: Double, yawRateTarget: Double, dt: Double): ActuatorOutput {
        targetRoll = null; targetPitch = null
        targetRollRate = 0.0; targetPitchRate = 0.0
        val rateMax = p("RATE_MAX")
        targetYawRate = yawRateTarget.coerceIn(-rateMax, rateMax)
        val yawCmd = yawPid.update(targetYawRate, Math.toDegrees(gyroBody[2]), dt, p("YAW_P"), p("YAW_I"), p("YAW_D"), p("I_MAX"))
        return ActuatorOutput(throttle, -yawCmd, input.pitch, input.roll)
    }

    private fun steerToHeading(input: ActuatorOutput, heading: Double, throttle: Double, dt: Double): ActuatorOutput {
        targetHeading = heading
        val err = Frames.wrap180(heading - attitude.yaw)
        return if (isPlane()) {
            val bank = (p("HDG_P") * err).coerceIn(-p("ANG_MAX_ROLL"), p("ANG_MAX_ROLL"))
            planeControl(input, bank, input.pitch * p("ANG_MAX_PITCH"), throttle, dt)
        } else {
            groundRate(input, throttle, p("HDG_P") * p("ANG_P") * err, dt)
        }
    }

    private fun holdHeading(input: ActuatorOutput, dt: Double): ActuatorOutput {
        // Pilot steering moves the held heading, as if turning a dial.
        val base = targetHeading ?: attitude.yaw
        val turnLeft = if (isPlane()) input.roll else input.steering
        val heading = Frames.norm360(base - turnLeft * 45.0 * dt)
        return steerToHeading(input, heading, input.throttle, dt)
    }

    /** Stay put: a plane has to keep flying, so it circles. Anything else stops and holds its heading. */
    private fun holdHere(input: ActuatorOutput, dt: Double): ActuatorOutput =
        if (isPlane()) planeControl(input, p("RTH_ORBIT"), 0.0, p("RTH_THROTTLE"), dt)
        else groundRate(input, 0.0, 0.0, dt)

    /** Follows the mission. The engine says where, and this steers there. */
    private fun auto(input: ActuatorOutput, dt: Double): ActuatorOutput {
        val now = nowMs()
        val est = gpsGuard.estimate(now)
        val item = missions?.currentItem()
        // Navigate only on a good position that's tighter than the point it's heading for (with some slack, so
        // accuracy hovering around the radius doesn't make it stop and start). Otherwise stop and wait.
        val usable = est != null && est.health == GpsGuard.Health.GOOD && (item == null || est.radiusM <= item.radiusM * 1.5)
        if (!usable) {
            if (navWaitSince == 0L) {
                navWaitSince = now
                log.event("gps", "phone", "waiting", "Mission waiting: ${gpsGuard.describe(est).replaceFirstChar { it.lowercase() }}" +
                    if (est != null && item != null && est.health == GpsGuard.Health.GOOD) ", point needs ${item.radiusM.toInt()} m" else "")
            }
            if (now - navWaitSince > p("NAV_WAIT_S") * 1000) {
                log.event("failsafe", "phone", "gps", "GPS not good enough for ${p("NAV_WAIT_S").toInt()} s: mission paused for a person to decide")
                missions?.pause()
                enterMode(FlightMode.HOLD, "GPS not good enough")
                navWaitSince = 0L
            }
            return holdHere(input, dt)
        }
        if (navWaitSince != 0L) { log.event("gps", "phone", "resumed", "Position good again: mission continues"); navWaitSince = 0L }
        val g = navFix()
        return when (val s = missions?.step(g)) {
            is MissionEngine.Step.GoTo -> {
                if (g == null) holdHere(input, dt)
                else steerToHeading(input, Navigation.bearingDeg(g.lat, g.lon, s.lat, s.lon), s.throttle ?: p("WP_THROTTLE"), dt)
            }
            is MissionEngine.Step.End -> {
                log.event("mission", "phone", "complete", "mission complete: ${s.action}")
                when (s.action) {
                    "home" -> enterMode(FlightMode.RETURN_HOME, "mission complete")
                    "stop" -> { enterMode(FlightMode.HOLD, "mission complete"); missionStopRequest = true }
                    else -> enterMode(FlightMode.HOLD, "mission complete")
                }
                holdHere(input, dt)
            }
            else -> holdHere(input, dt)
        }
    }

    private var missionStopRequest = false
    fun takeMissionStop(): Boolean = synchronized(lock) { missionStopRequest.also { missionStopRequest = false } }

    private fun returnHome(input: ActuatorOutput, dt: Double): ActuatorOutput {
        val g = navFix()
        val h = home
        // No trustworthy position: stop (a plane circles) rather than drive on a guess.
        if (g == null || h == null) return holdHere(input, dt)
        val dist = Navigation.distanceM(g.lat, g.lon, h.lat, h.lon)
        if (dist < p("RTH_RADIUS")) {
            if (!arrivedHome) { arrivedHome = true; log.event("fc", "phone", "home", "arrived home") }
            if (isPlane()) return planeControl(input, p("RTH_ORBIT"), 0.0, p("RTH_THROTTLE"), dt)
            return groundRate(input, 0.0, 0.0, dt).also { targetHeading = null }
        }
        arrivedHome = false
        return steerToHeading(input, Navigation.bearingDeg(g.lat, g.lon, h.lat, h.lon), p("RTH_THROTTLE"), dt)
    }

    // --- Health and failsafes.

    private fun monitor(now: Long, armed: Boolean) {
        if (mode != FlightMode.MANUAL && now - lastGyroWallMs > SENSOR_STALE_MS) {
            enterMode(FlightMode.MANUAL, "gyroscope stopped")
            if (armed) failsafeRequest = "gyroscope stopped"
        }
        val navHealth = gpsGuard.estimate(now)?.health
        if (navHealth != lastNavHealth) {
            if (lastNavHealth != null && navHealth != null && armed) log.event("gps", "phone", navHealth.name.lowercase(), "Navigation position: ${gpsGuard.describe(gpsGuard.estimate(now)).replaceFirstChar { it.lowercase() }}")
            lastNavHealth = navHealth
        }
        val gpsLost = gps == null || navHealth == null || navHealth == GpsGuard.Health.LOST
        if (mode != FlightMode.AUTO) navWaitSince = 0L
        if (mode == FlightMode.AUTO && gpsLost) {
            log.event("failsafe", "phone", "gps", "GPS lost during the mission: holding")
            missions?.pause()
            enterMode(FlightMode.HOLD, "GPS lost")
        }
        if (mode == FlightMode.RETURN_HOME && gpsLost) {
            // Without a position there's no way home: hold what we can, and the pilot must take over.
            enterMode(if (isPlane()) FlightMode.STABILIZE else FlightMode.MANUAL, "GPS lost")
            if (armed && !isPlane()) failsafeRequest = "GPS lost during return home"
        }
        val hot = thermal >= p("FS_THERMAL")
        if (hot && !thermalWarned) log.event("failsafe", "phone", "thermal", "phone is overheating, status $thermal")
        thermalWarned = hot
    }

    private fun meter(now: Long, dt: Double, tStart: Long) {
        loopRate.tick()
        val taskUs = (System.nanoTime() - tStart) / 1000.0
        if (taskUs > taskMaxUs) taskMaxUs = taskUs
        val dtMs = dt * 1000
        if (dtMs > dtMaxMs) dtMaxMs = dtMs
        if (dtMs > maxStallMs) maxStallMs = dtMs
        if (dtMs > STALL_MS) overruns++
        if (lastGyroNs != 0L) {
            val delay = (nowNs() - lastGyroNs) / 1e6
            if (delay in 0.0..1000.0) {
                delaySumMs += delay
                delayCount++
                if (delay > delayMaxMs) delayMaxMs = delay
            }
        }
        if (windowStartMs == 0L) windowStartMs = now
        val elapsed = now - windowStartMs
        if (elapsed >= 1000) {
            listOf(gyroRate, accelRate, magRate, gpsRate, loopRate).forEach { it.roll(elapsed) }
            delayAvgShown = if (delayCount > 0) delaySumMs / delayCount else 0.0
            delayMaxShown = delayMaxMs
            taskMaxShown = taskMaxUs
            dtMaxShown = dtMaxMs
            delaySumMs = 0.0; delayCount = 0; delayMaxMs = 0.0; taskMaxUs = 0.0; dtMaxMs = 0.0
            windowStartMs = now
        }
    }

    // --- Telemetry.

    private fun r1(v: Double) = Math.round(v * 10) / 10.0
    private fun r2(v: Double) = Math.round(v * 100) / 100.0
    private fun r3(v: Double) = Math.round(v * 1000) / 1000.0

    /** Sent often, so it's a compact array. The ground station knows the order. */
    fun fastJson(): String = synchronized(lock) {
        val (roll, pitch) = trimmed()
        val g = gyroBody
        val out = lastOutput
        fun t(v: Double?) = v?.let { r1(it).toString() } ?: "null"
        "{\"type\":\"fc\",\"t\":${nowMs()},\"a\":[${r1(roll)},${r1(pitch)},${r1(attitude.yaw)}]," +
            "\"r\":[${r1(Math.toDegrees(g[0]))},${r1(Math.toDegrees(g[1]))},${r1(Math.toDegrees(g[2]))}]," +
            "\"ta\":[${t(targetRoll)},${t(targetPitch)},${t(targetHeading)}]," +
            "\"tr\":[${r1(targetRollRate)},${r1(targetPitchRate)},${r1(targetYawRate)}]," +
            "\"o\":[${r3(out.throttle)},${r3(out.steering)},${r3(out.pitch)},${r3(out.roll)}]}"
    }

    private fun health(value: Boolean?) = when (value) { null -> "none"; true -> "ok"; false -> "bad" }

    /** Sent about twice a second. Location is left out for anyone not allowed to see it. */
    fun slowJson(canSeePrivate: Boolean) = synchronized(lock) {
        val now = nowMs()
        val fresh = { at: Long -> if (at == 0L) null else now - at < SENSOR_STALE_MS }
        val gpsFresh = gps?.let { now - it.atMs < GPS_STALE_MS }
        val (roll, pitch) = trimmed()
        val ref = reference
        buildJsonObject {
            put("type", "fcs")
            put("t", now)
            put("mode", mode.name)
            put("active", enabled)
            put("settled", estimator.converged)
            put("armProblem", armProblemLocked())
            put("modes", buildJsonObject { FlightMode.entries.forEach { m -> put(m.name, modeProblem(m)) } })
            put("vib", r2(vibration))
            put("baro", pressureHpa?.let { r1(it) })
            if (ref != null) {
                val rRoll = ref.roll - p("TRIM_ROLL")
                val rPitch = ref.pitch - p("TRIM_PITCH")
                putJsonArray("ref") { add(r1(rRoll)); add(r1(rPitch)); add(r1(ref.yaw)) }
                putJsonArray("diff") {
                    add(r1(Frames.wrap180(roll - rRoll)))
                    add(r1(Frames.wrap180(pitch - rPitch)))
                    add(r1(Frames.wrap180(attitude.yaw - ref.yaw)))
                }
            }
            putJsonObject("pid") {
                putJsonArray("roll") { add(r3(rollPid.lastP)); add(r3(rollPid.integral)); add(r3(rollPid.lastD)) }
                putJsonArray("pitch") { add(r3(pitchPid.lastP)); add(r3(pitchPid.integral)); add(r3(pitchPid.lastD)) }
                putJsonArray("yaw") { add(r3(yawPid.lastP)); add(r3(yawPid.integral)); add(r3(yawPid.lastD)) }
            }
            putJsonObject("rates") {
                put("gyro", r1(gyroRate.hz)); put("accel", r1(accelRate.hz)); put("mag", r1(magRate.hz))
                put("gps", r1(gpsRate.hz)); put("loop", r1(loopRate.hz))
            }
            putJsonObject("timing") {
                put("delayAvgMs", r1(delayAvgShown)); put("delayMaxMs", r1(delayMaxShown)); put("dtMaxMs", r1(dtMaxShown))
                put("taskMaxUs", r1(taskMaxShown)); put("stallMaxMs", r1(maxStallMs)); put("overruns", overruns)
            }
            putJsonObject("health") {
                put("gyro", health(fresh(lastGyroWallMs)))
                put("accel", health(fresh(lastAccelWallMs)))
                put("compass", health(fresh(lastMagWallMs)))
                put("baro", health(fresh(lastBaroWallMs)))
                put("gps", health(gpsFresh?.let { it && navFix() != null }))
                put("attitude", health(estimator.converged))
                put("timing", health(dtMaxShown < STALL_MS))
                put("battery", health(batteryPct?.let { it >= p("FS_BATT_MIN") }))
                put("heat", health(thermal < p("FS_THERMAL")))
            }
            put("battery", batteryPct?.let { r1(it) })
            put("thermal", thermal)
            putJsonObject("caps") {
                put("stabilize", lastGyroWallMs != 0L)
                put("heading", lastMagWallMs != 0L)
                put("altitude", if (lastBaroWallMs != 0L) "barometer" else "GPS only")
            }
            if (p("MIC_PROP") >= 1) putJsonObject("prop") {
                val hz = propHz(now)
                put("status", propUnavailable ?: when {
                    hz == null -> "listening, no propeller tone"
                    !lastArmed -> "disarmed: steady tones heard with the motor still count as background, ignored once armed"
                    else -> "hearing the propeller"
                })
                put("hz", hz?.let { r1(it) })
                put("rpm", hz?.let { Math.round(it / p("PROP_BLADES") * 60) })
                put("snr", propTone?.takeIf { now - propAtMs < 1000 }?.let { r1(it.snrDb) })
                if (p("MIC_PROP") >= 2) {
                    put("air", propAirspeed(now)?.let { r1(it) })
                    put("learned", p("PROP_KQ") > 0)
                    put("fitRms", p("PROP_KQ").takeIf { it > 0 }?.let { r1(it) })
                    put("samples", propAir.count)
                    put("sectors", propAir.sectorsCovered())
                    propAir.fit?.takeIf { it.samples > 0 && it.rmsMps <= p("PROP_FIT_RMS") }?.let { f -> put("wind", r1(f.windMps)); put("windFrom", f.windFromDeg.toInt()) }
                }
            }
            if (canSeePrivate) {
                gps?.let { g ->
                    putJsonObject("gps") {
                        put("lat", g.lat); put("lon", g.lon); put("alt", r1(g.altM)); put("speed", r1(g.speedMps))
                        put("course", r1(g.courseDeg)); put("acc", r1(g.accuracyM)); put("sats", g.satellites); put("signal", gnssSignal?.let { r1(it) })
                        gpsGuard.estimate(nowMs())?.let { e -> putJsonObject("nav") {
                            put("health", e.health.name.lowercase()); put("radius", r1(e.radiusM)); put("lat", e.lat); put("lon", e.lon)
                            put("rejected", gpsGuard.rejected); put("resets", gpsGuard.resets)
                        } }
                        put("ageMs", now - g.atMs)
                    }
                }
                home?.let { h ->
                    putJsonObject("home") {
                        put("lat", h.lat); put("lon", h.lon)
                        gps?.let { g ->
                            put("dist", r1(Navigation.distanceM(g.lat, g.lon, h.lat, h.lon)))
                            put("bearing", r1(Navigation.bearingDeg(g.lat, g.lon, h.lat, h.lon)))
                        }
                    }
                }
            }
        }
    }

    private fun armProblemLocked(): String? {
        val b = batteryPct
        if (b != null && b < p("FS_BATT_MIN")) return "phone battery is ${b.toInt()}%, needs ${p("FS_BATT_MIN").toInt()}%"
        return modeProblem(mode)
    }

    /** One row for the flight log. */
    fun logRow(): String = synchronized(lock) {
        val g = gyroBody
        val o = lastOutput
        val fix = gps
        "${nowMs()},${mode.name},${r2(attitude.roll)},${r2(attitude.pitch)},${r2(attitude.yaw)}," +
            "${r2(Math.toDegrees(g[0]))},${r2(Math.toDegrees(g[1]))},${r2(Math.toDegrees(g[2]))}," +
            "${targetRoll?.let(::r2) ?: ""},${targetPitch?.let(::r2) ?: ""},${targetHeading?.let(::r2) ?: ""}," +
            "${r3(o.throttle)},${r3(o.steering)},${r3(o.pitch)},${r3(o.roll)}," +
            "${fix?.lat ?: ""},${fix?.lon ?: ""},${fix?.altM ?: ""},${fix?.accuracyM ?: ""}"
    }

    fun statusText(): String = synchronized(lock) {
        val (roll, pitch) = trimmed()
        "%s  roll %.0f  pitch %.0f  hdg %.0f".format(mode.name, roll, pitch, attitude.yaw)
    }

    val attitudeNow: Attitude get() = synchronized(lock) { attitude }
    val gpsNow: GpsFix? get() = synchronized(lock) { gps }
    val gyroHz: Double get() = synchronized(lock) { gyroRate.hz }
    val loopHz: Double get() = synchronized(lock) { loopRate.hz }

    companion object {
        const val LOG_HEADER = "t,mode,roll,pitch,yaw,rollRate,pitchRate,yawRate,targetRoll,targetPitch,targetHeading,throttle,steering,pitchOut,rollOut,lat,lon,alt,gpsAcc"
        private const val SENSOR_STALE_MS = 500L
        private const val GPS_STALE_MS = 5000L
        private const val GPS_LOST_MS = 10000L
        private const val STALL_MS = 50.0
        /** Phone GPS height wanders by 10 m or more, so only clearly above home counts as flying. */
        private const val DISARM_AIR_ALT_M = 15.0
        private const val PROP_BACKGROUND_MS = 60_000L
        private const val PROP_SPINDOWN_MS = 3_000L
    }
}

/**
 * Bounded flight log kept on the phone. Writing happens on its own thread, so a slow disk can
 * never hold up the control loop: if the queue is full, rows are dropped and counted.
 */
class FlightLog(private val dir: java.io.File?) {
    private val queue = java.util.concurrent.ArrayBlockingQueue<String>(4096)
    @Volatile var dropped = 0L; private set
    @Volatile var running = false; private set
    @Volatile var currentFile: java.io.File? = null; private set
    private var thread: Thread? = null

    fun add(row: String) { if (running && !queue.offer(row)) dropped++ }

    @Synchronized fun start(stamp: Long) {
        val d = dir ?: return
        if (running) return
        d.mkdirs()
        // Keep the newest sessions only, so logs can't fill the phone.
        d.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES - 1)?.forEach { it.delete() }
        val f = java.io.File(d, "flight-$stamp.csv")
        currentFile = f
        running = true
        thread = Thread({
            f.bufferedWriter().use { w ->
                w.write(FlightController.LOG_HEADER); w.newLine()
                var written = 0L
                while (running || queue.isNotEmpty()) {
                    val row = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (row == null) { w.flush(); continue }
                    if (written < MAX_ROWS) { w.write(row); w.newLine(); written++ }
                }
            }
        }, "flight-log").apply { isDaemon = true; start() }
    }

    @Synchronized fun stop() { running = false }

    /** The latest rows of the newest log, limited so a download can't flood a mobile link. */
    fun latestCsv(maxRows: Int = 60_000): String? {
        val f = currentFile ?: dir?.listFiles()?.maxByOrNull { it.lastModified() } ?: return null
        if (!f.exists()) return null
        val lines = f.readLines()
        return if (lines.size <= maxRows) lines.joinToString("\n")
        else (listOf(lines.first()) + lines.takeLast(maxRows)).joinToString("\n")
    }

    private companion object {
        const val MAX_FILES = 10
        const val MAX_ROWS = 720_000L // Two hours at 100 Hz.
    }
}
