package com.smartphonex.core

import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.PI

/** The planning tools as an AI reaches them: through MCP, under the same rules as any client. */
class PlanToolsTest {
    private lateinit var dir: File
    private lateinit var rt: VehicleRuntime
    private lateinit var mcp: McpHandler
    private val lat0 = 18.52
    private val lon0 = 73.85
    private var seq = 0L

    @Before fun setUp() { dir = Files.createTempDirectory("tools").toFile(); rt = VehicleRuntime(dir); mcp = McpHandler(rt) }
    @After fun tearDown() { rt.ops.flush(); rt.stop(); dir.deleteRecursively() }

    /** Calls a tool and reads its answer. */
    private fun call(name: String, args: JsonObjectBuilder.() -> Unit = {}): Pair<JsonObject, Boolean> {
        val r = mcp.callForApi(name, buildJsonObject(args))
        val text = r["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        return Json.parseToJsonElement(text).jsonObject to r["isError"]!!.jsonPrimitive.boolean
    }
    private fun person(cmd: String, body: JsonObjectBuilder.() -> Unit) = rt.controller.submit(Envelope(Source.GROUND, ++seq, null, Command.OpsRequest(cmd, buildJsonObject(body))))
    private fun ll(lat: Double, lon: Double) = buildJsonArray { add(lat); add(lon) }
    private fun plan(north: Double) = buildJsonObject {
        put("format", PlanCompiler.FORMAT); put("home", ll(lat0, lon0))
        putJsonArray("steps") { addJsonObject { put("do", "goto"); put("at", ll(lat0 + north / 111_320, lon0)) }; addJsonObject { put("do", "home") } }
    }

    @Test fun theToolsAreListedForAnyClient() {
        val names = mcp.listToolsJson().map { it.jsonObject["name"]!!.jsonPrimitive.content }
        listOf("plan_get_draft", "plan_get_anchors", "plan_check", "plan_propose", "geo_shape", "geo_buffer", "place_lookup", "place_features").forEach { assertTrue(it, it in names) }
    }

    @Test fun exactGeometryInsteadOfGuessedCoordinates() {
        val (sq, _) = call("geo_shape") { put("shape", "square"); put("center", ll(lat0, lon0)); put("size_m", 200.0) }
        assertEquals(4, sq["polygon"]!!.jsonArray.size)
        assertEquals(40_000.0, sq["areaM2"]!!.jsonPrimitive.double, 50.0)
        val (circle, _) = call("geo_shape") { put("shape", "circle"); put("center", ll(lat0, lon0)); put("size_m", 100.0) }
        assertEquals(PI * 100 * 100, circle["areaM2"]!!.jsonPrimitive.double, 300.0)
        val (east, _) = call("geo_offset") { put("from", ll(lat0, lon0)); put("bearing_deg", 90.0); put("distance_m", 100.0) }
        val p = east["point"]!!.jsonArray
        assertEquals(100.0, Navigation.distanceM(lat0, lon0, p[0].jsonPrimitive.double, p[1].jsonPrimitive.double), 0.5)
        val (band, _) = call("geo_buffer") { put("path", buildJsonArray { add(ll(lat0, lon0)); add(ll(lat0 + 100 / 111_320.0, lon0)) }); put("distance_m", 10.0) }
        assertEquals("a 100 m path, 10 m either side, round ends", 100 * 20 + PI * 100, band["areaM2"]!!.jsonPrimitive.double, 60.0)
        val (m, _) = call("geo_measure") { put("points", buildJsonArray { add(ll(lat0, lon0)); add(p) }) }
        assertEquals(90.0, m["legs"]!!.jsonArray[0].jsonObject["bearingDeg"]!!.jsonPrimitive.double, 1.0)
        val (bad, err) = call("geo_shape") { put("shape", "circle"); put("center", ll(lat0, lon0)); put("size_m", -5.0) }
        assertTrue(err); assertTrue(bad.toString(), "size_m" in bad["reason"]!!.jsonPrimitive.content)
    }

    @Test fun theAiReadsWhatThePersonMarkedAndIsWorkingOn() {
        assertTrue(call("plan_get_anchors").first["note"] != null)
        assertTrue(person("plan_anchors_set") { putJsonArray("anchors") { addJsonObject { put("name", "A"); put("lat", lat0); put("lon", lon0 + 0.001) } } }.accepted)
        assertFalse("anchors are a person's", rt.controller.submit(Envelope(Source.AI, null, null, Command.OpsRequest("plan_anchors_set", buildJsonObject { putJsonArray("anchors") {} }))).accepted)
        assertEquals("A", call("plan_get_anchors").first["anchors"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        person("plan_check") { put("plan", plan(300.0)) }
        val draft = call("plan_get_draft").first
        assertEquals(plan(300.0), draft["draft"])
        assertEquals("rover", draft["vehicle"]!!.jsonPrimitive.content)
    }

    @Test fun theAiChecksAndProposesButNeverSends() {
        val (c, err) = call("plan_check") { put("plan", plan(300.0)) }
        assertFalse(c.toString(), err)
        assertEquals(true, c["ok"]!!.jsonPrimitive.boolean)
        assertNull("no long path for the AI", c["path"])
        assertTrue(c["timeline"]!!.jsonArray.isNotEmpty())
        val (p, _) = call("plan_propose") { put("plan", plan(300.0)); put("note", "Out and back"); put("fence", buildJsonObject { put("maxDistM", 400.0); put("action", "home") }) }
        assertEquals(true, p["accepted"]!!.jsonPrimitive.boolean)
        val prop = rt.ops.proposal!!
        assertEquals("Out and back", prop["note"]!!.jsonPrimitive.content)
        assertEquals(400.0, prop["fence"]!!.jsonObject["maxDistM"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("AI", prop["from"]!!.jsonPrimitive.content)
        // Sending is a person's: the AI is refused.
        assertFalse(rt.controller.submit(Envelope(Source.AI, null, null, Command.OpsRequest("plan_upload", buildJsonObject { put("plan", plan(300.0)) }))).accepted)
        assertFalse(rt.controller.submit(Envelope(Source.AI, null, null, Command.OpsRequest("fence_set", buildJsonObject { put("fence", buildJsonObject { put("maxDistM", 5000.0) }) }))).accepted)
        assertTrue(person("plan_proposal_clear") {}.accepted)
        assertNull(rt.ops.proposal)
    }

    @Test fun placesComeFromOpenStreetMapAndOfflineIsSaid() {
        val asked = ArrayList<String>()
        rt.webGet = { url ->
            asked += url
            if ("nominatim" in url) """[{"display_name":"Khadakwasla Dam, Pune","category":"waterway","type":"dam","lat":"18.4423","lon":"73.7676","boundingbox":["18.44","18.45","73.76","73.77"]}]"""
            else """{"elements":[{"type":"way","tags":{"landuse":"farmland","name":"North field"},"geometry":[{"lat":18.520,"lon":73.850},{"lat":18.521,"lon":73.850},{"lat":18.521,"lon":73.851},{"lat":18.520,"lon":73.851},{"lat":18.520,"lon":73.850}]}]}"""
        }
        val (found, _) = call("place_lookup") { put("query", "Khadakwasla dam"); put("near", ll(lat0, lon0)) }
        val place = found["places"]!!.jsonArray[0].jsonObject
        assertEquals(18.4423, place["at"]!!.jsonArray[0].jsonPrimitive.double, 1e-9)
        assertTrue(asked[0].contains("Khadakwasla") && asked[0].contains("viewbox"))
        val (fields, _) = call("place_features") { put("near", ll(lat0, lon0)); put("kind", "field") }
        val f = fields["features"]!!.jsonArray[0].jsonObject
        assertEquals("North field", f["name"]!!.jsonPrimitive.content)
        assertEquals(4, f["polygon"]!!.jsonArray.size)
        assertTrue(f["areaM2"]!!.jsonPrimitive.double > 10_000)
        rt.webGet = { throw java.net.UnknownHostException("no network") }
        val (off, err) = call("place_lookup") { put("query", "anything") }
        assertTrue(err)
        assertTrue(off["reason"]!!.jsonPrimitive.content.contains("internet"))
    }
}
