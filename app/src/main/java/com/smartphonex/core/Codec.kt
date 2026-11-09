package com.smartphonex.core

import kotlinx.serialization.json.*

class MalformedCommand(message: String) : Exception(message)

private val OPS_COMMANDS = setOf(
    "test_output", "test_stop", "diagnostics_run", "test_record", "checklist_set", "trim_set", "trim_undo",
    "trigger_save", "trigger_delete", "trigger_prepare", "trigger_fire", "mission_upload", "mission_start",
    "mission_pause", "mission_resume", "mission_skip", "mission_abort", "goto", "fence_set", "home_set", "pose_capture",
    "face_express", "face_show", "ground_run", "ground_run_stop", "ground_run_forget", "plan_check", "plan_upload",
)

/** Wire format shared by the WebSocket ground-station link and the MCP tools. */
object Codec {
    private fun JsonObject.num(k: String): Double? {
        val e = this[k] ?: return null
        val p = e as? JsonPrimitive ?: throw MalformedCommand("$k must be a number")
        if (p.isString) throw MalformedCommand("$k must be a number")
        val d = p.doubleOrNull ?: throw MalformedCommand("$k must be a number")
        if (!d.isFinite()) throw MalformedCommand("$k must be finite")
        return d
    }
    private fun JsonObject.req(k: String) = num(k) ?: throw MalformedCommand("missing $k")
    private fun JsonObject.str(k: String): String {
        val p = this[k] as? JsonPrimitive ?: throw MalformedCommand("missing $k")
        if (!p.isString) throw MalformedCommand("$k must be a string")
        return p.content
    }

    fun parseCommand(o: JsonObject): Command = when (val c = o.str("cmd")) {
        "drive" -> Command.Drive(o.req("throttle"), o.req("steering"), o.num("pitch") ?: 0.0, o.num("roll") ?: 0.0)
        "set_channels" -> Command.SetChannels(
            (o["channels"] as? JsonArray ?: throw MalformedCommand("missing channels")).map {
                parseChannel(it as? JsonObject ?: throw MalformedCommand("channel must be an object"))
            },
            (o["frameFormat"] as? JsonPrimitive)?.content,
        )
        "set_throttle" -> Command.SetThrottle(o.req("value"), (o.num("durationMs") ?: 1000.0).toLong())
        "set_steering" -> Command.SetSteering(o.req("value"), (o.num("durationMs") ?: 1000.0).toLong())
        "set_speed" -> Command.SetSpeed(o.req("mps"), (o.num("durationMs") ?: 2000.0).toLong())
        "stop" -> Command.Stop
        "set_mode" -> Command.SetMode(
            runCatching { Mode.valueOf(o.str("mode").uppercase()) }.getOrElse { throw MalformedCommand("unknown mode") },
        )
        "configure_pid" -> Command.ConfigurePid(o.num("kp"), o.num("ki"), o.num("kd"), o.num("ff"))
        "update_profile" -> {
            val f = o["fields"] as? JsonObject ?: throw MalformedCommand("missing fields")
            Command.UpdateProfile(f.keys.associateWith { f.req(it) })
        }
        "select_profile" -> Command.SelectProfile(o.str("id"))
        "set_input" -> Command.SetNamedInputs(
            (o["inputs"] as? JsonObject ?: throw MalformedCommand("missing inputs")).let { i -> i.keys.associateWith { k -> i.req(k) } },
            (o.num("durationMs") ?: 500.0).toLong(),
        )
        "run_skill" -> Command.RunSkill(o.str("id"))
        "stop_skill" -> Command.StopSkill
        "run_behavior" -> Command.RunBehavior(o.str("id"))
        "stop_behavior" -> Command.StopBehavior
        "kill" -> Command.Kill
        in OPS_COMMANDS -> Command.OpsRequest(c, o)
        "set_flight_mode" -> Command.SetFlightMode(
            runCatching { FlightMode.valueOf(o.str("mode").uppercase()) }.getOrElse { throw MalformedCommand("unknown flight mode") },
        )
        "run_mission" -> Command.RunMission(o.str("name"))
        else -> throw MalformedCommand("unknown cmd $c")
    }

    fun channel(c: Channel) = buildJsonObject {
        put("name", c.name); put("kind", c.kind.name); put("throttle", c.throttle); put("yaw", c.yaw)
        put("pitch", c.pitch); put("roll", c.roll); put("trim", c.trim); put("failsafe", c.failsafe)
    }

    fun parseChannel(o: JsonObject) = Channel(
        o.str("name"),
        runCatching { ChannelKind.valueOf((o["kind"] as? JsonPrimitive)?.content ?: "SERVO") }.getOrElse { throw MalformedCommand("unknown channel kind") },
        o.num("throttle") ?: 0.0, o.num("yaw") ?: 0.0, o.num("pitch") ?: 0.0, o.num("roll") ?: 0.0,
        o.num("trim") ?: 0.0, o.num("failsafe") ?: 0.0,
    )

    fun parseEnvelope(o: JsonObject, source: Source) = Envelope(
        source,
        o.num("seq")?.toLong() ?: throw MalformedCommand("missing seq"),
        o.num("sentAt")?.toLong() ?: throw MalformedCommand("missing sentAt"),
        parseCommand(o),
    )

    fun profile(p: VehicleProfile) = buildJsonObject {
        put("id", p.id); put("name", p.name)
        put("maxThrottle", p.maxThrottle); put("maxSteering", p.maxSteering); put("maxSpeedMps", p.maxSpeedMps)
        put("kp", p.kp); put("ki", p.ki); put("kd", p.kd); put("ff", p.ff)
        put("commandTimeoutMs", p.commandTimeoutMs); put("staleCommandMs", p.staleCommandMs)
        put("maxAiLeaseMs", p.maxAiLeaseMs); put("obstacleStopM", p.obstacleStopM); put("aiMayArm", p.aiMayArm)
        put("vehicleType", p.vehicleType); put("adapter", p.adapter); put("missionsAllowed", p.missionsAllowed)
        put("frameFormat", p.frameFormat)
        put("channels", JsonArray(p.channels.map { channel(it) }))
    }

    fun parseProfile(o: JsonObject): VehicleProfile {
        val d = VehicleProfile(o.str("id"), o.str("name"))
        return d.copy(
            maxThrottle = o.num("maxThrottle") ?: d.maxThrottle,
            maxSteering = o.num("maxSteering") ?: d.maxSteering,
            maxSpeedMps = o.num("maxSpeedMps") ?: d.maxSpeedMps,
            kp = o.num("kp") ?: d.kp, ki = o.num("ki") ?: d.ki, kd = o.num("kd") ?: d.kd, ff = o.num("ff") ?: d.ff,
            commandTimeoutMs = o.num("commandTimeoutMs")?.toLong() ?: d.commandTimeoutMs,
            staleCommandMs = o.num("staleCommandMs")?.toLong() ?: d.staleCommandMs,
            maxAiLeaseMs = o.num("maxAiLeaseMs")?.toLong() ?: d.maxAiLeaseMs,
            obstacleStopM = o.num("obstacleStopM") ?: d.obstacleStopM,
            aiMayArm = (o["aiMayArm"] as? JsonPrimitive)?.booleanOrNull ?: d.aiMayArm,
            vehicleType = (o["vehicleType"] as? JsonPrimitive)?.content ?: d.vehicleType,
            adapter = (o["adapter"] as? JsonPrimitive)?.content ?: d.adapter,
            missionsAllowed = (o["missionsAllowed"] as? JsonPrimitive)?.booleanOrNull ?: d.missionsAllowed,
            frameFormat = (o["frameFormat"] as? JsonPrimitive)?.content ?: d.frameFormat,
            channels = (o["channels"] as? JsonArray)?.map { parseChannel(it.jsonObject) } ?: d.channels,
        )
    }

    private fun r(v: Double) = Math.round(v * 1000.0) / 1000.0
    private fun r(v: Double?): Double? = v?.let { r(it) }

    fun telemetry(t: Telemetry) = buildJsonObject {
        put("t", t.t); put("mode", t.mode.name); put("profile", t.profileId)
        put("throttle", r(t.throttle)); put("steering", r(t.steering))
        put("targetSpeed", r(t.targetSpeed))
        val s = t.sensors
        put("speed", r(s.speedMps)); put("yawRate", r(s.yawRateRps))
        put("x", r(s.x)); put("y", r(s.y)); put("heading", r(s.headingRad))
        put("range", r(s.rangeM)); put("collided", s.collided)
        put("mission", t.mission); put("failsafeReason", t.failsafeReason)
        put("obstacleHold", t.obstacleHold); put("aiLocked", t.aiLocked)
        put("loopHz", r(t.loop.hz)); put("loopDtAvgMs", r(t.loop.dtAvgMs)); put("loopDtMaxMs", r(t.loop.dtMaxMs))
        put("loopOverruns", t.loop.overruns)
        put("accepted", t.accepted); put("rejected", t.rejected); put("cmdProcAvgUs", r(t.cmdProcAvgUs))
        put("out", buildJsonArray { t.outputs.forEach { o -> add(buildJsonArray { add(r(o.value)); add(o.pulseUs) }) } })
        put("frame", t.frameHex)
        put("would", buildJsonArray { t.would.forEach { add(it) } })
    }

    fun logEntry(e: LogEntry) = buildJsonObject {
        put("id", e.id); put("t", e.t); put("kind", e.kind); put("source", e.source)
        put("code", e.code); put("message", e.message)
    }

    private fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    fun telemetryCsv(rows: List<Telemetry>) = buildString {
        appendLine("t,mode,throttle,steering,targetSpeed,speed,yawRate,x,y,heading,range,collided,loopHz,loopDtMaxMs,overruns,outputsUs")
        rows.forEach { t ->
            val s = t.sensors
            appendLine(
                listOf(
                    t.t, t.mode, t.throttle, t.steering, t.targetSpeed ?: "", s.speedMps ?: "", s.yawRateRps ?: "",
                    s.x ?: "", s.y ?: "", s.headingRad ?: "", s.rangeM ?: "", s.collided, t.loop.hz, t.loop.dtMaxMs, t.loop.overruns,
                    t.outputs.joinToString(";") { it.pulseUs.toString() },
                ).joinToString(","),
            )
        }
    }

    fun logCsv(rows: List<LogEntry>) = buildString {
        appendLine("id,t,kind,source,code,message")
        rows.forEach { appendLine("${it.id},${it.t},${esc(it.kind)},${esc(it.source)},${esc(it.code)},${esc(it.message)}") }
    }
}
