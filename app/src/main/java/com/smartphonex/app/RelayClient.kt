package com.smartphonex.app

import com.smartphonex.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The phone's outgoing connection to the relay. Cellular networks block incoming connections,
 * so the phone always dials out. Nothing in vehicle control waits on this class: if it fails,
 * the control loop and local server carry on untouched.
 */
class RelayClient(private val rt: VehicleRuntime, dir: File, private val camera: CameraStreamer) {
    @Volatile private var videoSocket: WebSocket? = null
    @Volatile private var videoSink: VideoSink? = null
    @Volatile private var videoViewers = 0
    private val videoLock = Any()

    /** Video gets its own connection, opened only while someone is watching through the relay. */
    private fun syncVideo() = synchronized(videoLock) {
        if (videoViewers > 0 && videoSocket == null && url.isNotEmpty()) openVideo()
        if (videoViewers == 0) closeVideo()
    }

    private fun openVideo() {
        val wsUrl = url.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/vehicle/video"
        val req = Request.Builder().url(wsUrl).header("Authorization", "Bearer $key").build()
        videoSocket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val sink = VideoSink { webSocket.send(it.toByteString()) }
                synchronized(videoLock) { videoSink = sink }
                camera.addSink(sink)
            }
            override fun onMessage(webSocket: WebSocket, text: String) { videoSink?.acked() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = videoLost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = videoLost(webSocket)
        })
    }

    private fun videoLost(ws: WebSocket) {
        synchronized(videoLock) {
            if (videoSocket !== ws) return
            videoSocket = null
            videoSink?.let { camera.removeSink(it) }
            videoSink = null
        }
        // The control connection decides if the relay is reachable; retry shortly if still wanted.
        scope.launch { delay(2000); syncVideo() }
    }

    private fun closeVideo() {
        val ws = videoSocket
        videoSocket = null
        videoSink?.let { camera.removeSink(it) }
        videoSink = null
        ws?.close(1000, null)
    }

    private val configFile = File(dir, "relay.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()
    private val mcp = McpHandler(rt)
    private val channels = ConcurrentHashMap<String, LinkSession>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loopJob: Job? = null

    @Volatile private var url = ""
    @Volatile private var key = ""
    @Volatile private var socket: WebSocket? = null
    @Volatile private var lastInbound = 0L
    private var connects = 0L
    private var downSince = 0L
    private var lastOutageMs = 0L
    private var lastError = ""

    fun start() {
        load()
        publish(if (url.isEmpty()) "off" else "connecting")
        if (url.isNotEmpty()) loopJob = scope.launch { connectLoop() }
    }

    fun stop() {
        loopJob?.cancel()
        socket?.cancel()
        closeChannels()
        scope.cancel()
    }

    /** Returns an error message, or null when applied. */
    @Synchronized fun configure(newUrl: String, newKey: String): String? {
        val u = newUrl.trim().trimEnd('/')
        if (u.isNotEmpty()) validate(u, newKey)?.let { return it }
        url = u
        key = newKey
        save()
        loopJob?.cancel()
        socket?.cancel()
        publish(if (u.isEmpty()) "off" else "connecting")
        if (u.isNotEmpty()) loopJob = scope.launch { connectLoop() }
        rt.log.event("relay", "controller", "configured", if (u.isEmpty()) "relay turned off" else "relay set to ${host(u)}")
        return null
    }

    /**
     * Trades a one-time QR code for the vehicle key, then connects. Blocking: call off the main
     * thread. Returns an error message for the screen, or null when linked.
     */
    fun enroll(relayUrl: String, code: String): String? {
        val u = relayUrl.trim().trimEnd('/')
        validate(u, "x".repeat(16))?.let { return it }
        val body = buildJsonObject { put("code", code) }.toString().toRequestBody("application/json".toMediaType())
        val client = http.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
        return try {
            client.newCall(Request.Builder().url("$u/enroll/claim").post(body).build()).execute().use { res ->
                val text = res.body?.string() ?: ""
                when {
                    res.code == 429 -> "Too many tries. Wait a few minutes and show a new QR code."
                    res.code == 401 -> "This QR code has expired or was already used. Show a new one."
                    !res.isSuccessful -> "The server refused the request (${res.code})."
                    else -> {
                        val key = Json.parseToJsonElement(text).jsonObject["vehicleKey"]?.jsonPrimitive?.content
                            ?: return "The server sent an unexpected reply."
                        configure(u, key)
                    }
                }
            }
        } catch (e: java.io.IOException) {
            "Could not reach the server. Check this phone's internet connection."
        } catch (e: kotlinx.serialization.SerializationException) {
            "That address doesn't look like a SmartPhoneX server."
        }
    }

    /** Called when Android reports a new default network, so recovery doesn't wait out a backoff. */
    fun networkChanged() {
        socket?.cancel()
        wake.trySend(Unit)
    }

    fun drop() {
        socket?.cancel()
    }

    private fun validate(u: String, k: String): String? {
        if (k.length < 16) return "vehicle key is missing or too short"
        val uri = runCatching { URI(u) }.getOrNull() ?: return "not a valid URL"
        val h = uri.host ?: return "URL has no host"
        return when (uri.scheme) {
            "https" -> null
            // The vehicle key must never cross the internet unencrypted.
            "http" -> if (h == "localhost" || h == "127.0.0.1" || h == "10.0.2.2") null
            else "http is only allowed for local testing; use https"
            else -> "URL must start with https://"
        }
    }

    private fun host(u: String) = runCatching { URI(u).host }.getOrNull() ?: "?"

    private suspend fun connectLoop() {
        var backoffMs = 1000L
        while (currentCoroutineContext().isActive) {
            val blocked = rt.faults.linkBlockedUntil - System.currentTimeMillis()
            if (blocked > 0) delay(blocked)
            val connectedAt = runOnce()
            closeChannels()
            if (downSince == 0L) downSince = System.currentTimeMillis()
            publish("reconnecting")
            // A connection that lasted a while means the relay is fine: retry quickly.
            if (connectedAt != null && System.currentTimeMillis() - connectedAt > 10_000) backoffMs = 1000L
            val wait = backoffMs + (backoffMs * Math.random() * 0.3).toLong()
            withTimeoutOrNull(wait) { wake.receive() }
            backoffMs = (backoffMs * 2).coerceAtMost(if (lastError == "rejected") 60_000L else 15_000L)
        }
    }

    /** Holds one connection until it ends. Returns when it was established, or null if it never was. */
    private suspend fun runOnce(): Long? {
        val closed = CompletableDeferred<Unit>()
        val inbox = Channel<String>(Channel.UNLIMITED)
        var connectedAt: Long? = null
        val wsUrl = url.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/vehicle"
        val req = Request.Builder().url(wsUrl).header("Authorization", "Bearer $key").build()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connectedAt = System.currentTimeMillis()
                lastInbound = connectedAt!!
                connects++
                lastError = ""
                if (downSince != 0L) lastOutageMs = connectedAt!! - downSince
                downSince = 0L
                publish("connected")
                rt.log.event("relay", "controller", "connected", "relay connected, previous outage ${lastOutageMs} ms")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                lastInbound = System.currentTimeMillis()
                inbox.trySend(text)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                lastError = "closed $code"
                closed.complete(Unit)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lastError = if (response?.code == 401) "rejected" else (t.javaClass.simpleName)
                closed.complete(Unit)
            }
        })
        socket = ws
        try {
            coroutineScope {
                val worker = launch { for (m in inbox) handle(ws, m) }
                val watchdog = launch {
                    while (isActive) {
                        delay(2000)
                        if (connectedAt == null) continue
                        ws.send("""{"op":"hb"}""")
                        // TCP can take minutes to notice a dead cellular path; this takes seconds.
                        if (System.currentTimeMillis() - lastInbound > STALL_MS) {
                            lastError = "stalled"
                            ws.cancel()
                        }
                    }
                }
                closed.await()
                watchdog.cancel()
                worker.cancel()
            }
        } finally {
            ws.cancel()
            inbox.close()
        }
        rt.log.event("relay", "controller", "disconnected", lastError)
        return connectedAt
    }

    private suspend fun handle(ws: WebSocket, text: String) {
        val o = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
        val rpc = o["rpc"]
        if (rpc != null) {
            scope.launch { ws.send(answer(rpc, o)) }
            return
        }
        if ((o["op"] as? JsonPrimitive)?.content == "video") {
            videoViewers = (o["viewers"] as? JsonPrimitive)?.intOrNull ?: 0
            syncVideo()
            return
        }
        val ch = (o["ch"] as? JsonPrimitive)?.content ?: return
        when ((o["op"] as? JsonPrimitive)?.content) {
            "open" -> {
                val role = (o["role"] as? JsonPrimitive)?.content
                val s = LinkSession(rt, scope, { ws.send(envelope(ch, it)) }, { ws.queueSize() > MAX_QUEUE }, dropLink = { drop() }, canSeePrivate = role == "operator")
                channels.put(ch, s)?.stop()
                s.start()
            }
            "msg" -> (o["data"] as? JsonPrimitive)?.content?.let { channels[ch]?.onText(it) }
            "close" -> channels.remove(ch)?.stop()
        }
    }

    private fun answer(rpc: JsonElement, o: JsonObject): String {
        val kind = (o["kind"] as? JsonPrimitive)?.content
        val body = (o["body"] as? JsonPrimitive)?.content ?: ""
        val (status, out) = when (kind) {
            "mcp" -> mcp.handleText(body).let { it.status to it.body }
            "csv" -> rt.exportCsv(body)?.let { 200 to it } ?: (404 to null)
            // Review files go by parts of the path. Binary travels as base64 through the JSON channel.
            "review" -> reviewAnswer(body)
            "api_list" -> 200 to mcp.listToolsJson().toString()
            "api" -> runCatching {
                val j = Json.parseToJsonElement(body).jsonObject
                200 to mcp.callForApi(j["name"]!!.jsonPrimitive.content, j["args"] as? JsonObject ?: JsonObject(emptyMap())).toString()
            }.getOrElse { 400 to null }
            else -> 400 to null
        }
        return buildJsonObject { put("rpc", rpc); put("status", status); put("body", out) }.toString()
    }

    private fun reviewAnswer(path: String): Pair<Int, String?> {
        val p = path.split("/")
        fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
        return runCatching {
            when {
                path == "files" -> 200 to rt.ops.filesJson().toString()
                p.size == 3 && p[0] == "file" -> rt.ops.readFile(p[1], p[2])?.let { 200 to b64(it.first) } ?: (404 to null)
                p.size == 2 && p[0] == "frames" -> 200 to rt.ops.framesJson(p[1]).toString()
                p.size == 3 && p[0] == "frame" -> rt.ops.readFrame(p[1], p[2].toLongOrNull() ?: 0)?.let { 200 to b64(it) } ?: (404 to null)
                else -> 404 to null
            }
        }.getOrElse { 500 to null }
    }

    private fun envelope(ch: String, data: String) = buildJsonObject { put("ch", ch); put("op", "msg"); put("data", data) }.toString()

    private fun closeChannels() {
        channels.values.forEach { it.stop() }
        channels.clear()
        videoViewers = 0
        syncVideo()
    }

    private fun publish(state: String) {
        rt.relay = buildJsonObject {
            put("state", state)
            put("host", if (url.isEmpty()) null else host(url))
            put("connects", connects)
            put("lastOutageMs", lastOutageMs)
            put("lastError", lastError)
        }
    }

    private fun save() {
        configFile.writeText(buildJsonObject { put("url", url); put("key", key) }.toString())
    }

    private fun load() {
        if (!configFile.exists()) return
        runCatching {
            val o = Json.parseToJsonElement(configFile.readText()).jsonObject
            url = o["url"]!!.jsonPrimitive.content
            key = o["key"]!!.jsonPrimitive.content
        }
    }

    private companion object {
        const val STALL_MS = 8000L
        const val MAX_QUEUE = 32 * 1024L
    }
}
