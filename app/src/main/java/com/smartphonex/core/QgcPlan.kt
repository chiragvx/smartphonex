package com.smartphonex.core

import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * QGroundControl's .plan files, in and out, so plans can come from and go to ArduPilot and PX4 tools.
 *
 * Export writes the compiled route, not the templates: any autopilot then flies the same checked path, with
 * MAVLink commands for takeoff, waypoints, holds, orbits, landing, camera distance triggers and speed changes,
 * plus the fence and the safe zones as rally points. The plan file itself rides along under "x-smartphonex", so a
 * round trip through QGroundControl loses nothing.
 *
 * Import turns a QGroundControl mission into plan steps, surveys and corridor scans included, and reports anything
 * it can't carry over rather than dropping it silently.
 */
object QgcPlan {
    // MAVLink commands.
    const val NAV_WAYPOINT = 16
    const val NAV_LOITER_UNLIM = 17
    const val NAV_LOITER_TURNS = 18
    const val NAV_LOITER_TIME = 19
    const val NAV_RETURN_TO_LAUNCH = 20
    const val NAV_LAND = 21
    const val NAV_TAKEOFF = 22
    const val DO_CHANGE_SPEED = 178
    const val DO_SET_CAM_TRIGG_DIST = 206
    /** Altitude above home. */
    private const val FRAME_RELATIVE = 3
    private const val FRAME_MSL = 0

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonArray.d(i: Int) = (getOrNull(i) as? JsonPrimitive)?.doubleOrNull
    private fun ll(lat: Double, lon: Double) = buildJsonArray { add(lat); add(lon) }

    /** MAV_TYPE: what QGroundControl should treat the vehicle as. */
    private fun mavType(cls: String) = when (cls) { "plane" -> 1; "boat" -> 11; else -> 10 }

    /** A compiled plan as a QGroundControl .plan file. [plan] is the plan file, [fence] the phone's. */
    fun export(r: PlanResult, plan: JsonObject, fence: Fence, home: GpsFix?, perf: PlanPerf): JsonObject {
        val m = r.mission ?: error("only a plan that checks out can be exported")
        val items = ArrayList<JsonObject>()
        var seq = 1
        fun item(cmd: Int, p: List<Double?>, frame: Int = FRAME_RELATIVE) = items.add(buildJsonObject {
            put("type", "SimpleItem"); put("autoContinue", true); put("command", cmd); put("doJumpId", seq++); put("frame", frame)
            putJsonArray("params") { p.forEach { if (it == null) add(JsonNull) else add(it) } }
        })
        val plane = perf.plane
        var speed = perf.speed
        var every: Double? = null
        for (p in m.items) {
            val alt = p.alt ?: 0.0
            val s = p.speed ?: perf.speed
            if (abs(s - speed) > 0.05) { item(DO_CHANGE_SPEED, listOf(if (plane) 0.0 else 1.0, s, -1.0, 0.0, 0.0, 0.0, 0.0), frame = 2); speed = s }
            // Distance triggers run along the leg into this point, and stop after it.
            val e = p.every.firstOrNull()?.second
            if (e != every) { item(DO_SET_CAM_TRIGG_DIST, listOf(e ?: 0.0, 0.0, if (e != null) 1.0 else 0.0, 0.0, 0.0, 0.0, 0.0), frame = 2); every = e }
            when {
                p.kind == "takeoff" -> item(NAV_TAKEOFF, listOf(15.0, 0.0, 0.0, null, p.lat, p.lon, alt))
                p.kind == "touchdown" -> item(NAV_LAND, listOf(0.0, 0.0, 0.0, null, p.lat, p.lon, 0.0))
                p.orbit != null -> {
                    item(NAV_WAYPOINT, listOf(0.0, p.radiusM, 0.0, null, p.lat, p.lon, alt))
                    val o = p.orbit
                    // Positive radius circles clockwise.
                    item(NAV_LOITER_TURNS, listOf(o.laps, 0.0, if (o.cw) o.radiusM else -o.radiusM, 1.0, o.lat, o.lon, alt))
                }
                p.holdS > 0 && plane -> item(NAV_LOITER_TIME, listOf(p.holdS, 0.0, p.radiusM, 1.0, p.lat, p.lon, alt))
                else -> item(NAV_WAYPOINT, listOf(p.holdS, p.radiusM, 0.0, null, p.lat, p.lon, alt))
            }
        }
        if (every != null) item(DO_SET_CAM_TRIGG_DIST, listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), frame = 2)
        if (m.onEnd == "home") item(NAV_RETURN_TO_LAUNCH, listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), frame = 2)
        return buildJsonObject {
            put("fileType", "Plan"); put("version", 1); put("groundStation", "SmartPhoneX")
            putJsonObject("mission") {
                put("version", 2); put("firmwareType", 3); put("vehicleType", mavType(perf.vehicleClass))
                put("cruiseSpeed", perf.speed); put("hoverSpeed", perf.speed)
                val h = home
                putJsonArray("plannedHomePosition") { add(h?.lat ?: m.items.first().lat); add(h?.lon ?: m.items.first().lon); add(h?.altM ?: 0.0) }
                put("items", JsonArray(items))
            }
            putJsonObject("geoFence") {
                put("version", 2)
                putJsonArray("circles") {
                    if (fence.maxDistM > 0 && home != null) addJsonObject {
                        putJsonObject("circle") { putJsonArray("center") { add(home.lat); add(home.lon) }; put("radius", fence.maxDistM) }
                        put("inclusion", true); put("version", 1)
                    }
                }
                putJsonArray("polygons") {
                    if (fence.polygon.size >= 3) addJsonObject { put("inclusion", true); put("polygon", JsonArray(fence.polygon.map { ll(it.first, it.second) })); put("version", 1) }
                    fence.keepOut.filter { it.size >= 3 }.forEach { k -> addJsonObject { put("inclusion", false); put("polygon", JsonArray(k.map { ll(it.first, it.second) })); put("version", 1) } }
                }
            }
            putJsonObject("rallyPoints") {
                put("version", 2)
                putJsonArray("points") { m.zones.filter { it.id != "home" }.forEach { z -> addJsonArray { add(z.lat); add(z.lon); add(0.0) } } }
            }
            put("x-smartphonex", plan)
        }
    }

    class Imported(val plan: JsonObject, val fence: Fence?, val notes: List<String>)

    /** A QGroundControl .plan file as a plan file, and its fence. Throws for something that isn't one. */
    fun import(q: JsonObject): Imported {
        if (q.s("fileType") != "Plan") throw IllegalArgumentException("not a QGroundControl plan file")
        val notes = ArrayList<String>()
        val fence = importFence(q["geoFence"] as? JsonObject, q, notes)
        // Ours, round-tripped: the plan file as it was.
        (q["x-smartphonex"] as? JsonObject)?.let { return Imported(it, fence, notes + "the SmartPhoneX plan inside the file was used as it was") }

        val mission = q["mission"] as? JsonObject ?: throw IllegalArgumentException("the plan has no mission")
        val home = (mission["plannedHomePosition"] as? JsonArray)?.let { a -> a.d(0)?.let { la -> a.d(1)?.let { lo -> la to lo } } }
        val steps = ArrayList<JsonObject>()
        var speed: Double? = null
        var every: Double? = null
        var n = 0
        fun id(kind: String) = "$kind-${++n}"
        fun JsonObjectBuilder.common() {
            speed?.let { put("speed", it) }
            every?.let { e -> putJsonArray("actions") { addJsonObject { put("do", "trigger"); put("id", "photo"); put("everyM", e) } } }
        }
        fun altOf(frame: Int?, alt: Double?) = Pair(alt ?: 0.0, if (frame == FRAME_MSL) "msl" else "home")
        var lastAt: Pair<Double, Double>? = home
        fun bearing(a: Pair<Double, Double>?, lat: Double, lon: Double) = a?.let { Navigation.bearingDeg(it.first, it.second, lat, lon) } ?: 0.0
        val items = mission["items"] as? JsonArray ?: JsonArray(emptyList())
        for ((i, x) in items.withIndex()) {
            val o = x as? JsonObject ?: continue
            when (o.s("type")) {
                "SimpleItem" -> {
                    val cmd = o.d("command")?.toInt()
                    val p = o["params"] as? JsonArray ?: JsonArray(emptyList())
                    val lat = p.d(4); val lon = p.d(5)
                    val (alt, ref) = altOf(o.d("frame")?.toInt(), p.d(6))
                    fun at(b: JsonObjectBuilder) { if (lat != null && lon != null) b.put("at", ll(lat, lon)); b.put("alt", alt); if (ref == "msl") b.put("altRef", "msl") }
                    when (cmd) {
                        NAV_WAYPOINT -> {
                            if (lat == null || lon == null) { notes += "item ${i + 1}: a waypoint without a position was skipped"; continue }
                            val hold = p.d(0) ?: 0.0
                            steps += buildJsonObject {
                                put("do", if (hold > 0) "hold" else "goto"); put("id", id(if (hold > 0) "hold" else "goto")); at(this)
                                if (hold > 0) put("seconds", hold)
                                p.d(1)?.takeIf { it >= 1 }?.let { put("radius", it) }
                                common()
                            }
                            lastAt = lat to lon
                        }
                        NAV_TAKEOFF -> {
                            // QGroundControl's takeoff has no direction: towards the next point, as near as can be said.
                            val next = items.drop(i + 1).firstNotNullOfOrNull { y -> ((y as? JsonObject)?.get("params") as? JsonArray)?.let { a -> a.d(4)?.let { la -> a.d(5)?.let { lo -> la to lo } } }?.takeIf { it.first != 0.0 } }
                            val from = home ?: (if (lat != null && lon != null) lat to lon else null)
                            val hd = if (next != null) bearing(from, next.first, next.second) else 0.0
                            notes += "take off heads ${hd.toInt()}°, towards the first point: set it into the wind"
                            steps += buildJsonObject { put("do", "takeoff"); put("id", id("takeoff")); put("heading", hd); put("alt", alt) }
                        }
                        NAV_LAND -> {
                            if (lat == null || lon == null || (lat == 0.0 && lon == 0.0)) { notes += "item ${i + 1}: land where it is isn't carried over: add a land step"; continue }
                            steps += buildJsonObject { put("do", "land"); put("id", id("land")); put("at", ll(lat, lon)); put("heading", bearing(lastAt, lat, lon)) }
                            notes += "landing heads ${bearing(lastAt, lat, lon).toInt()}°, the way it arrives: set it into the wind"
                        }
                        NAV_LOITER_TURNS -> {
                            if (lat == null || lon == null) continue
                            val r = p.d(2) ?: 0.0
                            steps += buildJsonObject {
                                put("do", "orbit"); put("id", id("orbit")); put("center", ll(lat, lon)); put("alt", alt)
                                put("laps", (p.d(0) ?: 1.0).coerceIn(0.25, 50.0)); if (abs(r) > 0) put("radius", abs(r)); put("dir", if (r < 0) "ccw" else "cw")
                                common()
                            }
                        }
                        NAV_LOITER_TIME, NAV_LOITER_UNLIM -> {
                            val secs = if (cmd == NAV_LOITER_TIME) (p.d(0) ?: 30.0) else 600.0
                            if (cmd == NAV_LOITER_UNLIM) notes += "item ${i + 1}: an endless loiter became a 10 minute hold"
                            steps += buildJsonObject { put("do", "hold"); put("id", id("hold")); at(this); put("seconds", secs.coerceIn(1.0, 3600.0)); common() }
                        }
                        NAV_RETURN_TO_LAUNCH -> steps += buildJsonObject { put("do", "home"); put("id", id("home")) }
                        DO_CHANGE_SPEED -> speed = p.d(1)?.takeIf { it > 0 }
                        DO_SET_CAM_TRIGG_DIST -> every = p.d(0)?.takeIf { it > 0 }
                        else -> notes += "item ${i + 1}: MAVLink command $cmd isn't carried over"
                    }
                }
                "ComplexItem" -> when (o.s("complexItemType")) {
                    "survey" -> {
                        val poly = (o["polygon"] as? JsonArray)?.mapNotNull { (it as? JsonArray)?.let { a -> a.d(0)?.let { la -> a.d(1)?.let { lo -> ll(la, lo) } } } }
                        if (poly == null || poly.size < 3) { notes += "item ${i + 1}: a survey without an area was skipped"; continue }
                        val cam = ((o["TransectStyleComplexItem"] as? JsonObject)?.get("CameraCalc") as? JsonObject)
                        val spacing = cam?.d("AdjustedFootprintSide") ?: 30.0
                        steps += buildJsonObject {
                            put("do", "survey"); put("id", id("survey")); put("area", JsonArray(poly)); put("spacing", spacing.coerceIn(1.0, 5000.0))
                            put("angle", o.d("angle") ?: 0.0); cam?.d("DistanceToSurface")?.let { put("alt", it) }
                            // QGroundControl's camera triggers along the lines.
                            cam?.d("AdjustedFootprintFrontal")?.takeIf { it > 0 }?.let { f -> putJsonArray("actions") { addJsonObject { put("do", "trigger"); put("id", "photo"); put("everyM", f) } } }
                        }
                        notes += "survey ${steps.size}: lines ${spacing.toInt()} m apart from its camera settings; the camera itself isn't carried over"
                    }
                    "CorridorScan" -> {
                        val line = (o["polyline"] as? JsonArray)?.mapNotNull { (it as? JsonArray)?.let { a -> a.d(0)?.let { la -> a.d(1)?.let { lo -> ll(la, lo) } } } }
                        if (line == null || line.size < 2) { notes += "item ${i + 1}: a corridor without a path was skipped"; continue }
                        val cam = ((o["TransectStyleComplexItem"] as? JsonObject)?.get("CameraCalc") as? JsonObject)
                        steps += buildJsonObject {
                            put("do", "corridor"); put("id", id("corridor")); put("path", JsonArray(line)); put("width", o.d("CorridorWidth") ?: 0.0)
                            cam?.d("AdjustedFootprintSide")?.let { put("spacing", it.coerceIn(1.0, 5000.0)) }; cam?.d("DistanceToSurface")?.let { put("alt", it) }
                        }
                    }
                    else -> notes += "item ${i + 1}: a ${o.s("complexItemType")} pattern isn't carried over"
                }
            }
        }
        val zones = ((q["rallyPoints"] as? JsonObject)?.get("points") as? JsonArray)?.mapIndexedNotNull { k, x ->
            (x as? JsonArray)?.let { a -> a.d(0)?.let { la -> a.d(1)?.let { lo -> buildJsonObject { put("id", "rally-${k + 1}"); put("kind", "landing"); put("at", ll(la, lo)); put("radius", 30.0) } } } }
        } ?: emptyList()
        if (zones.isNotEmpty()) notes += "rally points became landing zones: check each is somewhere it can land"
        val vehicle = when ((mission.d("vehicleType") ?: 0.0).toInt()) { 1 -> "plane"; 10 -> "rover"; 11 -> "boat"; else -> null }
        val plan = buildJsonObject {
            put("format", PlanCompiler.FORMAT); put("name", "Imported plan")
            vehicle?.let { put("vehicle", it) }
            home?.let { put("home", ll(it.first, it.second)) }
            put("onEnd", "hold")
            put("zones", JsonArray(zones))
            put("steps", JsonArray(steps))
        }
        return Imported(plan, fence, notes)
    }

    private fun importFence(g: JsonObject?, q: JsonObject, notes: MutableList<String>): Fence? {
        g ?: return null
        var polygon: List<Pair<Double, Double>> = emptyList()
        val keep = ArrayList<List<Pair<Double, Double>>>()
        var maxDist = 0.0
        fun pts(a: JsonArray?) = a?.mapNotNull { (it as? JsonArray)?.let { p -> p.d(0)?.let { la -> p.d(1)?.let { lo -> la to lo } } } } ?: emptyList()
        (g["polygons"] as? JsonArray)?.forEach { x ->
            val o = x as? JsonObject ?: return@forEach
            val p = pts(o["polygon"] as? JsonArray)
            if (p.size < 3) return@forEach
            if ((o["inclusion"] as? JsonPrimitive)?.booleanOrNull != false) { if (polygon.isEmpty()) polygon = p else notes += "only the first inclusion polygon is the fence; another was left out" } else keep += p
        }
        val home = ((q["mission"] as? JsonObject)?.get("plannedHomePosition") as? JsonArray)?.let { a -> a.d(0)?.let { la -> a.d(1)?.let { lo -> la to lo } } }
        (g["circles"] as? JsonArray)?.forEach { x ->
            val o = x as? JsonObject ?: return@forEach
            val c = o["circle"] as? JsonObject ?: return@forEach
            val center = (c["center"] as? JsonArray)?.let { a -> a.d(0)?.let { la -> a.d(1)?.let { lo -> la to lo } } } ?: return@forEach
            val r = c.d("radius") ?: return@forEach
            if ((o["inclusion"] as? JsonPrimitive)?.booleanOrNull != false) {
                if (home != null && Navigation.distanceM(home.first, home.second, center.first, center.second) < 20) maxDist = r
                else notes += "an inclusion circle away from home isn't carried over: draw the fence instead"
            } else keep += (0 until 24).map { k ->
                val a = Math.toRadians(k * 15.0)
                (center.first + r * cos(a) / 111_320.0) to (center.second + r * sin(a) / (111_320.0 * cos(Math.toRadians(center.first))))
            }
        }
        if (polygon.isEmpty() && keep.isEmpty() && maxDist == 0.0) return null
        return Fence(polygon, keep, maxDist, 0.0, "home")
    }
}
