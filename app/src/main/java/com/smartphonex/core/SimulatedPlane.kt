package com.smartphonex.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * A simulated fixed-wing plane, flown by the real flight controller. Its attitude, GPS, barometer and propeller tone
 * replace the phone's sensors (which are ignored while it's selected), and the controller's real commands move its
 * surfaces and motor. So the autopilot, missions, miss rules, fences and contingencies all run as they would in the
 * air, without hardware.
 *
 * The model is deliberately simple and tuned to the planning defaults: about 15 m/s at 40% throttle, about 3 m/s
 * climb at full throttle, and an 8:1 glide. Path angle is pitch (no angle of attack); turns are coordinated; below
 * [STALL_MPS] the nose drops and it sinks. It's for testing the logic, not the aerodynamics.
 */
class SimulatedPlane(private val fc: FlightController, private val clockMs: () -> Long = System::currentTimeMillis) : VehicleAdapter {
    override val id = "sim-plane"
    override val capabilities = listOf("position", "heading", "airspeed")

    /** Wind blowing from this compass direction, at this speed. */
    @Volatile var windMps = 0.0
    @Volatile var windFromDeg = 270.0
    /** The motor stops: no thrust and no propeller tone. */
    @Volatile var motorFailed = false

    var originLat = 18.52; private set
    var originLon = 73.85; private set
    var groundAltM = 560.0; private set

    // State: metres east and north of the origin, height above the ground, airspeed, attitude in degrees.
    var x = 0.0; private set
    var y = 0.0; private set
    var h = 0.0; private set
    var airspeed = 0.0; private set
    var roll = 0.0; private set
    var pitch = 0.0; private set
    var yaw = 0.0; private set
    var onGround = true; private set
    /** A landing harder than [CRASH_SINK_MPS], or with a wing down. */
    var crashed = false; private set
    /** How the last touchdown went: how fast it was coming down, and its bank. */
    var touchdown: String? = null; private set
    private var p = 0.0
    private var q = 0.0
    private var launchHoldS = 0.0
    private var lastGpsMs = 0L
    private var lastBaroMs = 0L
    private var lastToneMs = 0L
    private var climbRate = 0.0

    /**
     * Starts on the ground, pointing north: where the phone is when it's first selected, else where it started before.
     * While it's flying, the position is its own, so a reset goes back to its start.
     */
    override fun reset() {
        if (!fc.simulation()) fc.gpsNow?.let { originLat = it.lat; originLon = it.lon; groundAltM = it.altM }
        // Moving back to the start is a teleport, not a GPS jump: navigation starts afresh.
        else fc.gpsGuard.reset()
        x = 0.0; y = 0.0; h = 0.0; airspeed = 0.0; roll = 0.0; pitch = 0.0; yaw = 0.0; p = 0.0; q = 0.0
        onGround = true; crashed = false; touchdown = null; launchHoldS = 0.0; motorFailed = false
        lastGpsMs = 0L; lastBaroMs = 0L; lastToneMs = 0L
    }

    /** Puts it somewhere for a test. */
    fun placeAt(lat: Double, lon: Double, groundAlt: Double = 560.0) { reset(); originLat = lat; originLon = lon; groundAltM = groundAlt }

    override fun safeStop() {}

    override fun exchange(output: ActuatorOutput, dtSec: Double): SensorFrame {
        val dt = dtSec.coerceIn(0.0, 0.1)
        val u = if (output.safe || motorFailed) 0.0 else output.throttle.coerceIn(0.0, 1.0)
        // Surfaces move the rates, with a little lag. Output roll is left-positive; the plane's roll is right-positive.
        val pCmd = if (output.safe) 0.0 else -output.roll.coerceIn(-1.0, 1.0) * ROLL_RATE
        val qCmd = if (output.safe) 0.0 else output.pitch.coerceIn(-1.0, 1.0) * PITCH_RATE
        if (onGround) step(u, dt) else fly(u, pCmd, qCmd, dt)
        feed(u)
        val wind = windVector()
        val vx = if (onGround) 0.0 else airspeed * sin(rad(yaw)) * cos(rad(pitch)) + wind.first
        val vy = if (onGround) 0.0 else airspeed * cos(rad(yaw)) * cos(rad(pitch)) + wind.second
        return SensorFrame(hypot(vx, vy), rad(yawRate()), x, y, rad(yaw), null, crashed)
    }

    /** On the ground: a hand launch once the motor's been at half throttle or more for half a second. */
    private fun step(u: Double, dt: Double) {
        roll = 0.0; pitch = 0.0; p = 0.0; q = 0.0
        airspeed = max(0.0, airspeed - 3 * dt)
        launchHoldS = if (u >= 0.5 && !crashed) launchHoldS + dt else 0.0
        if (launchHoldS >= 0.5) { onGround = false; airspeed = 13.0; pitch = 8.0; h = 1.5 }
    }

    private fun fly(u: Double, pCmd: Double, qCmd: Double, dt: Double) {
        p += (pCmd - p) * min(1.0, dt / SURFACE_LAG_S)
        q += (qCmd - q) * min(1.0, dt / SURFACE_LAG_S)
        roll = (roll + p * dt).coerceIn(-70.0, 70.0)
        pitch = (pitch + q * dt).coerceIn(-45.0, 45.0)
        // Too slow to fly: the nose drops and it sinks.
        if (airspeed < STALL_MPS) pitch -= 25 * dt
        val loadFactor = 1 / max(0.3, cos(rad(roll)))
        val accel = THRUST * u - DRAG * airspeed * airspeed * loadFactor - G * sin(rad(pitch))
        airspeed = max(0.0, airspeed + accel * dt)
        climbRate = airspeed * sin(rad(pitch)) - (if (airspeed < STALL_MPS) (STALL_MPS - airspeed) else 0.0)
        h += climbRate * dt
        yaw = (yaw + yawRate() * dt + 360) % 360
        val wind = windVector()
        x += (airspeed * sin(rad(yaw)) * cos(rad(pitch)) + wind.first) * dt
        y += (airspeed * cos(rad(yaw)) * cos(rad(pitch)) + wind.second) * dt
        if (h <= 0) {
            h = 0.0
            if (-climbRate > CRASH_SINK_MPS || abs(roll) > 30) crashed = true
            touchdown = "%.1f m/s down, %.1f m/s forward, %.0f° bank".format(-climbRate, airspeed, roll)
            onGround = true
        }
    }

    private fun yawRate() = if (onGround) 0.0 else deg(G * tan(rad(roll)) / max(5.0, airspeed))
    /** East and north components of the wind, blowing from [windFromDeg]. */
    private fun windVector(): Pair<Double, Double> = Pair(-windMps * sin(rad(windFromDeg)), -windMps * cos(rad(windFromDeg)))

    /** What the phone's sensors would read, through the controller's own inputs. */
    private fun feed(u: Double) {
        val now = clockMs()
        fc.simAttitude(Attitude(roll, pitch, yaw), doubleArrayOf(rad(p), rad(q), rad(yawRate())))
        val alt = groundAltM + h
        fc.simFeed {
            if (now - lastGpsMs >= 200) {
                lastGpsMs = now
                val wind = windVector()
                val vx = if (onGround) 0.0 else airspeed * sin(rad(yaw)) * cos(rad(pitch)) + wind.first
                val vy = if (onGround) 0.0 else airspeed * cos(rad(yaw)) * cos(rad(pitch)) + wind.second
                val lat = originLat + y / 111_320.0
                val lon = originLon + x / (111_320.0 * cos(rad(originLat)))
                fc.onGps(GpsFix(lat, lon, alt, hypot(vx, vy), (deg(atan2(vx, vy)) + 360) % 360, 2.5, 14, now), 0.0)
            }
            if (now - lastBaroMs >= 100) {
                lastBaroMs = now
                fc.onBaro((1013.25 * (1 - alt / 44330.0).pow(1 / 0.1903)).toFloat())
            }
            // The propeller as the microphone would hear it, when it's listening and the motor turns.
            if (now - lastToneMs >= 100 && fc.params.get("MIC_PROP") >= 1) {
                lastToneMs = now
                val blades = fc.params.get("PROP_BLADES")
                if (u > 0.05) {
                    val rev = 40 + 200 * u + 0.8 * airspeed
                    fc.onPropTone(PropTone.Tone(rev * blades, 30.0, -20.0, rev, listOf(2, 4)), outputsAtRest = false)
                } else fc.onPropTone(null, outputsAtRest = u <= 0.0)
            }
        }
    }

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    companion object {
        const val G = 9.80665
        /** Thrust at full throttle, m/s². With [DRAG]: 15 m/s level at 40%, about 3 m/s climb at full, 8:1 glide. */
        const val THRUST = 4.2
        const val DRAG = 0.0075
        const val STALL_MPS = 9.0
        const val ROLL_RATE = 200.0
        const val PITCH_RATE = 120.0
        const val SURFACE_LAG_S = 0.08
        const val CRASH_SINK_MPS = 3.0
    }
}
