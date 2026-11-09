package com.smartphonex.core

import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull

private fun atomicWrite(f: File, text: String) {
    f.parentFile?.mkdirs()
    val tmp = File(f.parentFile, f.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
}

data class OutputTrim(val trim: Double = 0.0, val reverse: Boolean = false, val lo: Double = -1.0, val hi: Double = 1.0) {
    /** Applies to a value from -1 to 1. Output limits always win. */
    fun apply(v: Double, hardLo: Double = -1.0, hardHi: Double = 1.0): Double =
        ((if (reverse) -v else v) + trim).coerceIn(maxOf(lo, hardLo), minOf(hi, hardHi))
}

/** Output trim, reverse and endpoints, per vehicle, with an undo history. */
class TrimStore(private val file: File?, private val vehicle: () -> String, private val io: (() -> Unit) -> Unit = { it() }) {
    private val data = HashMap<String, HashMap<String, OutputTrim>>()
    private val history = HashMap<String, ArrayDeque<OutputTrim>>()

    init {
        runCatching {
            Json.parseToJsonElement(file!!.readText()).jsonObject.forEach { (v, o) ->
                val m = data.getOrPut(v) { HashMap() }
                o.jsonObject.forEach { (name, t) ->
                    val j = t.jsonObject
                    m[name] = OutputTrim(j.d("trim") ?: 0.0, j.b("reverse") ?: false, j.d("lo") ?: -1.0, j.d("hi") ?: 1.0)
                }
            }
        }
    }

    @Synchronized fun get(name: String) = data[vehicle()]?.get(name) ?: OutputTrim()

    @Synchronized fun set(name: String, t: OutputTrim) {
        history.getOrPut("${vehicle()}/$name") { ArrayDeque() }.apply { addLast(get(name)); if (size > 20) removeFirst() }
        data.getOrPut(vehicle()) { HashMap() }[name] = t
        save()
    }

    @Synchronized fun undo(name: String): Boolean {
        val prev = history["${vehicle()}/$name"]?.removeLastOrNull() ?: return false
        data.getOrPut(vehicle()) { HashMap() }[name] = prev
        save()
        return true
    }

    @Synchronized fun json(names: List<String>) = buildJsonObject {
        names.forEach { n ->
            val t = get(n)
            putJsonObject(n) { put("trim", t.trim); put("reverse", t.reverse); put("lo", t.lo); put("hi", t.hi) }
        }
    }

    private fun save() {
        val f = file ?: return
        val text = buildJsonObject {
            data.forEach { (v, m) -> putJsonObject(v) { m.forEach { (n, t) -> putJsonObject(n) { put("trim", t.trim); put("reverse", t.reverse); put("lo", t.lo); put("hi", t.hi) } } } }
        }.toString()
        io { atomicWrite(f, text) }
    }
}

/** Hold to run: an output test lasts only while the person keeps pressing. */
class OutputTester(private val clock: () -> Long) {
    private var owner: Source? = null
    private var output: String? = null
    private var value = 0.0
    private var lastBeat = 0L

    @Synchronized fun hold(source: Source, out: String, v: Double): String? {
        val now = clock()
        if (owner != null && owner != source && now - lastBeat <= HOLD_MS) return "$owner is running a test"
        owner = source; output = out; value = v; lastBeat = now
        return null
    }
    @Synchronized fun stop() { owner = null; output = null }
    @Synchronized fun active(): Pair<String, Double>? {
        val o = output ?: return null
        if (clock() - lastBeat > HOLD_MS) { owner = null; output = null; return null }
        return o to value
    }
    @Synchronized fun status() = active()?.let { (o, v) -> buildJsonObject { put("output", o); put("value", v); put("owner", owner?.name) } }
    private companion object { const val HOLD_MS = 400L }
}

data class TriggerAction(val type: String, val output: String? = null, val value: Double = 0.0, val ms: Long = 500, val skill: String? = null, val text: String? = null)
data class TriggerDef(
    val id: String, val label: String, val action: TriggerAction,
    val danger: Boolean = false, val armedOnly: Boolean = true, val cooldownMs: Long = 500, val maxCount: Int = 0, val aiAllowed: Boolean = false,
    val everyMeters: Double = 0.0, val everySeconds: Double = 0.0,
    val sensor: String? = null, val op: String? = null, val compare: Double = 0.0,
)

object TriggerCodec {
    private val ID = Regex("^[a-zA-Z0-9_.-]{1,40}$")
    val ACTIONS = setOf("photo", "marker", "alert", "pulse", "set", "skill")

    fun parse(o: JsonObject): Pair<TriggerDef?, String?> {
        val id = o.s("id") ?: return null to "trigger id is required"
        if (!ID.matches(id)) return null to "trigger id must be short, with letters, digits, dots, dashes or underscores"
        val a = o["action"] as? JsonObject ?: return null to "action is required"
        val type = a.s("type") ?: ""
        if (type !in ACTIONS) return null to "action type must be one of $ACTIONS"
        val value = a.d("value") ?: 0.0
        if (abs(value) > 1.0) return null to "output value must be -1 to 1"
        val ms = (a.d("ms") ?: 500.0).toLong()
        if (ms !in 1..60000) return null to "ms must be 1 to 60000"
        if ((type == "pulse" || type == "set") && a.s("output") == null) return null to "this action needs an output"
        if (type == "skill" && a.s("skill") == null) return null to "this action needs a skill"
        val op = o.s("op")
        if (op != null && op !in setOf("<", ">")) return null to "op must be < or >"
        val every = o.d("everyMeters") ?: 0.0
        val sec = o.d("everySeconds") ?: 0.0
        if (every !in 0.0..100000.0 || sec !in 0.0..86400.0) return null to "interval out of range"
        val danger = o.b("danger") ?: false
        if (danger && (every > 0 || sec > 0 || o.s("sensor") != null)) return null to "a dangerous trigger can't fire by itself. Fire it by hand, or from a point in a mission"
        return TriggerDef(
            id, o.s("label")?.take(40) ?: id, TriggerAction(type, a.s("output"), value, ms, a.s("skill"), a.s("text")?.take(120)),
            danger, o.b("armedOnly") ?: true, (o.d("cooldownMs") ?: 500.0).toLong().coerceIn(0, 3600000), (o.d("maxCount") ?: 0.0).toInt().coerceAtLeast(0),
            // A dangerous trigger is never the AI's to fire, whatever the settings say.
            (o.b("aiAllowed") ?: false) && !danger, every, sec, o.s("sensor"), op, o.d("compare") ?: 0.0,
        ) to null
    }

    fun json(t: TriggerDef) = buildJsonObject {
        put("id", t.id); put("label", t.label); put("danger", t.danger); put("armedOnly", t.armedOnly); put("cooldownMs", t.cooldownMs)
        put("maxCount", t.maxCount); put("aiAllowed", t.aiAllowed); put("everyMeters", t.everyMeters); put("everySeconds", t.everySeconds)
        put("sensor", t.sensor); put("op", t.op); put("compare", t.compare)
        putJsonObject("action") { put("type", t.action.type); put("output", t.action.output); put("value", t.action.value); put("ms", t.action.ms); put("skill", t.action.skill); put("text", t.action.text) }
    }
}

data class MissionItem(val lat: Double, val lon: Double, val alt: Double? = null, val throttle: Double? = null, val holdS: Double = 0.0, val radiusM: Double = 5.0, val triggers: List<String> = emptyList())
data class Mission(val name: String, val items: List<MissionItem>, val onEnd: String = "hold", val onLinkLoss: String = "continue")
data class Fence(val polygon: List<Pair<Double, Double>> = emptyList(), val keepOut: List<List<Pair<Double, Double>>> = emptyList(), val maxDistM: Double = 0.0, val maxAltM: Double = 0.0, val action: String = "hold") {
    val isSet: Boolean get() = polygon.size >= 3 || keepOut.isNotEmpty() || maxDistM > 0 || maxAltM > 0
}

object Geo {
    fun inside(lat: Double, lon: Double, poly: List<Pair<Double, Double>>): Boolean {
        var odd = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (yi, xi) = poly[i]; val (yj, xj) = poly[j]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) odd = !odd
            j = i
        }
        return odd
    }

    /** Shortest distance in metres from a point to a polygon's edge. Flat-earth maths, fine at fence sizes. */
    fun edgeDistanceM(lat: Double, lon: Double, poly: List<Pair<Double, Double>>): Double {
        val kLat = 111_320.0
        val kLon = kLat * kotlin.math.cos(Math.toRadians(lat))
        var best = Double.MAX_VALUE
        for (i in poly.indices) {
            val (aLat, aLon) = poly[i]; val (bLat, bLon) = poly[(i + 1) % poly.size]
            val ax = (aLon - lon) * kLon; val ay = (aLat - lat) * kLat
            val bx = (bLon - lon) * kLon; val by = (bLat - lat) * kLat
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            best = minOf(best, kotlin.math.sqrt(px * px + py * py))
        }
        return best
    }

    /**
     * Why this position breaks the fence, or null. [marginM] is how unsure the position is: the vehicle could be
     * anywhere within it, so being inside by less than that counts as a breach. Planning checks pass 0.
     */
    fun breach(f: Fence, lat: Double, lon: Double, alt: Double?, home: GpsFix?, marginM: Double = 0.0): String? {
        if (f.polygon.size >= 3 && !inside(lat, lon, f.polygon)) return "outside the fence"
        if (marginM > 0 && f.polygon.size >= 3 && edgeDistanceM(lat, lon, f.polygon) < marginM) return "within ${marginM.toInt()} m of the fence, closer than GPS can tell"
        f.keepOut.forEachIndexed { i, p -> if (p.size >= 3 && inside(lat, lon, p)) return "inside keep-out area ${i + 1}" }
        f.keepOut.forEachIndexed { i, p -> if (marginM > 0 && p.size >= 3 && edgeDistanceM(lat, lon, p) < marginM) return "within ${marginM.toInt()} m of keep-out area ${i + 1}, closer than GPS can tell" }
        if (f.maxDistM > 0 && home != null && Navigation.distanceM(home.lat, home.lon, lat, lon) + marginM > f.maxDistM) return "more than ${f.maxDistM.toInt()} m from home" + if (marginM > 0) ", allowing for GPS" else ""
        if (f.maxAltM > 0 && home != null && alt != null && alt - home.altM > f.maxAltM) return "above ${f.maxAltM.toInt()} m"
        return null
    }

    private fun points(a: JsonArray?) = a?.mapNotNull { p -> (p as? JsonArray)?.takeIf { it.size >= 2 }?.let { it[0].jsonPrimitive.double to it[1].jsonPrimitive.double } } ?: emptyList()
    fun parseFence(o: JsonObject) = Fence(points(o["polygon"] as? JsonArray), (o["keepOut"] as? JsonArray)?.map { points(it as? JsonArray) } ?: emptyList(), o.d("maxDistM") ?: 0.0, o.d("maxAltM") ?: 0.0, o.s("action") ?: "hold")
    fun fenceJson(f: Fence) = buildJsonObject {
        fun arr(p: List<Pair<Double, Double>>) = JsonArray(p.map { (a, b) -> buildJsonArray { add(a); add(b) } })
        put("polygon", arr(f.polygon)); put("keepOut", JsonArray(f.keepOut.map(::arr))); put("maxDistM", f.maxDistM); put("maxAltM", f.maxAltM); put("action", f.action)
    }

    fun parseMission(o: JsonObject): Pair<Mission?, List<String>> {
        val e = ArrayList<String>()
        val items = (o["items"] as? JsonArray)?.mapIndexedNotNull { i, x ->
            val j = x as? JsonObject
            val lat = j?.d("lat"); val lon = j?.d("lon")
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) { e += "point ${i + 1}: needs a valid position"; null }
            else MissionItem(lat, lon, j.d("alt"), j.d("throttle"), j.d("holdS") ?: 0.0, j.d("radiusM") ?: 5.0, (j["triggers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList())
        } ?: emptyList()
        if (items.isEmpty() && e.isEmpty()) e += "a mission needs at least one point"
        if (items.size > 500) e += "at most 500 points"
        val m = Mission(o.s("name")?.take(60) ?: "Mission", items, o.s("onEnd") ?: "hold", o.s("onLinkLoss") ?: "continue")
        if (m.onEnd !in setOf("hold", "home", "stop")) e += "at the end must be hold, home or stop"
        if (m.onLinkLoss !in setOf("continue", "hold", "home")) e += "on link loss must be continue, hold or home"
        return (if (e.isEmpty()) m else null) to e
    }

    fun missionJson(m: Mission) = buildJsonObject {
        put("name", m.name); put("onEnd", m.onEnd); put("onLinkLoss", m.onLinkLoss)
        putJsonArray("items") {
            m.items.forEach { i -> addJsonObject {
                put("lat", i.lat); put("lon", i.lon); put("alt", i.alt); put("throttle", i.throttle); put("holdS", i.holdS); put("radiusM", i.radiusM)
                putJsonArray("triggers") { i.triggers.forEach { add(it) } }
            } }
        }
    }

    /** Everything wrong with a mission. These block it from running. */
    fun validate(m: Mission, f: Fence, home: GpsFix?, triggers: Set<String>, maxThrottle: Double): List<String> {
        val e = ArrayList<String>()
        m.items.forEachIndexed { i, p ->
            val n = "Point ${i + 1}"
            Geo.breach(f, p.lat, p.lon, p.alt?.plus(home?.altM ?: 0.0), home)?.let { e += "$n is $it" }
            if (p.radiusM !in 1.0..1000.0) e += "$n: arrival radius must be 1 to 1000 m"
            if (p.holdS !in 0.0..3600.0) e += "$n: hold must be 0 to 3600 s"
            p.throttle?.let { if (abs(it) > maxThrottle) e += "$n: throttle is over this vehicle's limit of $maxThrottle" }
            p.triggers.filter { it !in triggers }.forEach { e += "$n: unknown trigger $it" }
            if (i > 0) {
                val q = m.items[i - 1]
                for (s in 1..9) {
                    val la = q.lat + (p.lat - q.lat) * s / 10
                    val lo = q.lon + (p.lon - q.lon) * s / 10
                    val r = Geo.breach(f, la, lo, null, home)
                    if (r != null) { e += "The leg to point ${i + 1} goes $r"; break }
                }
            }
        }
        return e
    }
}

/** Follows a mission on the phone, so it carries on if the ground station goes away. */
class MissionEngine(private val clock: () -> Long) {
    enum class State { IDLE, RUNNING, PAUSED, DONE, ABORTED }
    sealed class Step {
        class GoTo(val lat: Double, val lon: Double, val throttle: Double?) : Step()
        data object Hold : Step()
        class End(val action: String) : Step()
    }

    @Volatile var mission: Mission? = null; private set
    @Volatile var state = State.IDLE; private set
    @Volatile var index = 0; private set
    private var holdUntil = 0L
    private var arrived = false
    @Volatile var lastDist: Double? = null; private set
    @Volatile var lastBearing: Double? = null; private set
    var onArrive: (MissionItem, Int) -> Unit = { _, _ -> }

    @Synchronized fun load(m: Mission) { mission = m; state = State.IDLE; index = 0; arrived = false }
    @Synchronized fun start() { state = State.RUNNING; index = 0; arrived = false; holdUntil = 0 }
    @Synchronized fun pause() { if (state == State.RUNNING) state = State.PAUSED }
    @Synchronized fun resume() { if (state == State.PAUSED) state = State.RUNNING }
    @Synchronized fun abort() { if (state == State.RUNNING || state == State.PAUSED) state = State.ABORTED }
    @Synchronized fun skip() { if (state == State.RUNNING) { index++; arrived = false; holdUntil = 0 } }
    @Synchronized fun guided(lat: Double, lon: Double) { load(Mission("Go here", listOf(MissionItem(lat, lon)))); start() }

    /** The point being headed for, while running. */
    @Synchronized fun currentItem(): MissionItem? = if (state == State.RUNNING) mission?.items?.getOrNull(index) else null

    @Synchronized fun step(g: GpsFix?): Step {
        val m = mission ?: return Step.Hold
        if (state != State.RUNNING || g == null) return Step.Hold
        val p = m.items.getOrNull(index) ?: run { state = State.DONE; return Step.End(m.onEnd) }
        val dist = Navigation.distanceM(g.lat, g.lon, p.lat, p.lon)
        lastDist = dist
        lastBearing = Navigation.bearingDeg(g.lat, g.lon, p.lat, p.lon)
        if (!arrived && dist <= p.radiusM) {
            arrived = true
            holdUntil = clock() + (p.holdS * 1000).toLong()
            onArrive(p, index)
        }
        if (arrived) {
            if (clock() < holdUntil) return Step.Hold
            index++
            arrived = false
            return step(g)
        }
        return Step.GoTo(p.lat, p.lon, p.throttle)
    }

    fun json() = buildJsonObject {
        val m = mission
        put("state", state.name); put("name", m?.name); put("index", index); put("total", m?.items?.size ?: 0)
        put("dist", lastDist?.let { Math.round(it * 10) / 10.0 }); put("bearing", lastBearing?.let { Math.round(it).toDouble() })
        put("onLinkLoss", m?.onLinkLoss)
    }
}

/**
 * The operating layer around the controller: readiness, tests, trim, triggers, missions and fences.
 * All rules live here on the phone. Screens only ask.
 */
class Ops(private val rt: VehicleRuntime, private val dir: File) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ops").apply { isDaemon = true } }
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "ops-store").apply { isDaemon = true } }
    val trims = TrimStore(File(dir, "trims.json"), { rt.profiles.activeId }, { job -> writer.execute(job) })
    val tester = OutputTester(System::currentTimeMillis)
    val groundRun = GroundRun(System::currentTimeMillis)
    val groundRuns = GroundRunStore(File(dir, "groundrun.json")) { job -> writer.execute(job) }
    /** Ends every hold-to-run test: arming, AUTO and kill all do. */
    fun stopTests() { tester.stop(); groundRun.stop("stopped: armed") }
    /** Nothing is being driven by a test, so the microphone may learn background sounds. */
    fun outputsAtRest() = tester.active() == null && !groundRun.running()
    val missions = MissionEngine(System::currentTimeMillis)
    private fun store(f: File, text: String) = writer.execute { runCatching { atomicWrite(f, text) } }
    /** Waits for queued saves. For shutdown and tests. */
    fun flush() { runCatching { writer.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS) } }
    private val triggers = HashMap<String, LinkedHashMap<String, TriggerDef>>()
    private val fences = HashMap<String, Fence>()
    private val triggerFile = File(dir, "triggers.json")
    private val fenceFile = File(dir, "fences.json")
    private val missionFile = File(dir, "mission.json")
    private val fired = HashMap<String, Long>()
    private val counts = HashMap<String, Int>()
    private val prepared = HashMap<String, Long>()
    // Read by the control loop, so it has its own thread-safe map rather than this object's lock.
    private val overrides = java.util.concurrent.ConcurrentHashMap<String, Pair<Double, Long>>()
    private val checklist = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var wasArmed = false
    private var lastDistFix: GpsFix? = null
    private val distAccum = HashMap<String, Double>()
    private var breachReason: String? = null
    private var linkLossApplied = false
    @Volatile private var linkLastMs = 0L
    /** Lets the Android side add location to a saved photo's EXIF. */
    @Volatile var geotag: ((File, GpsFix) -> Unit)? = null
    private val photoDir = File(dir, "photos")
    val videoDir = File(dir, "video")
    @Volatile var lastDiag: JsonObject? = null; private set
    /** An expression someone asked the face to show, and until when. Read by the phone app. */
    @Volatile var faceRequest: Pair<Face.Expr, Long>? = null
    /** Show (true) or hide the face, from a ground station or AI, and when it was asked. The phone app acts on it. */
    @Volatile var faceCommand: Pair<Boolean, Long>? = null
    /** What the face is doing now, reported by the phone app for the ground station. */
    @Volatile var faceStatus: JsonObject? = null
    /** Extra rows for Connected from the Android side: app health and updates. */
    @Volatile var appChecks: List<() -> Check?> = emptyList()
    @Volatile var diagRunning: String? = null; private set
    private val clientTests = HashMap<String, Pair<String, String>>()
    private val reportsDir = File(dir, "tests")
    @Volatile var gamepad: String? = null

    init {
        missions.onArrive = { item, i ->
            rt.log.event("mission", "phone", "arrived", "reached point ${i + 1}")
            item.triggers.forEach { fire(it, Source.BEHAVIOR, "waypoint ${i + 1}") }
        }
        runCatching {
            Json.parseToJsonElement(triggerFile.readText()).jsonObject.forEach { (v, a) ->
                val m = triggers.getOrPut(v) { LinkedHashMap() }
                a.jsonArray.forEach { x -> TriggerCodec.parse(x.jsonObject).first?.let { m[it.id] = it } }
            }
        }
        runCatching { Json.parseToJsonElement(fenceFile.readText()).jsonObject.forEach { (v, o) -> fences[v] = Geo.parseFence(o.jsonObject) } }
        runCatching { Geo.parseMission(Json.parseToJsonElement(missionFile.readText()).jsonObject).first?.let { missions.load(it) } }
        runCatching { Json.parseToJsonElement(paramHistoryFile.readText()).jsonArray.forEach { paramHistory.addLast(it.jsonObject) } }
        runCatching { lastDiag = reportsDir.listFiles()?.maxByOrNull { it.name }?.let { Json.parseToJsonElement(it.readText()).jsonObject } }
    }

    private val vehicle: String get() = rt.profiles.activeId
    private fun defaultTriggers() = linkedMapOf(
        "photo" to TriggerDef("photo", "Take photo", TriggerAction("photo"), armedOnly = false, cooldownMs = 1000, aiAllowed = true),
        "mark" to TriggerDef("mark", "Mark in log", TriggerAction("marker", text = "Marker"), armedOnly = false, cooldownMs = 200, aiAllowed = true),
    )
    // Lock order: controller, then flight controller, then mission, then this. Never the reverse,
    // so nothing holding this object's lock may call the controller or flight controller.
    @Synchronized private fun triggerMap() = triggers.getOrPut(vehicle) { defaultTriggers() }
    @Synchronized private fun triggerIds() = triggerMap().keys.toSet()
    val fence: Fence @Synchronized get() = fences[vehicle] ?: Fence()

    private fun saveTriggers() = store(triggerFile, buildJsonObject { triggers.forEach { (v, m) -> put(v, JsonArray(m.values.map(TriggerCodec::json))) } }.toString())
    private fun saveFences() = store(fenceFile, buildJsonObject { fences.forEach { (v, f) -> put(v, Geo.fenceJson(f)) } }.toString())

    private val paramHistoryFile = File(dir, "params-history.json")
    private val paramHistory = ArrayDeque<JsonObject>()

    /** Remembers each settings change, so it can be compared and undone later. */
    @Synchronized fun recordParam(name: String, old: Double, new: Double) {
        paramHistory.addFirst(buildJsonObject { put("t", System.currentTimeMillis()); put("device", vehicle); put("name", name); put("old", old); put("value", new) })
        while (paramHistory.size > 100) paramHistory.removeLast()
        store(paramHistoryFile, JsonArray(paramHistory.toList()).toString())
    }
    @Synchronized fun paramHistoryJson() = JsonArray(paramHistory.filter { (it["device"] as? JsonPrimitive)?.content == vehicle })

    fun touchLink() { linkLastMs = System.currentTimeMillis(); linkLossApplied = false }

    // --- Readiness: one source of truth for arming, on every screen.

    data class Check(val id: String, val label: String, val status: String, val detail: String, val fix: String = "")

    fun readiness(): List<Check> {
        val fc = rt.fc
        val out = ArrayList<Check>()
        val mode = fc.mode
        val h = fc.healthMap()
        val assisted = mode != FlightMode.MANUAL
        fun sensor(id: String, label: String, need: Boolean, fix: String) {
            val st = h[id] ?: "none"
            val okNow = st == "ok"
            out += Check(id, label, if (okNow) "ok" else if (need) "fail" else "warn", if (okNow) "Working" else if (st == "none") "Not available on this device" else "No data", if (okNow) "" else fix)
        }
        sensor("gyro", "Gyroscope", assisted, "This mode needs a gyroscope. Use Manual")
        sensor("accel", "Accelerometer", assisted, "This mode needs an accelerometer. Use Manual")
        if (assisted) sensor("attitude", "Attitude", true, "Hold still for a few seconds")
        if (h["compass"] != "ok") out += Check("compass", "Compass", "warn", "Heading comes from GPS while moving", "")
        val needGps = mode == FlightMode.RETURN_HOME || mode == FlightMode.AUTO
        val gps = h["gps"]
        out += Check("gps", "GPS", when { gps == "ok" -> "ok"; needGps -> "fail"; else -> "warn" }, fc.gpsText() ?: "No usable position", if (gps == "ok") weakSignalFix() else "Turn on Location under Sensors, and go outside")
        val fixNow = fc.gpsNow
        val tightest = missions.mission?.items?.minOfOrNull { it.radiusM }
        val navNow = fc.navEstimate()
        if (fixNow != null && navNow != null && tightest != null && navNow.radiusM > tightest) {
            out += Check("gpsfit", "Waypoint accuracy", "warn", "GPS is good to ±${navNow.radiusM.toInt()} m, but the tightest arrival radius is ${tightest.toInt()} m. The mission won't start until it fits", "Use a larger radius, or wait for a better position")
        }
        out += Check("battery", "Battery", if (h["battery"] == "bad") "fail" else "ok", fc.batteryText(), if (h["battery"] == "bad") "Charge the phone" else "")
        out += Check("heat", "Temperature", if (h["heat"] == "bad") "warn" else "ok", if (h["heat"] == "bad") "Phone is overheating" else "Normal", if (h["heat"] == "bad") "Let it cool down" else "")
        out += Check("timing", "Control timing", if (h["timing"] == "bad") "warn" else "ok", if (h["timing"] == "bad") "The loop had a long stall" else "Steady")
        val f = fence
        if (f.isSet && gps != "ok") out += Check("fence", "Geofence", "fail", "A fence is set, but there's no position to check it against", "Get a GPS position, or clear the fence")
        if (mode == FlightMode.AUTO) {
            val m = missions.mission
            if (m == null) out += Check("mission", "Mission", "fail", "No mission loaded", "Upload one in Plan")
            else {
                val errs = Geo.validate(m, f, fc.homeNow, triggerIds(), rt.profiles.active.maxThrottle)
                out += Check("mission", "Mission", if (errs.isEmpty()) "ok" else "fail", errs.firstOrNull() ?: "${m.items.size} points, checked", if (errs.isEmpty()) "" else "Fix it in Plan")
            }
        }
        if (rt.params.get("ARM_REQ_TEST") >= 1.0) {
            val fresh = lastDiag?.let { (it["device"] as? JsonPrimitive)?.content == vehicle && (it["passed"] as? JsonPrimitive)?.boolean == true && System.currentTimeMillis() - it["t"]!!.jsonPrimitive.long < 3600_000 } == true
            out += Check("test", "Tests", if (fresh) "ok" else "fail", if (fresh) "Passed within the last hour" else "This vehicle requires a passing test first", if (fresh) "" else "Run the tests in Test")
        }
        groundRunCheck()?.let { out += it }
        val items = checklistItems()
        if (rt.params.get("ARM_REQ_CHECK") >= 1.0) {
            val missing = items.filter { it !in checklist }
            out += Check("checklist", "Checklist", if (missing.isEmpty()) "ok" else "fail", if (missing.isEmpty()) "All ticked" else "${missing.size} left", if (missing.isEmpty()) "" else "Finish it in Test")
        }
        rt.engine.device?.takeIf { rt.profiles.active.adapter == "device" }?.let { d ->
            out += Check("hardware", "Hardware", if (d.commissioning.signedOffBy != null) "ok" else "warn", if (d.commissioning.signedOffBy != null) "Signed off" else "Preview only: not signed off")
            DeviceSchema.fitProblem(d.deviceClass, fc.gyroHz, fc.loopHz)?.takeIf { d.commissioning.signedOffBy != null }?.let { out += Check("fit", "Phone speed", "fail", it) }
        }
        return out
    }

    /** Below about 30 dB-Hz, the sky view is usually the limit: buildings, a roof or a window. */
    private fun weakSignalFix(): String = if ((rt.fc.gnssSignal ?: 99.0) < WEAK_SIGNAL) "Weak satellite signal: move under open sky" else ""

    fun armProblem(): String? = readiness().firstOrNull { it.status == "fail" }?.let { "${it.label}: ${it.detail}" + if (it.fix.isEmpty()) "" else ". ${it.fix}" }

    fun checklistItems(): List<String> {
        val base = arrayListOf("Vehicle secured, or clear of people", "Battery charged", "Stop tested")
        when (rt.profiles.active.vehicleType) {
            "plane" -> base += listOf("Control surfaces move the right way", "Props clear", "Safety pilot ready")
            "boat", "water" -> base += "Hull sealed"
            "biped", "multi-legged" -> base += "Clear space to fall"
            "arm" -> base += "Work area clear"
        }
        return base
    }

    // --- Output shaping, used by the mixer path and the device engine.

    /** A value from -1 to 1 forced on an output right now, from a test or trigger. */
    fun overrideFor(name: String): Double? {
        val now = System.currentTimeMillis()
        tester.active()?.let { (o, v) -> if (o == name) return v }
        if (groundRun.running() && rt.profiles.active.channels.any { it.name == name && it.kind == ChannelKind.MOTOR_FORWARD }) {
            return groundRunThrottle() ?: 0.0
        }
        val ov = overrides[name] ?: return null
        if (ov.second <= now) { overrides.remove(name); return null }
        return ov.first
    }

    // --- The ground run (GroundRun.kt): its throttle, and storing what it measured.
    private var storedRunAt = 0L

    private fun groundRunThrottle(): Double? {
        val s = rt.fc.propSample()
        val u = groundRun.tick(s?.first ?: -1L, s?.second, s?.third)
        synchronized(groundRun) { groundRun.result?.takeIf { it.atMs != storedRunAt }?.let { storedRunAt = it.atMs; storeGroundRun(it) } }
        return u
    }

    private fun storeGroundRun(r: GroundRun.Baseline) {
        val e = groundRuns.add(vehicle, r)
        val c = e.comparison
        val rpm = r.steps.joinToString(", ") { "${Math.round(it.revHz * 60)} rpm at ${Math.round(it.throttle * 100)}%" }
        when {
            c.changed -> {
                // Learned airspeed belongs to the old setup: being confidently wrong is worse than knowing nothing.
                val forgot = rt.params.get("PROP_KQ") > 0
                if (forgot) rt.params.set("PROP_KQ", 0.0)
                rt.log.event("prop", "phone", "setup changed", "ground run: ${c.why}. Stored as the new reference" + if (forgot) "; learned airspeed forgotten" else "")
            }
            e.reference === r -> rt.log.event("prop", "phone", "ground run", "first ground run for this setup, stored as its reference: $rpm")
            else -> rt.log.event("prop", "phone", "ground run", "$rpm. Pack ${strengthText(c.strength)} the reference")
        }
    }

    private fun strengthText(s: Double) = if (abs(s - 1) < 0.005) "as strong as" else "${"%.1f".format(abs(s - 1) * 100)}% ${if (s > 1) "stronger than" else "weaker than"}"

    /** Shown before arming when the microphone hears the propeller: whether this flight has a fresh ground run. */
    private fun groundRunCheck(): Check? {
        if (rt.params.get("MIC_PROP") < 1 || rt.profiles.active.vehicleType != "plane") return null
        val fix = "Restrain the plane and run the ground run in Test"
        val e = groundRuns.get(vehicle) ?: return Check("groundrun", "Ground run", "warn", "None for this setup yet. Readings can't be checked for a harmonic mistake", fix)
        val age = System.currentTimeMillis() - e.last.atMs
        if (age > GroundRun.FRESH_MS) return Check("groundrun", "Ground run", "warn", "Last one ${age / 60_000} min ago: this pack isn't measured", fix)
        if (e.comparison.changed) return Check("groundrun", "Ground run", "warn", "Setup changed: ${e.comparison.why}. Airspeed learning starts again", "")
        return Check("groundrun", "Ground run", "ok", "${age / 60_000} min ago. Pack ${strengthText(e.comparison.strength)} the reference")
    }

    private fun limitForTest(c: Channel?): Double = when (c?.kind) {
        ChannelKind.MOTOR_FORWARD, ChannelKind.MOTOR_REVERSIBLE -> rt.params.get("TEST_MAX_MOTOR")
        else -> rt.params.get("TEST_MAX_SERVO")
    }

    // --- Requests. All of these pass through the controller's validation first.

    fun handle(source: Source, name: String, a: JsonObject, mode: Mode, p: VehicleProfile): CommandResult {
        val human = source != Source.AI && source != Source.BEHAVIOR
        fun no(code: String, m: String) = CommandResult.reject(code, m)
        if (!human && name !in AI_ALLOWED) return no("not_permitted", "this needs a person")
        return when (name) {
            "test_output" -> {
                if (mode != Mode.DISARMED) return no("not_permitted", "tests only run while disarmed")
                val out = a.s("output") ?: return no("malformed", "output is required")
                val c = p.channels.firstOrNull { it.name == out }
                val known = c != null || rt.engine.device?.actuators?.any { it.id == out } == true
                if (!known) return no("out_of_range", "unknown output")
                val v = a.d("value") ?: 0.0
                val limit = limitForTest(c)
                if (abs(v) > limit) return no("out_of_range", "this test is limited to +/-$limit")
                if (c?.kind != ChannelKind.SERVO && c != null && a.b("confirmed") != true) return no("needs_confirm", "Confirm props are off or wheels are off the ground")
                tester.hold(source, out, v)?.let { return no("not_permitted", it) }
                CommandResult.OK
            }
            "test_stop" -> { tester.stop(); CommandResult.OK }
            "ground_run" -> {
                if (mode != Mode.DISARMED) return no("not_permitted", "the ground run only runs while disarmed")
                if (rt.controller.killed) return no("not_permitted", "kill is on. Arm once to clear it, then disarm")
                if (rt.params.get("MIC_PROP") < 1) return no("not_permitted", "turn on MIC_PROP first")
                rt.fc.propUnavailable?.let { return no("not_permitted", "microphone: $it") }
                if (p.channels.none { it.kind == ChannelKind.MOTOR_FORWARD }) return no("not_permitted", "this vehicle has no forward-only motor output")
                if (a.b("confirmed") != true) return no("needs_confirm", "Confirm the plane is restrained and everyone is clear of the propeller")
                val lo = rt.params.get("PROP_GR_LO"); val hi = rt.params.get("PROP_GR_HI")
                if (hi <= lo + 0.05) return no("out_of_range", "PROP_GR_HI must be above PROP_GR_LO")
                groundRun.start(source, GroundRun.steps(lo, hi), rt.params.get("PROP_BLADES").toInt())?.let { return no("not_permitted", it) }
                CommandResult.OK
            }
            "ground_run_stop" -> { groundRun.stop(); CommandResult.OK }
            "ground_run_forget" -> {
                if (mode != Mode.DISARMED) return no("not_permitted", "disarm first")
                groundRuns.clear(vehicle)
                CommandResult.OK
            }
            "diagnostics_run" -> {
                if (mode != Mode.DISARMED) return no("not_permitted", "disarm first")
                if (diagRunning != null) return no("not_permitted", "tests are already running")
                runDiagnostics()
                CommandResult.OK
            }
            "test_record" -> {
                val t = a.s("test") ?: return no("malformed", "test is required")
                synchronized(clientTests) { clientTests[t.take(30)] = (a.s("status") ?: "warn") to (a.s("detail")?.take(200) ?: "") }
                CommandResult.OK
            }
            "face_express" -> {
                val (req, why) = Face.parseRequest(a.s("expression"), a.d("seconds"), System.currentTimeMillis())
                if (req == null) return no("malformed", why ?: "bad expression")
                faceRequest = req.expr?.let { it to req.untilMs }
                CommandResult.OK
            }
            "face_show" -> {
                val show = a.b("show") ?: return no("malformed", "show must be true or false")
                faceCommand = show to System.nanoTime()
                CommandResult.OK
            }
            "checklist_set" -> {
                val i = a.s("item") ?: return no("malformed", "item is required")
                if (a.b("done") == true) checklist += i else checklist -= i
                CommandResult.OK
            }
            "trim_set" -> trimSet(a, mode, p)
            "trim_undo" -> if (trims.undo(a.s("output") ?: "")) CommandResult.OK else no("nothing", "nothing to undo")
            "trigger_save" -> {
                val (t, err) = TriggerCodec.parse(a["trigger"] as? JsonObject ?: JsonObject(emptyMap()))
                if (t == null) return no("out_of_range", err ?: "invalid")
                synchronized(this) { triggerMap()[t.id] = t; saveTriggers() }
                CommandResult.OK
            }
            "trigger_delete" -> { synchronized(this) { triggerMap().remove(a.s("id")); saveTriggers() }; CommandResult.OK }
            "trigger_prepare" -> {
                val id = a.s("id") ?: return no("malformed", "id is required")
                triggerMap()[id] ?: return no("out_of_range", "unknown trigger")
                synchronized(prepared) { prepared[id] = System.currentTimeMillis() + PREPARE_MS }
                CommandResult.OK
            }
            "trigger_fire" -> {
                val id = a.s("id") ?: return no("malformed", "id is required")
                fire(id, source, "button")?.let { no("not_permitted", it) } ?: CommandResult.OK
            }
            "mission_upload" -> {
                val (m, errs) = Geo.parseMission(a["mission"] as? JsonObject ?: JsonObject(emptyMap()))
                if (m == null) return no("invalid", errs.joinToString("; "))
                val bad = Geo.validate(m, fence, rt.fc.homeNow, triggerIds(), p.maxThrottle)
                if (bad.isNotEmpty()) return no("invalid", bad.joinToString("; "))
                if (!human) return no("not_permitted", "AI missions wait for a person. Use mission_propose")
                if (missions.state == MissionEngine.State.RUNNING || missions.state == MissionEngine.State.PAUSED) return no("not_permitted", "stop the running mission first")
                missions.load(m)
                store(missionFile, Geo.missionJson(m).toString())
                rt.log.event("mission", source.name, "uploaded", "${m.name}: ${m.items.size} points")
                CommandResult.OK
            }
            "mission_start" -> missionStart(source, p)
            "mission_pause" -> {
                if (missions.state != MissionEngine.State.RUNNING) return no("not_permitted", "no mission is running")
                missions.pause(); rt.fc.force(FlightMode.HOLD, "mission paused"); CommandResult.OK
            }
            "mission_resume" -> {
                if (missions.state != MissionEngine.State.PAUSED) return no("not_permitted", "no mission is paused")
                if (mode != Mode.AUTO && mode != Mode.MANUAL) return no("not_permitted", "arm first")
                rt.fc.modeProblem(FlightMode.AUTO)?.let { return no("not_permitted", it) }
                val r = rt.controller.startAuto(source)
                if (!r.accepted) return r
                rt.fc.setModeForce(FlightMode.AUTO)
                missions.resume()
                CommandResult.OK
            }
            "mission_skip" -> { missions.skip(); CommandResult.OK }
            "mission_abort" -> {
                if (missions.state != MissionEngine.State.RUNNING && missions.state != MissionEngine.State.PAUSED) return no("not_permitted", "no mission is running")
                missions.abort(); rt.fc.force(FlightMode.HOLD, "mission aborted"); rt.log.event("mission", source.name, "aborted", "mission aborted"); CommandResult.OK
            }
            "goto" -> {
                val lat = a.d("lat"); val lon = a.d("lon")
                if (lat == null || lon == null) return no("malformed", "lat and lon are required")
                val m = Mission("Go here", listOf(MissionItem(lat, lon)))
                val bad = Geo.validate(m, fence, rt.fc.homeNow, emptySet(), p.maxThrottle)
                if (bad.isNotEmpty()) return no("invalid", bad.joinToString("; "))
                if (missions.state == MissionEngine.State.RUNNING || missions.state == MissionEngine.State.PAUSED) missions.abort()
                missions.load(m)
                missionStart(source, p)
            }
            "fence_set" -> {
                val f = runCatching { Geo.parseFence(a["fence"] as? JsonObject ?: JsonObject(emptyMap())) }.getOrNull() ?: return no("invalid", "invalid fence")
                if (f.action !in setOf("hold", "home", "stop", "warn")) return no("invalid", "action must be hold, home, stop or warn")
                synchronized(this) { fences[vehicle] = f; saveFences() }
                rt.log.event("fence", source.name, "set", if (f.isSet) "fence updated" else "fence cleared")
                CommandResult.OK
            }
            "home_set" -> rt.fc.setHomeHere()?.let { no("not_permitted", it) } ?: CommandResult.OK
            "pose_capture" -> poseCapture(a)
            else -> no("malformed", "unknown request $name")
        }
    }

    private fun trimSet(a: JsonObject, mode: Mode, p: VehicleProfile): CommandResult {
        val out = a.s("output") ?: return CommandResult.reject("malformed", "output is required")
        val cur = trims.get(out)
        val t = OutputTrim(a.d("trim") ?: cur.trim, a.b("reverse") ?: cur.reverse, a.d("lo") ?: cur.lo, a.d("hi") ?: cur.hi)
        if (abs(t.trim) > 0.3) return CommandResult.reject("out_of_range", "trim is limited to +/-0.3")
        if (t.lo !in -1.0..0.0 || t.hi !in 0.0..1.0) return CommandResult.reject("out_of_range", "endpoints must be -1 to 0 and 0 to 1")
        val armed = mode == Mode.MANUAL || mode == Mode.AUTO
        if (armed) {
            if (t.reverse != cur.reverse || t.lo != cur.lo || t.hi != cur.hi) return CommandResult.reject("not_permitted", "reverse and endpoints can only change while disarmed")
            if (abs(t.trim - cur.trim) > ARMED_TRIM_STEP + 1e-9) return CommandResult.reject("out_of_range", "while armed, trim moves in small steps")
        }
        if (rt.engine.device?.takeIf { p.adapter == "device" }?.commissioning?.signedOffBy != null) {
            if (abs(t.trim) > 0.1 || t.reverse != cur.reverse) return CommandResult.reject("not_permitted", "this device is signed off. Larger trims and reversing mean re-running its limit and direction checks")
        }
        trims.set(out, t)
        return CommandResult.OK
    }

    private fun missionStart(source: Source, p: VehicleProfile): CommandResult {
        if (missions.mission == null) return CommandResult.reject("not_permitted", "no mission loaded")
        if (rt.controller.currentAdapter is SimulatedCar) return CommandResult.reject("unsupported", "the simulator doesn't navigate by GPS. Choose another vehicle")
        if (!p.missionsAllowed) return CommandResult.reject("unsupported", "missions aren't available for this vehicle type")
        val before = rt.fc.mode
        rt.fc.setModeForce(FlightMode.AUTO)
        val est = rt.fc.navEstimate()
        val tightest = missions.mission?.items?.minOfOrNull { it.radiusM }
        val tooTight = if (est != null && tightest != null && est.radiusM > tightest)
            "GPS is good to ±${est.radiusM.toInt()} m, but the tightest arrival radius is ${tightest.toInt()} m. Widen it to at least ${kotlin.math.ceil(est.radiusM).toInt()} m, or wait for a better position" else null
        val problem = rt.fc.modeProblem(FlightMode.AUTO) ?: tooTight ?: armProblem()
        if (problem != null) { rt.fc.setModeForce(before); return CommandResult.reject("not_permitted", problem) }
        val r = rt.controller.startAuto(source)
        if (!r.accepted) { rt.fc.setModeForce(before); return r }
        missions.start()
        // Started from the phone, nothing is listening for a link to lose until a ground station speaks.
        if (source == Source.LOCAL) linkLastMs = 0L else touchLink()
        rt.log.event("mission", source.name, "start", missions.mission?.name ?: "")
        return CommandResult.OK
    }

    private fun poseCapture(a: JsonObject): CommandResult {
        val d = rt.engine.device ?: return CommandResult.reject("not_permitted", "this needs a device with joints")
        val id = a.s("skill") ?: return CommandResult.reject("malformed", "skill is required")
        val steps = a["steps"] as? JsonArray ?: return CommandResult.reject("malformed", "steps are required")
        val skill = SkillDef(id, "sequence", buildJsonObject { put("steps", steps); put("loops", a.d("loops") ?: 1.0) })
        val next = d.copy(skills = d.skills.filter { it.id != id } + skill)
        DeviceSchema.validate(next).firstOrNull()?.let { return CommandResult.reject("invalid", it) }
        rt.devices.put(next)
        rt.engine.update(next)
        return CommandResult.OK
    }

    // --- Triggers.

    /** Fires a trigger. Returns why it didn't, or null. */
    fun fire(id: String, source: Source, why: String): String? {
        val t = synchronized(this) { triggerMap()[id] } ?: return "unknown trigger"
        val human = source != Source.AI && source != Source.BEHAVIOR
        val now = System.currentTimeMillis()
        val tel = rt.controller.latest()
        val armed = tel.mode == Mode.MANUAL || tel.mode == Mode.AUTO
        if (source == Source.AI && !t.aiAllowed) return "this trigger isn't allowed for AI"
        if (t.armedOnly && !armed) return "arm first"
        if ((t.action.type == "pulse" || t.action.type == "set") && !armed) return "arm first: this trigger moves an output"
        if (t.action.type == "pulse" || t.action.type == "set") {
            val p = rt.profiles.active
            val c = p.channels.firstOrNull { it.name == t.action.output }
            if (c == null && rt.engine.device?.actuators?.none { it.id == t.action.output } != false) return "this vehicle has no output ${t.action.output}"
            if (c != null && c.kind != ChannelKind.SERVO && abs(t.action.value) > p.maxThrottle) return "over this vehicle's throttle limit"
        }
        if (t.danger) {
            // A plan may fire it, since a person approved the plan. Anything else takes two deliberate steps.
            if (!human && source != Source.BEHAVIOR) return "needs a person"
            if (human) {
                val ok = synchronized(prepared) { (prepared.remove(id) ?: 0L) > now }
                if (!ok) return "confirm first: this trigger takes two steps"
            }
        }
        synchronized(this) {
            if (now - (fired[id] ?: 0L) < t.cooldownMs) return "cooling down"
            val c = counts[id] ?: 0
            if (t.maxCount > 0 && c >= t.maxCount) return "already used ${t.maxCount} times"
            fired[id] = now
            counts[id] = c + 1
        }
        run(t)
        val g = rt.fc.gpsNow
        rt.log.event("trigger", source.name, t.id, "${t.label} ($why)" + (g?.let { " at %.6f, %.6f".format(java.util.Locale.ROOT, it.lat, it.lon) } ?: ""))
        return null
    }

    private fun run(t: TriggerDef) {
        val a = t.action
        when (a.type) {
            "photo" -> worker.execute {
                val cam = rt.camera
                val jpeg = if (rt.sensors.camera.enabled) cam?.snapshot(3000) else null
                if (jpeg == null) rt.log.event("trigger", "phone", "photo_failed", "No photo: turn the camera on first")
                else rt.log.event("photo", "phone", "saved", savePhoto(jpeg, rt.fc.gpsNow, System.currentTimeMillis()))
            }
            "marker" -> rt.log.event("marker", "phone", t.id, a.text ?: t.label)
            "alert" -> rt.log.event("alert", "phone", t.id, a.text ?: t.label)
            "pulse" -> a.output?.let { overrides[it] = a.value to System.currentTimeMillis() + a.ms }
            "set" -> a.output?.let { overrides[it] = a.value to Long.MAX_VALUE }
            "skill" -> a.skill?.let { rt.engine.runSkill(it) }
        }
    }

    /** Called from the control loop, so nothing here may wait on storage or the network. */
    fun tick(t: Telemetry) {
        val now = t.t
        val armed = t.mode == Mode.MANUAL || t.mode == Mode.AUTO
        // A checklist is for one run. The next run starts it again.
        if (wasArmed && !armed && diagRunning == null) checklist.clear()
        wasArmed = armed
        val g = rt.fc.gpsNow?.takeIf { now - it.atMs < 3000 }
        if (armed && now - lastTickSlow >= 200) {
            lastTickSlow = now
            val list = synchronized(this) { triggerMap().values.toList() }
            val prev = lastDistFix
            val moved = if (g != null && prev != null) Navigation.distanceM(prev.lat, prev.lon, g.lat, g.lon) else 0.0
            if (g != null) lastDistFix = g
            for (d in list) {
                // A dangerous trigger only fires from a person or from a point in an approved plan.
                if (d.danger) continue
                if (d.everyMeters > 0 && moved in 0.0..200.0) {
                    val acc = (distAccum[d.id] ?: 0.0) + moved
                    if (acc >= d.everyMeters) { distAccum[d.id] = 0.0; fire(d.id, Source.BEHAVIOR, "every ${d.everyMeters.toInt()} m") } else distAccum[d.id] = acc
                }
                val last = synchronized(this) { fired[d.id] ?: 0L }
                if (d.everySeconds > 0 && now - last >= d.everySeconds * 1000) fire(d.id, Source.BEHAVIOR, "every ${d.everySeconds} s")
                if (d.sensor != null && d.op != null) {
                    val parts = d.sensor.split(":")
                    val v = rt.sensors.valueOf(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 0)
                    if (v != null && (if (d.op == "<") v < d.compare else v > d.compare)) fire(d.id, Source.BEHAVIOR, "sensor condition")
                }
            }
        }
        if (!armed) {
            overrides.entries.removeAll { it.value.second == Long.MAX_VALUE }
            lastDistFix = null
        }
        // The fence is checked on every position, in any mode, whatever the screen is doing.
        val f = fence
        val est = rt.fc.navEstimate()
        // Checked even when fixes stop arriving: a fence that can't be checked counts as broken.
        if (armed && f.isSet) {
            val b = if (est == null || est.health == GpsGuard.Health.LOST) "position lost, so the fence can't be checked"
                else Geo.breach(f, est.lat, est.lon, g?.altM, rt.fc.homeNow, est.radiusM + rt.params.get("FENCE_MARGIN"))
            if (b != null && breachReason == null) {
                rt.log.event("failsafe", "phone", "fence", "Fence: $b. Action: ${f.action}")
                when (f.action) {
                    "hold" -> { missions.pause(); rt.fc.force(FlightMode.HOLD, "fence: $b") }
                    "home" -> { missions.abort(); rt.fc.force(FlightMode.RETURN_HOME, "fence: $b") }
                    "stop" -> { missions.abort(); rt.fc.requestFailsafe("fence: $b") }
                }
            }
            breachReason = b
        } else if (!armed) breachReason = null
        // A running mission's own link-loss rule applies while the ground station is away.
        if (armed && missions.state == MissionEngine.State.RUNNING && linkLastMs != 0L && now - linkLastMs > LINK_LOSS_MS && !linkLossApplied) {
            linkLossApplied = true
            val rule = missions.mission?.onLinkLoss ?: "continue"
            rt.log.event("failsafe", "phone", "link_lost", "Ground link lost during the mission. Rule: $rule")
            when (rule) {
                "hold" -> { missions.pause(); rt.fc.force(FlightMode.HOLD, "link lost") }
                "home" -> { missions.abort(); rt.fc.force(FlightMode.RETURN_HOME, "link lost") }
            }
        }
    }
    private var lastTickSlow = 0L

    // --- Diagnostics: real checks that produce a saved, dated report.

    private fun runDiagnostics() {
        worker.execute {
            val results = ArrayList<JsonObject>()
            fun add(test: String, status: String, detail: String) { results += buildJsonObject { put("test", test); put("status", status); put("detail", detail) } }
            fun step(name: String, block: () -> Unit) { diagRunning = name; runCatching(block).onFailure { add(name, "fail", "Test crashed: ${it.javaClass.simpleName}") } }
            step("Sensors") {
                val fc = rt.fc
                val g = fc.accelMagnitude
                add("Accelerometer", when { g == null -> "warn"; g in 9.0..10.6 -> "pass"; else -> "warn" }, if (g == null) "No accelerometer" else "%.2f m/s² at rest: should be near 9.8. Hold still when testing".format(g))
                add("Gyroscope", if (fc.gyroHz >= rt.params.get("ARM_RATE_MIN")) "pass" else "warn", "${fc.gyroHz.toInt()} Hz")
                val m = fc.magStrength
                add("Compass", when { m == null -> "warn"; m in 20.0..70.0 -> "pass"; else -> "warn" }, if (m == null) "No compass" else "%.0f µT: Earth's field is 25 to 65".format(m))
            }
            step("GPS") { val g = rt.fc.gpsNow; val age = g?.let { System.currentTimeMillis() - it.atMs }
                add("GPS", if (g != null && age!! < 5000 && g.accuracyM <= rt.params.get("ARM_GPS_ACC")) "pass" else "warn", if (g == null) "No position. Turn on Location" else (rt.fc.gpsText() ?: "") + if (weakSignalFix().isEmpty()) "" else ". ${weakSignalFix()}") }
            step("Timing") {
                val before = rt.fc.overrunCount
                Thread.sleep(3000)
                val hz = rt.fc.loopHz; val worst = rt.fc.worstStepMs
                add("Control timing", if (hz > 40 && rt.fc.overrunCount == before) "pass" else "warn", "%.0f Hz, longest step %.1f ms, %d stalls".format(hz, worst, rt.fc.overrunCount - before))
            }
            step("Stop") {
                val ms = rt.controller.stopDrill(); add("Stop", if (ms != null && ms < 1000) "pass" else "fail", if (ms == null) "Didn't reach safe state" else "Outputs safe in $ms ms") }
            step("Failsafe") {
                val r = rt.controller.failsafeDrill()
                add("Link-loss failsafe", if (r.first != null) "pass" else "fail", r.second) }
            val battery = rt.fc.batteryNow
            step("Power") { add("Battery", if (battery == null || battery > 20) "pass" else "warn", battery?.let { "${it.toInt()}%" } ?: "Unknown") }
            synchronized(clientTests) { clientTests.forEach { (k, v) -> add(k, v.first, v.second) }; clientTests.clear() }
            val failed = results.count { it["status"]!!.jsonPrimitive.content == "fail" }
            val report = buildJsonObject {
                put("type", "testReport"); put("t", System.currentTimeMillis()); put("device", vehicle); put("name", rt.profiles.active.name)
                put("passed", failed == 0); put("results", JsonArray(results))
            }
            lastDiag = report
            atomicWrite(File(reportsDir, "report-${report["t"]!!.jsonPrimitive.long}.json"), report.toString())
            reportsDir.listFiles()?.sortedByDescending { it.name }?.drop(50)?.forEach { it.delete() }
            rt.log.event("test", "phone", if (failed == 0) "passed" else "failed", "Test report saved: $failed failed")
            diagRunning = null
        }
    }

    fun validateMission(m: Mission) = Geo.validate(m, fence, rt.fc.homeNow, triggerIds(), rt.profiles.active.maxThrottle)

    /** A person approved a mission that an AI proposed. */
    fun loadApproved(m: Mission) {
        missions.load(m)
        store(missionFile, Geo.missionJson(m).toString())
        rt.log.event("mission", "person", "approved", "${m.name}: ${m.items.size} points")
    }

    private fun savePhoto(jpeg: ByteArray, g: GpsFix?, t: Long): String = runCatching {
        photoDir.mkdirs()
        val f = File(photoDir, "photo-$t.jpg")
        f.writeBytes(jpeg)
        val fix = g?.takeIf { t - it.atMs < 10_000 }
        if (fix != null) runCatching { geotag?.invoke(f, fix) }
        File(photoDir, "photo-$t.json").writeText(buildJsonObject {
            put("t", t); put("lat", fix?.lat); put("lon", fix?.lon); put("alt", fix?.altM); put("acc", fix?.accuracyM)
        }.toString())
        photoDir.listFiles { x -> x.name.endsWith(".jpg") }?.sortedByDescending { it.name }?.drop(MAX_PHOTOS)?.forEach { old ->
            old.delete(); File(photoDir, old.nameWithoutExtension + ".json").delete()
        }
        "Saved ${f.name}" + if (fix == null) ", with no location" else ""
    }.getOrElse { "Photo couldn't be saved: ${it.javaClass.simpleName}" }

    /** Everything a person can review afterwards. */
    fun filesJson() = buildJsonObject {
        val logs = File(dir, "logs").listFiles { x -> x.name.endsWith(".csv") }?.sortedByDescending { it.name } ?: emptyList()
        put("logs", JsonArray(logs.map { f -> buildJsonObject {
            put("name", f.name); put("bytes", f.length()); put("t", f.lastModified())
            put("frames", File(videoDir, f.nameWithoutExtension).list()?.size ?: 0)
        } }))
        val photos = photoDir.listFiles { x -> x.name.endsWith(".jpg") }?.sortedByDescending { it.name } ?: emptyList()
        put("photos", JsonArray(photos.take(200).map { f -> buildJsonObject {
            put("name", f.name); put("bytes", f.length())
            val side = runCatching { Json.parseToJsonElement(File(photoDir, f.nameWithoutExtension + ".json").readText()).jsonObject }.getOrNull()
            put("t", side?.get("t") ?: JsonPrimitive(f.lastModified())); put("lat", side?.get("lat") ?: JsonNull); put("lon", side?.get("lon") ?: JsonNull)
        } }))
        put("reports", reportsJson())
    }

    /** Reads one review file. Names are checked, so a request can't leave these folders. */
    fun readFile(kind: String, name: String): Pair<ByteArray, String>? {
        if (name.contains('/') || name.contains('\\') || name.startsWith(".") || name.length > 80) return null
        return when (kind) {
            "log" -> File(dir, "logs/$name").takeIf { name.endsWith(".csv") && it.isFile }?.let { f ->
                val lines = f.useLines { it.toList() }
                // A long run is thinned, so charts stay quick over a mobile link.
                val step = (lines.size / MAX_LOG_ROWS).coerceAtLeast(1)
                val kept = if (step == 1) lines else listOf(lines.first()) + lines.drop(1).filterIndexed { i, _ -> i % step == 0 }
                kept.joinToString("\n").toByteArray() to "text/csv"
            }
            "photo" -> File(photoDir, name).takeIf { name.endsWith(".jpg") && it.isFile }?.let { it.readBytes() to "image/jpeg" }
            else -> null
        }
    }

    /** A saved video frame from a run: the one nearest to time t. */
    fun readFrame(run: String, t: Long): ByteArray? {
        if (run.contains('/') || run.contains('\\') || run.startsWith(".")) return null
        val names = File(videoDir, run).list()?.mapNotNull { it.removeSuffix(".jpg").toLongOrNull() } ?: return null
        val best = names.minByOrNull { abs(it - t) } ?: return null
        return if (abs(best - t) > 5000) null else File(videoDir, "$run/$best.jpg").readBytes()
    }

    fun framesJson(run: String): JsonArray {
        if (run.contains('/') || run.contains('\\') || run.startsWith(".")) return JsonArray(emptyList())
        return JsonArray(File(videoDir, run).list()?.mapNotNull { it.removeSuffix(".jpg").toLongOrNull() }?.sorted()?.map { JsonPrimitive(it) } ?: emptyList())
    }

    fun reportsJson() = JsonArray((reportsDir.listFiles()?.sortedByDescending { it.name }?.take(20) ?: emptyList()).mapNotNull { f -> runCatching { Json.parseToJsonElement(f.readText()) }.getOrNull() })

    // --- What everyone sees.

    fun triggersJson() = synchronized(this) { JsonArray(triggerMap().values.map { t -> JsonObject(TriggerCodec.json(t) + ("count" to JsonPrimitive(counts[t.id] ?: 0))) }) }

    fun outputNames(): List<String> = rt.engine.device?.takeIf { rt.profiles.active.adapter == "device" }?.actuators?.map { it.id } ?: rt.profiles.active.channels.map { it.name }

    fun connections(): List<Check> {
        val out = ArrayList<Check>()
        val adapters = runCatching { rt.transports?.list() }.getOrNull() ?: emptyList()
        val d = rt.engine.device?.takeIf { rt.profiles.active.adapter == "device" }
        out += when {
            d == null -> Check("output", "Vehicle output", "off", "Preview only: no hardware is used", "Set up a device to use hardware")
            d.commissioning.signedOffBy == null -> Check("output", "Vehicle output", "warn", "Closed until signed off", "Finish setup")
            rt.engine.hardware?.lastError != null -> Check("output", "Vehicle output", "bad", rt.engine.hardware?.lastError ?: "", "Check the USB adapter")
            else -> Check("output", "Vehicle output", "ok", "Signed off")
        }
        out += Check("usb", "USB adapters", if (adapters.isEmpty()) "off" else "ok", if (adapters.isEmpty()) "None connected" else adapters.joinToString(), if (adapters.isEmpty()) "Plug in a USB serial adapter" else "")
        val rx = rt.receiver.status
        out += Check("receiver", "Receiver", when (rx) { "off" -> "off"; "receiving" -> "ok"; else -> "warn" }, rx, if (rx == "listening") "No frames: check wiring and protocol" else "")
        out += Check("gamepad", "Gamepad", if (gamepad != null) "ok" else "off", gamepad ?: "None connected", if (gamepad == null) "Connect by Bluetooth, then press a button" else "")
        val ground = rt.groundLinks.get()
        val owner = rt.controller.inputOwner
        out += Check("ground", "Ground stations", if (ground > 0) "ok" else "off", "$ground connected" + (owner?.let { ", $it has control" } ?: ""), if (ground == 0) "Scan the QR code on More, or open the address" else "")
        val relay = (rt.relay["state"] as? JsonPrimitive)?.contentOrNull ?: "off"
        out += Check("relay", "Relay", when (relay) { "connected" -> "ok"; "off" -> "off"; else -> "warn" }, relay + ((rt.relay["host"] as? JsonPrimitive)?.contentOrNull?.let { " ($it)" } ?: ""), if (relay == "reconnecting") "Can't reach it: check mobile data" else "")
        val ai = rt.log.allEvents().lastOrNull { it.source == "AI" }
        out += Check("ai", "AI agents", if (ai != null && System.currentTimeMillis() - ai.t < 60000) "ok" else "off", ai?.let { "Last: ${it.code}" } ?: "None yet")
        val cat = rt.sensors.catalog()
        val working = cat.count { it.second.enabled }
        out += Check("sensors", "Sensors", "ok", "$working on, of ${cat.size} found")
        val g = rt.fc.gpsNow
        val locOn = rt.sensors.config("phone.location")?.enabled == true
        out += Check("gps", "GPS", when { g != null && System.currentTimeMillis() - g.atMs < 5000 -> "ok"; locOn -> "warn"; else -> "off" }, rt.fc.gpsText() ?: "No position", if (!locOn) "Turn on Location" else if (g == null) "Go outside" else weakSignalFix())
        val camera = rt.camera?.status() ?: "unavailable"
        out += Check("camera", "Camera", if (camera.startsWith("live")) "ok" else "off", camera)
        val net = (rt.net["radio"] as? JsonPrimitive)?.contentOrNull?.ifEmpty { null } ?: (rt.net["transport"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
        out += Check("network", "Network", if (net == "none") "bad" else "ok", net)
        out += Check("power", "Power", if (rt.fc.healthMap()["battery"] == "bad" || rt.fc.healthMap()["heat"] == "bad") "warn" else "ok", rt.fc.batteryText())
        appChecks.forEach { it()?.let { c -> out += c } }
        return out
    }

    private fun checksJson(list: List<Check>) = JsonArray(list.map { c -> buildJsonObject { put("id", c.id); put("label", c.label); put("status", c.status); put("detail", c.detail); put("fix", c.fix) } })

    fun opsJson(canSeePrivate: Boolean) = buildJsonObject {
        put("type", "ops")
        put("t", System.currentTimeMillis())
        put("flightMode", rt.fc.mode.name)
        put("caps", capsJson())
        put("owner", rt.controller.inputOwner?.name)
        put("killed", rt.controller.killed)
        faceStatus?.let { put("face", it) }
        put("readiness", checksJson(readiness()))
        put("armProblem", armProblem())
        tester.status()?.let { put("test", it) }
        put("groundRun", groundRun.json())
        put("mission", missions.json())
        if (canSeePrivate) missions.mission?.let { put("missionData", Geo.missionJson(it)) }
        put("fence", buildJsonObject { put("set", fence.isSet); put("breach", breachReason); if (canSeePrivate) put("data", Geo.fenceJson(fence)) })
        put("triggers", triggersJson())
        put("diag", diagRunning)
        put("ground", rt.groundLinks.get())
        lastDiag?.let { put("report", it) }
        put("checklist", JsonArray(checklistItems().map { i -> buildJsonObject { put("item", i); put("done", i in checklist) } }))
        put("trims", trims.json(outputNames()))
        put("connections", checksJson(connections()))
    }

    /** What the screen should show for this device. A device lacking something simply doesn't get that screen. */
    fun capsJson() = buildJsonObject {
        val p = rt.profiles.active
        val d = rt.engine.device?.takeIf { p.adapter == "device" }
        val joints = d?.actuators?.any { it.unit == "deg" || it.unit == "rad" } == true && d.actuators.none { it.mix.isNotEmpty() }
        val surfaces = p.channels.any { it.pitch != 0.0 || it.roll != 0.0 } && p.adapter != "device"
        put("vehicleType", p.vehicleType)
        put("simulated", rt.controller.currentAdapter is SimulatedCar)
        put("map", !joints)
        put("joints", d?.actuators?.size ?: 0)
        put("timeline", d != null)
        put("sticks2", surfaces)
        put("modeNoun", if (p.vehicleType == "plane") "Flight mode" else "Drive mode")
        put("gps", rt.sensors.config("phone.location") != null)
        put("cameras", (rt.camera?.catalog()?.size ?: 1) > 1)
        put("skills", JsonArray(d?.skills?.map { JsonPrimitive(it.id) } ?: emptyList()))
        put("behaviors", JsonArray(d?.behaviors?.map { JsonPrimitive(it.id) } ?: emptyList()))
    }

    private companion object {
        const val PREPARE_MS = 5000L
        const val LINK_LOSS_MS = 3000L
        const val ARMED_TRIM_STEP = 0.02
        const val WEAK_SIGNAL = 30.0
        const val MAX_PHOTOS = 500
        const val MAX_LOG_ROWS = 20_000
        val AI_ALLOWED = setOf("trigger_fire", "mission_upload", "mission_pause", "mission_abort", "face_express", "face_show")
    }
}
