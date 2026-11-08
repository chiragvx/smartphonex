package com.smartphonex.app

import com.smartphonex.core.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Serves the ground station, its WebSocket link and the MCP endpoint from the phone itself. */
class GroundServer(
    private val rt: VehicleRuntime,
    private val relay: RelayClient,
    private val camera: CameraStreamer,
    private val readAsset: (String) -> ByteArray?,
    private val restart: () -> Unit = {},
    private val diagnostics: Diagnostics? = null,
    private val updates: Updates? = null,
    private val port: Int = 8080,
) {
    private val sessions = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()
    private val mcp = McpHandler(rt)
    private var engine: ApplicationEngine? = null

    private fun authorized(presented: String?) =
        presented != null && MessageDigest.isEqual(presented.toByteArray(), rt.token.toByteArray())

    private fun bearer(call: ApplicationCall) = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")

    fun start() {
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) { pingPeriodMillis = 5000; timeoutMillis = 15000 }
            routing {
                get("/") { asset(call, "index.html", ContentType.Text.Html) }
                get("/assets/{name}") { static(call, "assets/" + (call.parameters["name"] ?: "")) }
                get("/classic/") { asset(call, "classic/index.html", ContentType.Text.Html) }
                get("/classic/app.js") { asset(call, "classic/app.js", ContentType.Text.JavaScript) }
                get("/review/files") { reviewFiles(call) }
                get("/review/file/{kind}/{name}") { reviewFile(call) }
                get("/review/frames/{run}") { reviewFrames(call) }
                get("/review/frame/{run}/{t}") { reviewFrame(call) }
                get("/favicon.ico") { call.respond(HttpStatusCode.NoContent) }
                get("/export/{name}") { export(call) }
                get("/api/tools") { apiList(call) }
                post("/api/tools/{name}") { apiCall(call) }
                get("/export/pack/{id}") { exportPack(call) }
                get("/backup") { backup(call) }
                get("/diagnostics") { diagnosticsJson(call) }
                get("/diagnostics/phone") { phoneReport(call) }
                get("/diagnostics/crash/{name}") { crash(call) }
                post("/diagnostics/update") { updateCheck(call) }
                post("/backup/restore") { restore(call) }
                post("/pair") { pair(call) }
                get("/pair/code") { pairCode(call) }
                post("/relay/config") { relayConfig(call) }
                post("/mcp") { handleMcp(call) }
                get("/mcp") { call.respond(HttpStatusCode.MethodNotAllowed) }
                webSocket("/ws") { link(this) }
                webSocket("/video") { video(this) }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(200, 500)
    }

    private suspend fun asset(call: ApplicationCall, name: String, type: ContentType) {
        val bytes = readAsset("web/$name") ?: return call.respond(HttpStatusCode.NotFound)
        call.respondBytes(bytes, type)
    }

    /** Build output only: these names are fixed at build time, and the path can't leave the folder. */
    private suspend fun static(call: ApplicationCall, path: String) {
        if (path.contains("..")) return call.respond(HttpStatusCode.NotFound)
        val type = when {
            path.endsWith(".js") -> ContentType.Text.JavaScript
            path.endsWith(".css") -> ContentType.Text.CSS
            else -> ContentType.Application.OctetStream
        }
        asset(call, path, type)
    }

    private suspend fun reviewFiles(call: ApplicationCall) {
        if (!authorized(call.request.queryParameters["token"])) return call.respond(HttpStatusCode.Unauthorized)
        call.respondText(rt.ops.filesJson().toString(), ContentType.Application.Json)
    }

    private suspend fun reviewFile(call: ApplicationCall) {
        if (!authorized(call.request.queryParameters["token"])) return call.respond(HttpStatusCode.Unauthorized)
        val (bytes, type) = rt.ops.readFile(call.parameters["kind"] ?: "", call.parameters["name"] ?: "") ?: return call.respond(HttpStatusCode.NotFound)
        call.respondBytes(bytes, ContentType.parse(type))
    }

    private suspend fun reviewFrames(call: ApplicationCall) {
        if (!authorized(call.request.queryParameters["token"])) return call.respond(HttpStatusCode.Unauthorized)
        call.respondText(rt.ops.framesJson(call.parameters["run"] ?: "").toString(), ContentType.Application.Json)
    }

    private suspend fun reviewFrame(call: ApplicationCall) {
        if (!authorized(call.request.queryParameters["token"])) return call.respond(HttpStatusCode.Unauthorized)
        val b = rt.ops.readFrame(call.parameters["run"] ?: "", call.parameters["t"]?.toLongOrNull() ?: 0) ?: return call.respond(HttpStatusCode.NotFound)
        call.respondBytes(b, ContentType.Image.JPEG)
    }

    private suspend fun export(call: ApplicationCall) {
        if (!authorized(call.request.queryParameters["token"])) return call.respond(HttpStatusCode.Unauthorized)
        val csv = rt.exportCsv(call.parameters["name"] ?: "") ?: return call.respond(HttpStatusCode.NotFound)
        call.respondText(csv, ContentType.Text.CSV)
    }

    /** Plain HTTP version of the tools, for anything that can't use MCP. */
    private suspend fun apiList(call: ApplicationCall) {
        if (!authorized(bearer(call))) return call.respond(HttpStatusCode.Unauthorized)
        call.respondText(mcp.listToolsJson().toString(), ContentType.Application.Json)
    }

    private suspend fun apiCall(call: ApplicationCall) {
        if (!authorized(bearer(call))) return call.respond(HttpStatusCode.Unauthorized)
        val args = runCatching { Json.parseToJsonElement(call.receiveText()) as JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val name = call.parameters["name"] ?: ""
        if (mcp.listToolsJson().none { it.jsonObject["name"]?.jsonPrimitive?.content == name }) return call.respond(HttpStatusCode.NotFound)
        call.respondText(mcp.callForApi(name, args).toString(), ContentType.Application.Json)
    }

    /** A pack holds no secrets or location, and commissioning restarts wherever it's imported. */
    private suspend fun exportPack(call: ApplicationCall) {
        if (!authorized(call.request.queryParameters["token"])) return call.respond(HttpStatusCode.Unauthorized)
        val d = rt.devices.get(call.parameters["id"] ?: "") ?: return call.respond(HttpStatusCode.NotFound)
        call.respondText(DeviceSchema.toJson(d).toString(), ContentType.Application.Json)
    }

    /** How the app has been: exits, crashes, and the update check. */
    private suspend fun diagnosticsJson(call: ApplicationCall) {
        if (!authorized(call.request.queryParameters["token"])) return call.respond(HttpStatusCode.Unauthorized)
        val d = diagnostics ?: return call.respond(HttpStatusCode.NotFound)
        call.respondText(buildJsonObject {
            d.json().forEach { (k, v) -> put(k, v) }
            updates?.let { put("update", it.json()) }
        }.toString(), ContentType.Application.Json)
    }

    /** What this phone can do, as a file to share for the compatibility table. */
    private suspend fun phoneReport(call: ApplicationCall) {
        if (!authorized(call.request.queryParameters["token"])) return call.respond(HttpStatusCode.Unauthorized)
        val d = diagnostics ?: return call.respond(HttpStatusCode.NotFound)
        val model = android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(40)
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"phone-report-$model.json\"")
        call.respondText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), d.phoneReport()), ContentType.Application.Json)
    }

    private suspend fun crash(call: ApplicationCall) {
        if (!authorized(call.request.queryParameters["token"])) return call.respond(HttpStatusCode.Unauthorized)
        val text = diagnostics?.crashText(call.parameters["name"] ?: "") ?: return call.respond(HttpStatusCode.NotFound)
        call.respondText(text, ContentType.Text.Plain)
    }

    private suspend fun updateCheck(call: ApplicationCall) {
        if (!authorized(bearer(call))) return call.respond(HttpStatusCode.Unauthorized)
        val u = updates ?: return call.respond(HttpStatusCode.NotFound)
        val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { u.check() }
        call.respondText(out.toString(), ContentType.Application.Json)
    }

    /** Everything the app has saved, apart from secrets, as one zip. Media only with ?media=1. */
    private suspend fun backup(call: ApplicationCall) {
        if (!authorized(call.request.queryParameters["token"])) return call.respond(HttpStatusCode.Unauthorized)
        val media = call.request.queryParameters["media"] == "1"
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"smartphonex-$stamp${if (media) "-media" else ""}.zip\"")
        rt.log.event("app", "backup", "ok", if (media) "with media" else "settings and logs")
        call.respondOutputStream(ContentType.Application.Zip) { AppData.backup(rt.dir, this, BuildConfig.VERSION_NAME, media) }
    }

    /**
     * Restores a backup made by [backup]. It's checked and unpacked beside the live files, then swapped in by a restart
     * of the app's service. Refused while armed. This phone keeps its own token, AI key and relay link.
     */
    private suspend fun restore(call: ApplicationCall) {
        if (!authorized(bearer(call))) return call.respond(HttpStatusCode.Unauthorized)
        fun no(why: String) = buildJsonObject { put("ok", false); put("error", why) }
        val t = rt.controller.latest()
        if (t.mode == Mode.MANUAL || t.mode == Mode.AUTO) return call.respondText(no("disarm first").toString(), ContentType.Application.Json, HttpStatusCode.Conflict)
        val size = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        val free = rt.dir.usableSpace
        // Unpacked size isn't known yet. Allow for the zip plus its contents, with room to spare.
        if (size != null && size * 3 > free) return call.respondText(no("not enough free space on the phone").toString(), ContentType.Application.Json, HttpStatusCode.InsufficientStorage)
        val why = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            call.receiveStream().use { AppData.stage(rt.dir, it) }
        }
        if (why != null) {
            rt.log.event("app", "restore", "refused", why)
            return call.respondText(no(why).toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
        }
        rt.log.event("app", "restore", "ok", "staged; restarting to apply")
        call.respondText(buildJsonObject { put("ok", true); put("restarting", true) }.toString(), ContentType.Application.Json)
        restart()
    }

    private suspend fun pair(call: ApplicationCall) {
        val o = runCatching { Json.parseToJsonElement(call.receiveText()) as JsonObject }.getOrNull()
        val attempt = (o?.get("code") as? JsonPrimitive)?.content ?: ""
        val (status, body) = when (val r = rt.pairing.redeem(attempt)) {
            is PairResult.Ok -> {
                rt.log.event("pair", "GROUND", "paired", "browser paired with code")
                HttpStatusCode.OK to buildJsonObject { put("token", r.token) }
            }
            PairResult.Wrong -> {
                rt.log.event("pair", "GROUND", "wrong_code", "wrong pairing code")
                HttpStatusCode.Unauthorized to buildJsonObject { put("error", "wrong_code") }
            }
            is PairResult.Locked -> {
                rt.log.event("pair", "GROUND", "locked", "pairing locked for ${r.seconds}s")
                HttpStatusCode.TooManyRequests to buildJsonObject { put("error", "locked"); put("retryAfterSeconds", r.seconds) }
            }
        }
        call.respondText(body.toString(), ContentType.Application.Json, status)
    }

    /** For tools that already hold the token, such as the acceptance test. */
    private suspend fun pairCode(call: ApplicationCall) {
        if (!authorized(bearer(call))) return call.respond(HttpStatusCode.Unauthorized)
        call.respondText(buildJsonObject { put("code", rt.pairing.currentCode()) }.toString(), ContentType.Application.Json)
    }

    /** Points this phone at a relay. An empty url disconnects it. Local network and token holders only. */
    private suspend fun relayConfig(call: ApplicationCall) {
        if (!authorized(bearer(call))) return call.respond(HttpStatusCode.Unauthorized)
        val o = runCatching { Json.parseToJsonElement(call.receiveText()) as JsonObject }.getOrNull()
        val url = (o?.get("url") as? JsonPrimitive)?.content ?: ""
        val key = (o?.get("key") as? JsonPrimitive)?.content ?: ""
        val err = relay.configure(url, key)
        if (err != null) return call.respondText(err, ContentType.Text.Plain, HttpStatusCode.BadRequest)
        call.respondText(rt.relay.toString(), ContentType.Application.Json)
    }

    private suspend fun handleMcp(call: ApplicationCall) {
        if (!authorized(bearer(call))) return call.respond(HttpStatusCode.Unauthorized)
        val r = mcp.handleText(call.receiveText())
        val status = HttpStatusCode.fromValue(r.status)
        if (r.body == null) call.respond(status) else call.respondText(r.body, ContentType.Application.Json, status)
    }

    private suspend fun link(ws: DefaultWebSocketServerSession) = with(ws) {
        if (!authorized(call.request.queryParameters["token"])) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "bad token"))
            return@with
        }
        if (System.currentTimeMillis() < rt.faults.linkBlockedUntil) {
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "link fault injected"))
            return@with
        }
        sessions += this
        rt.log.event("link", "GROUND", "connected", "ground station connected")
        val session = LinkSession(rt, this, { send(Frame.Text(it)) }, dropLink = ::dropAll)
        session.start()
        try {
            for (frame in incoming) {
                if (frame is Frame.Text) session.onText(frame.readText())
            }
        } finally {
            session.stop()
            sessions -= this
            rt.log.event("link", "GROUND", "disconnected", "ground station disconnected")
        }
    }

    /** Video has its own socket, so a frame in flight can never hold up a command. */
    private suspend fun video(ws: DefaultWebSocketServerSession) = with(ws) {
        if (!authorized(call.request.queryParameters["token"])) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "bad token"))
            return@with
        }
        val out = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.CONFLATED)
        val sink = VideoSink { out.trySend(it) }
        val writer = launch { for (f in out) send(Frame.Binary(true, f)) }
        camera.addSink(sink)
        try {
            for (frame in incoming) sink.acked()
        } finally {
            camera.removeSink(sink)
            writer.cancel()
        }
    }

    private suspend fun dropAll() {
        sessions.toList().forEach { it.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "link fault injected")) }
        relay.drop()
    }
}
