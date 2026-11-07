package com.smartphonex.core

/** Moves bytes. USB-UART lives in the Android layer, and anything else can be added behind this. */
interface Transport {
    fun open(onBytes: (ByteArray) -> Unit)
    fun write(data: ByteArray)
    fun close()
}

/** Implemented by the Android layer: which adapters exist now, and how to open one. */
interface TransportProvider {
    fun list(): List<String>
    fun open(def: TransportDef): Transport
}

/** Plain network socket, for serial-to-Wi-Fi bridges. */
class TcpTransport(private val host: String, private val port: Int) : Transport {
    private var socket: java.net.Socket? = null
    override fun open(onBytes: (ByteArray) -> Unit) {
        val s = java.net.Socket()
        s.connect(java.net.InetSocketAddress(host, port), 5000)
        s.tcpNoDelay = true
        socket = s
        Thread({
            val buf = ByteArray(512)
            runCatching { while (true) { val n = s.getInputStream().read(buf); if (n < 0) break; onBytes(buf.copyOf(n)) } }
        }, "tcp-read").apply { isDaemon = true; start() }
    }
    override fun write(data: ByteArray) { socket?.getOutputStream()?.write(data) }
    override fun close() { runCatching { socket?.close() } }
}

/** Turns actuator values into the bytes a device family expects. Pure, so all of it can be tested. */
interface ProtocolDriver {
    /** One write cycle for this driver's actuators. Returns the bytes to send. */
    fun encode(values: List<Pair<ActuatorDef, Double>>, moveMs: Int): ByteArray
    fun encodeLimp(actuators: List<ActuatorDef>): ByteArray? = null
    fun ping(address: Int): ByteArray? = null
}

private fun fraction(a: ActuatorDef, v: Double) = ((v - a.min) / (a.max - a.min)).coerceIn(0.0, 1.0)

/** Maps an actuator's range to 1000-2000 us, on the channel number in its address. */
class PulseFrameDriver(private val format: String) : ProtocolDriver {
    override fun encode(values: List<Pair<ActuatorDef, Double>>, moveMs: Int): ByteArray {
        val pulses = MutableList(16) { 1500 }
        for ((a, v) in values) if (a.address in 1..16) pulses[a.address - 1] = (1000 + fraction(a, v) * 1000).toInt()
        return SerialFrames.encode(format, pulses)
    }
}

/**
 * Hiwonder LX-16A style serial bus servo: position 0-1000 over 240 degrees.
 * Layout from the published protocol. Not yet checked against a real servo.
 */
class Lx16aDriver : ProtocolDriver {
    private fun frame(id: Int, cmd: Int, params: IntArray): ByteArray {
        val len = params.size + 3
        var sum = id + len + cmd
        params.forEach { sum += it }
        val f = ByteArray(params.size + 6)
        f[0] = 0x55; f[1] = 0x55; f[2] = id.toByte(); f[3] = len.toByte(); f[4] = cmd.toByte()
        params.forEachIndexed { i, p -> f[5 + i] = p.toByte() }
        f[f.size - 1] = (sum.inv() and 0xFF).toByte()
        return f
    }
    fun move(id: Int, pos: Int, ms: Int) = frame(id, 1, intArrayOf(pos and 0xFF, pos shr 8, ms and 0xFF, ms shr 8))
    override fun encode(values: List<Pair<ActuatorDef, Double>>, moveMs: Int): ByteArray =
        values.map { (a, v) -> move(a.address, (fraction(a, v) * 1000).toInt(), moveMs.coerceIn(0, 30000)) }.fold(ByteArray(0)) { x, y -> x + y }
    override fun encodeLimp(actuators: List<ActuatorDef>) = actuators.map { frame(it.address, 31, intArrayOf(0)) }.fold(ByteArray(0)) { x, y -> x + y }
    override fun ping(address: Int) = frame(address, 14, IntArray(0))
}

/**
 * Feetech SCS/STS style serial bus servo: position 0-4095, little-endian as on STS models.
 * Layout from the published protocol. Not yet checked against a real servo.
 */
class FeetechDriver : ProtocolDriver {
    private fun frame(id: Int, inst: Int, params: IntArray): ByteArray {
        val len = params.size + 2
        var sum = id + len + inst
        params.forEach { sum += it }
        val f = ByteArray(params.size + 6)
        f[0] = 0xFF.toByte(); f[1] = 0xFF.toByte(); f[2] = id.toByte(); f[3] = len.toByte(); f[4] = inst.toByte()
        params.forEachIndexed { i, p -> f[5 + i] = p.toByte() }
        f[f.size - 1] = (sum.inv() and 0xFF).toByte()
        return f
    }
    fun move(id: Int, pos: Int, ms: Int) = frame(id, 3, intArrayOf(42, pos and 0xFF, pos shr 8, ms and 0xFF, ms shr 8, 0, 0))
    override fun encode(values: List<Pair<ActuatorDef, Double>>, moveMs: Int): ByteArray =
        values.map { (a, v) -> move(a.address, (fraction(a, v) * 4095).toInt(), moveMs.coerceIn(0, 30000)) }.fold(ByteArray(0)) { x, y -> x + y }
    override fun encodeLimp(actuators: List<ActuatorDef>) = actuators.map { frame(it.address, 3, intArrayOf(40, 0)) }.fold(ByteArray(0)) { x, y -> x + y }
    override fun ping(address: Int) = frame(address, 1, IntArray(0))
}

/**
 * Dynamixel Protocol 2.0: goal position register 116, 0-4095.
 * Layout from the published protocol. Not yet checked against a real servo.
 */
class Dynamixel2Driver : ProtocolDriver {
    fun crc(data: ByteArray, len: Int): Int {
        var c = 0
        for (i in 0 until len) {
            c = c xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
        }
        return c
    }
    private fun frame(id: Int, inst: Int, params: IntArray): ByteArray {
        val len = params.size + 3
        val f = ByteArray(params.size + 10)
        f[0] = 0xFF.toByte(); f[1] = 0xFF.toByte(); f[2] = 0xFD.toByte(); f[3] = 0
        f[4] = id.toByte(); f[5] = (len and 0xFF).toByte(); f[6] = (len shr 8).toByte(); f[7] = inst.toByte()
        params.forEachIndexed { i, p -> f[8 + i] = p.toByte() }
        val c = crc(f, f.size - 2)
        f[f.size - 2] = (c and 0xFF).toByte(); f[f.size - 1] = (c shr 8).toByte()
        return f
    }
    fun move(id: Int, pos: Int) = frame(id, 3, intArrayOf(116, 0, pos and 0xFF, pos shr 8 and 0xFF, pos shr 16 and 0xFF, pos shr 24 and 0xFF))
    override fun encode(values: List<Pair<ActuatorDef, Double>>, moveMs: Int): ByteArray =
        values.map { (a, v) -> move(a.address, (fraction(a, v) * 4095).toInt()) }.fold(ByteArray(0)) { x, y -> x + y }
    override fun encodeLimp(actuators: List<ActuatorDef>) = actuators.map { frame(it.address, 3, intArrayOf(64, 0, 0)) }.fold(ByteArray(0)) { x, y -> x + y }
    override fun ping(address: Int) = frame(address, 1, IntArray(0))
}

/**
 * Custom text commands, for devices with a simple serial command set such as a microcontroller sketch.
 * It's data, not code: the template only fills in {address}, {id}, {value} and {percent}.
 */
class TextDriver(private val template: String) : ProtocolDriver {
    override fun encode(values: List<Pair<ActuatorDef, Double>>, moveMs: Int): ByteArray = values.joinToString("") { (a, v) ->
        template.replace("{address}", a.address.toString()).replace("{id}", a.id)
            .replace("{value}", "%.3f".format(java.util.Locale.ROOT, v))
            .replace("{percent}", (fraction(a, v) * 100).toInt().toString())
            .replace("\\n", "\n").replace("\\r", "\r")
    }.toByteArray()
}

object Drivers {
    fun create(driver: String, t: TransportDef?): ProtocolDriver? = when (driver) {
        "crsf" -> PulseFrameDriver("crsf")
        "ibus" -> PulseFrameDriver("ibus")
        "lx16a" -> Lx16aDriver()
        "feetech" -> FeetechDriver()
        "dynamixel2" -> Dynamixel2Driver()
        "text" -> TextDriver(t?.template ?: "{address}:{value}\\n")
        else -> null
    }
}

/** Decoded receiver frame. Channels are -1 to 1, with 0 at centre stick. */
data class RcFrame(val channels: List<Double>, val failsafe: Boolean)

/** Parses a serial stream into receiver frames, tolerating partial frames and noise. */
interface ReceiverParser { fun feed(data: ByteArray): List<RcFrame> }

private abstract class BufferedParser : ReceiverParser {
    protected val buf = java.io.ByteArrayOutputStream()
    protected fun bytes(): ByteArray = buf.toByteArray()
    protected fun keep(from: ByteArray, at: Int) { buf.reset(); if (at < from.size) buf.write(from, at, from.size - at) }
}

class CrsfParser : ReceiverParser {
    private var pending = ByteArray(0)
    override fun feed(data: ByteArray): List<RcFrame> {
        pending += data
        val out = ArrayList<RcFrame>()
        var i = 0
        while (pending.size - i >= 3) {
            val addr = pending[i].toInt() and 0xFF
            val len = pending[i + 1].toInt() and 0xFF
            if ((addr != 0xC8 && addr != 0xEE && addr != 0xEC) || len !in 2..62) { i++; continue }
            if (pending.size - i < len + 2) break
            val crc = SerialFrames.crc8DvbS2(pending, i + 2, i + 1 + len)
            if (crc != (pending[i + 1 + len].toInt() and 0xFF)) { i++; continue }
            if ((pending[i + 2].toInt() and 0xFF) == 0x16 && len == 24) {
                var bits = 0L; var n = 0; var p = i + 3
                val ch = ArrayList<Double>(16)
                repeat(16) {
                    while (n < 11) { bits = bits or ((pending[p++].toLong() and 0xFF) shl n); n += 8 }
                    val v = (bits and 0x7FF).toInt()
                    bits = bits ushr 11; n -= 11
                    ch += ((v - 992) / 820.0).coerceIn(-1.0, 1.0)
                }
                out += RcFrame(ch, false)
            }
            i += len + 2
        }
        pending = pending.copyOfRange(i, pending.size)
        if (pending.size > 512) pending = ByteArray(0)
        return out
    }
}

class IbusParser : ReceiverParser {
    private var pending = ByteArray(0)
    override fun feed(data: ByteArray): List<RcFrame> {
        pending += data
        val out = ArrayList<RcFrame>()
        var i = 0
        while (pending.size - i >= 32) {
            if (pending[i] != 0x20.toByte() || pending[i + 1] != 0x40.toByte()) { i++; continue }
            var sum = 0xFFFF
            for (k in 0 until 30) sum -= pending[i + k].toInt() and 0xFF
            val got = (pending[i + 30].toInt() and 0xFF) or ((pending[i + 31].toInt() and 0xFF) shl 8)
            if (got != (sum and 0xFFFF)) { i++; continue }
            val ch = (0 until 14).map { n ->
                val us = (pending[i + 2 + n * 2].toInt() and 0xFF) or ((pending[i + 3 + n * 2].toInt() and 0x0F) shl 8)
                ((us - 1500) / 500.0).coerceIn(-1.0, 1.0)
            }
            out += RcFrame(ch, false)
            i += 32
        }
        pending = pending.copyOfRange(i, pending.size)
        if (pending.size > 512) pending = ByteArray(0)
        return out
    }
}

/** SBUS at 100000 baud, 8E2, inverted: the adapter has to invert it and support even parity. */
class SbusParser : ReceiverParser {
    private var pending = ByteArray(0)
    override fun feed(data: ByteArray): List<RcFrame> {
        pending += data
        val out = ArrayList<RcFrame>()
        var i = 0
        while (pending.size - i >= 25) {
            if (pending[i] != 0x0F.toByte() || pending[i + 24] != 0x00.toByte()) { i++; continue }
            var bits = 0L; var n = 0; var p = i + 1
            val ch = ArrayList<Double>(16)
            repeat(16) {
                while (n < 11) { bits = bits or ((pending[p++].toLong() and 0xFF) shl n); n += 8 }
                val v = (bits and 0x7FF).toInt()
                bits = bits ushr 11; n -= 11
                ch += ((v - 992) / 820.0).coerceIn(-1.0, 1.0)
            }
            val flags = pending[i + 23].toInt() and 0xFF
            out += RcFrame(ch, flags and 0x08 != 0)
            i += 25
        }
        pending = pending.copyOfRange(i, pending.size)
        if (pending.size > 512) pending = ByteArray(0)
        return out
    }
}

object Receivers {
    fun parser(driver: String): ReceiverParser? = when (driver) {
        "crsf" -> CrsfParser()
        "ibus" -> IbusParser()
        "sbus" -> SbusParser()
        else -> null
    }
}

/** Fixed priorities: a person with their hands on the controls outranks everything except a stop. */
class InputRouter(private val clock: () -> Long) {
    private val claims = HashMap<Source, Long>()
    private val order = listOf(Source.RC, Source.GAMEPAD, Source.LOCAL, Source.GROUND)

    /** False means a higher-priority person is holding control, so this input is ignored. */
    @Synchronized fun allow(source: Source, moved: Boolean): Boolean {
        val now = clock()
        if (source !in order) return true
        if (moved) claims[source] = now
        val owner = order.firstOrNull { (claims[it] ?: Long.MIN_VALUE / 2) + HOLD_MS > now }
        return owner == null || owner == source
    }

    @Synchronized fun owner(): Source? { val now = clock(); return order.firstOrNull { (claims[it] ?: Long.MIN_VALUE / 2) + HOLD_MS > now } }

    private companion object { const val HOLD_MS = 1500L }
}

/** Telemetry-only MAVLink 2 frames, so standard ground control apps can watch. Nothing is accepted back. */
object Mavlink {
    fun crc(data: ByteArray, from: Int, to: Int, extra: Int): Int {
        var c = 0xFFFF
        fun add(b: Int) {
            var t = (b xor c) and 0xFF
            t = (t xor (t shl 4)) and 0xFF
            c = ((c ushr 8) xor (t shl 8) xor (t shl 3) xor (t ushr 4)) and 0xFFFF
        }
        for (i in from until to) add(data[i].toInt() and 0xFF)
        if (extra >= 0) add(extra)
        return c
    }

    private fun frame(seq: Int, msgId: Int, crcExtra: Int, payload: java.nio.ByteBuffer): ByteArray {
        val p = payload.array()
        var n = p.size
        while (n > 1 && p[n - 1] == 0.toByte()) n-- // MAVLink 2 trims trailing zeros.
        val f = ByteArray(10 + n + 2)
        f[0] = 0xFD.toByte(); f[1] = n.toByte(); f[4] = seq.toByte(); f[5] = 1; f[6] = 1
        f[7] = msgId.toByte(); f[8] = (msgId shr 8).toByte(); f[9] = (msgId shr 16).toByte()
        System.arraycopy(p, 0, f, 10, n)
        val c = crc(f, 1, 10 + n, crcExtra)
        f[10 + n] = c.toByte(); f[11 + n] = (c shr 8).toByte()
        return f
    }

    private fun le(size: Int) = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)

    fun heartbeat(seq: Int, armed: Boolean, mavType: Int): ByteArray {
        val b = le(9).putInt(0).put(mavType.toByte()).put(8) // Autopilot: 8 is "invalid", since this isn't a known stack.
        b.put(if (armed) 0x80.toByte() else 0).put(4).put(3) // Base mode, system status active, MAVLink version.
        return frame(seq, 0, 50, b)
    }

    fun attitude(seq: Int, ms: Int, rollRad: Float, pitchRad: Float, yawRad: Float, rr: Float, pr: Float, yr: Float) =
        frame(seq, 30, 39, le(28).putInt(ms).putFloat(rollRad).putFloat(pitchRad).putFloat(yawRad).putFloat(rr).putFloat(pr).putFloat(yr))

    fun globalPosition(seq: Int, ms: Int, lat: Double, lon: Double, altM: Double, headingDeg: Double) =
        frame(seq, 33, 104, le(28).putInt(ms).putInt((lat * 1e7).toInt()).putInt((lon * 1e7).toInt()).putInt((altM * 1000).toInt()).putInt((altM * 1000).toInt())
            .putShort(0).putShort(0).putShort(0).putShort((Frames.norm360(headingDeg) * 100).toInt().toShort()))
}
