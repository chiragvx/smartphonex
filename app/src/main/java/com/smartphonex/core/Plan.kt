package com.smartphonex.core

import kotlinx.serialization.json.*
import java.security.MessageDigest
import kotlin.math.*

/*
 * Plans: what a vehicle should do, written as a file by a person or an AI. The phone compiles a plan into the path the
 * vehicle will really fly or drive (turns, climbs and descents included), a timeline of what happens when, and a list
 * of problems. Problems block the plan; warnings are shown. See docs/PLANNING.md.
 */

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
private fun r1(x: Double) = Math.round(x * 10) / 10.0
private fun r7(x: Double) = Math.round(x * 1e7) / 1e7

data class V2(val x: Double, val y: Double) {
    operator fun plus(o: V2) = V2(x + o.x, y + o.y)
    operator fun minus(o: V2) = V2(x - o.x, y - o.y)
    operator fun times(k: Double) = V2(x * k, y * k)
    val len: Double get() = hypot(x, y)
    fun unit() = if (len < 1e-9) V2(0.0, 1.0) else this * (1 / len)
    fun dot(o: V2) = x * o.x + y * o.y
    fun cross(o: V2) = x * o.y - y * o.x
    /** A quarter turn anticlockwise: to the left of a heading. */
    fun left() = V2(-y, x)
    fun rot(a: Double) = V2(x * cos(a) - y * sin(a), x * sin(a) + y * cos(a))
    companion object {
        /** A unit heading from a compass bearing. x is east, y is north. */
        fun bearing(deg: Double) = V2(sin(Math.toRadians(deg)), cos(Math.toRadians(deg)))
    }
}

/** Metres east and north of an origin. Flat-earth maths, fine across tens of kilometres. */
class LocalFrame(val lat0: Double, val lon0: Double) {
    private val kLat = 111_320.0
    private val kLon = kLat * cos(Math.toRadians(lat0))
    fun xy(lat: Double, lon: Double) = V2((lon - lon0) * kLon, (lat - lat0) * kLat)
    fun xy(p: Pair<Double, Double>) = xy(p.first, p.second)
    fun lat(v: V2) = lat0 + v.y / kLat
    fun lon(v: V2) = lon0 + v.x / kLon
}

/** Exact segment geometry, so a leg can't slip between sample points. */
object PlanGeo {
    fun pointSegDist(p: V2, a: V2, b: V2): Double {
        val d = b - a
        val l2 = d.dot(d)
        val t = if (l2 == 0.0) 0.0 else ((p - a).dot(d) / l2).coerceIn(0.0, 1.0)
        return (a + d * t - p).len
    }
    fun crosses(a: V2, b: V2, c: V2, d: V2): Boolean {
        val d1 = (b - a).cross(c - a); val d2 = (b - a).cross(d - a)
        val d3 = (d - c).cross(a - c); val d4 = (d - c).cross(b - c)
        return (d1 > 0) != (d2 > 0) && (d3 > 0) != (d4 > 0)
    }
    fun segDist(a: V2, b: V2, c: V2, d: V2) =
        if (crosses(a, b, c, d)) 0.0 else minOf(pointSegDist(a, c, d), pointSegDist(b, c, d), pointSegDist(c, a, b), pointSegDist(d, a, b))
    /** Nearest approach of segment ab to the polygon's edges. 0 when it crosses one. */
    fun edgeDist(a: V2, b: V2, poly: List<V2>): Double {
        var best = Double.MAX_VALUE
        for (i in poly.indices) best = minOf(best, segDist(a, b, poly[i], poly[(i + 1) % poly.size]))
        return best
    }
    fun inside(p: V2, poly: List<V2>): Boolean {
        var odd = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) odd = !odd
            j = i
        }
        return odd
    }
}

/** What the vehicle can do, from its settings. The compiler plans with these and reports them. */
data class PlanPerf(
    val vehicleClass: String,
    val speed: Double,
    val speedSource: String,
    val speedMin: Double,
    val climb: Double,
    val sink: Double,
    val bankDeg: Double,
    val groundTurnR: Double,
    val wind: Double,
    val glide: Double,
    val enduranceS: Double,
    val reservePct: Double,
    val margin: Double,
    val landSlopeDeg: Double,
) {
    val plane get() = vehicleClass == "plane"
    /** A plane's turning radius at this airspeed and bank. Ground vehicles and boats have a fixed one. */
    fun turnRadius(v: Double) = if (plane) v * v / (G * tan(Math.toRadians(bankDeg))) else groundTurnR

    companion object {
        const val G = 9.80665
        fun classOf(vehicleType: String) = when (vehicleType) { "plane" -> "plane"; "boat" -> "boat"; else -> "rover" }
        fun from(params: ParamStore, vehicleType: String): PlanPerf {
            fun p(n: String) = params.get(n, vehicleType)
            val cls = classOf(vehicleType)
            val learned = if (cls == "plane" && p("PROP_KQ") > 0 && p("PROP_CRUISE") > 0) p("PROP_CRUISE") else null
            return PlanPerf(
                cls, learned ?: p("PLAN_SPEED"), if (learned != null) "learned cruise" else "PLAN_SPEED", if (cls == "plane") p("PLAN_SPEED_MIN") else 0.0,
                p("PLAN_CLIMB"), p("PLAN_SINK"), p("PLAN_BANK"), p("PLAN_TURN_R"), p("PLAN_WIND"), if (cls == "plane") p("PLAN_GLIDE") else 0.0,
                p("PLAN_ENDURANCE") * 60, p("PLAN_RESERVE"), p("PLAN_MARGIN"), p("PLAN_LAND_SLOPE"),
            )
        }
    }
}

/** What to do when a step can't be done as planned. Waiting (circle or hold) is limited by [maxS], then [then]. */
data class Miss(val act: String, val tolerance: Double = 10.0, val maxS: Double = 0.0, val then: String? = null) {
    val waits get() = act == "circle" || act == "hold"
    fun json() = buildJsonObject {
        put("do", act); put("tolerance", tolerance)
        if (waits) { put("maxS", maxS); put("then", then) }
    }
}
data class Misses(val altitude: Miss, val arrival: Miss, val speed: Miss, val action: Miss) {
    fun json() = buildJsonObject { put("altitude", altitude.json()); put("arrival", arrival.json()); put("speed", speed.json()); put("action", action.json()) }
}

data class PlanIssue(val step: String?, val text: String, val lat: Double? = null, val lon: Double? = null) {
    fun json() = buildJsonObject { put("step", step); put("text", text); lat?.let { put("lat", r7(it)) }; lon?.let { put("lon", r7(it)) } }
}

/** Where and with what the plan is checked. [triggers] maps each trigger id to whether it's dangerous. */
class PlanContext(val perf: PlanPerf, val home: GpsFix?, val fence: Fence, val triggers: Map<String, Boolean>)

class PlanResult(val ok: Boolean, val problems: List<PlanIssue>, val warnings: List<PlanIssue>, val hash: String?, val json: JsonObject)

object PlanCompiler {
    const val FORMAT = "smartphonex.plan/1"
    const val VERSION = 1
    val KINDS = setOf("goto", "path", "hold", "orbit", "survey", "corridor", "takeoff", "land", "home", "repeat")
    private val ID = Regex("^[a-zA-Z0-9_.-]{1,40}$")
    const val MAX_STEPS = 200
    const val MAX_POINTS = 2000

    fun compile(plan: JsonObject, ctx: PlanContext): PlanResult = Run(plan, ctx).go()

    fun defaults(cls: String) = if (cls == "plane")
        Misses(Miss("circle", 10.0, 90.0, "abort"), Miss("abort"), Miss("continue"), Miss("continue"))
    else Misses(Miss("continue"), Miss("hold", 10.0, 30.0, "abort"), Miss("continue"), Miss("continue"))

    private class Orbit(val c: V2, val r: Double, val laps: Double, val cw: Boolean)

    private class Nav(
        val p: V2, val alt: Double, val speed: Double, val radius: Double, val step: String, val kind: String, val phase: String, val miss: Misses,
        val holdS: Double = 0.0, val orbit: Orbit? = null, val triggers: List<String> = emptyList(),
        /** Triggers fired every so many metres along the leg that arrives here. */
        val every: List<Pair<String, Double>> = emptyList(), val notes: List<String> = emptyList(), val first: Boolean = true, val last: Boolean = true,
        /** Reached by turning around from a parallel line, so it arrives lined up. */
        val uturn: Boolean = false,
        /** Arrive heading this way: the start of a line to fly straight. */
        val align: V2? = null,
    )

    private class Smp(val p: V2, val alt: Double, val tm: Double, val tf: Double, val step: String, val phase: String, val turning: Boolean)
    private class Ev(val tm: Double, val tf: Double, val step: String?, val kind: String, val p: V2, val alt: Double, val text: String, val untilTm: Double? = null, val untilTf: Double? = null)

    private class Acts(val now: List<String>, val every: List<Pair<String, Double>>, val notes: List<String>)

    private class Run(val plan: JsonObject, val ctx: PlanContext) {
        val perf = ctx.perf
        val problems = ArrayList<PlanIssue>()
        val warnings = ArrayList<PlanIssue>()
        private val seen = HashSet<String>()
        lateinit var fr: LocalFrame
        var homeV: V2? = null
        var homeSource = "none"
        val navs = ArrayList<Nav>()
        var cur: V2? = null
        var landed = false
        var takeoffDir: V2? = null
        var altIgnored = false

        fun issue(list: MutableList<PlanIssue>, step: String?, text: String, at: V2?, key: String?) {
            if (!seen.add("${list === problems}|$step|${key ?: text}")) return
            list += PlanIssue(step, text, at?.let { fr.lat(it) }, at?.let { fr.lon(it) })
        }
        fun bad(step: String?, text: String, at: V2? = null, key: String? = null) = issue(problems, step, text, at, key)
        fun warn(step: String?, text: String, at: V2? = null, key: String? = null) = issue(warnings, step, text, at, key)

        // --- Plan-wide settings.
        val defaults = plan["defaults"] as? JsonObject ?: JsonObject(emptyMap())
        val misses = parseMisses(plan["miss"] as? JsonObject, PlanCompiler.defaults(perf.vehicleClass), null)
        val defSpeed get() = defaults.d("speed") ?: perf.speed
        val defAlt get() = defaults.d("alt") ?: 50.0
        val defAltRef get() = defaults.s("altRef") ?: "home"

        fun point(e: JsonElement?, step: String?, what: String): V2? {
            val a = e as? JsonArray
            val lat = (a?.getOrNull(0) as? JsonPrimitive)?.doubleOrNull
            val lon = (a?.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) { bad(step, "$what needs a position as [lat, lon]"); return null }
            return fr.xy(lat, lon)
        }
        fun points(e: JsonElement?, step: String, what: String, min: Int): List<V2>? {
            val a = e as? JsonArray
            if (a == null || a.size < min) { bad(step, "$what needs at least $min positions"); return null }
            val ps = a.map { point(it, step, what) ?: return null }
            return ps
        }

        fun latLon(e: JsonElement?): Pair<Double, Double>? {
            val a = e as? JsonArray ?: return null
            val la = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
            val lo = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
            return (la to lo).takeIf { la in -90.0..90.0 && lo in -180.0..180.0 }
        }

        /** Finds a position to centre the local frame on when there's no home. */
        fun firstPosition(arr: JsonArray?): Pair<Double, Double>? {
            arr?.forEach { x ->
                val o = x as? JsonObject ?: return@forEach
                for (k in listOf("at", "center")) latLon(o[k])?.let { return it }
                for (k in listOf("area", "path", "points")) latLon((o[k] as? JsonArray)?.firstOrNull())?.let { return it }
                firstPosition(o["steps"] as? JsonArray)?.let { return it }
            }
            return null
        }

        fun parseMiss(e: JsonElement?, base: Miss, kind: String, step: String?): Miss {
            if (e == null) return base
            val o = e as? JsonObject
            val act = (e as? JsonPrimitive)?.contentOrNull ?: o?.s("do") ?: run { bad(step, "$kind miss needs \"do\""); return base }
            if (act !in setOf("circle", "hold", "continue", "skip", "abort")) { bad(step, "$kind miss must be circle, hold, continue, skip or abort"); return base }
            if (act == "circle" && !perf.plane) { bad(step, "$kind miss: circle is for planes. Use hold"); return base }
            val waits = act == "circle" || act == "hold"
            val tol = o?.d("tolerance") ?: base.tolerance
            if (tol !in 1.0..500.0) bad(step, "$kind miss tolerance must be 1 to 500 m")
            val maxS = o?.d("maxS") ?: if (waits) (base.maxS.takeIf { base.waits && it > 0 } ?: 60.0) else 0.0
            val then = o?.s("then") ?: if (waits) "abort" else null
            if (waits && maxS !in 1.0..600.0) bad(step, "$kind miss: maxS must be 1 to 600 s, so waiting always ends")
            // Fallbacks always end: what follows a wait can't be another wait.
            if (waits && then !in setOf("continue", "skip", "abort")) bad(step, "$kind miss: then must be continue, skip or abort")
            if (!waits && o?.s("then") != null) bad(step, "$kind miss: then only follows circle or hold")
            return Miss(act, tol, maxS, then)
        }
        fun parseMisses(o: JsonObject?, base: Misses, step: String?) = if (o == null) base else Misses(
            parseMiss(o["altitude"], base.altitude, "altitude", step), parseMiss(o["arrival"], base.arrival, "arrival", step),
            parseMiss(o["speed"], base.speed, "speed", step), parseMiss(o["action"], base.action, "action", step),
        )

        fun altOf(o: JsonObject, step: String): Double {
            if (!perf.plane) { if (o["alt"] != null) altIgnored = true; return 0.0 }
            val a = o.d("alt") ?: defAlt
            val alt = when (o.s("altRef") ?: defAltRef) {
                "home" -> a
                "msl" -> ctx.home?.let { a - it.altM } ?: run { bad(step, "altitude above sea level needs a home with a height. Set home first, or use altRef home"); a }
                else -> { bad(step, "altRef must be home or msl"); a }
            }
            if (alt < 5) bad(step, "altitude ${alt.toInt()} m above home is too low to fly a step at. Only a land step goes lower")
            if (ctx.fence.maxAltM > 0 && alt > ctx.fence.maxAltM) bad(step, "altitude ${alt.toInt()} m is above the fence's ${ctx.fence.maxAltM.toInt()} m")
            return alt
        }

        fun actions(arr: JsonArray?, step: String): Acts {
            val now = ArrayList<String>(); val every = ArrayList<Pair<String, Double>>(); val notes = ArrayList<String>()
            arr?.forEach { x ->
                val o = x as? JsonObject ?: run { bad(step, "each action is an object"); return@forEach }
                when (o.s("do")) {
                    "trigger" -> {
                        val id = o.s("id") ?: run { bad(step, "a trigger action needs id"); return@forEach }
                        val danger = ctx.triggers[id] ?: run { bad(step, "unknown trigger $id"); return@forEach }
                        val e = o.d("everyM")
                        if (e == null) now += id
                        else if (e !in 1.0..10000.0) bad(step, "trigger $id: everyM must be 1 to 10000")
                        else if (danger) bad(step, "trigger $id is dangerous: it fires by hand or at a point, never every so many metres")
                        else every += id to e
                    }
                    "notify" -> notes += (o.s("text") ?: "").take(120).ifBlank { bad(step, "notify needs text"); return@forEach }
                    else -> bad(step, "an action must be trigger or notify")
                }
            }
            return Acts(now, every, notes)
        }

        // --- Steps.

        /** Gives every step an id, keeping the ones it has. The plan is sent back with them so edits can refer to them. */
        fun withIds(arr: JsonArray?, explicit: Set<String>, assigned: MutableSet<String>, counts: HashMap<String, Int>): JsonArray = JsonArray((arr ?: JsonArray(emptyList())).map { x ->
            val o = x as? JsonObject ?: return@map x
            val kind = o.s("do") ?: "step"
            var id = o.s("id")
            if (id == null) {
                do { id = "$kind-${counts.merge(kind, 1, Int::plus)}" } while (id in explicit || id in assigned)
                assigned += id!!
            } else {
                if (!ID.matches(id)) bad(id.take(40), "step id must be up to 40 letters, digits, dots, dashes or underscores")
                if (!assigned.add(id)) bad(id, "two steps have the id $id")
            }
            JsonObject(o + ("id" to JsonPrimitive(id)) + (if (o["steps"] is JsonArray) mapOf("steps" to withIds(o["steps"] as JsonArray, explicit, assigned, counts)) else emptyMap()))
        })

        fun defRadius(speed: Double) = defaults.d("radius") ?: if (perf.plane) max(30.0, ceil(perf.turnRadius(speed))) else 5.0

        fun expand(arr: JsonArray, rep: String, depth: Int) {
            for (x in arr) {
                val o = x as? JsonObject
                if (o == null) { bad(null, "each step is an object"); continue }
                val id = o.s("id")!! + rep
                val kind = o.s("do")
                if (kind !in KINDS) { bad(id, "do must be one of ${KINDS.joinToString()}"); continue }
                if (landed) { bad(id, "nothing can follow a land step"); continue }
                if (kind == "repeat") {
                    val times = o.d("times")?.toInt() ?: 0
                    if (depth > 0) { bad(id, "a repeat can't be inside another repeat"); continue }
                    if (times !in 1..50) { bad(id, "repeat times must be 1 to 50"); continue }
                    for (k in 1..times) expand(o["steps"] as? JsonArray ?: run { bad(id, "repeat needs steps"); JsonArray(emptyList()) }, "#$k", depth + 1)
                    continue
                }
                step(o, id, kind!!)
            }
        }

        fun step(o: JsonObject, id: String, kind: String) {
            val speed = o.d("speed") ?: defSpeed
            if (speed !in 0.2..80.0) bad(id, "speed must be 0.2 to 80 m/s")
            if (perf.plane && speed < perf.speedMin) bad(id, "${r1(speed)} m/s is slower than this plane's safe minimum of ${r1(perf.speedMin)} m/s (PLAN_SPEED_MIN)")
            val radius = o.d("radius") ?: defRadius(speed)
            if (radius !in 1.0..1000.0) bad(id, "arrival radius must be 1 to 1000 m")
            val r = perf.turnRadius(speed)
            if (perf.plane && radius < r / 2) bad(id, "arrival radius ${radius.toInt()} m is under half this plane's turning radius of ${r.toInt()} m: it would circle trying to hit the point. Use at least ${ceil(r / 2).toInt()} m")
            val miss = parseMisses(o["miss"] as? JsonObject, misses, id)
            val acts = actions(o["actions"] as? JsonArray, id)
            fun alt() = altOf(o, id)
            fun nav(p: V2, alt: Double, k: String = kind, phase: String = "cruise", holdS: Double = 0.0, orbit: Orbit? = null, first: Boolean = true, last: Boolean = true, every: Boolean = true, uturn: Boolean = false, align: V2? = null) {
                navs += Nav(p, alt, speed, radius, id, k, phase, miss, holdS, orbit,
                    if (last) acts.now else emptyList(), if (every) acts.every else emptyList(), if (last) acts.notes else emptyList(), first, last, uturn, align)
                cur = p
            }
            fun many(ps: List<V2>, alt: Double, everyOn: (Int) -> Boolean, uturn: (Int) -> Boolean = { false }, align: (Int) -> V2? = { null }) {
                if (ps.size + navs.size > MAX_POINTS) { bad(id, "the plan expands to more than $MAX_POINTS points"); return }
                ps.forEachIndexed { i, p -> nav(p, alt, first = i == 0, last = i == ps.lastIndex, every = everyOn(i), uturn = uturn(i), align = align(i)) }
            }
            when (kind) {
                "goto" -> point(o["at"], id, "goto")?.let { nav(it, alt()) }
                "path" -> points(o["points"], id, "path", 2)?.let { ps -> many(ps, alt(), { it > 0 }) }
                "home" -> homeV?.let { nav(it, alt()) } ?: bad(id, "there's no home to go to. Set home on the phone, or give the plan a home")
                "hold" -> {
                    val s = o.d("seconds") ?: 0.0
                    if (s !in 1.0..3600.0) bad(id, "hold seconds must be 1 to 3600")
                    val p = if (o["at"] != null) point(o["at"], id, "hold") else cur
                    if (p == null) bad(id, "hold needs a position: at, or a step before it") else nav(p, alt(), holdS = s)
                }
                "orbit" -> {
                    val c = point(o["center"], id, "orbit") ?: return
                    val rr = o.d("radius") ?: max(r, 20.0)
                    val laps = o.d("laps") ?: 1.0
                    if (laps !in 0.25..50.0) bad(id, "orbit laps must be 0.25 to 50")
                    if (rr !in 2.0..5000.0) bad(id, "orbit radius must be 2 to 5000 m")
                    if (perf.plane && rr < r) bad(id, "orbit radius ${rr.toInt()} m is tighter than this plane can turn at ${r1(speed)} m/s (${ceil(r).toInt()} m)")
                    val from = cur ?: (c + V2(0.0, 1.0))
                    nav(c + (from - c).unit() * rr, alt(), orbit = Orbit(c, rr, laps, (o.s("dir") ?: "cw") != "ccw"))
                }
                "survey" -> {
                    val area = points(o["area"], id, "survey area", 3) ?: return
                    val spacing = o.d("spacing") ?: run { bad(id, "survey needs spacing, the metres between lines"); return }
                    if (spacing !in 1.0..5000.0) { bad(id, "survey spacing must be 1 to 5000 m"); return }
                    val lines = lawnmower(area, spacing, o.d("angle") ?: 0.0)
                    if (lines.isEmpty()) { bad(id, "the survey area is narrower than one line spacing"); return }
                    if (lines.size > 500) { bad(id, "more than 500 survey lines. Widen the spacing"); return }
                    if (perf.plane && spacing < 2 * r) warn(id, "lines are ${spacing.toInt()} m apart, closer than this plane's turning circle (${ceil(2 * r).toInt()} m): each turn swings out past the area", key = "tight")
                    // A plane flies each line straight and level: it lines up before the first, and between lines it turns
                    // around beyond the further end, so it comes onto the next line already lined up.
                    // 0 on the way, 1 a line's end (distance triggers fire along the line), 2 turned around onto, 3 joined lined up.
                    val ps = ArrayList<Pair<V2, Int>>()
                    lines.forEachIndexed { i, (a, b) ->
                        if (i > 0 && perf.plane) {
                            val (pa, pb) = lines[i - 1]
                            val dp = (pb - pa).unit()
                            val ahead = (a - pb).dot(dp)
                            if (ahead > 0.5) ps += pb + dp * ahead to 0
                            val into = a + dp * max(0.0, -ahead)
                            ps += into to 2
                            if ((into - a).len > 0.5) ps += a to 0
                        } else ps += a to if (perf.plane) 3 else 0
                        ps += b to 1
                    }
                    val dir0 = (lines[0].second - lines[0].first).unit()
                    many(ps.map { it.first }, alt(), { ps[it].second == 1 }, { ps[it].second == 2 }, { if (ps[it].second == 3) dir0 else null })
                }
                "corridor" -> {
                    val path = points(o["path"], id, "corridor path", 2) ?: return
                    val width = o.d("width") ?: 0.0
                    val spacing = o.d("spacing") ?: max(width, 1.0)
                    if (width !in 0.0..5000.0 || spacing !in 1.0..5000.0) { bad(id, "corridor width must be 0 to 5000 m and spacing 1 to 5000 m"); return }
                    val n = max(1, ceil(width / spacing).toInt())
                    if (n > 50) { bad(id, "more than 50 corridor passes. Widen the spacing"); return }
                    val passes = (0 until n).map { j ->
                        val off = if (n == 1) 0.0 else -width / 2 + spacing / 2 + j * spacing
                        offset(path, off).let { if (j % 2 == 1) it.reversed() else it }
                    }
                    many(passes.flatten(), alt(), { i -> i % path.size != 0 })
                }
                "takeoff" -> {
                    if (!perf.plane) { bad(id, "takeoff is for planes"); return }
                    if (navs.isNotEmpty()) { bad(id, "takeoff must be the first step"); return }
                    val h = homeV ?: run { bad(id, "takeoff needs a home: set home on the phone, or give the plan a home"); return }
                    val hd = o.d("heading") ?: run { bad(id, "takeoff needs a heading: the compass direction to take off into, usually into the wind"); return }
                    val a = alt()
                    takeoffDir = V2.bearing(hd)
                    nav(h + V2.bearing(hd) * (a * speed / perf.climb + 20), a, phase = "takeoff")
                }
                "land" -> {
                    if (!perf.plane) { bad(id, "land is for planes. Use goto or home, then the plan's end"); return }
                    val t = point(o["at"], id, "land") ?: return
                    val hd = o.d("heading") ?: run { bad(id, "land needs a heading: the compass direction it travels as it lands, usually into the wind"); return }
                    val slope = o.d("slope") ?: perf.landSlopeDeg
                    val hA = o.d("approachAlt") ?: 30.0
                    if (slope !in 2.0..20.0) bad(id, "landing slope must be 2 to 20 degrees")
                    if (hA !in 5.0..200.0) bad(id, "approachAlt must be 5 to 200 m")
                    val needSink = speed * tan(Math.toRadians(slope))
                    if (needSink > perf.sink) bad(id, "a ${r1(slope)}° approach at ${r1(speed)} m/s descends at ${r1(needSink)} m/s, faster than the ${r1(perf.sink)} m/s this plane counts on (PLAN_SINK). Use a shallower slope or slow down")
                    val e = t - V2.bearing(hd) * (hA / tan(Math.toRadians(slope.coerceIn(2.0, 20.0))))
                    nav(e, hA, "approach", "land", first = true, last = false)
                    navs += Nav(t, 0.0, speed, radius, id, "touchdown", "land", miss, triggers = acts.now, notes = acts.notes, first = false, last = true)
                    cur = t
                    landed = true
                }
            }
        }

        /** Survey lines across an area, alternating direction. [angle] is the lines' compass bearing. */
        fun lawnmower(area: List<V2>, spacing: Double, angle: Double): List<Pair<V2, V2>> {
            val th = Math.toRadians(90 - angle)
            val rot = area.map { it.rot(-th) }
            val lines = ArrayList<Pair<V2, V2>>()
            var y = rot.minOf { it.y } + spacing / 2
            val top = rot.maxOf { it.y }
            var k = 0
            while (y < top && lines.size <= 500) {
                val xs = ArrayList<Double>()
                for (i in rot.indices) {
                    val a = rot[i]; val b = rot[(i + 1) % rot.size]
                    if ((a.y > y) != (b.y > y)) xs += a.x + (y - a.y) / (b.y - a.y) * (b.x - a.x)
                }
                xs.sort()
                val row = xs.chunked(2).filter { it.size == 2 && it[1] - it[0] > 0.5 }.map { V2(it[0], y) to V2(it[1], y) }
                (if (k % 2 == 0) row else row.reversed().map { it.second to it.first }).forEach { lines += it.first.rot(th) to it.second.rot(th) }
                if (row.isNotEmpty()) k++
                y += spacing
            }
            return lines
        }

        /** A path moved sideways by [off] metres (left is positive), with mitred corners. */
        fun offset(path: List<V2>, off: Double): List<V2> {
            if (off == 0.0) return path
            val normals = path.zipWithNext { a, b -> (b - a).unit().left() }
            return path.indices.map { i ->
                val n1 = normals[max(0, i - 1)]; val n2 = normals[min(normals.lastIndex, i)]
                val m = (n1 + n2).unit()
                path[i] + m * (off / max(0.3, m.dot(n1)))
            }
        }

        // --- Flying it.

        inner class Sim(val windy: Boolean, val report: Boolean) {
            val samples = ArrayList<Smp>()
            val events = ArrayList<Ev>()
            val everyCount = LinkedHashMap<String, DoubleArray>() // step|trigger -> count, first tm, first tf, last tm, last tf, every
            var pos = V2(0.0, 0.0); var heading = V2(0.0, 1.0); var alt = 0.0
            var tm = 0.0; var tf = 0.0; var dist = 0.0
            var target = 0.0; var speed = perf.speed; var step = ""; var phase = "cruise"
            var turning = false
            fun r() = perf.turnRadius(if (windy) speed + perf.wind else speed)
            fun add() { samples += Smp(pos, alt, tm, tf, step, phase, turning) }
            fun ev(kind: String, text: String, at: V2 = pos) { if (report) events += Ev(tm, tf, step.ifEmpty { null }, kind, at, alt, text) }

            fun move(to: V2) {
                val l = (to - pos).len
                if (l < 1e-9) return
                val dt = l / speed
                if (perf.plane && abs(target - alt) > 1e-6) {
                    val rate = if (target > alt) perf.climb else perf.sink
                    val need = abs(target - alt) / rate
                    if (need < dt) {
                        pos += (to - pos) * (need / dt); tm += need; dist += need * speed; alt = target; add()
                        tm += dt - need; dist += l - need * speed; pos = to; add()
                        return
                    }
                    alt += sign(target - alt) * rate * dt
                }
                tm += dt; dist += l; pos = to; add()
            }

            /** Turns along the vehicle's turning circle until it points at [q]. If [q] is inside the circle, it goes on straight first. */
            fun turnToward(q: V2) {
                val rr = r()
                if (rr < 0.5) { heading = (q - pos).unit(); return }
                turning = true
                var guard = 0
                while (guard++ < 400) {
                    val to = q - pos
                    if (to.len < 1e-6) break
                    val ang = atan2(heading.cross(to), heading.dot(to))
                    if (abs(ang) < Math.toRadians(2.0)) break
                    val s = sign(ang)
                    val c = pos + heading.left() * (s * rr)
                    if ((q - c).len < rr * 1.02) { move(pos + heading * max(2.0, rr / 5)); continue }
                    val da = s * min(abs(ang), Math.toRadians(5.0))
                    move(c + (pos - c).rot(da))
                    heading = heading.rot(da)
                }
                turning = false
            }

            /** How far to turn from one heading to another, going left (side 1) or right (side -1), 0 to 2π. */
            fun turnAngle(from: V2, to: V2, side: Double): Double {
                var a = atan2(from.cross(to), from.dot(to)) * side
                if (a < -1e-9) a += 2 * PI
                return max(0.0, a)
            }

            /**
             * Flies the shortest turn-straight-turn path (Dubins) to [q], arriving with heading [hq]: how a plane joins
             * a line so it's lined up from the start. False if there's none (a ground vehicle pivots instead).
             */
            fun join(q: V2, hq: V2): Boolean {
                val rr = r()
                if (rr < 0.5) return false
                var best: DoubleArray? = null
                var s0b = 0.0; var s1b = 0.0; var t1b = q
                for (s0 in doubleArrayOf(1.0, -1.0)) for (s1 in doubleArrayOf(1.0, -1.0)) {
                    val c0 = pos + heading.left() * (s0 * rr)
                    val c1 = q + hq.left() * (s1 * rr)
                    val w = c1 - c0
                    val d = w.len
                    val u = if (s0 == s1) { if (d < 1e-9) continue; w.unit() }
                    else { if (d < 2 * rr) continue; w.unit().rot(atan2(2 * s0 * rr, sqrt(d * d - 4 * rr * rr))) }
                    val t0 = c0 - u.left() * (s0 * rr)
                    val t1 = c1 - u.left() * (s1 * rr)
                    if ((t1 - t0).dot(u) < -1e-6) continue
                    val a0 = turnAngle(heading, u, s0); val a1 = turnAngle(u, hq, s1)
                    val len = rr * (a0 + a1) + (t1 - t0).len
                    if (best == null || len < best[0]) { best = doubleArrayOf(len, a0, a1); s0b = s0; s1b = s1; t1b = t1 }
                }
                val b = best ?: return false
                turning = true; arc(s0b, b[1], rr); turning = false
                move(t1b)
                turning = true; arc(s1b, b[2], rr); turning = false
                heading = hq
                return true
            }

            /** An arc of [ang] radians, turning left (side 1) or right (side -1). */
            fun arc(side: Double, ang: Double, rr: Double) {
                val c = pos + heading.left() * (side * rr)
                val n = max(1, ceil(ang / Math.toRadians(5.0)).toInt())
                val da = side * ang / n
                repeat(n) { move(c + (pos - c).rot(da)); heading = heading.rot(da) }
            }

            /**
             * Turns around onto the parallel line through [q], arriving lined up. Lines at least a turning circle apart
             * take a U: a quarter turn, a straight, a quarter turn. Closer lines take an omega: out, then round.
             */
            fun uTurn(q: V2) {
                val to = q - pos
                val side = if (heading.cross(to) >= 0) 1.0 else -1.0
                val s = abs(heading.cross(to))
                val rr = r()
                if (rr < 0.5) { heading = to.unit(); return }
                turning = true
                val h0 = heading
                if (s >= 2 * rr) { arc(side, PI / 2, rr); move(pos + heading * (s - 2 * rr)); arc(side, PI / 2, rr) }
                else {
                    val a = acos(((rr + s / 2) / (2 * rr)).coerceIn(-1.0, 1.0))
                    arc(-side, a, rr); arc(side, PI + 2 * a, rr)
                }
                heading = h0 * -1.0
                turning = false
            }

            /** Circles beside the current position, for holds and for climbing at a point. Time is added by the caller. */
            fun circle(rr: Double, laps: Double = 1.0, cw: Boolean = true, timed: Boolean = false, centre: V2? = null) {
                if (rr < 0.5) return
                val s = if (cw) -1.0 else 1.0
                val c = centre ?: (pos + heading.left() * (s * rr))
                val steps = max(4, (laps * 36).toInt())
                val da = s * laps * 2 * PI / steps
                turning = true
                repeat(steps) {
                    val np = c + (pos - c).rot(da)
                    if (timed) move(np) else { pos = np; add() }
                    heading = heading.rot(da)
                }
                turning = false
            }

            fun run(): Sim {
                val first = navs.firstOrNull() ?: return this
                val start = homeV ?: first.p
                pos = start
                heading = takeoffDir ?: (first.p - start).unit()
                alt = if (perf.plane && takeoffDir == null) first.alt else 0.0
                add()
                ev("start", "start")
                for (n in navs) {
                    step = n.step; phase = n.phase; speed = n.speed; target = if (perf.plane) n.alt else 0.0
                    if (n.first && !n.last) ev("begin", "begins ${n.kind}")
                    // Distance triggers fire along the straight part of a leg, once the turn onto it is done.
                    var d0 = dist; var tm0 = tm; var tf0 = tf
                    var start = pos
                    if ((n.p - pos).len > 1e-6) {
                        val joined = when {
                            n.uturn -> { uTurn(n.p); true }
                            n.align != null -> join(n.p, n.align)
                            else -> false
                        }
                        if (joined) { pos = n.p; add() }
                        else {
                            turnToward(n.p)
                            d0 = dist; tm0 = tm; tf0 = tf; start = pos
                            heading = (n.p - pos).unit()
                            move(n.p)
                        }
                    }
                    for ((t, every) in n.every) {
                        val l = dist - d0
                        if (l < 1e-6) continue
                        val k = "${n.step}|$t"
                        val a = everyCount.getOrPut(k) { doubleArrayOf(0.0, tm0, tf0, 0.0, 0.0, every, start.x, start.y, alt) }
                        a[0] += floor(l / every + 1e-6) + 1; a[3] = tm; a[4] = tf
                    }
                    arrive(n)
                    n.triggers.forEach { ev("action", "trigger $it") }
                    n.notes.forEach { ev("notify", it) }
                    if (n.holdS > 0) {
                        ev("hold", "holds ${n.holdS.toInt()} s" + if (perf.plane) ", circling" else "")
                        if (perf.plane) circle(max(r(), n.radius))
                        tf += n.holdS; add()
                    }
                    n.orbit?.let { o ->
                        ev("orbit", "orbits ${r1(o.laps)} × at ${o.r.toInt()} m")
                        circle(o.r, o.laps, o.cw, timed = true, centre = o.c)
                    }
                    if (n.last) when {
                        n.kind == "touchdown" -> ev("land", "touches down")
                        n.first -> ev("arrive", "arrives (${n.kind})")
                        else -> ev("end", "ends ${n.kind}")
                    }
                }
                if (report) everyCount.forEach { (k, a) ->
                    val (st, t) = k.split("|")
                    events += Ev(a[1], a[2], st, "action", V2(a[6], a[7]), a[8], "trigger $t ×${a[0].toInt()}, every ${a[5].toInt()} m", a[3], a[4])
                }
                if (!landed) ev("end", "plan ends")
                return this
            }

            /** On arrival, if the altitude wasn't reached, the step's altitude miss rule decides. */
            fun arrive(n: Nav) {
                if (!perf.plane || n.phase == "land" && n.kind == "touchdown") return
                val off = n.alt - alt
                val m = n.miss.altitude
                if (abs(off) <= m.tolerance) return
                val rate = if (off > 0) perf.climb else perf.sink
                val need = abs(off) / rate
                val verb = if (off > 0) "climb" else "descend"
                val have = "reaches it at ${alt.toInt()} m of ${n.alt.toInt()} m"
                if (!report) {
                    if (m.waits) { circle(r()); tf += min(need, m.maxS); alt += sign(off) * rate * min(need, m.maxS); add() }
                    return
                }
                when {
                    m.waits -> {
                        val t = min(need, m.maxS)
                        ev("circle", "circles ${t.toInt()} s to $verb to ${n.alt.toInt()} m")
                        circle(r()); tf += t; alt += sign(off) * rate * t; add()
                        if (need <= m.maxS) warn(n.step, "$have, then circles about ${ceil(need).toInt()} s to $verb. A longer leg avoids it", n.p, "alt")
                        else if (m.then == "abort") bad(n.step, "$have and can't $verb the rest in ${m.maxS.toInt()} s of circling, so it would abort. Lengthen the leg, change the altitude, or allow longer", n.p, "alt")
                        else warn(n.step, "$have; after ${m.maxS.toInt()} s of circling it's still ${abs(n.alt - alt).toInt()} m off and carries on (${m.then})", n.p, "alt")
                    }
                    m.act == "abort" -> bad(n.step, "$have, and its altitude miss rule is abort. Lengthen the leg, change the altitude, or let it circle", n.p, "alt")
                    else -> warn(n.step, "$have and keeps going, still ${verb}ing (${m.act})", n.p, "alt")
                }
            }
        }

        // --- Checks on the path as flown.

        fun checkFence(s: List<Smp>) {
            val f = ctx.fence
            val m = perf.margin
            val poly = if (f.polygon.size >= 3) f.polygon.map { fr.xy(it) } else null
            val keeps = f.keepOut.filter { it.size >= 3 }.map { k -> k.map { fr.xy(it) } }
            fun why(b: Smp, what: String) = if (b.turning) "turning here, ${if (perf.plane) "the plane" else "it"} $what. Move the point in, slow down, or allow a tighter turn" else what
            if (poly != null && s.isNotEmpty() && !PlanGeo.inside(s[0].p, poly)) bad(s[0].step.ifEmpty { null }, "starts outside the fence", s[0].p, "fence")
            for (i in 1 until s.size) {
                val a = s[i - 1]; val b = s[i]
                val st = b.step.ifEmpty { null }
                if (poly != null) {
                    val d = PlanGeo.edgeDist(a.p, b.p, poly)
                    if (d == 0.0) bad(st, why(b, "goes outside the fence"), b.p, "fence")
                    else if (d < m) bad(st, why(b, "passes ${d.toInt()} m from the fence, inside the ${m.toInt()} m margin (PLAN_MARGIN)"), b.p, "fence")
                }
                keeps.forEachIndexed { k, kp ->
                    val d = PlanGeo.edgeDist(a.p, b.p, kp)
                    if (d == 0.0 || PlanGeo.inside(b.p, kp)) bad(st, why(b, "goes into keep-out area ${k + 1}"), b.p, "keep$k")
                    else if (d < m) bad(st, why(b, "passes ${d.toInt()} m from keep-out area ${k + 1}, inside the ${m.toInt()} m margin"), b.p, "keep$k")
                }
                val h = homeV
                if (h != null && f.maxDistM > 0 && (b.p - h).len > f.maxDistM - m) bad(st, why(b, "goes ${(b.p - h).len.toInt()} m from home; the fence allows ${f.maxDistM.toInt()} m less the ${m.toInt()} m margin"), b.p, "dist")
                if (perf.plane && f.maxAltM > 0 && b.alt > f.maxAltM + 0.5) bad(st, "climbs to ${b.alt.toInt()} m, above the fence's ${f.maxAltM.toInt()} m", b.p, "alt-cap")
            }
        }

        class Zone(val id: String, val kind: String, val p: V2, val r: Double)
        val zones = ArrayList<Zone>()

        /** Gaps where the vehicle couldn't reach safety: on battery (always enforced) and, for planes, gliding with the motor off. */
        fun checkReach(s: List<Smp>, fHi: Double): JsonArray {
            val gaps = ArrayList<JsonObject>()
            if (zones.isEmpty()) { warn(null, "no home and no safe zones, so battery and glide reach can't be checked", key = "nozones"); return JsonArray(gaps) }
            val usable = perf.enduranceS * (1 - perf.reservePct / 100)
            val vg = max(0.1, perf.speed - perf.wind)
            val bvlos = plan.b("bvlos") == true
            var open: Triple<String, Smp, Double>? = null
            var last: Smp? = null
            // Gaps are found with the slow (worst-case) clock, and shown on the expected one like the rest of the timeline.
            fun close(at: Smp?) {
                val (kind, from, t0) = open ?: return
                val t1 = at?.let { it.tm + it.tf } ?: t0
                gaps += buildJsonObject {
                    put("kind", kind); put("step", from.step); put("fromS", t0.roundToInt()); put("toS", t1.roundToInt())
                    put("lat", r7(fr.lat(from.p))); put("lon", r7(fr.lon(from.p)))
                }
                val span = "T+${clock(t0)} to T+${clock(t1)}"
                if (kind == "battery") bad(from.step.ifEmpty { null }, "$span: not enough battery to reach home or a landing zone and keep the ${perf.reservePct.toInt()}% reserve", from.p, "battery")
                else (if (bvlos) ::bad else ::warn)(from.step.ifEmpty { null }, "$span: no landing or ditch zone within gliding distance if the motor stops" + if (bvlos) ". Beyond line of sight, add a zone or fly higher" else "", from.p, "glide$t0")
                open = null
            }
            fun look(p: V2, sm: Smp, alt: Double, tm: Double, tf: Double) {
                val near = zones.minOf { ((it.p - p).len - it.r).coerceAtLeast(0.0) }
                val kind = when {
                    usable - (tm * fHi + tf) < near / vg -> "battery"
                    perf.plane && perf.glide > 0 && sm.phase == "cruise" && near > alt * perf.glide * vg / perf.speed -> "glide"
                    else -> null
                }
                val here = Smp(p, alt, tm, tf, sm.step, sm.phase, false)
                val o = open
                if (o != null && o.first != kind) close(here)
                if (kind != null && open == null) open = Triple(kind, here, tm + tf)
            }
            for (i in s.indices) {
                val b = s[i]; val a = last ?: b
                val n = max(1, ceil((b.p - a.p).len / 25).toInt())
                for (j in 1..n) {
                    val f = j.toDouble() / n
                    look(a.p + (b.p - a.p) * f, b, a.alt + (b.alt - a.alt) * f, a.tm + (b.tm - a.tm) * f, b.tf)
                }
                last = b
            }
            close(last)
            return JsonArray(gaps)
        }

        fun clock(s: Double): String { val t = s.roundToInt(); return "%d:%02d".format(t / 60, t % 60) }

        fun go(): PlanResult {
            val stepsIn = plan["steps"] as? JsonArray
            val h = ctx.home
            val planHome = latLon(plan["home"])
            val origin = h?.let { it.lat to it.lon } ?: planHome ?: firstPosition(stepsIn) ?: (0.0 to 0.0)
            fr = LocalFrame(origin.first, origin.second)
            when {
                h != null -> { homeV = fr.xy(h.lat, h.lon); homeSource = "phone" }
                planHome != null -> { homeV = fr.xy(planHome.first, planHome.second); homeSource = "plan"; warn(null, "the phone has no home yet: checked from the plan's home. It's checked again against the real home when sent", key = "planhome") }
            }
            if (h != null && planHome != null) {
                val d = (fr.xy(planHome.first, planHome.second) - homeV!!).len
                if (d > 100) warn(null, "this plan's home is ${d.toInt()} m from the phone's home: it was made for somewhere else", key = "farhome")
            }

            if (plan.s("format") != FORMAT) bad(null, "format must be $FORMAT")
            val name = plan.s("name") ?: "Plan"
            plan.s("vehicle")?.let { if (it != perf.vehicleClass) bad(null, "this plan is for a $it, but the vehicle is a ${perf.vehicleClass}") }
            val onEnd = plan.s("onEnd") ?: "hold"
            if (onEnd !in setOf("hold", "home", "stop")) bad(null, "onEnd must be hold, home or stop")
            if (onEnd == "stop" && perf.plane) bad(null, "a plane can't stop in the air. End with home, or a land step")
            val onLinkLoss = plan.s("onLinkLoss") ?: "continue"
            if (onLinkLoss !in setOf("continue", "hold", "home")) bad(null, "onLinkLoss must be continue, hold or home")
            if (stepsIn.isNullOrEmpty()) bad(null, "a plan needs at least one step")

            val used = HashSet<String>()
            fun collect(a: JsonArray?) { a?.forEach { x -> (x as? JsonObject)?.let { o -> o.s("id")?.let(used::add); collect(o["steps"] as? JsonArray) } } }
            collect(stepsIn)
            val steps = withIds(stepsIn, used, HashSet(), HashMap())
            if (steps.size > MAX_STEPS) bad(null, "at most $MAX_STEPS steps")

            homeV?.let { zones += Zone("home", "landing", it, 0.0) }
            (plan["zones"] as? JsonArray)?.forEachIndexed { i, x ->
                val o = x as? JsonObject ?: return@forEachIndexed
                val id = o.s("id") ?: "zone-${i + 1}"
                val kind = o.s("kind") ?: "landing"
                if (kind !in setOf("landing", "ditch")) bad(null, "zone $id: kind must be landing or ditch")
                val r = o.d("radius") ?: 20.0
                if (r !in 1.0..5000.0) bad(null, "zone $id: radius must be 1 to 5000 m")
                point(o["at"], null, "zone $id")?.let { zones += Zone(id, kind, it, r) }
            }
            if (zones.size > 51) bad(null, "at most 50 zones")

            cur = homeV
            expand(steps, "", 0)
            if (perf.plane && navs.isNotEmpty() && takeoffDir == null) warn(null, "no takeoff step: planned as if launched to ${navs[0].alt.toInt()} m at the start", key = "notakeoff")
            if (altIgnored) warn(null, "altitudes are ignored: a ${perf.vehicleClass} stays on the surface", key = "alt")
            if (perf.wind >= perf.speed * 0.9) bad(null, "the planned wind of ${r1(perf.wind)} m/s is nearly as fast as the vehicle's ${r1(perf.speed)} m/s: it couldn't make headway")

            val sim = Sim(windy = false, report = true).run()
            // Fences are checked on the widest path: turns flown downwind in the planned wind.
            val wide = if (perf.wind > 0 && perf.plane) Sim(windy = true, report = false).run() else sim
            checkFence(wide.samples)
            val fLo = perf.speed / (perf.speed + perf.wind)
            val fHi = perf.speed / max(0.1, perf.speed - perf.wind)
            val lo = if (perf.plane) fLo else min(fLo, 0.9)
            val hi = if (perf.plane) fHi else max(fHi, 1.1)
            val gaps = checkReach(sim.samples, hi)

            val endTm = sim.tm; val endTf = sim.tf
            val usedPct = if (perf.enduranceS > 0) (endTm * hi + endTf) / perf.enduranceS * 100 else 0.0
            val assumptions = buildJsonObject {
                put("vehicle", perf.vehicleClass); put("speed", r1(perf.speed)); put("speedSource", perf.speedSource)
                put("wind", r1(perf.wind)); put("enduranceMin", r1(perf.enduranceS / 60)); put("reservePct", perf.reservePct); put("margin", perf.margin)
                if (perf.plane) {
                    put("climb", perf.climb); put("sink", perf.sink); put("bank", perf.bankDeg); put("glide", perf.glide)
                    put("turnRadius", r1(perf.turnRadius(perf.speed))); put("turnRadiusWind", r1(perf.turnRadius(perf.speed + perf.wind)))
                } else put("turnRadius", perf.groundTurnR)
                put("home", homeSource); put("compiler", VERSION)
            }
            val points = JsonArray(navs.map { n ->
                buildJsonObject {
                    put("lat", r7(fr.lat(n.p))); put("lon", r7(fr.lon(n.p))); put("alt", r1(n.alt)); put("step", n.step); put("kind", n.kind)
                    put("speed", n.speed); put("radius", n.radius); if (n.holdS > 0) put("holdS", n.holdS)
                    if (n.triggers.isNotEmpty()) putJsonArray("triggers") { n.triggers.forEach { add(it) } }
                    if (n.every.isNotEmpty()) putJsonArray("every") { n.every.forEach { (t, e) -> addJsonObject { put("trigger", t); put("m", e) } } }
                    n.orbit?.let { o -> putJsonObject("orbit") { put("lat", r7(fr.lat(o.c))); put("lon", r7(fr.lon(o.c))); put("radius", o.r); put("laps", o.laps); put("cw", o.cw) } }
                }
            })
            val hash = if (navs.isEmpty()) null else MessageDigest.getInstance("SHA-256")
                .digest((points.toString() + assumptions.toString() + onEnd + onLinkLoss).toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
            val stride = max(1, sim.samples.size / 4000)
            val path = JsonArray(sim.samples.filterIndexed { i, _ -> i % stride == 0 || i == sim.samples.lastIndex }.map { s ->
                buildJsonArray { add(r7(fr.lat(s.p))); add(r7(fr.lon(s.p))); add(r1(s.alt)); add(r1(s.tm + s.tf)) }
            })
            fun t(tm: Double, tf: Double) = r1(tm + tf)
            val timeline = JsonArray(sim.events.sortedBy { it.tm + it.tf }.map { e ->
                buildJsonObject {
                    put("t", t(e.tm, e.tf)); put("lo", t(e.tm * lo, e.tf)); put("hi", t(e.tm * hi, e.tf))
                    put("step", e.step); put("kind", e.kind); put("text", e.text)
                    put("lat", r7(fr.lat(e.p))); put("lon", r7(fr.lon(e.p))); if (perf.plane) put("alt", r1(e.alt))
                    if (e.untilTm != null) put("until", t(e.untilTm, e.untilTf ?: 0.0))
                }
            })
            val ok = problems.isEmpty()
            val json = buildJsonObject {
                put("ok", ok)
                put("problems", JsonArray(problems.map { it.json() }))
                put("warnings", JsonArray(warnings.map { it.json() }))
                put("hash", if (ok) hash else null)
                putJsonObject("summary") {
                    put("name", name); put("points", navs.size); put("distanceM", sim.dist.roundToInt())
                    put("timeS", t(endTm, endTf)); put("timeLoS", t(endTm * lo, endTf)); put("timeHiS", t(endTm * hi, endTf))
                    put("batteryPct", usedPct.roundToInt())
                    if (perf.plane) put("maxAltM", r1(sim.samples.maxOfOrNull { it.alt } ?: 0.0))
                    put("onEnd", onEnd); put("onLinkLoss", onLinkLoss)
                }
                put("assumptions", assumptions)
                put("timeline", timeline)
                put("gaps", gaps)
                putJsonArray("zones") { zones.forEach { z -> addJsonObject { put("id", z.id); put("kind", z.kind); put("lat", r7(fr.lat(z.p))); put("lon", r7(fr.lon(z.p))); put("radius", z.r) } } }
                put("points", points)
                put("path", path)
                put("misses", misses.json())
                put("plan", JsonObject(plan + ("steps" to steps)))
            }
            return PlanResult(ok, problems, warnings, if (ok) hash else null, json)
        }
    }
}
