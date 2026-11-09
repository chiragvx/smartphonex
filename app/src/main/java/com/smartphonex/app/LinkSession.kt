package com.smartphonex.app

import com.smartphonex.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import kotlin.random.Random

/**
 * One ground-station conversation, independent of how it is carried: a local WebSocket or a
 * channel through the relay. Both therefore behave identically, including under fault injection.
 */
class LinkSession(
    private val rt: VehicleRuntime,
    private val scope: CoroutineScope,
    private val send: suspend (String) -> Unit,
    /** True when the outgoing path is backed up: telemetry is skipped rather than queued. */
    private val congested: () -> Boolean = { false },
    private val dropLink: suspend () -> Unit,
    /** False for viewers through the relay: they don't get location or the camera list. */
    private val canSeePrivate: Boolean = true,
) {
    private var sender: Job? = null
    private var sensorSender: Job? = null
    private var fcFastSender: Job? = null
    private var fcSlowSender: Job? = null
    private var opsSender: Job? = null
    private val sensorInbox = Channel<SensorPayload>(Channel.CONFLATED)
    private val listener: (SensorPayload) -> Unit = { sensorInbox.trySend(it) }

    suspend fun start() {
        rt.groundLinks.incrementAndGet()
        rt.ops.touchLink()
        send(hello().toString())
        opsSender = scope.launch {
            while (isActive) {
                if (!congested()) send(rt.ops.opsJson(canSeePrivate).toString())
                delay(500)
            }
        }
        var lastEvent = rt.log.allEvents().lastOrNull()?.id?.minus(50) ?: 0L
        var lastVersion = rt.sensors.version
        var tick = 0
        var lastSetup = -1L
        rt.addSensorListener(listener)
        sensorSender = scope.launch {
            for (p in sensorInbox) {
                // Sensors wait their turn: under congestion they're skipped, never queued.
                if (!congested()) send(if (canSeePrivate) p.full else p.public)
            }
        }
        // Flight state has its own guaranteed slot: it isn't subject to the data budget.
        fcFastSender = scope.launch {
            var active = true
            while (isActive) {
                delay((1000.0 / rt.params.get("TLM_HZ")).toLong().coerceAtLeast(20))
                if (!congested()) send(rt.fc.fastJson())
                active = !active
            }
        }
        fcSlowSender = scope.launch {
            while (isActive) {
                delay(500)
                if (!congested()) send(rt.fc.slowJson(canSeePrivate).toString())
            }
        }
        sender = scope.launch {
            while (isActive) {
                delay(100)
                tick++
                if (rt.sensors.version != lastVersion) {
                    lastVersion = rt.sensors.version
                    send(config().toString())
                }
                // Old telemetry is worthless: under congestion, skip it so the queue can drain.
                if (congested()) continue
                val events = rt.log.eventsSince(lastEvent)
                events.lastOrNull()?.let { lastEvent = it.id }
                val msg = buildJsonObject {
                    put("type", "telemetry")
                    put("data", Codec.telemetry(rt.controller.latest()))
                    if (events.isNotEmpty()) put("events", JsonArray(events.map { Codec.logEntry(it) }))
                    if (tick % 10 == 0) { put("net", rt.net); put("relay", rt.relay) }
                }.toString()
                if (Random.nextInt(100) >= rt.faults.lossPct || events.isNotEmpty()) deliver { send(msg) }
                if (tick % 5 == 0 && rt.profiles.active.adapter == "device") {
                    send(JsonObject(rt.engine.statusJson() + ("type" to JsonPrimitive("device"))).toString())
                }
                if (rt.setup.version != lastSetup) {
                    lastSetup = rt.setup.version
                    send(rt.setup.pendingJson().toString())
                }
                if (tick % 10 == 0) {
                    send(JsonObject(rt.sensors.stats(canSeePrivate) + ("camera" to JsonPrimitive(cameraStatus()))).toString())
                }
            }
        }
    }

    fun stop() {
        rt.removeSensorListener(listener)
        sender?.cancel()
        sensorSender?.cancel()
        fcFastSender?.cancel()
        fcSlowSender?.cancel()
        opsSender?.cancel()
        rt.groundLinks.decrementAndGet()
        sensorInbox.close()
    }

    private fun cameraStatus() = rt.camera?.status() ?: "unavailable"

    private fun deliver(block: suspend () -> Unit) {
        val d = rt.faults.delayMs
        scope.launch {
            if (d > 0) delay(d)
            block()
        }
    }

    private fun lost() = rt.faults.lossPct > 0 && Random.nextInt(100) < rt.faults.lossPct

    suspend fun onText(text: String) {
        rt.ops.touchLink()
        val o = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull()
        if (o == null) {
            rt.controller.recordMalformed(Source.GROUND, "not a JSON object")
            send(ack(null, CommandResult.reject("malformed", "not a JSON object"), null, System.currentTimeMillis(), 0))
            return
        }
        when ((o["type"] as? JsonPrimitive)?.content) {
            "fault" -> onFault(o)
            "probe" -> scope.launch(Dispatchers.IO) { send(Probe.run().toString()) }
            "sensor_cfg", "budget", "camera_cfg", "ai_access" -> onConfig(o)
            "param_set", "fc_action" -> onFlightConfig(o)
            "setup_approve" -> {
                val err = rt.setup.approve(o.text("id") ?: "", o.bool("allow") == true, "ground station")
                send(buildJsonObject { put("type", "cfgAck"); put("what", "approval"); put("ok", err == null); put("message", err ?: "") }.toString())
            }
            "device_import" -> onImport(o)
            "mavlink_cfg" -> {
                val err = rt.mavlink?.invoke(o.text("host") ?: "")
                send(buildJsonObject { put("type", "cfgAck"); put("what", "mavlink"); put("ok", err == null); put("message", err ?: "") }.toString())
            }
            "assistant_cfg", "assistant_say", "assistant_reset" -> rt.assistant?.onMessage(o) { scope.launch { send(it) } }
            "ping" -> if (!lost()) deliver {
                send(buildJsonObject { put("type", "pong"); put("c", o["c"] ?: JsonNull); put("t", System.currentTimeMillis()) }.toString())
            }
            "cmd" -> if (!lost()) deliver { send(runCommand(o)) }
            else -> {
                rt.controller.recordMalformed(Source.GROUND, "unknown message type")
                send(ack(o["seq"], CommandResult.reject("malformed", "unknown message type"), null, System.currentTimeMillis(), 0))
            }
        }
    }

    /** Importing is data only, and a shared pack always restarts commissioning. */
    private suspend fun onImport(o: JsonObject) {
        val error = runCatching {
            val text = o.text("text") ?: ""
            val isXml = text.trimStart().startsWith("<")
            val d = if (isXml) DeviceSchema.fromUrdf(text, o.text("name") ?: "Imported robot") else {
                val (parsed, errors) = DeviceSchema.parse(Json.parseToJsonElement(text).jsonObject)
                parsed ?: error(errors.joinToString("; "))
            }
            val fresh = DeviceSchema.forImport(d)
            val errors = DeviceSchema.validate(fresh)
            if (errors.isNotEmpty()) error(errors.joinToString("; "))
            rt.devices.put(fresh)
            rt.registerDevice(fresh)
            rt.log.event("setup", "GROUND", "import", "imported ${fresh.name}: safety stages must be run again")
        }.exceptionOrNull()?.message?.take(400)
        send(buildJsonObject { put("type", "cfgAck"); put("what", "import"); put("ok", error == null); put("message", error ?: "") }.toString())
        if (error == null) send(hello().toString())
    }

    private suspend fun onFlightConfig(o: JsonObject) {
        val type = o.text("type")
        val error = if (type == "param_set") {
            val name = o.text("name") ?: ""
            val before = runCatching { rt.params.get(name, rt.profiles.active.vehicleType) }.getOrNull()
            rt.params.set(name, o.dbl("value") ?: Double.NaN).also {
                if (it == null) rt.log.event("param", "GROUND", name, "set to ${o.dbl("value")}")
                if (it == null && before != null && before != o.dbl("value")) rt.ops.recordParam(name, before, o.dbl("value")!!)
                if (it == null && name == "VIS_LINE") rt.camera?.refresh()
            }
        } else when (o.text("action")) {
            "calibrate_gyro" -> rt.fc.calibrateGyro()
            "set_level" -> rt.fc.setLevel()
            "set_home" -> rt.fc.setHomeHere()
            else -> "unknown action"
        }
        send(buildJsonObject {
            put("type", "cfgAck"); put("what", o.text("name") ?: o.text("action")); put("ok", error == null); put("message", error ?: "")
        }.toString())
        send(buildJsonObject { put("type", "params"); put("list", rt.params.toJson(rt.profiles.active.vehicleType)); put("history", rt.ops.paramHistoryJson()) }.toString())
    }

    private fun JsonObject.dbl(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private suspend fun onConfig(o: JsonObject) {
        val hub = rt.sensors
        val type = o.text("type")
        val error = when (type) {
            "sensor_cfg" -> hub.configure(o.text("id") ?: "", o.bool("enabled"), o.dbl("rateHz"), o.dbl("priority")?.toInt())
            "budget" -> hub.setBudget(o.dbl("kbps")?.toInt() ?: -1)
            "ai_access" -> {
                hub.setAiPrivateAccess(o.bool("value") == true)
                rt.log.event("privacy", "GROUND", "ai_access", if (o.bool("value") == true) "AI may read camera and location" else "AI access to camera and location removed")
                null
            }
            else -> {
                val c = hub.camera
                val err = hub.setCamera(c.copy(
                    enabled = o.bool("enabled") ?: c.enabled,
                    cameraId = o.text("cameraId") ?: c.cameraId,
                    width = o.dbl("width")?.toInt() ?: c.width,
                    height = o.dbl("height")?.toInt() ?: c.height,
                    fps = o.dbl("fps") ?: c.fps,
                    quality = o.dbl("quality")?.toInt() ?: c.quality,
                    priority = o.dbl("priority")?.toInt() ?: c.priority,
                ))
                if (err == null && c.enabled != hub.camera.enabled) {
                    rt.log.event("privacy", "GROUND", "camera", if (hub.camera.enabled) "camera turned on" else "camera turned off")
                }
                err
            }
        }
        send(buildJsonObject {
            put("type", "cfgAck"); put("what", type); put("id", o["id"] ?: JsonNull); put("ok", error == null); put("message", error ?: "")
        }.toString())
    }

    private fun runCommand(o: JsonObject): String {
        val recvAt = System.currentTimeMillis()
        val start = System.nanoTime()
        val r = try {
            rt.controller.submit(Codec.parseEnvelope(o, Source.GROUND))
        } catch (e: MalformedCommand) {
            rt.controller.recordMalformed(Source.GROUND, e.message ?: "invalid")
            CommandResult.reject("malformed", e.message ?: "invalid")
        }
        return ack(o["seq"], r, (o["cmd"] as? JsonPrimitive)?.content, recvAt, (System.nanoTime() - start) / 1000)
    }

    private fun ack(seq: JsonElement?, r: CommandResult, cmd: String?, recvAt: Long, procUs: Long) = buildJsonObject {
        put("type", "ack"); put("seq", seq ?: JsonNull); put("cmd", cmd)
        put("accepted", r.accepted); put("code", r.code); put("message", r.message)
        r.data?.let { put("data", it) }
        put("t", System.currentTimeMillis()); put("recvAt", recvAt); put("procUs", procUs)
    }.toString()

    private fun config() = buildJsonObject {
        put("type", "config")
        put("sensors", rt.sensors.catalogJson(canSeePrivate))
        put("budgetKbps", rt.sensors.budgetKbps)
        if (canSeePrivate) {
            put("cameras", rt.camera?.catalog() ?: JsonArray(emptyList()))
            put("camera", rt.sensors.cameraJson())
            put("aiAccess", rt.sensors.aiPrivateAccess)
        }
    }

    private fun hello() = buildJsonObject {
        put("type", "hello")
        put("t", System.currentTimeMillis())
        put("vehicle", rt.controller.adapterId)
        put("simulated", rt.controller.currentAdapter is SimulatedCar)
        put("missions", JsonArray(Missions.all.keys.map { JsonPrimitive(it) }))
        put("activeProfile", rt.profiles.activeId)
        put("profiles", JsonArray(rt.profiles.list().map { Codec.profile(it) }))
        put("net", rt.net)
        put("relay", rt.relay)
        put("params", rt.params.toJson(rt.profiles.active.vehicleType))
        put("assistant", rt.assistant?.configJson() ?: JsonNull)
        put("device", rt.engine.device?.takeIf { rt.profiles.active.adapter == "device" }?.let { d ->
            buildJsonObject {
                put("id", d.id); put("name", d.name); put("class", d.deviceClass); put("hazard", d.hazard); put("stage", d.commissioning.stage)
                put("signedOff", d.commissioning.signedOffBy != null)
                putJsonArray("skills") { d.skills.forEach { add(it.id) } }
                putJsonArray("behaviors") { d.behaviors.forEach { add(it.id) } }
            }
        } ?: JsonNull)
        put("flightModes", JsonArray(FlightMode.entries.map { JsonPrimitive(it.name) }))
        put("protocol", 2)
        put("caps", rt.ops.capsJson())
        config().forEach { (k, v) -> if (k != "type") put(k, v) }
        val car = rt.controller.currentAdapter as? SimulatedCar
        if (car != null) {
            put("arenaHalf", car.arenaHalf)
            put("obstacles", JsonArray(car.obstacles.map { b ->
                buildJsonArray { add(b.x0); add(b.y0); add(b.x1); add(b.y1) }
            }))
        }
    }

    /** Fault control is exempt from the faults it sets, so a test can always be ended. */
    private suspend fun onFault(o: JsonObject) {
        val f = rt.faults
        val v = (o["value"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val name = (o["fault"] as? JsonPrimitive)?.content ?: return
        when (name) {
            "delay" -> f.delayMs = v.toLong().coerceIn(0, 5000)
            "loss" -> f.lossPct = v.toInt().coerceIn(0, 100)
            "mcp_down" -> f.mcpDown = v != 0.0
            "stall" -> f.stallMs = v.toLong().coerceIn(0, 5000)
            "cpu" -> f.startCpuLoad(Runtime.getRuntime().availableProcessors(), v.toInt().coerceIn(1, 30))
            "sim_reset" -> rt.controller.resetVehicle()
            "disconnect" -> {
                f.linkBlockedUntil = System.currentTimeMillis() + (v * 1000).toLong().coerceIn(0, 60000)
                rt.log.event("fault", "GROUND", "disconnect", "link dropped for ${v}s")
                dropLink()
                return
            }
            else -> return
        }
        rt.log.event("fault", "GROUND", name, "value $v")
        send(buildJsonObject {
            put("type", "faults"); put("delay", f.delayMs); put("loss", f.lossPct); put("mcpDown", f.mcpDown)
        }.toString())
    }
}
