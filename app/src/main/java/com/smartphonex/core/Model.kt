package com.smartphonex.core

import kotlinx.serialization.json.JsonObject

enum class Mode { DISARMED, MANUAL, AUTO, FAILSAFE }
enum class Source { LOCAL, GROUND, AI, RC, GAMEPAD, BEHAVIOR }

/** Normalised actuator request, -1..1 on both axes. */
data class ActuatorOutput(
    val throttle: Double,
    val steering: Double,
    val pitch: Double = 0.0,
    val roll: Double = 0.0,
    val channels: List<ChannelOutput> = emptyList(),
    /** Disarmed or in failsafe: outputs must go to their safe state. */
    val safe: Boolean = false,
    val failsafe: Boolean = false,
    /** Kill: everything off, with no output sent at all. */
    val killed: Boolean = false,
)

/** What a vehicle can report. Null means this vehicle has no such sensor. */
data class SensorFrame(
    val speedMps: Double?,
    val yawRateRps: Double?,
    val x: Double?,
    val y: Double?,
    val headingRad: Double?,
    val rangeM: Double?,
    val collided: Boolean,
)

/**
 * The one hardware boundary. The simulator implements it now; the USB-UART car
 * implements it in Phase 3, and nothing above this interface changes.
 */
interface VehicleAdapter {
    val id: String
    val capabilities: List<String>
    /** Called once per control tick: apply the output, advance, return latest sensors. */
    fun exchange(output: ActuatorOutput, dtSec: Double): SensorFrame
    /** Put the actuators in a safe state immediately. */
    fun safeStop()
    fun reset() {}
    /** Adapters with their own outputs report them here, in place of the vehicle mixer's. */
    fun reportOutputs(): List<ChannelOutput>? = null
}

data class VehicleProfile(
    val id: String,
    val name: String,
    val maxThrottle: Double = 0.8,
    val maxSteering: Double = 1.0,
    val maxSpeedMps: Double = 3.0,
    val kp: Double = 0.4,
    val ki: Double = 0.3,
    val kd: Double = 0.0,
    val ff: Double = 0.25,
    val commandTimeoutMs: Long = 500,
    val staleCommandMs: Long = 1000,
    val maxAiLeaseMs: Long = 10000,
    val obstacleStopM: Double = 0.8,
    val aiMayArm: Boolean = true,
    val vehicleType: String = "car",
    /** Which adapter drives this vehicle: "simulator" or "preview". */
    val adapter: String = "simulator",
    val channels: List<Channel> = listOf(
        Channel("Motor", ChannelKind.MOTOR_REVERSIBLE, throttle = 1.0),
        Channel("Steering", yaw = 1.0),
    ),
    val missionsAllowed: Boolean = true,
    /** Serial format shown in the output preview: "crsf" or "ibus". */
    val frameFormat: String = "crsf",
)

sealed class Command {
    data class Drive(val throttle: Double, val steering: Double, val pitch: Double = 0.0, val roll: Double = 0.0) : Command()
    data class SetChannels(val channels: List<Channel>, val frameFormat: String?) : Command()
    data class SetFlightMode(val mode: FlightMode) : Command()
    data class SetNamedInputs(val inputs: Map<String, Double>, val durationMs: Long) : Command()
    data class RunSkill(val id: String) : Command()
    data object StopSkill : Command()
    data class RunBehavior(val id: String) : Command()
    data object StopBehavior : Command()
    data object Kill : Command()
    /** Operating requests: tests, trim, triggers, missions and fences. Handled by Ops. */
    data class OpsRequest(val name: String, val args: JsonObject) : Command()
    data class SetThrottle(val value: Double, val durationMs: Long) : Command()
    data class SetSteering(val value: Double, val durationMs: Long) : Command()
    data class SetSpeed(val mps: Double, val durationMs: Long) : Command()
    data object Stop : Command()
    data class SetMode(val mode: Mode) : Command()
    data class ConfigurePid(val kp: Double?, val ki: Double?, val kd: Double?, val ff: Double?) : Command()
    data class UpdateProfile(val fields: Map<String, Double>) : Command()
    data class SelectProfile(val id: String) : Command()
    data class RunMission(val name: String) : Command()
}

/** seq/sentAt are null for commands that originate on this device or in the MCP request itself. */
data class Envelope(val source: Source, val seq: Long?, val sentAtMs: Long?, val command: Command)

data class CommandResult(val accepted: Boolean, val code: String, val message: String = "") {
    companion object {
        val OK = CommandResult(true, "ok")
        fun reject(code: String, message: String) = CommandResult(false, code, message)
    }
}

data class LoopStats(val hz: Double, val dtAvgMs: Double, val dtMaxMs: Double, val overruns: Long)

data class Telemetry(
    val t: Long,
    val mode: Mode,
    val profileId: String,
    val throttle: Double,
    val steering: Double,
    val targetSpeed: Double?,
    val sensors: SensorFrame,
    val mission: String?,
    val failsafeReason: String?,
    val obstacleHold: Boolean,
    val aiLocked: Boolean,
    val loop: LoopStats,
    val accepted: Long,
    val rejected: Long,
    val cmdProcAvgUs: Double,
    val outputs: List<ChannelOutput> = emptyList(),
    val frameHex: String = "",
    /** What each output would do if armed. */
    val would: List<Int> = emptyList(),
)

data class MissionStep(val speed: Double, val steering: Double, val durationMs: Long)

object Missions {
    val all: Map<String, List<MissionStep>> = mapOf(
        "straight_and_stop" to listOf(MissionStep(1.5, 0.0, 3000), MissionStep(0.0, 0.0, 1500)),
        "square" to List(4) { listOf(MissionStep(1.2, 0.0, 2500), MissionStep(0.8, 0.7, 1450)) }.flatten() +
            MissionStep(0.0, 0.0, 1500),
        "figure_eight" to listOf(
            MissionStep(1.2, 0.6, 7000), MissionStep(1.2, -0.6, 7000), MissionStep(0.0, 0.0, 1500),
        ),
    )
}

class Pid {
    private var integral = 0.0
    private var lastMeasured: Double? = null
    fun reset() { integral = 0.0; lastMeasured = null }
    /** Output is clamped to +/-limit; the integral stops growing while saturated (anti-windup). */
    fun update(target: Double, measured: Double, dt: Double, p: VehicleProfile, limit: Double): Double {
        val error = target - measured
        val deriv = lastMeasured?.let { if (dt > 0) -(measured - it) / dt else 0.0 } ?: 0.0
        lastMeasured = measured
        val unsat = p.ff * target + p.kp * error + p.ki * integral + p.kd * deriv
        val out = unsat.coerceIn(-limit, limit)
        if (out == unsat) integral += error * dt
        return out
    }
}
