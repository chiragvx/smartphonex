package com.smartphonex.core

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

data class Box(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    fun contains(x: Double, y: Double) = x in x0..x1 && y in y0..y1
}

/** Kinematic bicycle model with first-order motor and steering servo lag, plus sensor noise. */
class SimulatedCar(seed: Long = 42L) : VehicleAdapter {
    override val id = "sim-rc-car"
    override val capabilities = listOf("throttle", "steering", "speed", "yaw_rate", "position", "heading", "range")

    val arenaHalf = 15.0
    val obstacles = listOf(Box(6.0, -1.5, 7.0, 1.5), Box(-5.0, 5.0, -2.0, 6.0), Box(-8.0, -8.0, -7.0, -4.0))

    private val topSpeed = 4.0
    private val motorTau = 0.6
    private val servoTau = 0.12
    private val maxSteerRad = Math.toRadians(28.0)
    private val wheelbase = 0.26
    private val rng = Random(seed)

    private var x = 0.0
    private var y = 0.0
    private var heading = 0.0
    private var speed = 0.0
    private var steer = 0.0
    private var collided = false

    override fun reset() {
        x = 0.0; y = 0.0; heading = 0.0; speed = 0.0; steer = 0.0; collided = false
    }

    override fun safeStop() { speed = 0.0 }

    private fun blocked(px: Double, py: Double) =
        abs(px) >= arenaHalf || abs(py) >= arenaHalf || obstacles.any { it.contains(px, py) }

    override fun exchange(output: ActuatorOutput, dtSec: Double): SensorFrame {
        val dt = dtSec.coerceIn(0.0, 0.1)
        speed += (output.throttle * topSpeed - speed) / motorTau * dt
        steer += (output.steering * maxSteerRad - steer) / servoTau * dt
        val yawRate = speed * tan(steer) / wheelbase
        heading += yawRate * dt
        val nx = x + speed * cos(heading) * dt
        val ny = y + speed * sin(heading) * dt
        collided = blocked(nx, ny)
        if (collided) speed = 0.0 else { x = nx; y = ny }
        return SensorFrame(
            speedMps = speed + noise(0.02),
            yawRateRps = yawRate + noise(0.01),
            x = x + noise(0.01),
            y = y + noise(0.01),
            headingRad = heading,
            rangeM = range(),
            collided = collided,
        )
    }

    private fun noise(sd: Double) = rng.nextGaussian() * sd

    private fun range(): Double {
        var d = 0.0
        while (d < 8.0) {
            d += 0.05
            if (blocked(x + d * cos(heading), y + d * sin(heading))) return d
        }
        return 8.0
    }
}
