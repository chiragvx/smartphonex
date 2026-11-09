package com.smartphonex.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QgcPlanTest {
    private val lat0 = 18.52
    private val lon0 = 73.85
    private val fr = LocalFrame(lat0, lon0)
    private val home = GpsFix(lat0, lon0, 560.0, 0.0, 0.0, 3.0, 12, 0L)
    private val plane = PlanPerf("plane", 15.0, "test", 11.0, 2.5, 2.0, 30.0, 0.0, 0.0, 8.0, 900.0, 20.0, 15.0, 6.0)
    private fun at(e: Double, n: Double) = buildJsonArray { add(fr.lat(V2(e, n))); add(fr.lon(V2(e, n))) }
    private val fence = Fence(listOf(V2(-900.0, -900.0), V2(900.0, -900.0), V2(900.0, 900.0), V2(-900.0, 900.0)).map { fr.lat(it) to fr.lon(it) }, maxDistM = 1200.0)

    private val plan = buildJsonObject {
        put("format", PlanCompiler.FORMAT); put("name", "Field")
        putJsonArray("zones") { addJsonObject { put("id", "field"); put("kind", "landing"); put("at", at(300.0, 300.0)) } }
        putJsonArray("steps") {
            addJsonObject { put("do", "takeoff"); put("heading", 0.0); put("alt", 60.0) }
            addJsonObject {
                put("do", "survey"); put("alt", 60.0); put("spacing", 100.0)
                put("area", buildJsonArray { listOf(V2(-300.0, 200.0), V2(100.0, 200.0), V2(100.0, 600.0), V2(-300.0, 600.0)).forEach { add(at(it.x, it.y)) } })
                putJsonArray("actions") { addJsonObject { put("do", "trigger"); put("id", "photo"); put("everyM", 20.0) } }
            }
            addJsonObject { put("do", "orbit"); put("center", at(0.0, 700.0)); put("radius", 60.0); put("laps", 2.0); put("dir", "ccw") }
            addJsonObject { put("do", "hold"); put("at", at(0.0, 300.0)); put("seconds", 20.0); put("speed", 18.0) }
            addJsonObject { put("do", "land"); put("at", at(0.0, -300.0)); put("heading", 180.0) }
        }
    }

    private fun compiled() = PlanCompiler.compile(plan, PlanContext(plane, home, fence, mapOf("photo" to false)))

    @Test fun aPlanExportsAsTheRouteItWillFlyInMavlinkCommands() {
        val r = compiled()
        assertTrue(r.problems.toString(), r.ok)
        val q = QgcPlan.export(r, plan, fence, home, plane)
        assertEquals("Plan", q["fileType"]!!.jsonPrimitive.content)
        val items = q["mission"]!!.jsonObject["items"]!!.jsonArray.map { it.jsonObject }
        val cmds = items.map { it["command"]!!.jsonPrimitive.int }
        assertEquals("starts with a takeoff", QgcPlan.NAV_TAKEOFF, cmds.first { it in setOf(16, 22) })
        assertEquals("ends with a landing", QgcPlan.NAV_LAND, cmds.last())
        // The camera runs along the survey lines and is stopped after them.
        val cam = items.filter { it["command"]!!.jsonPrimitive.int == QgcPlan.DO_SET_CAM_TRIGG_DIST }.map { it["params"]!!.jsonArray[0].jsonPrimitive.double }
        assertTrue(cam.toString(), 20.0 in cam && cam.count { it == 20.0 } >= 4 && 0.0 in cam)
        val orbit = items.first { it["command"]!!.jsonPrimitive.int == QgcPlan.NAV_LOITER_TURNS }["params"]!!.jsonArray
        assertEquals(2.0, orbit[0].jsonPrimitive.double, 1e-9)
        assertEquals("anticlockwise is a negative radius", -60.0, orbit[2].jsonPrimitive.double, 1e-9)
        assertTrue("a plane holds by circling", QgcPlan.NAV_LOITER_TIME in cmds)
        assertTrue("the speed change is carried", items.any { it["command"]!!.jsonPrimitive.int == QgcPlan.DO_CHANGE_SPEED && it["params"]!!.jsonArray[1].jsonPrimitive.double == 18.0 })
        // Every position is the compiled route's: the same path the phone checked.
        val navs = items.filter { it["command"]!!.jsonPrimitive.int in setOf(16, 21, 22) }
        assertEquals(r.mission!!.items.size, navs.size + items.count { it["command"]!!.jsonPrimitive.int == QgcPlan.NAV_LOITER_TIME })
        assertEquals(1, q["geoFence"]!!.jsonObject["polygons"]!!.jsonArray.size)
        assertEquals(1200.0, q["geoFence"]!!.jsonObject["circles"]!!.jsonArray[0].jsonObject["circle"]!!.jsonObject["radius"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("the landing zone is a rally point", 1, q["rallyPoints"]!!.jsonObject["points"]!!.jsonArray.size)
    }

    @Test fun aRoundTripThroughQGroundControlLosesNothing() {
        val q = QgcPlan.export(compiled(), plan, fence, home, plane)
        val back = QgcPlan.import(Json.parseToJsonElement(q.toString()).jsonObject)
        assertEquals(plan, back.plan)
        assertEquals(fence.polygon.size, back.fence!!.polygon.size)
        assertEquals(1200.0, back.fence.maxDistM, 1e-9)
    }

    @Test fun aQGroundControlMissionBecomesPlanSteps() {
        fun simple(cmd: Int, vararg p: Double?, frame: Int = 3) = buildJsonObject {
            put("type", "SimpleItem"); put("command", cmd); put("frame", frame); put("autoContinue", true)
            putJsonArray("params") { p.forEach { if (it == null) add(JsonNull) else add(it) } }
        }
        val a = at(0.0, 400.0); val b = at(200.0, 400.0)
        val q = buildJsonObject {
            put("fileType", "Plan"); put("version", 1)
            putJsonObject("mission") {
                put("vehicleType", 1)
                putJsonArray("plannedHomePosition") { add(lat0); add(lon0); add(560.0) }
                putJsonArray("items") {
                    add(simple(22, 15.0, 0.0, 0.0, null, 0.0, 0.0, 50.0))
                    add(simple(178, 0.0, 16.0, -1.0, 0.0, 0.0, 0.0, 0.0, frame = 2))
                    add(simple(16, 0.0, 30.0, 0.0, null, a[0].jsonPrimitive.double, a[1].jsonPrimitive.double, 70.0))
                    add(simple(206, 25.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, frame = 2))
                    add(simple(16, 10.0, 0.0, 0.0, null, b[0].jsonPrimitive.double, b[1].jsonPrimitive.double, 70.0))
                    add(simple(206, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, frame = 2))
                    add(buildJsonObject {
                        put("type", "ComplexItem"); put("complexItemType", "survey"); put("angle", 45.0)
                        putJsonArray("polygon") { listOf(V2(-200.0, 500.0), V2(200.0, 500.0), V2(200.0, 800.0)).forEach { v -> addJsonArray { add(fr.lat(v)); add(fr.lon(v)) } } }
                        putJsonObject("TransectStyleComplexItem") { putJsonObject("CameraCalc") { put("AdjustedFootprintSide", 40.0); put("AdjustedFootprintFrontal", 30.0); put("DistanceToSurface", 80.0) } }
                    })
                    add(simple(183, 9.0, 1500.0, 0.0, 0.0, 0.0, 0.0, 0.0, frame = 2))
                    add(simple(20, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, frame = 2))
                }
            }
            putJsonObject("geoFence") { putJsonArray("polygons") { addJsonObject { put("inclusion", false); putJsonArray("polygon") { listOf(V2(-50.0, 50.0), V2(50.0, 50.0), V2(0.0, 120.0)).forEach { v -> addJsonArray { add(fr.lat(v)); add(fr.lon(v)) } } } } } }
            putJsonObject("rallyPoints") { putJsonArray("points") { addJsonArray { add(lat0 + 0.003); add(lon0); add(0.0) } } }
        }
        val im = QgcPlan.import(q)
        val steps = im.plan["steps"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("takeoff", "goto", "hold", "survey", "home"), steps.map { it["do"]!!.jsonPrimitive.content })
        assertEquals("plane", im.plan["vehicle"]!!.jsonPrimitive.content)
        assertEquals("take off heads for the first point", 0.0, steps[0]["heading"]!!.jsonPrimitive.double, 1.0)
        assertEquals(16.0, steps[1]["speed"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("the camera ran along the leg into the hold", 25.0, steps[2]["actions"]!!.jsonArray[0].jsonObject["everyM"]!!.jsonPrimitive.double, 1e-9)
        assertNull("and stopped before the survey's own", steps[1]["actions"])
        assertEquals(40.0, steps[3]["spacing"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(80.0, steps[3]["alt"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(1, im.plan["zones"]!!.jsonArray.size)
        assertEquals("the exclusion polygon is a keep-out area", 1, im.fence!!.keepOut.size)
        assertTrue("what it couldn't carry is said", im.notes.any { "183" in it })
        // And it compiles.
        val r = PlanCompiler.compile(im.plan, PlanContext(plane, home, im.fence, mapOf("photo" to false)))
        assertTrue(r.problems.toString(), r.problems.none { "unknown" in it.text || "needs" in it.text })
    }

    @Test fun somethingElseIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { QgcPlan.import(buildJsonObject { put("fileType", "GeoFence") }) }
    }
}
