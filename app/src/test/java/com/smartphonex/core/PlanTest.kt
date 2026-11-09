package com.smartphonex.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PlanTest {
    private val lat0 = 18.52
    private val lon0 = 73.85
    private val fr = LocalFrame(lat0, lon0)
    private val home = GpsFix(lat0, lon0, 560.0, 0.0, 0.0, 3.0, 12, 0L)

    /** A plane whose turning circle is 39.7 m: 15 m/s at 30° of bank. */
    private val plane = PlanPerf("plane", 15.0, "test", 11.0, 2.5, 2.0, 30.0, 0.0, 0.0, 8.0, 900.0, 20.0, 15.0, 6.0)
    private val rover = PlanPerf("rover", 2.0, "test", 0.0, 2.5, 2.0, 30.0, 1.5, 0.0, 0.0, 1800.0, 20.0, 5.0, 6.0)

    /** A position this many metres east and north of home. */
    private fun at(east: Double, north: Double) = buildJsonArray { add(fr.lat(V2(east, north))); add(fr.lon(V2(east, north))) }
    private fun square(half: Double, cx: Double = 0.0, cy: Double = 0.0) =
        listOf(V2(cx - half, cy - half), V2(cx + half, cy - half), V2(cx + half, cy + half), V2(cx - half, cy + half)).map { fr.lat(it) to fr.lon(it) }

    private fun plan(vararg steps: JsonObject, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("format", PlanCompiler.FORMAT); put("name", "test")
        put("steps", JsonArray(steps.toList()))
        extra()
    }
    private fun step(kind: String, body: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("do", kind); body() }
    private fun compile(p: JsonObject, perf: PlanPerf = plane, fence: Fence = Fence(), withHome: Boolean = true, triggers: Map<String, Boolean> = mapOf("photo" to false, "drop" to true)) =
        PlanCompiler.compile(p, PlanContext(perf, if (withHome) home else null, fence, triggers))
    private fun PlanResult.says(vararg words: String) = problems.any { p -> words.all { it in p.text } }
    private fun PlanResult.warns(vararg words: String) = warnings.any { p -> words.all { it in p.text } }

    @Test fun aSurveyCompilesIntoATimelineWithTimesPlacesAndActions() {
        val r = compile(plan(
            step("takeoff") { put("id", "up"); put("heading", 0.0); put("alt", 60.0) },
            step("survey") {
                put("id", "field"); put("alt", 60.0); put("spacing", 100.0); put("angle", 0.0)
                put("area", buildJsonArray { listOf(V2(200.0, 200.0), V2(600.0, 200.0), V2(600.0, 600.0), V2(200.0, 600.0)).forEach { add(at(it.x, it.y)) } })
                putJsonArray("actions") { addJsonObject { put("do", "trigger"); put("id", "photo"); put("everyM", 20.0) } }
            },
            step("home") { put("id", "back"); put("alt", 60.0); putJsonArray("actions") { addJsonObject { put("do", "notify"); put("text", "survey done") } } },
        ))
        assertTrue(r.problems.toString(), r.ok)
        assertNotNull(r.hash)
        val tl = r.json["timeline"]!!.jsonArray.map { it.jsonObject }
        val times = tl.map { it["t"]!!.jsonPrimitive.double }
        assertEquals("in time order", times.sorted(), times)
        assertEquals("start", tl.first()["kind"]!!.jsonPrimitive.content)
        // Four lines, 400 m each: the camera fires along the lines only, not on the turns between them.
        val photos = tl.first { "trigger photo" in it["text"]!!.jsonPrimitive.content }
        assertEquals("field", photos["step"]!!.jsonPrimitive.content)
        assertTrue(photos.toString(), "×84" in photos["text"]!!.jsonPrimitive.content)
        assertTrue(photos["until"]!!.jsonPrimitive.double > photos["t"]!!.jsonPrimitive.double)
        assertTrue(tl.any { it["kind"]!!.jsonPrimitive.content == "notify" && it["step"]!!.jsonPrimitive.content == "back" })
        // Every event has a place. The photos are placed where the first one is taken: at the start of the first line.
        tl.forEach { assertNotNull(it["lat"]); assertNotNull(it["lon"]) }
        val firstPhoto = fr.xy(photos["lat"]!!.jsonPrimitive.double, photos["lon"]!!.jsonPrimitive.double)
        assertTrue(firstPhoto.toString(), abs(firstPhoto.y - 200) < 2 || abs(firstPhoto.y - 600) < 2)
        assertEquals(60.0, photos["alt"]!!.jsonPrimitive.double, 1e-6)
        val s = r.json["summary"]!!.jsonObject
        val dist = s["distanceM"]!!.jsonPrimitive.double
        assertTrue("4 lines of 400 m, turns, and the way out and back: $dist", dist in 2400.0..3600.0)
        assertEquals(dist / 15.0, s["timeS"]!!.jsonPrimitive.double, 5.0)
        // The path climbs out along the takeoff heading.
        val path = r.json["path"]!!.jsonArray.map { it.jsonArray }
        assertEquals(0.0, path.first()[2].jsonPrimitive.double, 1e-9)
        assertEquals(60.0, path.maxOf { it[2].jsonPrimitive.double }, 1e-6)
    }

    @Test fun surveyTurnaroundsAreFlownAndCheckedAgainstTheFence() {
        fun survey(spacing: Double, fenceHalf: Double) = compile(plan(step("survey") {
            put("id", "field"); put("alt", 60.0); put("spacing", spacing)
            put("area", buildJsonArray { listOf(V2(-200.0, -200.0), V2(200.0, -200.0), V2(200.0, 200.0), V2(-200.0, 200.0)).forEach { add(at(it.x, it.y)) } })
        }), fence = Fence(square(fenceHalf)))
        // Turnarounds swing out beyond the lines' ends: a U needs one turning radius, an omega more.
        assertTrue(survey(100.0, 300.0).problems.toString(), survey(100.0, 300.0).ok)
        val tight = survey(30.0, 300.0)
        assertTrue(tight.warns("closer than this plane's turning circle"))
        assertTrue("an omega turn reaches further out than the lines' ends", tight.json["path"]!!.jsonArray.maxOf { p -> fr.xy(p.jsonArray[0].jsonPrimitive.double, p.jsonArray[1].jsonPrimitive.double).y } > 200 + 39.7)
        val r = survey(100.0, 230.0)
        assertFalse(r.ok)
        assertTrue(r.problems.toString(), r.says("turning here", "fence"))
    }

    @Test fun aTurnThatSwingsOutOfTheFenceIsCaughtBeforeFlight() {
        val fence = Fence(square(1000.0))
        fun turnAt(north: Double) = compile(plan(
            step("goto") { put("id", "out"); put("at", at(0.0, north)); put("alt", 60.0) },
            step("goto") { put("id", "back"); put("at", at(0.0, 0.0)); put("alt", 60.0) },
        ), fence = fence)
        // Turning back from 900 m, a 39.7 m circle reaches about 940 m: clear of 1000 m less the 15 m margin.
        assertTrue(turnAt(900.0).problems.toString(), turnAt(900.0).ok)
        // From 960 m the same turn reaches the fence.
        val r = turnAt(960.0)
        assertFalse(r.ok)
        assertTrue(r.problems.toString(), r.says("turning here", "fence"))
        assertEquals("back", r.problems.first { "turning" in it.text }.step)
        // The straight line from the old check would have passed: the point itself is inside.
        assertTrue(Geo.validate(Mission("m", listOf(MissionItem(fr.lat(V2(0.0, 960.0)), lon0), MissionItem(lat0, lon0))), fence, home, emptySet(), 1.0).isEmpty())
    }

    @Test fun theWindWidensTurnsAndTimes() {
        val windy = plane.copy(wind = 5.0)
        val p = plan(step("goto") { put("at", at(0.0, 930.0)); put("alt", 60.0) }, step("goto") { put("at", at(0.0, 0.0)); put("alt", 60.0) })
        // Still air: a 39.7 m turn from 930 m reaches 970 m, clear of 1000 m less the 15 m margin.
        assertTrue(compile(p, fence = Fence(square(1000.0))).ok)
        // Downwind, the 20 m/s ground speed makes a 70.6 m turn: it reaches 1000 m.
        assertFalse(compile(p, perf = windy, fence = Fence(square(1000.0))).ok)
        val s = compile(p, perf = windy).json["summary"]!!.jsonObject
        val t = s["timeS"]!!.jsonPrimitive.double
        assertEquals(t * 15 / 20, s["timeLoS"]!!.jsonPrimitive.double, 0.5)
        assertEquals(t * 15 / 10, s["timeHiS"]!!.jsonPrimitive.double, 0.5)
    }

    @Test fun aClimbThatCantBeMadeFollowsItsMissRule() {
        fun climb(miss: JsonObject? = null) = compile(plan(
            step("takeoff") { put("heading", 0.0); put("alt", 30.0) },
            // 200 m at 15 m/s is 13 s: 33 m of climb, but it asks for 100.
            step("goto") { put("id", "steep"); put("at", at(0.0, 400.0)); put("alt", 130.0); miss?.let { put("miss", it) } },
        ))
        val circling = climb()
        assertTrue(circling.problems.toString(), circling.ok)
        assertTrue(circling.warnings.toString(), circling.warns("circles about 27 s"))
        assertTrue(circling.json["timeline"]!!.jsonArray.any { it.jsonObject["kind"]!!.jsonPrimitive.content == "circle" })

        assertTrue(climb(buildJsonObject { put("altitude", "abort") }).says("abort"))
        assertTrue(climb(buildJsonObject { putJsonObject("altitude") { put("do", "circle"); put("maxS", 10.0) } }).says("can't climb the rest in 10 s"))
        assertTrue(climb(buildJsonObject { put("altitude", "continue") }).warns("keeps going"))
        assertTrue("fallbacks always end", climb(buildJsonObject { putJsonObject("altitude") { put("do", "circle"); put("maxS", 30.0); put("then", "circle") } }).says("then must be"))
        assertTrue("waiting is limited", climb(buildJsonObject { putJsonObject("altitude") { put("do", "circle"); put("maxS", 5000.0) } }).says("maxS"))
    }

    @Test fun batteryReachIsEnforcedAndGlideGapsAreShown() {
        // 720 s usable at 15 m/s: 6 km out and back doesn't fit.
        val far = compile(plan(step("goto") { put("at", at(0.0, 6000.0)); put("alt", 60.0) }, step("home") { put("alt", 60.0) }))
        assertFalse(far.ok)
        assertTrue(far.problems.toString(), far.says("battery"))

        // 1.5 km away at 50 m: gliding 8:1 reaches 400 m. Beyond line of sight that's a problem, until zones cover it.
        fun out(bvlos: Boolean, zones: Boolean) = compile(plan(
            step("goto") { put("at", at(0.0, 1500.0)); put("alt", 50.0) }, step("home") { put("alt", 50.0) },
        ) {
            put("bvlos", bvlos)
            if (zones) putJsonArray("zones") {
                addJsonObject { put("id", "field"); put("kind", "landing"); put("at", at(0.0, 750.0)) }
                addJsonObject { put("id", "scrub"); put("kind", "ditch"); put("at", at(0.0, 1500.0)) }
            }
        })
        assertTrue(out(false, false).ok)
        assertTrue(out(false, false).warns("gliding distance"))
        assertTrue(out(false, false).json["gaps"]!!.jsonArray.isNotEmpty())
        assertFalse(out(true, false).ok)
        val covered = out(true, true)
        assertTrue(covered.problems.toString(), covered.ok)
        assertTrue(covered.json["gaps"]!!.jsonArray.isEmpty())
    }

    @Test fun planeSafetyRules() {
        assertTrue(compile(plan(step("goto") { put("at", at(0.0, 300.0)); put("speed", 9.0) })).says("safe minimum"))
        assertTrue(compile(plan(step("goto") { put("at", at(0.0, 300.0)); put("radius", 10.0) })).says("circle trying to hit"))
        assertTrue(compile(plan(step("goto") { put("at", at(0.0, 300.0)) }) { put("onEnd", "stop") }).says("can't stop in the air"))
        assertTrue(compile(plan(step("goto") { put("at", at(0.0, 300.0)); put("alt", 200.0) }), fence = Fence(maxAltM = 120.0)).says("above the fence"))
        assertTrue(compile(plan(step("land") { put("at", at(0.0, 300.0)); put("heading", 0.0); put("slope", 10.0) })).says("descends at"))
        val landed = compile(plan(step("land") { put("at", at(0.0, 600.0)); put("heading", 0.0) }, step("goto") { put("at", at(0.0, 0.0)) }))
        assertTrue(landed.says("nothing can follow"))
        val land = compile(plan(step("goto") { put("at", at(0.0, -500.0)); put("alt", 30.0) }, step("land") { put("id", "down"); put("at", at(0.0, 300.0)); put("heading", 0.0) }))
        assertTrue(land.problems.toString(), land.ok)
        val last = land.json["timeline"]!!.jsonArray.last().jsonObject
        assertEquals("one ending: it touches down", "touches down", last["text"]!!.jsonPrimitive.content)
        assertEquals(0.0, last["alt"]!!.jsonPrimitive.double, 1e-6)
        assertTrue(compile(plan(step("takeoff") { put("alt", 60.0) })).says("heading"))
        assertTrue(compile(plan(step("goto") { put("at", at(0.0, 300.0)) }, step("takeoff") { put("heading", 0.0) })).says("first step"))
        assertTrue("a dangerous trigger never fires by distance",
            compile(plan(step("goto") { put("at", at(0.0, 300.0)); putJsonArray("actions") { addJsonObject { put("do", "trigger"); put("id", "drop"); put("everyM", 10.0) } } })).says("dangerous"))
        assertTrue(compile(plan(step("goto") { put("at", at(0.0, 300.0)); putJsonArray("actions") { addJsonObject { put("do", "trigger"); put("id", "nope") } } })).says("unknown trigger"))
    }

    @Test fun theSamePlanFileSuitsARoverWithItsOwnRules() {
        val r = compile(plan(step("goto") { put("at", at(0.0, 50.0)); put("alt", 60.0) }, step("hold") { put("seconds", 10.0) }), perf = rover)
        assertTrue(r.problems.toString(), r.ok)
        assertTrue(r.warns("ignored"))
        assertEquals(35.0, r.json["summary"]!!.jsonObject["timeS"]!!.jsonPrimitive.double, 0.5) // 50 m at 2 m/s, then 10 s.
        assertTrue(compile(plan(step("takeoff") { put("heading", 0.0) }), perf = rover).says("for planes"))
        assertTrue(compile(plan(step("goto") { put("at", at(0.0, 50.0)); putJsonObject("miss") { put("altitude", "circle") } }), perf = rover).says("circle is for planes"))
        assertTrue(compile(plan(step("goto") { put("at", at(0.0, 50.0)) }) { put("vehicle", "boat") }, perf = rover).says("for a boat"))
    }

    @Test fun stepsGetStableIdsAndRepeatsAreNumbered() {
        val r = compile(plan(
            step("goto") { put("at", at(0.0, 300.0)) },
            step("repeat") { put("id", "laps"); put("times", 3.0); putJsonArray("steps") { add(step("goto") { put("id", "leg"); put("at", at(300.0, 300.0)) }); add(step("goto") { put("at", at(0.0, 300.0)) }) } },
        ))
        assertTrue(r.problems.toString(), r.ok)
        val steps = r.json["plan"]!!.jsonObject["steps"]!!.jsonArray
        assertEquals("goto-1", steps[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("goto-2", steps[1].jsonObject["steps"]!!.jsonArray[1].jsonObject["id"]!!.jsonPrimitive.content)
        val named = r.json["points"]!!.jsonArray.map { it.jsonObject["step"]!!.jsonPrimitive.content }
        assertEquals(listOf("goto-1", "leg#1", "goto-2#1", "leg#2", "goto-2#2", "leg#3", "goto-2#3"), named)
        assertTrue(compile(plan(step("goto") { put("id", "a"); put("at", at(0.0, 300.0)) }, step("goto") { put("id", "a"); put("at", at(0.0, 0.0)) })).says("two steps"))
        assertEquals("the same plan, the same hash", r.hash, compile(r.json["plan"]!!.jsonObject).hash)
        assertNotEquals(r.hash, compile(plan(step("goto") { put("at", at(0.0, 301.0)) })).hash)
    }

    @Test fun aLegThatClipsASmallKeepOutBetweenTheOldSamplePointsIsRefused() {
        // A 10 m keep-out 50 m along a 1 km leg: the old check looked every 100 m and missed it.
        val keep = square(5.0, 0.0, 50.0)
        val m = Mission("m", listOf(MissionItem(lat0, lon0), MissionItem(fr.lat(V2(0.0, 1000.0)), lon0)))
        val e = Geo.validate(m, Fence(keepOut = listOf(keep)), home, emptySet(), 1.0)
        assertTrue(e.toString(), e.any { "The leg to point 2 goes inside keep-out area 1" in it })
    }

    @Test fun withoutAHomeThePlansOwnHomeIsUsedAndSaidSo() {
        val r = compile(plan(step("goto") { put("at", at(0.0, 300.0)) }) { put("home", at(0.0, 0.0)) }, withHome = false)
        assertTrue(r.problems.toString(), r.ok)
        assertTrue(r.warns("no home yet"))
        assertEquals("plan", r.json["assumptions"]!!.jsonObject["home"]!!.jsonPrimitive.content)
    }
}
