package com.smartphonex.core

import kotlinx.serialization.json.*
import kotlin.math.*

/**
 * The ground test: evidence the phone gathers itself, on the ground, that the vehicle does what it's told before it's
 * trusted with more. Nothing here is ticked by hand except what only a person can see (which way an output moved).
 *
 *  - **Self-test** and **link**: from the automatic tests and the link test.
 *  - **Outputs**: every output was run each way it can go, then a person said it moved the right way.
 *  - **STOP**: a STOP while armed disarmed it. Driving, also how long and how far it took to stop.
 *  - **Route**: a plan of at least three points ran to the end, every point reached, and how far off the line it got.
 *  - **Fence**: armed, it went out of the fence and the phone caught it and did the fence's action.
 *
 * Walking it (carrying the phone, nothing driving) proves the navigation, arrivals, timeline, triggers and fence on real
 * GPS with nothing that can run away. Driving it proves the same with the vehicle steering itself, and its stop.
 * Kept per vehicle, and saved, so it survives a restart.
 */
class GroundTest(private val clock: () -> Long, private val save: (String) -> Unit = {}) {
    enum class How { WALK, DRIVE }
    data class Stage(val status: String, val detail: String, val atMs: Long, val how: How? = null)

    private val stages = HashMap<String, LinkedHashMap<String, Stage>>()
    private val hows = HashMap<String, How>()
    private val outputsRun = HashMap<String, HashSet<String>>()
    /** When each vehicle's test was last started again: evidence from before then doesn't count. */
    private val resetAt = HashMap<String, Long>()

    private class Stopping(val vehicle: String, val atMs: Long, val fromMps: Double, val lat: Double?, val lon: Double?, val source: String)
    private var stopping: Stopping? = null

    private class Run(val vehicle: String, val name: String, val items: List<Pair<Double, Double>>, val startLat: Double, val startLon: Double, val startMs: Long, val radiusM: Double) {
        val reached = HashSet<Int>()
        var maxOffM = 0.0
        var offSamples = 0
        var lastIndex = 0
    }
    private var run: Run? = null

    @Synchronized fun how(vehicle: String) = hows[vehicle] ?: How.WALK
    @Synchronized fun setHow(vehicle: String, h: How) { hows[vehicle] = h; persist() }

    @Synchronized fun stage(vehicle: String, id: String): Stage? = stages[vehicle]?.get(id)

    @Synchronized fun reset(vehicle: String) {
        stages.remove(vehicle); outputsRun.remove(vehicle); resetAt[vehicle] = clock()
        if (run?.vehicle == vehicle) run = null
        if (stopping?.vehicle == vehicle) stopping = null
        persist()
    }

    private fun put(vehicle: String, id: String, s: Stage) {
        stages.getOrPut(vehicle) { LinkedHashMap() }[id] = s
        persist()
    }

    // --- Outputs.

    /** An output test ran this output this way (the sign of its value). */
    @Synchronized fun outputRun(vehicle: String, output: String, value: Double) {
        if (abs(value) < 1e-6) return
        outputsRun.getOrPut(vehicle) { HashSet() } += output + if (value > 0) "+" else "-"
    }

    /** Which of [needed] (output name to whether it only goes forward) haven't been run each way yet. */
    @Synchronized fun outputsMissing(vehicle: String, needed: List<Pair<String, Boolean>>): List<String> {
        val done = outputsRun[vehicle] ?: emptySet<String>()
        return needed.flatMap { (o, fwdOnly) -> listOfNotNull("$o+".takeIf { it !in done }, "$o-".takeIf { !fwdOnly && it !in done }) }
    }

    /** A person watched the outputs. Only accepted once each was run each way, so the answer is about something seen. */
    @Synchronized fun confirmOutputs(vehicle: String, needed: List<Pair<String, Boolean>>, ok: Boolean, who: String): String? {
        val missing = outputsMissing(vehicle, needed)
        if (missing.isNotEmpty()) return "run each output each way first: " + missing.joinToString(", ") { it.dropLast(1) + if (it.endsWith("+")) " forward" else " reverse" }
        put(vehicle, "outputs", Stage(if (ok) "pass" else "fail", if (ok) "${needed.size} output(s) run each way; $who saw them move the right way" else "$who saw an output move the wrong way: fix it in Setup, Trim (Reverse), and test again", clock()))
        return null
    }

    // --- STOP.

    /** STOP arrived. [armed] is whether it was armed just before. */
    @Synchronized fun stopped(vehicle: String, source: String, armed: Boolean, speedMps: Double?, lat: Double?, lon: Double?) {
        if (!armed) return
        val now = clock()
        put(vehicle, "stop", Stage("pass", "STOP from ${source.lowercase()} disarmed it at once", now))
        // Only a vehicle driving itself says anything about stopping distance: a person carrying the phone keeps walking.
        if (how(vehicle) == How.DRIVE && speedMps != null && speedMps >= MOVING_MPS) stopping = Stopping(vehicle, now, speedMps, lat, lon, source)
    }

    // --- Fence.

    @Synchronized fun fenceCaught(vehicle: String, reason: String, action: String) {
        put(vehicle, "fence", Stage(if (action == "warn") "warn" else "pass", "Caught: $reason. It did: $action" + if (action == "warn") " (only a warning: set the fence to hold, home or stop)" else "", clock(), how(vehicle)))
    }

    // --- Each tick: a route that's running, and a vehicle that's stopping.

    /**
     * [state] is the mission's, [index] the point it's heading for, [items] the points. [lat], [lon] and [radiusM] are the
     * navigation estimate, null when there's no good one. [speedMps] is ground speed.
     */
    @Synchronized fun tick(
        vehicle: String, name: String?, state: MissionEngine.State, index: Int, items: List<MissionItem>,
        lat: Double?, lon: Double?, good: Boolean, speedMps: Double?, lateS: Double?,
    ) {
        val now = clock()
        stopping?.let { s ->
            val t = (now - s.atMs) / 1000.0
            if (speedMps != null && speedMps < STOPPED_MPS) {
                val dist = if (s.lat != null && s.lon != null && lat != null && lon != null) Navigation.distanceM(s.lat, s.lon, lat, lon) else null
                put(s.vehicle, "stop-moving", Stage(if (t <= STOP_LIMIT_S) "pass" else "fail",
                    "From %.1f m/s it stopped in %.1f s".format(s.fromMps, t) + (dist?.let { ", %.0f m".format(it) } ?: "") + if (t > STOP_LIMIT_S) ": too slow, check the brake or the ESC's drag brake" else "", now, How.DRIVE))
                stopping = null
            } else if (t > STOP_GIVE_UP_S) {
                put(s.vehicle, "stop-moving", Stage("fail", "Still moving %.0f s after STOP".format(t), now, How.DRIVE))
                stopping = null
            }
        }
        val r = run
        if (r == null) {
            if (state == MissionEngine.State.RUNNING && items.size >= MIN_POINTS && lat != null && lon != null)
                run = Run(vehicle, name ?: "plan", items.map { it.lat to it.lon }, lat, lon, now, items.minOf { it.radiusM })
            return
        }
        if (r.vehicle != vehicle) { run = null; return }
        when (state) {
            MissionEngine.State.RUNNING -> {
                if (index > r.lastIndex) { for (i in r.lastIndex until index) r.reached += i; r.lastIndex = index }
                if (good && lat != null && lon != null && index < r.items.size) {
                    val from = if (index == 0) r.startLat to r.startLon else r.items[index - 1]
                    val off = offLineM(from, r.items[index], lat to lon)
                    // Arriving swings it round the point, which isn't being off the line.
                    if (Navigation.distanceM(lat, lon, r.items[index].first, r.items[index].second) > r.radiusM) { r.maxOffM = max(r.maxOffM, off); r.offSamples++ }
                }
            }
            MissionEngine.State.PAUSED -> {}
            MissionEngine.State.DONE -> {
                for (i in r.lastIndex until r.items.size) r.reached += i
                finish(r, null, lateS)
            }
            else -> finish(r, "ended at point ${min(r.lastIndex + 1, r.items.size)} of ${r.items.size}", lateS)
        }
    }

    private fun finish(r: Run, early: String?, lateS: Double?) {
        run = null
        val how = how(r.vehicle)
        val limit = offLimitM(how, r.radiusM)
        val took = (clock() - r.startMs) / 1000.0
        val timing = lateS?.let { if (abs(it) < 5) ", on time" else if (it > 0) ", ${it.roundToInt()} s late" else ", ${(-it).roundToInt()} s early" } ?: ""
        val line = if (r.offSamples > 0) "off the line by at most %.1f m (allowed %.0f m)".format(r.maxOffM, limit) else "no good position on the legs to measure the line"
        val status = when {
            early != null -> "fail"
            r.offSamples == 0 -> "warn"
            r.maxOffM > limit -> "fail"
            else -> "pass"
        }
        val detail = (if (how == How.WALK) "Walked" else "Drove") + " \"${r.name}\": " +
            (early ?: "all ${r.items.size} points reached") + ", $line, %d:%02d".format(took.toInt() / 60, took.toInt() % 60) + timing
        put(r.vehicle, "route", Stage(status, detail, clock(), how))
    }

    // --- Saved and shown.

    @Synchronized fun json(vehicle: String, extra: Map<String, Stage>, outputsNeeded: List<Pair<String, Boolean>>): JsonObject = buildJsonObject {
        val how = how(vehicle)
        put("how", how.name.lowercase())
        val mine = stages[vehicle] ?: emptyMap<String, Stage>()
        val all = LinkedHashMap<String, Stage>()
        val since = resetAt[vehicle] ?: 0L
        ORDER.forEach { id -> (extra[id]?.takeIf { it.atMs >= since } ?: mine[id])?.let { all[id] = it } }
        val missing = outputsMissing(vehicle, outputsNeeded)
        var passed = 0
        var needed = 0
        putJsonArray("stages") {
            ORDER.forEach { id ->
                val drivingOnly = id == "stop-moving"
                val s = all[id]
                val skip = drivingOnly && how == How.WALK && s == null
                if (!skip) { needed++; if (s?.status == "pass" || s?.status == "warn") passed++ }
                addJsonObject {
                    put("id", id); put("label", LABELS[id]); put("how", HOW[id])
                    put("status", s?.status ?: if (skip) "skip" else "todo")
                    put("detail", s?.detail ?: if (skip) "Needs the vehicle driving itself: switch to Drive it." else if (id == "outputs" && missing.isNotEmpty() && missing.size < outputsNeeded.size * 2) "Still to run: ${missing.joinToString(", ")}" else "")
                    s?.let { put("at", it.atMs) }
                    s?.how?.let { put("ranAs", it.name.lowercase()) }
                }
            }
        }
        put("passed", passed); put("needed", needed)
        put("done", passed == needed)
        run?.takeIf { it.vehicle == vehicle }?.let { r -> putJsonObject("route") { put("name", r.name); put("reached", r.reached.size); put("of", r.items.size); put("maxOffM", (r.maxOffM * 10).roundToInt() / 10.0) } }
    }

    @Synchronized fun load(text: String) {
        runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            o["resetAt"]?.jsonObject?.forEach { (v, t) -> resetAt[v] = t.jsonPrimitive.long }
            o["how"]?.jsonObject?.forEach { (v, h) -> runCatching { hows[v] = How.valueOf(h.jsonPrimitive.content) } }
            o["stages"]?.jsonObject?.forEach { (v, m) ->
                val map = LinkedHashMap<String, Stage>()
                m.jsonObject.forEach { (id, s) ->
                    val x = s.jsonObject
                    map[id] = Stage(x["status"]!!.jsonPrimitive.content, x["detail"]!!.jsonPrimitive.content, x["at"]!!.jsonPrimitive.long,
                        x["how"]?.jsonPrimitive?.contentOrNull?.let { runCatching { How.valueOf(it) }.getOrNull() })
                }
                stages[v] = map
            }
        }
    }

    private fun persist() = save(buildJsonObject {
        putJsonObject("how") { hows.forEach { (v, h) -> put(v, h.name) } }
        putJsonObject("resetAt") { resetAt.forEach { (v, t) -> put(v, t) } }
        putJsonObject("stages") {
            stages.forEach { (v, m) -> putJsonObject(v) { m.forEach { (id, s) -> putJsonObject(id) { put("status", s.status); put("detail", s.detail); put("at", s.atMs); s.how?.let { put("how", it.name) } } } } }
        }
    }.toString())

    companion object {
        val ORDER = listOf("selftest", "link", "outputs", "stop", "route", "fence", "stop-moving")
        val LABELS = mapOf(
            "selftest" to "Phone self-test", "link" to "Link", "outputs" to "Each output moves the right way", "stop" to "STOP disarms it",
            "route" to "It follows a plan", "fence" to "The fence catches it", "stop-moving" to "It stops when moving",
        )
        val HOW = mapOf(
            "selftest" to "Run the automatic tests below, with the phone still.",
            "link" to "Run the link test below, from where you'll stand.",
            "outputs" to "Wheels off the ground or props off. Run each output both ways below, then say whether each moved the right way.",
            "stop" to "Arm it, then press STOP.",
            "route" to "Make a ground-test plan, send it, and start it. Walking: carry the phone round it. Driving: let it drive, ready on STOP.",
            "fence" to "With the test plan's fence sent and armed, walk or drive out of the fence.",
            "stop-moving" to "Driving at walking pace or more, press STOP. The phone times how long and how far it takes to stop.",
        )
        const val MIN_POINTS = 3
        const val MOVING_MPS = 1.0
        const val STOPPED_MPS = 0.4
        const val STOP_LIMIT_S = 3.0
        const val STOP_GIVE_UP_S = 15.0

        /** How far off the line counts as following it: a person walking wanders more than a vehicle steering. */
        fun offLimitM(how: How, radiusM: Double) = if (how == How.WALK) max(10.0, radiusM * 1.5) else max(5.0, radiusM)

        /** Distance from [p] to the segment [a]-[b], metres, on a local flat frame. */
        fun offLineM(a: Pair<Double, Double>, b: Pair<Double, Double>, p: Pair<Double, Double>): Double {
            val k = cos(Math.toRadians(a.first)) * 111_320.0
            val bx = (b.second - a.second) * k; val by = (b.first - a.first) * 111_320.0
            val px = (p.second - a.second) * k; val py = (p.first - a.first) * 111_320.0
            val len2 = bx * bx + by * by
            val t = if (len2 < 1e-9) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            return hypot(px - t * bx, py - t * by)
        }
    }
}
