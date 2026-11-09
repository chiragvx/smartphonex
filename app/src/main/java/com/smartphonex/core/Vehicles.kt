package com.smartphonex.core

/** How a channel's normalized value becomes a pulse width. */
enum class ChannelKind { SERVO, MOTOR_REVERSIBLE, MOTOR_FORWARD }

/**
 * One physical output. Vehicle types are only lists of these, so a new kind of vehicle is data,
 * not code. Gains say how much of each pilot axis feeds this output.
 */
data class Channel(
    val name: String,
    val kind: ChannelKind = ChannelKind.SERVO,
    val throttle: Double = 0.0,
    val yaw: Double = 0.0,
    val pitch: Double = 0.0,
    val roll: Double = 0.0,
    val trim: Double = 0.0,
    /** Value held whenever the vehicle is disarmed or in failsafe. */
    val failsafe: Double = 0.0,
)

data class ChannelOutput(val name: String, val value: Double, val pulseUs: Int)

object Mixer {
    private fun limitsOf(k: ChannelKind) = if (k == ChannelKind.MOTOR_FORWARD) 0.0 to 1.0 else -1.0 to 1.0

    fun pulseUs(kind: ChannelKind, value: Double): Int = when (kind) {
        ChannelKind.MOTOR_FORWARD -> (1000 + value.coerceIn(0.0, 1.0) * 1000).toInt()
        else -> (1500 + value.coerceIn(-1.0, 1.0) * 500).toInt()
    }

    fun mix(channels: List<Channel>, out: ActuatorOutput, safe: Boolean): List<ChannelOutput> = channels.map { c ->
        val (lo, hi) = limitsOf(c.kind)
        val raw = if (safe) c.failsafe
        else c.trim + c.throttle * out.throttle + c.yaw * out.steering + c.pitch * out.pitch + c.roll * out.roll
        val v = raw.coerceIn(lo, hi)
        ChannelOutput(c.name, v, pulseUs(c.kind, v))
    }

    fun validate(channels: List<Channel>): String? {
        if (channels.isEmpty() || channels.size > 16) return "a vehicle needs 1 to 16 channels"
        if (channels.map { it.name }.toSet().size != channels.size) return "channel names must be different"
        for (c in channels) {
            if (c.name.isBlank() || c.name.length > 24) return "channel names must be 1 to 24 characters"
            for (g in listOf(c.throttle, c.yaw, c.pitch, c.roll)) if (!g.isFinite() || g !in -2.0..2.0) return "${c.name}: gains must be -2 to 2"
            if (!c.trim.isFinite() || c.trim !in -1.0..1.0) return "${c.name}: trim must be -1 to 1"
            val (lo, hi) = limitsOf(c.kind)
            if (!c.failsafe.isFinite() || c.failsafe !in lo..hi) return "${c.name}: failsafe must be $lo to $hi"
        }
        return null
    }
}

/** Standard serial frames a USB-UART adapter could send to a receiver or flight controller. */
object SerialFrames {
    fun crc8DvbS2(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0xD5) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }

    /** CRSF RC channels: 16 channels of 11 bits, neutral 1500 us = 992. Unused channels stay neutral. */
    fun crsf(pulses: List<Int>): ByteArray {
        val f = ByteArray(26)
        f[0] = 0xC8.toByte(); f[1] = 24; f[2] = 0x16
        var bits = 0L
        var bitCount = 0
        var i = 3
        for (n in 0 until 16) {
            val us = pulses.getOrNull(n) ?: 1500
            val ticks = (((us - 1500) * 8) / 5 + 992).coerceIn(172, 1811).toLong()
            bits = bits or (ticks shl bitCount)
            bitCount += 11
            while (bitCount >= 8) {
                f[i++] = (bits and 0xFF).toByte()
                bits = bits ushr 8
                bitCount -= 8
            }
        }
        f[25] = crc8DvbS2(f, 2, 25).toByte()
        return f
    }

    /** FlySky iBus: 14 little-endian channels in microseconds, with a checksum. */
    fun ibus(pulses: List<Int>): ByteArray {
        val f = ByteArray(32)
        f[0] = 0x20; f[1] = 0x40
        for (n in 0 until 14) {
            val us = (pulses.getOrNull(n) ?: 1500).coerceIn(1000, 2000)
            f[2 + n * 2] = (us and 0xFF).toByte()
            f[3 + n * 2] = (us shr 8).toByte()
        }
        var sum = 0xFFFF
        for (b in 0 until 30) sum -= f[b].toInt() and 0xFF
        f[30] = (sum and 0xFF).toByte()
        f[31] = (sum shr 8 and 0xFF).toByte()
        return f
    }

    fun encode(format: String, pulses: List<Int>): ByteArray = if (format == "ibus") ibus(pulses) else crsf(pulses)
    fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
}

/** Real outputs, no hardware attached: the numbers are shown so the whole path can be tested. */
class OutputPreview : VehicleAdapter {
    override val id = "output-preview"
    override val capabilities = listOf("throttle", "steering")
    override fun exchange(output: ActuatorOutput, dtSec: Double) = SensorFrame(null, null, null, null, null, null, false)
    override fun safeStop() {}
}

object Presets {
    private val motor = Channel("Motor", ChannelKind.MOTOR_REVERSIBLE, throttle = 1.0)

    /** Plane and wing safe state: motor off, surfaces neutral. An aircraft can't stop, only glide. */
    val all: List<VehicleProfile> = listOf(
        VehicleProfile("car", "Car", vehicleType = "car", adapter = "preview", maxThrottle = 1.0,
            channels = listOf(motor, Channel("Steering", yaw = 1.0))),
        VehicleProfile("skid", "Skid-steer / tank", vehicleType = "skid", adapter = "preview", maxThrottle = 1.0,
            channels = listOf(
                Channel("Left motor", ChannelKind.MOTOR_REVERSIBLE, throttle = 1.0, yaw = -1.0),
                Channel("Right motor", ChannelKind.MOTOR_REVERSIBLE, throttle = 1.0, yaw = 1.0),
            )),
        VehicleProfile("boat", "Boat (rudder)", vehicleType = "boat", adapter = "preview", maxThrottle = 1.0,
            channels = listOf(motor, Channel("Rudder", yaw = 1.0))),
        VehicleProfile("boat-twin", "Boat (twin thrusters)", vehicleType = "boat", adapter = "preview", maxThrottle = 1.0,
            channels = listOf(
                Channel("Left thruster", ChannelKind.MOTOR_REVERSIBLE, throttle = 1.0, yaw = -1.0),
                Channel("Right thruster", ChannelKind.MOTOR_REVERSIBLE, throttle = 1.0, yaw = 1.0),
            )),
        VehicleProfile("plane", "Fixed-wing plane", vehicleType = "plane", adapter = "preview", maxThrottle = 1.0,
            aiMayArm = false, missionsAllowed = false,
            channels = listOf(
                Channel("Motor", ChannelKind.MOTOR_FORWARD, throttle = 1.0),
                Channel("Aileron", roll = 1.0),
                Channel("Elevator", pitch = 1.0),
                Channel("Rudder", yaw = 1.0),
            )),
        // Flown by the real autopilot on simulated sensors: plans, miss rules and contingencies without hardware.
        VehicleProfile("plane-sim", "Simulated plane", vehicleType = "plane", adapter = "simulator-plane", maxThrottle = 1.0,
            aiMayArm = false, missionsAllowed = false,
            channels = listOf(
                Channel("Motor", ChannelKind.MOTOR_FORWARD, throttle = 1.0),
                Channel("Aileron", roll = 1.0),
                Channel("Elevator", pitch = 1.0),
                Channel("Rudder", yaw = 1.0),
            )),
        VehicleProfile("wing", "Flying wing (elevons)", vehicleType = "plane", adapter = "preview", maxThrottle = 1.0,
            aiMayArm = false, missionsAllowed = false,
            channels = listOf(
                Channel("Motor", ChannelKind.MOTOR_FORWARD, throttle = 1.0),
                Channel("Left elevon", pitch = 0.7, roll = 0.7),
                Channel("Right elevon", pitch = 0.7, roll = -0.7),
            )),
        VehicleProfile("custom", "Custom", vehicleType = "custom", adapter = "preview", maxThrottle = 1.0,
            aiMayArm = false, missionsAllowed = false,
            channels = listOf(Channel("Output 1", throttle = 1.0), Channel("Output 2", yaw = 1.0))),
    )
}
