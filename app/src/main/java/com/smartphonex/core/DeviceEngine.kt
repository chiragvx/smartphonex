package com.smartphonex.core

import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Runs any described device. It's a VehicleAdapter, so arming, authority and failsafes stay in the
 * vehicle controller. Everything reaches actuators through one safety layer here.
 *
 * Real hardware stays closed until the device is signed off, or a single small commissioning move
 * is under way. Otherwise values only go to the preview.
 */
class DeviceEngine(
    private val log: EventLog,
    private val attitude: () -> Attitude,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Bytes bound for real hardware go here. Null means preview only. */
    @Volatile var hardware: HardwareLink? = null,
) : VehicleAdapter {
    override val id get() = "device:" + (device?.id ?: "none")
    override val capabilities get() = listOf("throttle", "steering")

    @Volatile var device: DeviceDescription? = null
        private set
    /** Set during tests: outputs are computed but nothing reaches hardware. */
    @Volatile var muted = false
    /** Trim and test overrides, supplied by the operating layer. */
    @Volatile var trimFor: (String) -> OutputTrim = { OutputTrim() }
    @Volatile var overrideFor: (String) -> Double? = { null }
    private val lock = Any()
    private val current = HashMap<String, Double>()
    private val named = HashMap<String, Pair<Double, Long>>()
    private var skill: RunningSkill? = null
    private var commission: CommissionMove? = null
    private var lastReport: List<ChannelOutput> = emptyList()
    private var fallen = false
    private var wasFailsafe = false
    /** Where the last skill left each joint. A finished pose holds, rather than snapping back to neutral. */
    private val held = HashMap<String, Double>()
    private var safeSkillRan = false

    class CommissionMove(val id: String, val target: Double, val until: Long)

    private class RunningSkill(val def: SkillDef, val steps: List<Step>, val loops: Int, val startedAt: Long, val inputs: Map<String, Double>?, val durationMs: Long) {
        var stepIndex = 0
        var stepStart = startedAt
        var from: Map<String, Double> = emptyMap()
        var loopsDone = 0
    }
    private class Step(val joints: Map<String, Double>, val ms: Long)

    fun load(d: DeviceDescription?) = synchronized(lock) {
        hardware?.close()
        device = d
        current.clear()
        named.clear()
        skill = null
        commission = null
        fallen = false
        held.clear()
        safeSkillRan = false
        d?.actuators?.forEach { current[it.id] = it.neutral }
    }

    /** Hot-swaps settings during setup, such as widened limits, keeping current positions. */
    fun update(d: DeviceDescription) = synchronized(lock) { device = d; d.actuators.forEach { current.getOrPut(it.id) { it.neutral } } }

    fun setNamedInput(name: String, value: Double, ms: Long) = synchronized(lock) { named[name] = value.coerceIn(-1.0, 1.0) to clock() + ms }

    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun joints(o: JsonObject?) = (o?.get("joints") as? JsonObject)?.entries?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }?.toMap() ?: emptyMap()

    /** Starts a skill. Returns an error message, or null. Skills can never exceed actuator limits. */
    fun runSkill(id: String): String? = synchronized(lock) {
        val d = device ?: return "no device loaded"
        val s = d.skills.firstOrNull { it.id == id } ?: return "unknown skill $id"
        skill = when (s.type) {
            "stop" -> { held.clear(); null }
            "set_inputs" -> {
                val inputs = (s.params["inputs"] as? JsonObject)?.entries?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it.coerceIn(-1.0, 1.0) } }?.toMap() ?: emptyMap()
                RunningSkill(s, emptyList(), 1, clock(), inputs, (s.params.num("ms") ?: 1000.0).toLong().coerceIn(1, 60000))
            }
            "pose" -> RunningSkill(s, listOf(Step(joints(s.params), (s.params.num("ms") ?: 1000.0).toLong().coerceAtLeast(1))), 1, clock(), null, 0)
            "sequence" -> {
                val steps = (s.params["steps"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { Step(joints(it), (it.num("ms") ?: 500.0).toLong().coerceAtLeast(1)) } ?: emptyList()
                if (steps.isEmpty()) return "skill $id has no steps"
                RunningSkill(s, steps, (s.params.num("loops") ?: 1.0).toInt().coerceIn(1, 1000), clock(), null, 0)
            }
            "gait" -> {
                val steps = Gaits.build(s.params)
                if (steps == null) return "skill $id: unknown gait pattern"
                RunningSkill(s, steps.map { Step(it.first, it.second) }, (s.params.num("loops") ?: 10.0).toInt().coerceIn(1, 10000), clock(), null, 0)
            }
            else -> return "unknown skill type ${s.type}"
        }
        skill?.from = HashMap(current)
        log.event("skill", "device", id, "skill started")
        null
    }

    fun stopSkill() = synchronized(lock) { skill = null; held.clear() }
    val runningSkill: String? get() = synchronized(lock) { skill?.def?.id }

    /** A single small move during setup. The setup session has already checked permission and size. */
    fun commissionMove(actuator: String, target: Double, ms: Long) = synchronized(lock) { commission = CommissionMove(actuator, target, clock() + ms) }

    private fun skillTargets(now: Long): Map<String, Double>? {
        val r = skill ?: return null
        if (r.steps.isEmpty()) return null
        val step = r.steps[r.stepIndex]
        val t = ((now - r.stepStart).toDouble() / step.ms).coerceIn(0.0, 1.0)
        val out = HashMap<String, Double>()
        for ((j, target) in step.joints) {
            val from = r.from[j] ?: current[j] ?: target
            out[j] = from + (target - from) * t
        }
        held.putAll(step.joints)
        if (t >= 1.0) {
            r.from = HashMap(current).apply { putAll(step.joints) }
            r.stepStart = now
            r.stepIndex++
            if (r.stepIndex >= r.steps.size) {
                r.stepIndex = 0
                r.loopsDone++
                if (r.loopsDone >= r.loops) { log.event("skill", "device", r.def.id, "skill finished"); skill = null }
            }
        }
        return out
    }

    private fun skillInputs(now: Long): Map<String, Double>? {
        val r = skill ?: return null
        val i = r.inputs ?: return null
        if (now - r.startedAt > r.durationMs) { skill = null; return null }
        return i
    }

    private fun balanceOffsets(d: DeviceDescription): Map<String, Double> {
        val out = HashMap<String, Double>()
        val a = attitude()
        for (c in d.controllers.filter { it.type == "balance" }) {
            val limit = c.gains["limit"] ?: 10.0
            fun axis(name: String, angle: Double) {
                (c.params[name] as? JsonObject)?.forEach { (joint, g) ->
                    (g as? JsonPrimitive)?.doubleOrNull?.let { out[joint] = (out[joint] ?: 0.0) + (g.toString().toDouble() * angle).coerceIn(-limit, limit) }
                }
            }
            axis("pitch", a.pitch)
            axis("roll", a.roll)
        }
        return out
    }

    private fun checkFall(d: DeviceDescription) {
        val c = d.controllers.firstOrNull { it.type == "fall_detect" } ?: return
        val limit = c.gains["limit"] ?: 50.0
        val a = attitude()
        val down = abs(a.roll) > limit || abs(a.pitch) > limit
        if (down && !fallen) {
            fallen = true
            log.event("failsafe", "device", "fall", "fall detected: roll ${a.roll.toInt()}, pitch ${a.pitch.toInt()}")
            skill = null
            d.onFall?.takeIf { it != "hold" && it != "stop" }?.let { runSkill(it) }
        } else if (!down && fallen && abs(a.roll) < limit / 2 && abs(a.pitch) < limit / 2) {
            fallen = false
        }
    }

    override fun exchange(output: ActuatorOutput, dtSec: Double): SensorFrame = synchronized(lock) {
        val d = device ?: return@synchronized NO_SENSORS
        val now = clock()
        val dt = dtSec.coerceIn(0.0, 0.1)
        if (output.failsafe && !wasFailsafe) {
            skill = null
            d.onLinkLoss?.takeIf { it != "hold" && it != "stop" }?.let { safeSkillRan = runSkill(it) == null }
        }
        if (!output.failsafe) safeSkillRan = false
        wasFailsafe = output.failsafe
        if (!output.safe) checkFall(d)
        val move = commission?.takeIf { it.until > now }
        if (commission != null && move == null) commission = null
        val targets = skillTargets(now)
        val inputs = HashMap<String, Double>()
        inputs["forward"] = output.throttle; inputs["turn"] = output.steering; inputs["pitch"] = output.pitch; inputs["roll"] = output.roll
        named.entries.removeAll { it.value.second <= now }
        named.forEach { (k, v) -> inputs[k] = v.first }
        skillInputs(now)?.let { inputs.putAll(it) }
        val balance = if (!output.safe) balanceOffsets(d) else emptyMap()
        val limp = ArrayList<ActuatorDef>()
        val report = ArrayList<ChannelOutput>(d.actuators.size)
        val written = ArrayList<Pair<ActuatorDef, Double>>(d.actuators.size)
        for (a in d.actuators) {
            val cur = current[a.id] ?: a.neutral
            var want: Double
            var rate = a.maxRate
            var isLimp = false
            when {
                move?.id == a.id -> { want = move.target; rate = a.maxRate * COMMISSION_RATE }
                overrideFor(a.id) != null -> {
                    val n = overrideFor(a.id)!!
                    want = a.neutral + if (n >= 0) n * (a.max - a.neutral) else n * (a.neutral - a.min)
                    rate = a.maxRate * COMMISSION_RATE
                }
                output.safe && safeSkillRan && skill == null && held.containsKey(a.id) -> want = held.getValue(a.id)
                output.safe && (skill == null || !output.failsafe) -> when (a.safe.action) {
                    "hold" -> want = cur
                    "limp" -> { want = cur; isLimp = true }
                    "value" -> want = a.safe.value ?: a.neutral
                    else -> want = a.neutral
                }
                targets?.containsKey(a.id) == true -> want = targets.getValue(a.id)
                else -> {
                    var n = 0.0
                    a.mix.forEach { (input, gain) -> n += gain * (inputs[input] ?: 0.0) }
                    n = n.coerceIn(-1.0, 1.0)
                    want = if (n == 0.0 && held.containsKey(a.id)) held.getValue(a.id)
                    else a.neutral + if (n >= 0) n * (a.max - a.neutral) else n * (a.neutral - a.min)
                }
            }
            balance[a.id]?.let { want += it }
            // The safety layer: nothing gets past these limits, whoever asked.
            want = want.coerceIn(a.min, a.max)
            val step = rate * dt
            val next = cur + (want - cur).coerceIn(-step, step)
            current[a.id] = next
            // Trim shapes what's sent, and the actuator's limits always win.
            val t = trimFor(a.id)
            val half = (a.max - a.min) / 2
            val mid = (a.max + a.min) / 2
            val shaped = (mid + t.apply((next - mid) / half) * half).coerceIn(a.min, a.max)
            if (isLimp) limp += a else written += a to shaped
            val f = (shaped - a.min) / (a.max - a.min)
            report += if (output.killed) ChannelOutput(a.id, shaped, 0) else ChannelOutput(a.id, shaped, (1000 + f * 1000).toInt())
        }
        lastReport = report
        val signedOff = d.commissioning.signedOffBy != null
        if (muted) return@synchronized NO_SENSORS
        if (output.killed) { if (signedOff) hardware?.send(emptyList(), d.actuators, d); return@synchronized NO_SENSORS }
        if (signedOff) hardware?.send(written, limp, d)
        else if (move != null) hardware?.send(written.filter { it.first.id == move.id }, emptyList(), d)
        NO_SENSORS
    }

    override fun reportOutputs(): List<ChannelOutput> = synchronized(lock) { lastReport }
    override fun safeStop() { synchronized(lock) { skill = null; commission = null } }

    fun statusJson() = synchronized(lock) {
        val d = device
        buildJsonObject {
            put("device", d?.id)
            put("name", d?.name)
            put("skill", skill?.def?.id)
            put("fallen", fallen)
            put("hardwareOpen", d?.commissioning?.signedOffBy != null && hardware != null)
            putJsonArray("actuators") {
                d?.actuators?.forEach { a -> addJsonObject { put("id", a.id); put("unit", a.unit); put("value", Math.round((current[a.id] ?: a.neutral) * 1000) / 1000.0); put("min", a.min); put("max", a.max) } }
            }
        }
    }

    private companion object {
        val NO_SENSORS = SensorFrame(null, null, null, null, null, null, false)
        /** Commissioning moves are deliberately slow. */
        const val COMMISSION_RATE = 0.2
    }
}

/** Turns the engine's values into bytes on real transports, on its own thread so the loop never waits on USB. */
class HardwareLink(private val provider: TransportProvider, private val log: EventLog) {
    private class Latest(val values: List<Pair<ActuatorDef, Double>>, val limp: List<ActuatorDef>, val device: DeviceDescription)
    @Volatile private var latest: Latest? = null
    private val open = HashMap<String, Transport>()
    private val thread = Thread({ pump() }, "hardware-output").apply { isDaemon = true }
    private var started = false
    @Volatile private var running = true
    @Volatile var lastError: String? = null; private set
    @Volatile private var lastSentAt = 0L

    fun send(values: List<Pair<ActuatorDef, Double>>, limp: List<ActuatorDef>, d: DeviceDescription) {
        latest = Latest(values, limp, d)
        if (!started) { started = true; thread.start() }
    }

    private fun pump() {
        runCatching { Realtime.enter("hardware-output", OUTPUT_PERIOD_MS * 1_000_000) }
        while (running) {
            val l = latest
            if (l == null) { Thread.sleep(5); continue }
            latest = null
            val byDriver = l.values.groupBy { it.first.driver to it.first.transport }
            for ((key, group) in byDriver) writeGroup(key.first, key.second, l.device) { it.encode(group, MOVE_MS) }
            l.limp.groupBy { it.driver to it.transport }.forEach { (key, group) -> writeGroup(key.first, key.second, l.device) { it.encodeLimp(group) } }
            Thread.sleep(OUTPUT_PERIOD_MS)
        }
    }

    private fun writeGroup(driverName: String, transportId: String?, d: DeviceDescription, encode: (ProtocolDriver) -> ByteArray?) {
        val def = d.transports.firstOrNull { it.id == transportId } ?: return
        if (def.type == "preview") return
        val driver = Drivers.create(driverName, def) ?: return
        val bytes = encode(driver) ?: return
        try {
            val t = open.getOrPut(def.id) { provider.open(def).also { it.open { } } }
            t.write(bytes)
            lastSentAt = System.currentTimeMillis()
            lastError = null
        } catch (e: Exception) {
            if (lastError == null) log.event("hardware", "device", "error", "${def.id}: ${e.javaClass.simpleName} ${e.message ?: ""}".take(120))
            lastError = e.message ?: e.javaClass.simpleName
            open.remove(def.id)?.let { runCatching { it.close() } }
        }
    }

    fun close() {
        latest = null
    }

    private companion object {
        const val OUTPUT_PERIOD_MS = 10L
        const val MOVE_MS = 20
    }
}

/** Stored gaits. An LLM picks one and adapts it: it isn't expected to invent a stable walk from nothing. */
object Gaits {
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull

    /** Returns steps of (joint targets, time), or null for an unknown pattern. */
    fun build(p: JsonObject): List<Pair<Map<String, Double>, Long>>? {
        val joints = (p["joints"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: "" } ?: emptyMap()
        val stepMs = (p.num("step_ms") ?: 600.0).toLong().coerceIn(100, 5000)
        val swing = p.num("swing") ?: 15.0
        val lift = p.num("lift") ?: 15.0
        val shift = p.num("shift") ?: 8.0
        fun m(vararg pairs: Pair<String, Double>) = pairs.mapNotNull { (role, v) -> joints[role]?.let { it to v } }.toMap()
        return when (p["pattern"]?.jsonPrimitive?.content) {
            // Static walk: move weight over one foot, swing the other, repeat. Slow, and never relies on momentum.
            "biped_static" -> listOf(
                m("left_ankle_roll" to shift, "right_ankle_roll" to shift) to stepMs,
                m("right_knee" to lift, "right_hip" to swing, "left_hip" to -swing) to stepMs,
                m("right_knee" to 0.0, "left_ankle_roll" to 0.0, "right_ankle_roll" to 0.0) to stepMs,
                m("left_ankle_roll" to -shift, "right_ankle_roll" to -shift) to stepMs,
                m("left_knee" to lift, "left_hip" to swing, "right_hip" to -swing) to stepMs,
                m("left_knee" to 0.0, "left_ankle_roll" to 0.0, "right_ankle_roll" to 0.0) to stepMs,
            )
            // Tripod: alternating sets of three legs.
            "hexapod_tripod" -> {
                val a = listOf("l1", "r2", "l3"); val b = listOf("r1", "l2", "r3")
                fun set(group: List<String>, part: String, v: Double) = group.map { "${it}_$part" to v }.toTypedArray()
                listOf(
                    m(*set(a, "lift", lift)) to stepMs,
                    m(*set(a, "swing", swing), *set(b, "swing", -swing)) to stepMs,
                    m(*set(a, "lift", 0.0)) to stepMs,
                    m(*set(b, "lift", lift)) to stepMs,
                    m(*set(b, "swing", swing), *set(a, "swing", -swing)) to stepMs,
                    m(*set(b, "lift", 0.0)) to stepMs,
                )
            }
            else -> null
        }
    }
}

/**
 * Behaviors written by an LLM or a person. They only call skills and read values, so they can't
 * reach an actuator or exceed a limit. Size, depth, loops and waits are all bounded.
 */
object BehaviorTree {
    private const val MAX_NODES = 200
    private const val MAX_DEPTH = 16
    private val KINDS = setOf("sequence", "selector", "condition", "skill", "wait", "repeat", "stop_skill")

    fun validate(tree: JsonObject, skills: Set<String>): List<String> {
        val e = ArrayList<String>()
        var count = 0
        fun walk(n: JsonObject, depth: Int) {
            if (++count > MAX_NODES) { if (count == MAX_NODES + 1) e += "more than $MAX_NODES nodes"; return }
            if (depth > MAX_DEPTH) { e += "deeper than $MAX_DEPTH levels"; return }
            val kind = (n["node"] as? JsonPrimitive)?.content
            if (kind !in KINDS) { e += "node must be one of $KINDS"; return }
            when (kind) {
                "skill" -> if ((n["id"] as? JsonPrimitive)?.content !in skills) e += "unknown skill ${n["id"]}"
                "wait" -> if (((n["ms"] as? JsonPrimitive)?.doubleOrNull ?: -1.0) !in 0.0..600000.0) e += "wait ms must be 0-600000"
                "condition" -> {
                    if ((n["value"] as? JsonPrimitive)?.content == null) e += "condition needs value and compare"
                    if ((n["op"] as? JsonPrimitive)?.content !in setOf("<", ">", "<=", ">=")) e += "condition op must be <, >, <= or >="
                    if ((n["compare"] as? JsonPrimitive)?.doubleOrNull == null) e += "condition compare must be a number"
                }
                "repeat" -> {
                    if (((n["times"] as? JsonPrimitive)?.doubleOrNull ?: -1.0) !in 1.0..100000.0) e += "repeat times must be 1-100000"
                    (n["child"] as? JsonObject)?.let { walk(it, depth + 1) } ?: run { e += "repeat needs a child" }
                }
                "sequence", "selector" -> {
                    val kids = (n["children"] as? JsonArray) ?: run { e += "$kind needs children"; return }
                    kids.forEach { c -> (c as? JsonObject)?.let { walk(it, depth + 1) } ?: run { e += "child must be a node" } }
                }
            }
        }
        walk(tree, 0)
        return e
    }
}

/**
 * Ticks one behavior. Each tick does a bounded amount of work, and it never blocks, so a long wait
 * or a bad tree can't hold anything else up.
 */
class BehaviorRunner(
    private val readValue: (String) -> Double?,
    private val runSkill: (String) -> String?,
    private val skillRunning: () -> Boolean,
    private val stopSkill: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private enum class R { RUNNING, OK, FAIL }
    private val state = HashMap<JsonObject, Long>()
    private val index = java.util.IdentityHashMap<JsonObject, Int>()
    private val counts = java.util.IdentityHashMap<JsonObject, Int>()
    private val waits = java.util.IdentityHashMap<JsonObject, Long>()
    private val started = java.util.IdentityHashMap<JsonObject, Boolean>()
    private var tree: JsonObject? = null
    @Volatile var runningId: String? = null; private set

    fun start(id: String, t: JsonObject) = synchronized(this) { clear(); tree = t; runningId = id }
    fun stop() = synchronized(this) { if (tree != null) stopSkill(); tree = null; runningId = null; clear() }
    private fun clear() { state.clear(); index.clear(); counts.clear(); waits.clear(); started.clear() }

    /** Returns true while still running. */
    fun tick(): Boolean = synchronized(this) {
        val t = tree ?: return false
        val r = eval(t)
        if (r != R.RUNNING) { tree = null; runningId = null; clear(); return false }
        true
    }

    private fun reset(n: JsonObject) {
        index.remove(n); counts.remove(n); waits.remove(n); started.remove(n)
        (n["children"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(::reset) }
        (n["child"] as? JsonObject)?.let(::reset)
    }

    private fun eval(n: JsonObject): R = when ((n["node"] as JsonPrimitive).content) {
        "sequence" -> children(n, R.OK, R.FAIL)
        "selector" -> children(n, R.FAIL, R.OK)
        "condition" -> {
            val v = readValue((n["value"] as JsonPrimitive).content)
            val c = (n["compare"] as JsonPrimitive).double
            val ok = v != null && when ((n["op"] as JsonPrimitive).content) { "<" -> v < c; ">" -> v > c; "<=" -> v <= c; else -> v >= c }
            if (ok) R.OK else R.FAIL
        }
        "wait" -> {
            val until = waits.getOrPut(n) { clock() + (n["ms"] as JsonPrimitive).double.toLong() }
            if (clock() >= until) { waits.remove(n); R.OK } else R.RUNNING
        }
        "stop_skill" -> { stopSkill(); R.OK }
        "skill" -> {
            if (started[n] != true) {
                started[n] = true
                if (runSkill((n["id"] as JsonPrimitive).content) != null) { started.remove(n); R.FAIL } else R.RUNNING
            } else if (skillRunning()) R.RUNNING else { started.remove(n); R.OK }
        }
        "repeat" -> {
            val child = n["child"] as JsonObject
            val times = (n["times"] as JsonPrimitive).double.toInt()
            when (eval(child)) {
                R.RUNNING -> R.RUNNING
                R.FAIL -> { reset(n); R.FAIL }
                R.OK -> {
                    reset(child)
                    val c = (counts[n] ?: 0) + 1
                    if (c >= times) { counts.remove(n); R.OK } else { counts[n] = c; R.RUNNING }
                }
            }
        }
        else -> R.FAIL
    }

    /** Sequences stop on the first failure. Selectors stop on the first success. */
    private fun children(n: JsonObject, passOn: R, stopOn: R): R {
        val kids = (n["children"] as JsonArray).map { it as JsonObject }
        var i = index[n] ?: 0
        // Bounded: at most one pass over the children per tick.
        while (i < kids.size) {
            when (eval(kids[i])) {
                R.RUNNING -> { index[n] = i; return R.RUNNING }
                stopOn -> { reset(n); return stopOn }
                else -> i++
            }
        }
        reset(n)
        return passOn
    }
}

/**
 * Simple on-phone vision: find where a bright or dark line crosses the bottom of the image.
 * Enough to follow a line or a mark without any model or network. Not object recognition.
 */
object LineTracker {
    /** Returns (found 0 or 1, position -1 left to 1 right), from a greyscale image. */
    fun track(y: ByteArray, width: Int, height: Int, dark: Boolean): DoubleArray {
        if (width <= 0 || height <= 0 || y.size < width * height) return doubleArrayOf(0.0, 0.0)
        val top = height * 3 / 4
        var sum = 0L
        var n = 0
        var lo = 255
        var hi = 0
        for (r in top until height step 2) for (c in 0 until width step 2) {
            val v = y[r * width + c].toInt() and 0xFF
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        if (hi - lo < MIN_CONTRAST) return doubleArrayOf(0.0, 0.0)
        val threshold = (hi + lo) / 2
        for (r in top until height step 2) for (c in 0 until width step 2) {
            val v = y[r * width + c].toInt() and 0xFF
            if ((dark && v < threshold) || (!dark && v > threshold)) { sum += c; n++ }
        }
        if (n == 0) return doubleArrayOf(0.0, 0.0)
        return doubleArrayOf(1.0, (sum.toDouble() / n / (width - 1)) * 2 - 1)
    }
    private const val MIN_CONTRAST = 40
}
