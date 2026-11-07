package com.smartphonex.core

import kotlinx.serialization.json.*

/**
 * MCP over stateless JSON-RPC. Tools describe vehicle capabilities, never phone or hardware
 * details, and every request passes through the same controller checks as any other command.
 */
class McpHandler(private val rt: VehicleRuntime) {
    private val supportedVersions = setOf("2025-06-18", "2025-03-26", "2024-11-05")
    private val latestVersion = "2025-06-18"

    private fun schema(required: List<String> = emptyList(), props: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties", props)
        putJsonArray("required") { required.forEach { add(it) } }
    }

    private fun JsonObjectBuilder.numProp(name: String, desc: String) = putJsonObject(name) {
        put("type", "number"); put("description", desc)
    }

    private fun JsonObjectBuilder.strProp(name: String, desc: String, values: List<String>) = putJsonObject(name) {
        put("type", "string"); put("description", desc)
        putJsonArray("enum") { values.forEach { add(it) } }
    }

    private val leaseDesc = "How long the request stays valid, in ms. When it expires the vehicle returns to a stop. Default applies if omitted; see vehicle_get_capabilities for the maximum."

    private fun tool(name: String, desc: String, input: JsonObject) = buildJsonObject {
        put("name", name); put("description", desc); put("inputSchema", input)
    }

    private val tools = listOf(
        tool("vehicle_get_capabilities", "Describe this vehicle: sensors, limits, missions, and the rules the local controller enforces. Read this first.", schema()),
        tool("vehicle_get_status", "Read the operating mode, connection state, position, speed and any failsafe or obstacle hold.", schema()),
        tool("vehicle_get_telemetry", "Get the latest telemetry plus recent samples at 10 Hz.", schema { numProp("samples", "Recent samples to return, 0-100. Default 20.") }),
        tool("vehicle_get_sensors", "Read the latest value of every enabled device sensor. Location is included only if an operator granted access.", schema()),
        tool("vehicle_get_camera_frame", "Take one still from the camera the operator selected. Needs the camera enabled and access granted by an operator.", schema()),
        tool("vehicle_set_mode", "Request an operating mode. AUTO lets you command the vehicle; DISARMED stops it. You cannot select MANUAL or clear a failsafe.", schema(listOf("mode")) { strProp("mode", "Requested mode", listOf("AUTO", "DISARMED")) }),
        tool("vehicle_set_flight_mode", "Request an autopilot mode: MANUAL, STABILIZE, HOLD_HEADING or RETURN_HOME. The phone refuses if sensors or GPS aren't good enough, and says why.", schema(listOf("mode")) { strProp("mode", "Requested flight mode", FlightMode.entries.map { it.name }) }),
        tool("vehicle_set_speed", "Hold a target speed in m/s using the phone's closed-loop controller. Preferred over raw throttle. Requires AUTO.", schema(listOf("mps")) { numProp("mps", "Target speed in m/s; negative reverses."); numProp("durationMs", leaseDesc) }),
        tool("vehicle_set_throttle", "Request open-loop throttle, -1 to 1, within configured limits. Requires AUTO.", schema(listOf("value")) { numProp("value", "Throttle request."); numProp("durationMs", leaseDesc) }),
        tool("vehicle_set_steering", "Request steering, -1 (right) to 1 (left), within configured limits. Requires AUTO.", schema(listOf("value")) { numProp("value", "Steering request."); numProp("durationMs", leaseDesc) }),
        tool("vehicle_stop", "Stop the vehicle now and disarm. Always accepted.", schema()),
        tool("vehicle_configure_pid", "Submit speed-controller gains for validation. Omitted values are unchanged.", schema { numProp("kp", "0-5"); numProp("ki", "0-5"); numProp("kd", "0-2"); numProp("ff", "Feed-forward, 0-2") }),
        tool("vehicle_run_mission", "Run an approved mission. Requires AUTO. It finishes by itself; vehicle_stop cancels it.", schema(listOf("name")) { strProp("name", "Mission name", Missions.all.keys.toList()) }),
    )

    data class Reply(val status: Int, val body: String?)

    private fun allTools() = tools + rt.setup.tools.map { (n, d, s) -> tool(n, d, s) }

    /** The same tools for clients without MCP, and for the built-in assistant. */
    fun listToolsJson() = JsonArray(allTools())
    fun callForApi(name: String, args: JsonObject): JsonObject = callTool(name, args)

    /** The HTTP-level handling, shared by the local endpoint and the relay. */
    fun handleText(text: String): Reply {
        if (rt.faults.mcpDown) return Reply(503, null)
        val req = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull()
            ?: return Reply(400, error(null, -32700, "Parse error").toString())
        val res = handle(req) ?: return Reply(202, null)
        return Reply(200, res.toString())
    }

    /** Returns the JSON-RPC response, or null for a notification, which gets no response. */
    fun handle(req: JsonObject): JsonObject? {
        val id = req["id"]
        val method = (req["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return error(id, -32600, "Invalid request")
        if (id == null) return null
        return when (method) {
            "initialize" -> {
                val requested = (req["params"] as? JsonObject)?.get("protocolVersion")?.let { (it as? JsonPrimitive)?.content }
                result(id, buildJsonObject {
                    put("protocolVersion", requested?.takeIf { it in supportedVersions } ?: latestVersion)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") { put("name", "smartphonex-vehicle"); put("version", "0.1") }
                    put("instructions", "Supervisory control of a vehicle. The vehicle's local controller decides whether each request is permitted, and you are not in its real-time loop: expect to observe with vehicle_get_status rather than steer reactively. Call vehicle_get_capabilities first.")
                })
            }
            "ping" -> result(id, buildJsonObject {})
            "tools/list" -> result(id, buildJsonObject { put("tools", JsonArray(allTools())) })
            "tools/call" -> {
                val params = req["params"] as? JsonObject ?: return error(id, -32602, "Missing params")
                val name = (params["name"] as? JsonPrimitive)?.content ?: return error(id, -32602, "Missing tool name")
                if (allTools().none { it["name"]!!.jsonPrimitive.content == name }) return error(id, -32602, "Unknown tool $name")
                result(id, callTool(name, params["arguments"] as? JsonObject ?: buildJsonObject {}))
            }
            else -> error(id, -32601, "Method not found: $method")
        }
    }

    private fun callTool(name: String, args: JsonObject): JsonObject {
        val start = System.nanoTime()
        val out = try {
            when (name) {
                "vehicle_get_capabilities" -> ok(capabilities())
                "vehicle_get_status" -> ok(status())
                "vehicle_get_telemetry" -> ok(telemetry(args))
                "vehicle_get_sensors" -> ok(rt.sensors.readings(rt.sensors.aiPrivateAccess))
                "vehicle_get_camera_frame" -> cameraFrame()
                "vehicle_set_mode" -> command(args, "set_mode")
                "vehicle_set_flight_mode" -> command(args, "set_flight_mode")
                "vehicle_set_speed" -> command(args, "set_speed")
                "vehicle_set_throttle" -> command(args, "set_throttle")
                "vehicle_set_steering" -> command(args, "set_steering")
                "vehicle_stop" -> command(args, "stop")
                "vehicle_configure_pid" -> command(args, "configure_pid")
                "vehicle_run_mission" -> command(args, "run_mission")
                in rt.setup.toolNames -> {
                    val out = rt.setup.call(name, args)
                    content(out, (out["ok"] as? JsonPrimitive)?.booleanOrNull == false)
                }
                else -> throw MalformedCommand("unknown tool")
            }
        } catch (e: MalformedCommand) {
            rt.controller.recordMalformed(Source.AI, "$name: ${e.message}")
            rejected("malformed", e.message ?: "invalid arguments")
        }
        val micros = (System.nanoTime() - start) / 1000
        if (!name.startsWith("vehicle_get")) rt.log.event("mcp", "AI", name, "handled in ${micros}us")
        return out
    }

    private fun cameraFrame(): JsonObject {
        if (!rt.sensors.aiPrivateAccess) return rejected("not_permitted", "an operator has not granted AI access to the camera")
        if (!rt.sensors.camera.enabled) return rejected("not_permitted", "the camera is turned off")
        val jpeg = rt.camera?.snapshot(4000) ?: return rejected("unavailable", "the camera gave no frame in time")
        rt.log.event("mcp", "AI", "camera_frame", "AI took a camera still")
        return buildJsonObject {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "image")
                    put("mimeType", "image/jpeg")
                    put("data", java.util.Base64.getEncoder().encodeToString(jpeg))
                }
            }
            put("isError", false)
        }
    }

    private fun command(args: JsonObject, cmd: String): JsonObject {
        val c = Codec.parseCommand(buildJsonObject { args.forEach { (k, v) -> put(k, v) }; put("cmd", cmd) })
        val r = rt.controller.submit(Envelope(Source.AI, null, null, c))
        return if (r.accepted) ok(buildJsonObject { put("accepted", true); put("status", status()) }) else rejected(r.code, r.message)
    }

    private fun ok(data: JsonObject) = content(data, false)

    private fun rejected(code: String, message: String) = content(
        buildJsonObject { put("accepted", false); put("code", code); put("reason", message); put("status", status()) }, true,
    )

    private fun content(data: JsonObject, isError: Boolean) = buildJsonObject {
        putJsonArray("content") { addJsonObject { put("type", "text"); put("text", data.toString()) } }
        put("isError", isError)
    }

    private fun status(): JsonObject {
        val t = rt.controller.latest()
        return buildJsonObject {
            put("mode", t.mode.name)
            put("aiMayCommand", t.mode == Mode.AUTO)
            put("aiMayArm", !t.aiLocked && t.mode == Mode.DISARMED && rt.profiles.active.aiMayArm)
            put("speedMps", t.sensors.speedMps); put("x", t.sensors.x); put("y", t.sensors.y)
            put("headingRad", t.sensors.headingRad); put("rangeM", t.sensors.rangeM)
            put("collided", t.sensors.collided); put("mission", t.mission)
            put("failsafeReason", t.failsafeReason); put("obstacleHold", t.obstacleHold)
            put("profile", t.profileId); put("loopHz", t.loop.hz)
            put("flightMode", rt.fc.mode.name)
            rt.fc.attitudeNow.let { a -> put("rollDeg", a.roll); put("pitchDeg", a.pitch); put("headingDeg", a.yaw) }
        }
    }

    private fun capabilities(): JsonObject {
        val p = rt.profiles.active
        return buildJsonObject {
            put("vehicle", rt.controller.adapterId)
            put("vehicleType", p.vehicleType)
            put("simulated", rt.controller.adapterId.startsWith("sim"))
            put("hardwareConnected", false)
            putJsonArray("outputs") { p.channels.forEach { add(it.name) } }
            putJsonArray("deviceSensors") { rt.sensors.catalog().filter { (i, c) -> c.enabled && (!i.isPrivate || rt.sensors.aiPrivateAccess) }.forEach { add(it.first.name) } }
            putJsonArray("sensorsAndActuators") { rt.controller.capabilities.forEach { add(it) } }
            put("limits", Codec.profile(p))
            putJsonObject("missions") { Missions.all.forEach { (k, v) -> put(k, v.sumOf { it.durationMs }) } }
            put("rules", "Stop always works. Motion needs AUTO. Requests expire after durationMs and the vehicle stops. A human can take over at any time, and only a human can clear a failsafe or a human stop. The phone stops for obstacles on its own.")
        }
    }

    private fun telemetry(args: JsonObject): JsonObject {
        val n = (args["samples"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 20
        if (n !in 0..100) throw MalformedCommand("samples must be 0-100")
        return buildJsonObject {
            put("latest", Codec.telemetry(rt.controller.latest()))
            putJsonArray("recent") { rt.history(n).forEach { add(Codec.telemetry(it)) } }
        }
    }

    private fun result(id: JsonElement, r: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", r)
    }

    private fun error(id: JsonElement?, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id ?: JsonNull)
        putJsonObject("error") { put("code", code); put("message", message) }
    }
}
