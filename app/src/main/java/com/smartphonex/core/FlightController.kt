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
    /**
     * Every fix passes through this before navigation sees it. A profile's top speed is a ground vehicle's (it's also
     * the AI's speed limit), so a plane uses its planned speed with room for a dive and a tailwind: without that,
     * every fix in flight looked like a jump.
     */
    val gpsGuard = GpsGuard({ p("ARM_GPS_ACC") }, { p("GPS_TIMEOUT_S") }, {
        if (vehicleType() == "plane") maxOf(maxSpeedMps(), 2.5 * p("PLAN_SPEED") + p("PLAN_WIND")) else maxSpeedMps()
    })
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

    // --- Simulation: a simulated vehicle's sensors replace the phone's, which are ignored while it's selected.

    /** True while a simulated vehicle is flying: set by the runtime. */
    @Volatile var simulation: () -> Boolean = { false }
    private var simFeeding = false
    private var wasSimulating = false
    private fun ignored() = !simFeeding && simulation()

    /** The simulator's readings, fed through the same inputs as the phone's, so everything downstream is the same. */
    fun simFeed(block: () -> Unit) = synchronized(lock) { simFeeding = true; try { block() } finally { simFeeding = false } }

    /** The simulator's true attitude and body rates (rad/s), in place of the estimator, with the sensors counted as fresh. */
    fun simAttitude(a: Attitude, gyroRad: DoubleArray) = synchronized(lock) {
        val now = nowMs()
        lastGyroWallMs = now; lastAccelWallMs = now; lastMagWallMs = now
        gyroRate.tick(); accelRate.tick(); magRate.tick()
        accelNorm = 9.80665; magNorm = 45.0
        gyroBody = gyroRad
        attitude = a
    }

    // --- Sensor inputs: called from the sensor thread at whatever rate this phone manages.

    fun onGyro(tNs: Long, x: Float, y: Float, z: Float) = synchronized(lock) {
        if (ignored()) return@synchronized
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
        if (ignored()) return@synchronized
        lastAccelWallMs = nowMs()
        accelRate.tick()
        val n = sqrt((x * x + y * y + z * z).toDouble())
        accelNorm = n
        vibration += (abs(n - 9.80665) - vibration) * 0.02
        val b = Frames.apply(mount, x.toDouble(), y.toDouble(), z.toDouble())
        estimator.onAccel(b[0], b[1], b[2])
    }

    fun onMag(x: Float, y: Float, z: Float) = synchronized(lock) {
        if (ignored()) return@synchronized
        lastMagWallMs = nowMs()
        magRate.tick()
        magNorm = sqrt((x * x + y * y + z * z).toDouble())
        val b = Frames.apply(mount, x.toDouble(), y.toDouble(), z.toDouble())
        estimator.onMag(b[0], b[1], b[2])
    }

    fun onRotationVector(x: Float, y: Float, z: Float, w: Float) = synchronized(lock) {
        if (ignored()) return@synchronized
        lastRefWallMs = nowMs()
        reference = Frames.fromRotationVector(x.toDouble(), y.toDouble(), z.toDouble(), w.toDouble(), mount)
    }

    fun onBaro(hPa: Float) = synchronized(lock) { if (ignored()) return@synchronized; lastBaroWallMs = nowMs(); pressureHpa = hPa.toDouble() }

    /**
     * A new position. [fromSatellites] is false for Android's network position (Wi-Fi and cell towers): it's shown,
     * but never steers anything, because it can be hundreds of metres out while claiming far better.
     */
    fun onGps(fix: GpsFix, declination: Double?, fromSatellites: Boolean = true) = synchronized(lock) {
        if (ignored()) return@synchronized
        gps = fix
        gpsRate.tick()
        if (declination != null) declinationDeg = declination
        if (!fromSatellites) return@synchronized
        // Only an armed vehicle that isn't being driven is surely still. Disarmed, it may be carried, as in a walk test.
        // An armed plane never is: with the throttle closed it's gliding or coming in to land, at full flying speed.
        val moving = !lastArmed || isPlane() || kotlin.math.abs(lastOutput.throttle) > 0.05
        when (val v = gpsGuard.feed(fix, moving)) {
            is GpsGuard.Verdict.Reset -> log.event("gps", "phone", "jump", "${v.reason}: navigation waits until it settles")
            is GpsGuard.Verdict.Rejected -> if (fix.atMs - lastRejectLogMs > 5000) { lastRejectLogMs = fix.atMs; log.event("gps", "phone", "rejected", "GPS fix ignored: ${v.reason}") }
            else -> {}
        }
        if (isPlane()) countFlyingFix(fix)
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
     * Background: tones heard for [PROP_STEADY_MS] with every output at rest, within a few percent of one pitch,
     * such as mains hum or a laptop fan hunting for speed, whatever their frequency. The analyzer skips them, armed or not, and they're forgotten a minute after
     * they stop. A motor spun on the Test page never lands here, or the real propeller would be skipped in flight.
     */
    private class Heard(var hz: Double, val firstMs: Long, var atMs: Long, var hits: Int = 0)
    private val propBackground = ArrayDeque<Heard>()
    /** Tones heard with every output at rest, which become background if they stay at one pitch long enough. */
    private val propSteady = ArrayList<Heard>()
    /** When every output last came to rest, or 0 while one is being driven. */
    private var propQuietSinceMs = 0L
    /** The tracked tone over the last few seconds, to tell a propeller changing speed from a steady hum. */
    private val propRecent = ArrayDeque<Pair<Long, Double>>()
    private fun samePitch(a: Double, b: Double) = abs(a - b) <= a * PropTone.EXCLUDE_WIDTH + 2.0

    /** Background tones for the analyzer to skip. */
    fun propExclusions(): List<Double> = synchronized(lock) { propBackground.map { it.hz } }
    /** Why the microphone isn't listening, set by the app, or null when it is. */
    @Volatile var propUnavailable: String? = null

    /**
     * A tone from the microphone, or null when nothing stood out. Called about ten times a second. [heard] is every
     * strong peak in the sound, background included, so background that's still humming stays remembered.
     */
    fun onPropTone(tone: PropTone.Tone?, outputsAtRest: Boolean = true, heard: List<Double> = tone?.peaks ?: emptyList()) = synchronized(lock) {
        if (ignored()) return@synchronized
        val now = nowMs()
        val hz = propTracker.update(tone, now)
        if (hz != null) propRecent.addLast(now to hz)
        while (propRecent.isNotEmpty() && now - propRecent.first().first > PROP_STEADY_MS) propRecent.removeFirst()
        val propVarying = propRecent.size > 1 && propRecent.maxOf { it.second } > propRecent.minOf { it.second } * 1.05
        if (tone != null) { propTone = tone; propAtMs = now }
        if (!outputsAtRest) propQuietSinceMs = 0L else if (propQuietSinceMs == 0L) propQuietSinceMs = now
        // Background still humming stays remembered, and follows a slow drift.
        for (f in heard) propBackground.firstOrNull { samePitch(it.hz, f) }?.let { it.atMs = now; it.hz += 0.1 * (f - it.hz) }
        if (!lastArmed) {
            propBackground.removeAll { now - it.atMs > PROP_BACKGROUND_MS }
            propSteady.removeAll { now - it.atMs > PROP_STEADY_GAP_MS }
            // A motor takes a moment to spin down after a test, so wait before trusting the quiet.
            val quiet = propQuietSinceMs > 0 && now - propQuietSinceMs >= PROP_SPINDOWN_MS
            if (!quiet) propSteady.clear() else {
                val hit = HashSet<Heard>()
                // Tones of a tracked tone that's changing speed (a propeller tested on the bench) are never background.
                // A hum or fan holds its pitch, so it's still learned even though it's tracked.
                val rev = if (hz != null && propVarying) tone?.revHz else null
                fun isProp(f: Double) = rev != null && Math.round(f / rev).let { k -> k in 1..PropTone.MAX_HARMONIC && abs(f - k * rev) <= f * 0.01 + 2.0 }
                for (f in heard) {
                    if (isProp(f) || propBackground.any { samePitch(it.hz, f) }) continue
                    val c = propSteady.firstOrNull { samePitch(it.hz, f) }
                    if (c == null) { if (propSteady.size < 64) propSteady += Heard(f, now, now); continue }
                    if (hit.add(c)) { c.hits++; c.atMs = now; c.hz += 0.1 * (f - c.hz) }
                }
                // Heard a quarter of the time or more for the whole stretch: a weak fan flickers at the threshold.
                propSteady.filter { now - it.firstMs >= PROP_STEADY_MS && it.hits >= PROP_STEADY_MS / 400 }.forEach { c ->
                    propSteady.remove(c)
                    propBackground.addLast(Heard(c.hz, now, now))
                    while (propBackground.size > PROP_BACKGROUND_MAX) propBackground.removeFirst()
                }
            }
        }
        // Someone set PROP_KQ to 0 to forget: drop the old samples too, or the next fit would learn them again.
        val kq = p("PROP_KQ")
        if (kq == 0.0 && lastPropKq > 0.0) { propAir.clear(); log.event("prop", "phone", "forgot", "propeller airspeed forgotten") }
        lastPropKq = kq
        if (hz == null || p("MIC_PROP") < 2 || !lastArmed) return@synchronized
        val heardHz = propHz(now) ?: return@synchronized
        if (propMismatch()) return@synchronized
        // Learn only from a clean tone: not distorted, not mixed with another propeller, well above the noise.
        if (tone == null || tone.clipped || tone.otherFamily || tone.snrDb < p("MIC_SNR") + 6) return@synchronized
        // Learn only from a fresh, trusted satellite fix, so GPS jumps don't teach it wrong.
        val g = gps ?: return@synchronized
        if (now - g.atMs > 1500 || gpsGuard.estimate(now)?.health != GpsGuard.Health.GOOD) return@synchronized
        // Level and steady only: climbing trades airspeed for height, and while speeding up or slowing down the
        // smoothed tone and the late GPS speed don't line up.
        val n = heardHz / p("PROP_BLADES")
        propSteadyLog.addLast(Steady(now, n, g.speedMps, altitudeM(g)))
        while (propSteadyLog.isNotEmpty() && now - propSteadyLog.first().atMs > STEADY_SPAN_MS) propSteadyLog.removeFirst()
        if (!steadyAndLevel(now)) return@synchronized
        propAir.add(PropAirspeed.Sample(n, lastOutput.throttle * packStrength(now), minutesArmed(now), g.speedMps, g.courseDeg, now))
        if (propAir.count % 50 == 0) propAir.refit()?.let { f ->
            if (f.rmsMps <= p("PROP_FIT_RMS")) {
                val first = p("PROP_KQ") == 0.0
                listOf("PROP_K0", "PROP_K1", "PROP_K2", "PROP_K3").forEachIndexed { i, n -> params.set(n, f.k[i]) }
                params.set("PROP_NMIN", f.nMin); params.set("PROP_NMAX", f.nMax); params.set("PROP_KQ", maxOf(f.rmsMps, 0.01))
                params.set("PROP_CELLS", f.cells.toDouble()); params.set("PROP_CRUISE", f.cruiseMps); params.set("PROP_VMIN", f.slowestMps)
                propFitAtMs = now
                if (first) log.event("prop", "phone", "learned", "propeller airspeed learned from ${f.samples} samples, ±${"%.1f".format(f.rmsMps)} m/s; wind ${"%.0f".format(f.windMps)} m/s from ${f.windFromDeg.toInt()}°")
            }
        }
    }


    private fun savedPropFit(): PropAirspeed.Fit? {
        val q = p("PROP_KQ")
        if (q <= 0) return null
        return PropAirspeed.Fit(doubleArrayOf(p("PROP_K0"), p("PROP_K1"), p("PROP_K2"), p("PROP_K3")), q, 0.0, 0.0, p("PROP_NMIN"), p("PROP_NMAX"), 0,
            p("PROP_CELLS").toLong(), p("PROP_CRUISE"), p("PROP_VMIN"))
    }

    private class Steady(val atMs: Long, val n: Double, val groundMps: Double, val altM: Double)
    private val propSteadyLog = ArrayDeque<Steady>()
    /** When the airspeed fit was last updated in this run of the app; 0 when it came from an earlier one. */
    private var propFitAtMs = 0L

    private fun baroAltM(): Double? = pressureHpa?.takeIf { nowMs() - lastBaroWallMs < 2000 }?.let { 44330.0 * (1 - Math.pow(it / 1013.25, 0.1903)) }
    /** The barometer's height when home was set, so heights above home compare like with like. */
    private var homeBaroM: Double? = null

    /** Height above home: from the barometer when it was read at home too, else from GPS. Null without a home. */
    fun heightAboveHome(): Double? = synchronized(lock) {
        val h = home ?: return null
        val b = baroAltM(); val hb = homeBaroM
        if (b != null && hb != null) return b - hb
        val g = navFix() ?: return null
        g.altM - h.altM
    }

    /** Barometric height when there's a barometer (smoother than GPS), else GPS altitude. */
    private fun altitudeM(g: GpsFix): Double = pressureHpa?.takeIf { nowMs() - lastBaroWallMs < 2000 }?.let { 44330.0 * (1 - Math.pow(it / 1013.25, 0.1903)) } ?: g.altM

    /** The last [STEADY_SPAN_MS]: motor speed within 2%, ground speed within 1 m/s, and height changing slowly. */
    private fun steadyAndLevel(now: Long): Boolean {
        val l = propSteadyLog
        if (l.size < 5 || now - l.first().atMs < STEADY_SPAN_MS * 0.9) return false
        if (l.maxOf { it.n } > l.minOf { it.n } * 1.02) return false
        if (l.maxOf { it.groundMps } - l.minOf { it.groundMps } > 1.0) return false
        val rate = abs(l.last().altM - l.first().altM) / ((l.last().atMs - l.first().atMs) / 1000.0)
        return rate <= if (lastBaroWallMs != 0L) 0.7 else 1.2
    }

    private fun minutesArmed(now: Long) = if (lastArmed && armedAtMs > 0) (now - armedAtMs) / 60_000.0 else 0.0

    /**
     * The setup's ground runs (GroundRun.kt), set by the runtime for the active vehicle. Its reference curve checks
     * flight readings for a harmonic mistaken for the fundamental, and the latest run's pack strength scales throttle.
     */
    @Volatile var groundRuns: () -> GroundRunStore.Entry? = { null }
    private var propNote: String? = null

    /** The latest raw reading for the ground run: when it was heard, revolutions per second, and the tone. */
    fun propSample(): Triple<Long, Double, PropTone.Tone?>? = synchronized(lock) {
        val hz = propTracker.hz ?: return null
        if (nowMs() - propAtMs >= 1000) return null
        Triple(propAtMs, hz / p("PROP_BLADES"), propTone)
    }

    /**
     * How strong this pack is next to the reference run's, from a ground run taken in the half hour before arming
     * (or the last half hour, while disarmed). 1 when there's none.
     */
    private fun packStrength(now: Long): Double {
        val e = groundRuns() ?: return 1.0
        if (e.comparison.changed) return 1.0
        val ref = if (lastArmed && armedAtMs > 0) armedAtMs else now
        return if (ref - e.last.atMs in 0..GroundRun.FRESH_MS) e.comparison.strength else 1.0
    }

    /** Blade-pass Hz, checked against the ground run while armed, with a harmonic mistake undone. */
    private fun propHz(now: Long): Double? {
        val raw = propTracker.hz?.takeIf { p("MIC_PROP") >= 1 && now - propAtMs < 1000 }
        if (raw == null || !lastArmed) { propNote = null; return raw }
        val b = p("PROP_BLADES")
        val (rev, note) = GroundRun.check(raw / b, lastOutput.throttle, groundRuns()?.reference, packStrength(now))
        propNote = note
        return rev * b
    }
    private fun propMismatch() = propNote?.startsWith("doesn't") == true

    /** Airspeed from the propeller, when it's been learned and the motor is in the range it learned. */
    private fun propAirspeed(now: Long): Double? {
        if (p("MIC_PROP") < 2 || p("PROP_KQ") <= 0) return null
        if (propAir.fit == null || propAir.fit?.samples == 0) propAir.restore(savedPropFit())
        val hz = propHz(now) ?: return null
        if (propMismatch()) return null
        return propAir.airspeed(hz / p("PROP_BLADES"), lastOutput.throttle * packStrength(now), minutesArmed(now))
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

    /**
     * A plane in the air can't stop. An automatic failsafe would cut its motor and drop the controls, so instead it
     * flies home, or circles where it is when it can't navigate. False when that doesn't apply (not a plane, not
     * flying, or no gyroscope to fly with), and the normal failsafe goes ahead. A person's STOP never comes here.
     */
    fun keepFlying(why: String): Boolean = synchronized(lock) {
        if (!isPlane() || why == GYRO_STOPPED) return false
        updateFlying(nowMs())
        val flying = flyingWhy ?: return false
        val m = if (modeProblem(FlightMode.RETURN_HOME) == null) FlightMode.RETURN_HOME else FlightMode.HOLD
        log.event("failsafe", "phone", "airborne", "$why while flying ($flying): " +
            (if (m == FlightMode.RETURN_HOME) "returning home" else "circling here, it can't navigate") + " instead of cutting the motor")
        enterMode(m, why)
        true
    }
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
        homeBaroM = baroAltM()
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
     * STOP still works, as the deliberate kill switch. Ground vehicles never count as flying. It takes two airborne
     * fixes in a row to say yes, and three seconds without any sign to say no again. Without a fresh fix it can't tell,
     * so once those seconds pass it doesn't refuse.
     */
    fun flyingReason(): String? = synchronized(lock) { updateFlying(nowMs()); flyingWhy }

    // One bad reading must not flip "flying" either way. Setting it needs two fixes in a row (or a second of propeller
    // airspeed); clearing it needs every sign gone for a few seconds, so a dip in ground speed in a headwind doesn't.
    private var gpsFlyFixes = 0
    private var gpsFlyWhy: String? = null
    private var propFlySinceMs = 0L
    private var flyingWhy: String? = null
    private var lastFlyingMs = 0L

    /** Counts fixes in a row that look airborne. Called for every fix, after the guard has seen it. */
    private fun countFlyingFix(g: GpsFix) {
        val e = gpsGuard.estimate(g.atMs)?.takeIf { it.health != GpsGuard.Health.LOST && it.health != GpsGuard.Health.NONE }
        // The higher of the fix's own speed and the guard's smoothed one: missing a real flight is the worse mistake.
        val speed = maxOf(g.speedMps, e?.speedMps ?: 0.0)
        val h = home
        val why = when {
            speed > p("DISARM_AIR_SPD") -> "moving at ${"%.0f".format(speed)} m/s"
            h != null && g.altM - h.altM > DISARM_AIR_ALT_M -> "${(g.altM - h.altM).toInt()} m above home"
            else -> null
        }
        if (why != null) { gpsFlyFixes++; gpsFlyWhy = why } else { gpsFlyFixes = 0; gpsFlyWhy = null }
    }

    private fun updateFlying(now: Long) {
        if (!isPlane()) { flyingWhy = null; propFlySinceMs = 0L; return }
        // Airspeed doesn't drop to zero in a headwind the way ground speed can.
        val air = propAirspeed(now)
        if (air != null && air > p("DISARM_AIR_SPD")) { if (propFlySinceMs == 0L) propFlySinceMs = now } else propFlySinceMs = 0L
        val g = gps
        val gpsFresh = g != null && now - g.atMs <= p("GPS_TIMEOUT_S") * 1000
        val why = when {
            propFlySinceMs > 0 && now - propFlySinceMs >= FLY_CONFIRM_MS -> "airspeed about ${"%.0f".format(air)} m/s, from the propeller"
            gpsFresh && gpsFlyFixes >= 2 -> gpsFlyWhy
            else -> null
        }
        if (why != null) { flyingWhy = why; lastFlyingMs = now }
        else if (flyingWhy != null && now - lastFlyingMs >= FLY_RELEASE_MS) flyingWhy = null
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
        if (m != FlightMode.AUTO) landing = false
        if (m != FlightMode.RETURN_HOME) divert = null
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
        if (armed && !lastArmed && home == null) goodFix()?.let { home = it; homeBaroM = baroAltM(); log.event("fc", "phone", "home", "home set at arming") }
        if (!armed) landing = false
        if (armed && !lastArmed) { armedAtMs = now; propBackground.removeAll { now - it.atMs > PROP_BACKGROUND_MS } }
        lastArmed = armed
        // Into or out of a simulation, nothing it knew about where it is holds: home, the position and any diversion
        // belong to one world only. A real vehicle must never navigate to a home set in a simulation.
        val sim = simulation()
        if (sim != wasSimulating) {
            wasSimulating = sim
            home = null; homeBaroM = null; gps = null; divert = null; landing = false
            gpsGuard.reset()
            enterMode(FlightMode.MANUAL, if (sim) "simulation started" else "simulation ended")
            log.event("fc", "phone", "simulation", if (sim) "Simulation: the phone's sensors are set aside" else "Simulation over: home and position cleared, back to the phone's own sensors")
        }
        monitor(now, armed)
        updateFlying(now)
        watchMotor(now)
        if (!armed) { motorOutWhy = null; divert = null }
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

    private fun steerToHeading(input: ActuatorOutput, heading: Double, throttle: Double, dt: Double, pitch: Double? = null): ActuatorOutput {
        targetHeading = heading
        val err = Frames.wrap180(heading - attitude.yaw)
        return if (isPlane()) {
            val bank = (p("HDG_P") * err).coerceIn(-p("ANG_MAX_ROLL"), p("ANG_MAX_ROLL"))
            planeControl(input, bank, pitch ?: (input.pitch * p("ANG_MAX_PITCH")), throttle, dt)
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
        if (landing) return landOut(input, dt)
        return when (val s = missions?.step(g, if (isPlane()) heightAboveHome() else null)) {
            is MissionEngine.Step.GoTo -> {
                if (g == null) holdHere(input, dt)
                else {
                    val heading = if (s.fromLat != null && s.fromLon != null) lineHeading(g, s.fromLat, s.fromLon, s.lat, s.lon)
                        else Navigation.bearingDeg(g.lat, g.lon, s.lat, s.lon)
                    val base = s.throttle ?: p("WP_THROTTLE")
                    val (pitch, thr) = s.alt?.takeIf { isPlane() }?.let { planeHeight(it, base) } ?: (null to base)
                    steerToHeading(input, heading, thr, dt, pitch)
                }
            }
            is MissionEngine.Step.Circle -> if (g == null) holdHere(input, dt) else circleAround(input, g, s.lat, s.lon, s.radiusM, s.cw, s.alt, s.throttle, dt)
            is MissionEngine.Step.Wait -> when {
                !isPlane() -> groundRate(input, 0.0, 0.0, dt)
                g == null -> holdHere(input, dt)
                else -> circleAround(input, g, s.lat, s.lon, maxOf(s.radiusM, planeTurnRadius()), true, s.alt, null, dt)
            }
            is MissionEngine.Step.Abort -> {
                log.event("mission", "phone", "aborted", "mission aborted by its own rule (${s.why}): returning home")
                enterMode(FlightMode.RETURN_HOME, "mission aborted")
                returnHome(input, dt)
            }
            is MissionEngine.Step.End -> {
                log.event("mission", "phone", "complete", "mission complete: ${s.action}")
                when (s.action) {
                    "home" -> enterMode(FlightMode.RETURN_HOME, "mission complete")
                    "stop" -> { enterMode(FlightMode.HOLD, "mission complete"); missionStopRequest = true }
                    "land" -> { landing = true; landingSinceMs = nowMs(); log.event("mission", "phone", "landing", "touchdown point reached: motor off, wings level, gliding in") }
                    else -> enterMode(FlightMode.HOLD, "mission complete")
                }
                if (landing) landOut(input, dt) else holdHere(input, dt)
            }
            else -> holdHere(input, dt)
        }
    }

    // --- Following a plan.

    /**
     * Follows the straight line from one point to the next, as the plan was checked: aims at a point [NAV_LOOKAHEAD]
     * metres further along the line from where the vehicle is beside it, so it comes back onto the line and stays there.
     */
    internal fun lineHeading(g: GpsFix, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val fr = LocalFrame(fromLat, fromLon)
        val b = fr.xy(toLat, toLon)
        val v = fr.xy(g.lat, g.lon)
        if (b.len < 1e-6) return Navigation.bearingDeg(g.lat, g.lon, toLat, toLon)
        val u = b.unit()
        val ahead = (v.dot(u) + p("NAV_LOOKAHEAD")).coerceAtMost(b.len)
        val aim = u * ahead
        return Frames.norm360(Math.toDegrees(atan2(aim.x - v.x, aim.y - v.y)))
    }

    /** Goes round a centre at [radiusM], aiming a little ahead on the circle. */
    private fun circleAround(input: ActuatorOutput, g: GpsFix, lat: Double, lon: Double, radiusM: Double, cw: Boolean, alt: Double?, throttle: Double?, dt: Double): ActuatorOutput {
        val fr = LocalFrame(lat, lon)
        val v = fr.xy(g.lat, g.lon)
        val theta = atan2(v.y, v.x)
        val step = (p("NAV_LOOKAHEAD") / radiusM).coerceIn(0.05, 1.0) * if (cw) -1 else 1
        val aim = V2(cos(theta + step), sin(theta + step)) * radiusM
        val heading = Frames.norm360(Math.toDegrees(atan2(aim.x - v.x, aim.y - v.y)))
        val base = throttle ?: p("WP_THROTTLE")
        val (pitch, thr) = alt?.takeIf { isPlane() }?.let { planeHeight(it, base) } ?: (null to base)
        return steerToHeading(input, heading, thr, dt, pitch)
    }

    private fun planeTurnRadius(): Double { val v = p("PLAN_SPEED"); return v * v / (9.80665 * kotlin.math.tan(Math.toRadians(p("PLAN_BANK")))) }

    @Volatile private var speedProtected = false

    /**
     * A plane's pitch and throttle to reach a height. Speed comes first: climbs are limited to [NAV_PITCH_UP], and when
     * the propeller says the plane is below its safe minimum it stops climbing and opens the throttle, giving up height
     * rather than speed. It never stalls to make a height; the altitude miss rule decides what happens instead.
     */
    internal fun planeHeight(target: Double, base: Double): Pair<Double, Double> {
        // Without a height, hold level; speed protection still applies.
        val err = heightAboveHome()?.let { target - it } ?: 0.0
        var pitch = (p("NAV_ALT_P") * err).coerceIn(-p("NAV_PITCH_DN"), p("NAV_PITCH_UP"))
        var thr = (base + p("NAV_THR_P") * err).coerceIn(p("NAV_THR_MIN"), p("NAV_THR_MAX"))
        val air = propAirspeed(nowMs())
        val vmin = p("PLAN_SPEED_MIN")
        val slow = air != null && vmin > 0 && air < vmin
        if (slow) { pitch = minOf(pitch, 0.0); thr = p("NAV_THR_MAX") }
        if (slow != speedProtected) {
            speedProtected = slow
            log.event("nav", "phone", if (slow) "speed_protect" else "speed_ok",
                if (slow) "Airspeed about ${"%.0f".format(air)} m/s, under the safe ${"%.0f".format(vmin)}: not climbing, full throttle, until it's back" else "Airspeed back above the safe minimum")
        }
        return pitch to thr
    }

    /** Landing, after the touchdown point: wings level on the heading it has, motor off, nose slightly up. */
    @Volatile var landing = false; private set
    private var landingSinceMs = 0L
    private fun landOut(input: ActuatorOutput, dt: Double): ActuatorOutput {
        targetHeading = targetHeading ?: attitude.yaw
        val err = Frames.wrap180(targetHeading!! - attitude.yaw)
        // Down and still for a few seconds: landed, so the controller disarms.
        updateFlying(nowMs())
        if (flyingWhy == null && nowMs() - landingSinceMs > 5000 && !missionStopRequest) {
            log.event("mission", "phone", "landed", "landed: disarming")
            missionStopRequest = true
        }
        return planeControl(input, (p("HDG_P") * err).coerceIn(-10.0, 10.0), 2.0, 0.0, dt)
    }

    private var missionStopRequest = false
    fun takeMissionStop(): Boolean = synchronized(lock) { missionStopRequest.also { missionStopRequest = false } }

    /** Going somewhere safer than home, chosen by the contingency rules. Gliding means the motor has stopped. */
    class Divert(val id: String, val kind: String, val lat: Double, val lon: Double, val radiusM: Double, val glide: Boolean, val why: String)
    @Volatile var divert: Divert? = null; private set

    /** Flies to a zone instead of home, in RETURN_HOME. Cleared by any other mode, so a pilot taking over ends it. */
    fun divertTo(d: Divert) = synchronized(lock) {
        divert = d
        log.event("failsafe", "phone", "divert", "${d.why}: ${if (d.glide) "gliding" else "going"} to ${if (d.id == "home") "home" else "${d.kind} zone ${d.id}"}")
        enterMode(FlightMode.RETURN_HOME, d.why)
    }

    private fun returnHome(input: ActuatorOutput, dt: Double): ActuatorOutput {
        val g = navFix()
        val d = divert
        val glide = d?.glide == true && isPlane()
        val tLat = d?.lat ?: home?.lat
        val tLon = d?.lon ?: home?.lon
        // Gliding, the last few metres are flown nearly wings level, still turning gently towards the zone, so it never
        // meets the ground banked. A glide with no trustworthy position goes wings level. Otherwise, with no position:
        // stop (a plane circles) rather than drive on a guess.
        if (glide && g == null) return planeControl(input, 0.0, p("NAV_GLIDE_PITCH"), 0.0, dt)
        if (glide && g != null && tLat != null && tLon != null && (heightAboveHome() ?: Double.MAX_VALUE) < GLIDE_LEVEL_M) {
            val err = Frames.wrap180(Navigation.bearingDeg(g.lat, g.lon, tLat, tLon) - attitude.yaw)
            return planeControl(input, (p("HDG_P") * err).coerceIn(-GLIDE_LEVEL_BANK, GLIDE_LEVEL_BANK), p("NAV_GLIDE_PITCH"), 0.0, dt)
        }
        if (g == null || tLat == null || tLon == null) return holdHere(input, dt)
        val dist = Navigation.distanceM(g.lat, g.lon, tLat, tLon)
        if (dist < maxOf(p("RTH_RADIUS"), d?.radiusM ?: 0.0)) {
            if (!arrivedHome) { arrivedHome = true; log.event("fc", "phone", "home", if (d == null || d.id == "home") "arrived home" else "arrived at ${d.kind} zone ${d.id}") }
            // Over the zone a gliding plane circles down inside it; under power it circles and waits.
            if (isPlane()) return planeControl(input, p("RTH_ORBIT"), if (glide) p("NAV_GLIDE_PITCH") else 0.0, if (glide) 0.0 else p("RTH_THROTTLE"), dt)
            return groundRate(input, 0.0, 0.0, dt).also { targetHeading = null }
        }
        arrivedHome = false
        val heading = Navigation.bearingDeg(g.lat, g.lon, tLat, tLon)
        return if (glide) steerToHeading(input, heading, 0.0, dt, p("NAV_GLIDE_PITCH")) else steerToHeading(input, heading, p("RTH_THROTTLE"), dt)
    }

    // --- The motor stopping in flight, heard by the microphone.

    private var motorOutSinceMs = 0L
    /** Why the motor seems to have stopped in flight, once it's lasted [MOTOR_OUT_MS]. Stays until it's heard again under throttle. */
    @Volatile var motorOutWhy: String? = null; private set

    private fun motorOutSign(now: Long): String? {
        if (!isPlane() || !lastArmed || p("MIC_PROP") < 1 || flyingWhy == null) return null
        val u = lastOutput.throttle
        if (u < 0.3) return null
        // Never heard this flight: no way to tell a stopped motor from a microphone that can't hear it.
        if (propAtMs == 0L || propAtMs < armedAtMs) return null
        val pct = "${(u * 100).toInt()}%"
        if (now - propAtMs >= 1000) return "the propeller can't be heard at $pct throttle"
        val raw = propTracker.hz ?: return null
        val exp = groundRuns()?.reference?.expectedRevHz(u, packStrength(now)) ?: return null
        val rev = raw / p("PROP_BLADES")
        return if (rev < exp * 0.4) "the motor turns at ${(rev / exp * 100).toInt()}% of what $pct throttle gave on the ground" else null
    }

    private fun watchMotor(now: Long) {
        val why = motorOutSign(now)
        if (why == null) {
            motorOutSinceMs = 0L
            if (motorOutWhy != null && lastOutput.throttle >= 0.3 && now - propAtMs < 1000) {
                motorOutWhy = null
                log.event("failsafe", "phone", "motor_ok", "the motor is heard again")
            }
            return
        }
        if (motorOutSinceMs == 0L) motorOutSinceMs = now
        if (motorOutWhy == null && now - motorOutSinceMs >= MOTOR_OUT_MS) {
            motorOutWhy = why
            log.event("failsafe", "phone", "motor_out", "The motor seems to have stopped: $why")
        }
    }

    /** Seconds since arming, for flight time left. 0 while disarmed. */
    fun armedSeconds(): Double = synchronized(lock) { if (lastArmed && armedAtMs > 0) (nowMs() - armedAtMs) / 1000.0 else 0.0 }

    // --- Health and failsafes.

    private fun monitor(now: Long, armed: Boolean) {
        if (mode != FlightMode.MANUAL && now - lastGyroWallMs > SENSOR_STALE_MS) {
            enterMode(FlightMode.MANUAL, "gyroscope stopped")
            if (armed) failsafeRequest = GYRO_STOPPED
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
            // Without a position there's no way home. A plane circles under power (STABILIZE would take its throttle from
            // the controller, which is zero when the link is lost); anything else stops, and the pilot must take over.
            enterMode(if (isPlane()) FlightMode.HOLD else FlightMode.MANUAL, "GPS lost")
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
                    !lastArmed -> "disarmed: tones heard steadily for 10 s with the motor still are taken as background and ignored"
                    else -> "hearing the propeller"
                })
                put("hz", hz?.let { r1(it) })
                put("rpm", hz?.let { Math.round(it / p("PROP_BLADES") * 60) })
                val t = propTone?.takeIf { now - propAtMs < 1000 }
                put("snr", t?.let { r1(it.snrDb) })
                put("harmonics", t?.harmonics?.joinToString(","))
                put("background", propBackground.joinToString(",") { "%.0f".format(it.hz) }.ifEmpty { null })
                // Problems with what's heard, for the operator: each makes the reading less trustworthy.
                put("warning", when {
                    propUnavailable != null -> "microphone lost"
                    t?.clipped == true -> "too loud: the microphone is distorting. Mount the phone further from the propeller"
                    t?.otherFamily == true -> "another propeller or engine is audible"
                    propMismatch() -> propNote
                    else -> null
                })
                put("check", propNote)
                groundRuns()?.let { e -> putJsonObject("groundRun") {
                    put("referenceRpm", e.reference.steps.joinToString(",") { Math.round(it.revHz * 60).toString() })
                    put("lastRpm", e.last.steps.joinToString(",") { Math.round(it.revHz * 60).toString() })
                    put("throttles", e.last.steps.joinToString(",") { Math.round(it.throttle * 100).toString() })
                    put("lastAt", e.last.atMs)
                    put("strength", Math.round(e.comparison.strength * 1000) / 1000.0)
                    put("inUse", packStrength(now).let { Math.round(it * 1000) / 1000.0 })
                    put("changed", e.comparison.why)
                } }
                if (p("MIC_PROP") >= 2) {
                    val air = propAirspeed(now)
                    put("air", air?.let { r1(it) })
                    // Presented honestly: an estimate, with its error and how it compares with this plane's usual speed,
                    // or why there's none.
                    put("airText", air?.let { a ->
                        val cruise = p("PROP_CRUISE")
                        val vs = if (cruise > 0) (a / cruise - 1) * 100 else null
                        "~${Math.round(a)} m/s ±${Math.round(maxOf(1.0, p("PROP_KQ")))}" + when {
                            vs == null -> ""
                            abs(vs) < 3 -> ", about your cruise"
                            else -> ", ${Math.round(abs(vs))}% ${if (vs < 0) "below" else "above"} your cruise"
                        }
                    })
                    put("airWhy", if (air != null || p("PROP_KQ") <= 0) null else propHz(now)?.let { hz ->
                        if (propMismatch()) propNote else propAir.whyNot(hz / p("PROP_BLADES"), lastOutput.throttle * packStrength(now), minutesArmed(now))
                    } ?: "no propeller tone")
                    put("learnedAgo", if (p("PROP_KQ") <= 0) null else if (propFitAtMs > 0) "${(now - propFitAtMs) / 60_000} min ago" else "on an earlier flight")
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
                        heightAboveHome()?.let { put("height", r1(it)) }
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
        private const val GYRO_STOPPED = "gyroscope stopped"
        private const val MOTOR_OUT_MS = 1_500L
        /** Gliding down over a zone, wings level below this height above home. */
        private const val GLIDE_LEVEL_M = 8.0
        private const val GLIDE_LEVEL_BANK = 10.0
        private const val GPS_STALE_MS = 5000L
        private const val GPS_LOST_MS = 10000L
        private const val STALL_MS = 50.0
        /** Phone GPS height wanders by 10 m or more, so only clearly above home counts as flying. */
        private const val DISARM_AIR_ALT_M = 15.0
        private const val FLY_CONFIRM_MS = 1_000L
        private const val FLY_RELEASE_MS = 3_000L
        private const val PROP_BACKGROUND_MS = 60_000L
        private const val STEADY_SPAN_MS = 3_000L
        private const val PROP_SPINDOWN_MS = 3_000L
        private const val PROP_BACKGROUND_MAX = 32
        /** How long a tone must hold one pitch, motor still, to count as background. */
        private const val PROP_STEADY_MS = 10_000L
        private const val PROP_STEADY_GAP_MS = 3_000L
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
