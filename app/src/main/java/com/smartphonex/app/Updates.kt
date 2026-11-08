package com.smartphonex.app

import com.smartphonex.core.Ops
import com.smartphonex.core.Versions
import com.smartphonex.core.VehicleRuntime
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Tells people when a newer release exists. It never downloads or installs anything: a person follows the link.
 * Off unless the build names a feed (spx.updateUrl in gradle.properties), so builds from other stores can leave it out.
 */
class Updates(private val rt: VehicleRuntime, private val feed: String = BuildConfig.UPDATE_URL) {
    private val file = File(rt.dir, "diagnostics/update.json")
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private var timer: ScheduledExecutorService? = null
    @Volatile private var state: JsonObject = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    val enabled get() = feed.isNotBlank()

    /** Plain HTTP only for a feed on this phone, which is how it's tested. */
    private fun allowed(url: String) = url.startsWith("https://") || url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")

    fun start() {
        if (!enabled) return
        timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "updates").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ runCatching { check() } }, 30, 24 * 3600, TimeUnit.SECONDS)
        }
    }

    fun stop() { timer?.shutdownNow(); timer = null }

    /** Asks the feed now. Blocking: call off the main thread. */
    fun check(): JsonObject {
        if (!enabled) return json()
        val out = buildJsonObject {
            put("checked", System.currentTimeMillis())
            if (!allowed(feed)) { put("error", "the update address must use https"); return@buildJsonObject }
            val body = runCatching {
                http.newCall(Request.Builder().url(feed).header("Accept", "application/vnd.github+json").header("User-Agent", "SmartPhoneX").build()).execute().use { r ->
                    if (!r.isSuccessful) error("the feed answered ${r.code}")
                    r.body?.string()?.take(2_000_000) ?: error("empty reply")
                }
            }
            body.exceptionOrNull()?.let { put("error", it.message ?: "couldn't reach it"); return@buildJsonObject }
            val rel = Versions.parseFeed(body.getOrThrow())
            if (rel == null) { put("error", "the feed didn't say which version is latest"); return@buildJsonObject }
            put("latest", rel.version); put("page", rel.page); rel.apk?.let { put("apk", it) }
            put("newer", (Versions.compare(rel.version, BuildConfig.VERSION_NAME) ?: 0) > 0)
        }
        state = out
        runCatching { file.parentFile?.mkdirs(); file.writeText(out.toString()) }
        if (out["newer"]?.jsonPrimitive?.booleanOrNull == true) rt.log.event("app", "update", "available", "version ${out["latest"]?.jsonPrimitive?.content}")
        return json()
    }

    fun json() = buildJsonObject {
        put("enabled", enabled); put("current", BuildConfig.VERSION_NAME)
        state.forEach { (k, v) -> put(k, v) }
    }

    fun check(row: Boolean = true): Ops.Check? {
        if (!enabled || !row) return null
        val s = state
        val checked = s["checked"]?.jsonPrimitive?.longOrNull
        return when {
            s["newer"]?.jsonPrimitive?.booleanOrNull == true -> Ops.Check("update", "App update", "warn", "Version ${s["latest"]?.jsonPrimitive?.content} is out. This is ${BuildConfig.VERSION_NAME}",
                "Get it from ${s["page"]?.jsonPrimitive?.content}. Back up first in Setup")
            s["error"] != null -> Ops.Check("update", "App update", "off", "Couldn't check: ${s["error"]?.jsonPrimitive?.content}")
            checked != null -> Ops.Check("update", "App update", "ok", "Up to date (${BuildConfig.VERSION_NAME})")
            else -> Ops.Check("update", "App update", "off", "Not checked yet")
        }
    }
}
