package com.smartphonex.core

import kotlinx.serialization.json.*
import java.io.File

/** What an actuator does when the device must be made safe. "Stop" isn't always the safe thing. */
data class SafeAction(val action: String = "neutral", val value: Double? = null)

data class ActuatorDef(
    val id: String,
    val driver: String = "preview",
    val transport: String? = null,
    val address: Int = 1,
    val unit: String = "norm",
    val min: Double = -1.0,
    val max: Double = 1.0,
    val neutral: Double = 0.0,
    val maxRate: Double = 2.0,
    val safe: SafeAction = SafeAction(),
    /** How much each named input moves this actuator, as a fraction of its range. */
    val mix: Map<String, Double> = emptyMap(),
    val feedback: List<String> = emptyList(),
)

/**
 * How to reach the device. For serial: [dataBits], [parity] (none, even, odd) and [stopBits] set the line, so SBUS can use
 * 100000 8E2. [inverted] means the line is idle-low, as SBUS is: only FTDI adapters set up with FT_PROG can do that alone.
 */
data class TransportDef(
    val id: String, val type: String, val baud: Int = 115200, val host: String? = null, val port: Int? = null, val template: String? = null,
    val dataBits: Int = 8, val parity: String = "none", val stopBits: Int = 1, val inverted: Boolean = false,
)
data class InputDef(val id: String, val driver: String, val transport: String, val map: Map<String, String>, val failsafeOnLoss: Boolean = true)
data class SensorDef(val id: String, val source: String)
data class ControllerDef(val id: String, val type: String, val gains: Map<String, Double> = emptyMap(), val params: JsonObject = JsonObject(emptyMap()))
data class SkillDef(val id: String, val type: String, val params: JsonObject = JsonObject(emptyMap()))
data class BehaviorDef(val id: String, val tree: JsonObject)
data class Commissioning(
    val stage: Int = 1,
    val tests: Map<String, String> = emptyMap(),
    val approvals: Set<String> = emptySet(),
    val signedOffBy: String? = null,
)

data class DeviceDescription(
    val id: String,
    val name: String,
    val deviceClass: String,
    val hazard: String,
    val transports: List<TransportDef> = emptyList(),
    val actuators: List<ActuatorDef> = emptyList(),
    val sensors: List<SensorDef> = emptyList(),
    val inputs: List<InputDef> = emptyList(),
    val controllers: List<ControllerDef> = emptyList(),
    val skills: List<SkillDef> = emptyList(),
    val behaviors: List<BehaviorDef> = emptyList(),
    val onLinkLoss: String? = null,
    val onFall: String? = null,
    val aiAutonomy: String = "supervised",
    val commissioning: Commissioning = Commissioning(),
)

object DeviceSchema {
    const val VERSION = "smartphonex.device/1"
    val CLASSES = setOf("fixed", "wheeled", "tracked", "water", "arm", "fixed-wing", "multi-legged", "biped", "multirotor", "other")
    val HAZARDS = listOf("toy", "light", "heavy", "flying")
    val AUTONOMY = listOf("none", "supervised", "unattended")
    val SAFE_ACTIONS = setOf("hold", "neutral", "limp", "value")
    val DRIVERS = setOf("preview", "crsf", "ibus", "lx16a", "feetech", "dynamixel2", "text")
    val TRANSPORTS = setOf("preview", "usb-uart", "tcp")
    val INPUT_DRIVERS = setOf("crsf", "ibus", "sbus")
    val SKILL_TYPES = setOf("set_inputs", "pose", "sequence", "gait", "stop")
    val CONTROLLER_TYPES = setOf("balance", "fall_detect")
    private val ID = Regex("^[a-zA-Z0-9_.-]{1,40}$")
    const val MAX_ACTUATORS = 64

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonObject.i(k: String) = d(k)?.toInt()
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.arr(k: String) = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.nums(k: String) = obj(k)?.entries?.mapNotNull { (a, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { a to it } }?.toMap() ?: emptyMap()
    private fun JsonObject.strs(k: String) = obj(k)?.entries?.mapNotNull { (a, v) -> (v as? JsonPrimitive)?.content?.let { a to it } }?.toMap() ?: emptyMap()

    /** Reads a description. Anything malformed comes back as readable errors, so an LLM can fix it. */
    fun parse(o: JsonObject): Pair<DeviceDescription?, List<String>> {
        val errors = ArrayList<String>()
        if (o.s("schema") != VERSION) errors += "schema must be \"$VERSION\""
        val name = o.s("name") ?: "".also { errors += "name is required" }
        val id = o.s("id") ?: name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
        val actuators = o.arr("actuators").map { a ->
            val safe = a.obj("safe")
            ActuatorDef(
                a.s("id") ?: "", a.s("driver") ?: "preview", a.s("transport"), a.i("address") ?: 1, a.s("unit") ?: "norm",
                a.d("min") ?: -1.0, a.d("max") ?: 1.0, a.d("neutral") ?: 0.0, a.d("max_rate") ?: 2.0,
                SafeAction(safe?.s("action") ?: "neutral", safe?.d("value")), a.nums("mix"),
                (a["feedback"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
            )
        }
        val c = o.obj("commissioning")
        val state = o.obj("safe_state")
        val d = DeviceDescription(
            id, name, o.s("class") ?: "other", o.s("hazard") ?: "light",
            o.arr("transports").map {
                TransportDef(it.s("id") ?: "", it.s("type") ?: "", it.i("baud") ?: 115200, it.s("host"), it.i("port"), it.s("template"),
                    it.i("data_bits") ?: 8, it.s("parity") ?: "none", it.i("stop_bits") ?: 1, (it["inverted"] as? JsonPrimitive)?.booleanOrNull ?: false)
            },
            actuators,
            o.arr("sensors").map { SensorDef(it.s("id") ?: "", it.s("source") ?: "") },
            o.arr("inputs").map { InputDef(it.s("id") ?: "", it.s("driver") ?: "", it.s("transport") ?: "", it.strs("map"), it.b("failsafe_on_loss") ?: true) },
            o.arr("controllers").map { ControllerDef(it.s("id") ?: "", it.s("type") ?: "", it.nums("gains"), it.obj("params") ?: JsonObject(emptyMap())) },
            o.arr("skills").map { SkillDef(it.s("id") ?: "", it.s("type") ?: "", it.obj("params") ?: JsonObject(emptyMap())) },
            o.arr("behaviors").map { BehaviorDef(it.s("id") ?: "", it.obj("tree") ?: JsonObject(emptyMap())) },
            state?.s("on_link_loss"), state?.s("on_fall"),
            o.obj("limits")?.s("ai_autonomy") ?: "supervised",
            if (c == null) Commissioning() else Commissioning(
                c.i("stage") ?: 1, c.strs("tests"),
                (c["approvals"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: emptySet(),
                c.s("signed_off_by"),
            ),
        )
        errors += validate(d)
        return (if (errors.isEmpty()) d else null) to errors
    }

    fun validate(d: DeviceDescription): List<String> {
        val e = ArrayList<String>()
        fun uniqueIds(what: String, ids: List<String>) {
            ids.filter { !ID.matches(it) }.forEach { e += "$what id \"$it\" must be 1-40 letters, digits, dots, dashes or underscores" }
            ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { e += "$what id \"$it\" is used twice" }
        }
        if (!ID.matches(d.id)) e += "device id \"${d.id}\" is invalid"
        if (d.name.isBlank() || d.name.length > 60) e += "name must be 1-60 characters"
        if (d.deviceClass !in CLASSES) e += "class must be one of $CLASSES"
        if (d.hazard !in HAZARDS) e += "hazard must be one of $HAZARDS"
        if (d.aiAutonomy !in AUTONOMY) e += "ai_autonomy must be one of $AUTONOMY"
        uniqueIds("transport", d.transports.map { it.id })
        uniqueIds("actuator", d.actuators.map { it.id })
        uniqueIds("input", d.inputs.map { it.id })
        uniqueIds("controller", d.controllers.map { it.id })
        uniqueIds("skill", d.skills.map { it.id })
        uniqueIds("behavior", d.behaviors.map { it.id })
        if (d.actuators.isEmpty()) e += "a device needs at least one actuator"
        if (d.actuators.size > MAX_ACTUATORS) e += "at most $MAX_ACTUATORS actuators"
        val transportIds = d.transports.map { it.id }.toSet()
        for (t in d.transports) {
            if (t.type !in TRANSPORTS) e += "transport ${t.id}: type must be one of $TRANSPORTS"
            if (t.baud !in 300..4_000_000) e += "transport ${t.id}: baud out of range"
            if (t.type == "tcp" && (t.host == null || t.port == null)) e += "transport ${t.id}: tcp needs host and port"
            if (t.template != null && t.template.length > 80) e += "transport ${t.id}: template too long"
            if (t.dataBits !in 5..8) e += "transport ${t.id}: data_bits must be 5 to 8"
            if (t.parity !in setOf("none", "even", "odd")) e += "transport ${t.id}: parity must be none, even or odd"
            if (t.stopBits !in 1..2) e += "transport ${t.id}: stop_bits must be 1 or 2"
            if (t.baud !in 300..4_000_000) e += "transport ${t.id}: baud must be 300 to 4000000"
        }
        for (a in d.actuators) {
            val n = "actuator ${a.id}"
            if (a.driver !in DRIVERS) e += "$n: driver must be one of $DRIVERS"
            if (a.driver != "preview" && a.transport !in transportIds) e += "$n: transport \"${a.transport}\" isn't defined"
            if (a.address !in 0..253) e += "$n: address must be 0-253"
            if (!(a.min.isFinite() && a.max.isFinite()) || a.min >= a.max) e += "$n: min must be below max"
            if (a.neutral !in a.min..a.max) e += "$n: neutral must be between min and max"
            if (!a.maxRate.isFinite() || a.maxRate <= 0) e += "$n: max_rate must be above 0"
            if (a.safe.action !in SAFE_ACTIONS) e += "$n: safe action must be one of $SAFE_ACTIONS"
            if (a.safe.action == "value" && (a.safe.value == null || a.safe.value !in a.min..a.max)) e += "$n: safe value must be between min and max"
            if (a.mix.values.any { !it.isFinite() || it !in -2.0..2.0 }) e += "$n: mix gains must be -2 to 2"
        }
        val actuatorIds = d.actuators.map { it.id }.toSet()
        for (i in d.inputs) {
            if (i.driver !in INPUT_DRIVERS) e += "input ${i.id}: driver must be one of $INPUT_DRIVERS"
            if (i.transport !in transportIds) e += "input ${i.id}: transport \"${i.transport}\" isn't defined"
        }
        // A receiver and output device at different speeds can't share one serial port.
        for (i in d.inputs) if (d.actuators.any { it.transport == i.transport && it.driver != i.driver }) {
            e += "input ${i.id}: shares transport ${i.transport} with an output using a different protocol. Use a second adapter on a USB hub"
        }
        for (c in d.controllers) if (c.type !in CONTROLLER_TYPES) e += "controller ${c.id}: type must be one of $CONTROLLER_TYPES"
        for (s in d.skills) {
            if (s.type !in SKILL_TYPES) e += "skill ${s.id}: type must be one of $SKILL_TYPES"
            jointsIn(s).filter { it !in actuatorIds }.forEach { e += "skill ${s.id}: unknown joint \"$it\"" }
        }
        val skillIds = d.skills.map { it.id }.toSet()
        for (name in listOfNotNull(d.onLinkLoss, d.onFall)) if (name !in skillIds && name !in setOf("hold", "stop")) e += "safe_state refers to unknown skill \"$name\""
        for (b in d.behaviors) BehaviorTree.validate(b.tree, skillIds).forEach { e += "behavior ${b.id}: $it" }
        return e
    }

    private fun jointsIn(s: SkillDef): Set<String> {
        val out = HashSet<String>()
        fun scan(o: JsonObject) { o.obj("joints")?.keys?.let { out += it } }
        // A gait's map goes from role to joint, so it's the values that have to exist.
        if (s.type == "gait") {
            s.params.obj("joints")?.values?.mapNotNull { (it as? JsonPrimitive)?.content }?.let { out += it }
            return out
        }
        scan(s.params)
        (s.params["steps"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(::scan) }
        return out
    }

    fun toJson(d: DeviceDescription) = buildJsonObject {
        put("schema", VERSION); put("id", d.id); put("name", d.name); put("class", d.deviceClass); put("hazard", d.hazard)
        putJsonArray("transports") { d.transports.forEach { t -> addJsonObject {
            put("id", t.id); put("type", t.type); put("baud", t.baud); t.host?.let { put("host", it) }; t.port?.let { put("port", it) }; t.template?.let { put("template", it) }
            if (t.dataBits != 8) put("data_bits", t.dataBits)
            if (t.parity != "none") put("parity", t.parity)
            if (t.stopBits != 1) put("stop_bits", t.stopBits)
            if (t.inverted) put("inverted", true)
        } } }
        putJsonArray("actuators") { d.actuators.forEach { a -> addJsonObject {
            put("id", a.id); put("driver", a.driver); a.transport?.let { put("transport", it) }; put("address", a.address); put("unit", a.unit)
            put("min", a.min); put("max", a.max); put("neutral", a.neutral); put("max_rate", a.maxRate)
            putJsonObject("safe") { put("action", a.safe.action); a.safe.value?.let { put("value", it) } }
            putJsonObject("mix") { a.mix.forEach { (k, v) -> put(k, v) } }
            putJsonArray("feedback") { a.feedback.forEach { add(it) } }
        } } }
        putJsonArray("sensors") { d.sensors.forEach { s -> addJsonObject { put("id", s.id); put("source", s.source) } } }
        putJsonArray("inputs") { d.inputs.forEach { i -> addJsonObject {
            put("id", i.id); put("driver", i.driver); put("transport", i.transport); put("failsafe_on_loss", i.failsafeOnLoss)
            putJsonObject("map") { i.map.forEach { (k, v) -> put(k, v) } }
        } } }
        putJsonArray("controllers") { d.controllers.forEach { c -> addJsonObject {
            put("id", c.id); put("type", c.type); putJsonObject("gains") { c.gains.forEach { (k, v) -> put(k, v) } }; put("params", c.params)
        } } }
        putJsonArray("skills") { d.skills.forEach { s -> addJsonObject { put("id", s.id); put("type", s.type); put("params", s.params) } } }
        putJsonArray("behaviors") { d.behaviors.forEach { b -> addJsonObject { put("id", b.id); put("tree", b.tree) } } }
        putJsonObject("safe_state") { d.onLinkLoss?.let { put("on_link_loss", it) }; d.onFall?.let { put("on_fall", it) } }
        putJsonObject("limits") { put("ai_autonomy", d.aiAutonomy) }
        putJsonObject("commissioning") {
            put("stage", d.commissioning.stage)
            putJsonObject("tests") { d.commissioning.tests.forEach { (k, v) -> put(k, v) } }
            putJsonArray("approvals") { d.commissioning.approvals.forEach { add(it) } }
            put("signed_off_by", d.commissioning.signedOffBy)
        }
    }

    /** A shared pack starts over at the safety stages: someone else's tests say nothing about your hardware. */
    fun forImport(d: DeviceDescription) = d.copy(commissioning = Commissioning(stage = minOf(d.commissioning.stage, 4), tests = emptyMap(), approvals = emptySet(), signedOffBy = null))

    /** Bring a joint structure in from URDF. Output drivers and safety data still need setting up. */
    fun fromUrdf(xml: String, name: String): DeviceDescription {
        // A URDF is untrusted input: no external entities or doctype tricks. Android's parser doesn't
        // offer the setting that blocks these, so they're refused before it ever sees the file.
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) throw IllegalArgumentException("URDF files can't contain a DOCTYPE or ENTITY")
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val joints = doc.getElementsByTagName("joint")
        val actuators = ArrayList<ActuatorDef>()
        for (i in 0 until joints.length) {
            val j = joints.item(i) as? org.w3c.dom.Element ?: continue
            val type = j.getAttribute("type")
            if (type != "revolute" && type != "continuous" && type != "prismatic") continue
            val limit = j.getElementsByTagName("limit").item(0) as? org.w3c.dom.Element
            val rad = type != "prismatic"
            fun conv(a: String, dflt: Double): Double {
                val v = limit?.getAttribute(a)?.toDoubleOrNull() ?: return dflt
                return if (rad) Math.toDegrees(v) else v
            }
            val lo = if (type == "continuous") -180.0 else conv("lower", -90.0)
            val hi = if (type == "continuous") 180.0 else conv("upper", 90.0)
            val rate = conv("velocity", 90.0)
            val id = j.getAttribute("name").replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(40)
            actuators += ActuatorDef(id, unit = if (rad) "deg" else "m", min = lo, max = hi, neutral = 0.0.coerceIn(lo, hi), maxRate = if (rate > 0) rate else 90.0, safe = SafeAction("hold"))
        }
        val cleanName = name.take(60)
        return DeviceDescription(cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "urdf-device" }.take(40), cleanName, "other", "light", actuators = actuators)
    }

    /** Whether this phone is fast enough for a class. Measured, not assumed. */
    fun fitProblem(deviceClass: String, gyroHz: Double, loopHz: Double): String? = when (deviceClass) {
        "multirotor" -> if (gyroHz < 400 || loopHz < 400) "a multirotor needs about 400 Hz sensing and control. This phone gives ${gyroHz.toInt()} and ${loopHz.toInt()} Hz" else null
        "fixed-wing", "biped" -> if (gyroHz < 100 || loopHz < 50) "this class needs at least 100 Hz gyro and 50 Hz control. This phone gives ${gyroHz.toInt()} and ${loopHz.toInt()} Hz" else null
        else -> null
    }
}

/** Saved devices, one file each. */
class DeviceStore(private val dir: File?) {
    private val devices = linkedMapOf<String, DeviceDescription>()

    init {
        dir?.mkdirs()
        dir?.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            runCatching { DeviceSchema.parse(Json.parseToJsonElement(f.readText()).jsonObject).first?.let { devices[it.id] = it } }
        }
    }

    @Synchronized fun all(): List<DeviceDescription> = devices.values.toList()
    @Synchronized fun get(id: String) = devices[id]

    @Synchronized fun put(d: DeviceDescription) {
        devices[d.id] = d
        val f = File(dir ?: return, "${d.id}.json")
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(DeviceSchema.toJson(d).toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
