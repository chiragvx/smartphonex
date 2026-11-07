package com.smartphonex.core

import kotlinx.serialization.json.*
import java.io.File

/** Whatever the device has. Nothing assumes a particular sensor exists. */
data class SensorInfo(
    val id: String,
    val name: String,
    val group: String,
    val unit: String,
    val axes: List<String>,
    val vendor: String = "",
    /** Sensitive data: hidden from viewers and AI unless granted. */
    val isPrivate: Boolean = false,
    /** Set when Android requires a permission this app doesn't request, or the user declined. */
    val unavailableReason: String? = null,
    val decimals: Int = 3,
)

data class SensorConfig(val enabled: Boolean, val rateHz: Double, val priority: Int)

data class CameraConfig(
    val enabled: Boolean = false,
    val cameraId: String = "",
    val width: Int = 320,
    val height: Int = 240,
    val fps: Double = 5.0,
    val quality: Int = 50,
    val priority: Int = Priority.LOW,
)

object Priority {
    const val HIGH = 1
    const val NORMAL = 2
    const val LOW = 3
}

data class SensorPayload(val full: String, val public: String)

/**
 * Holds the latest reading of every sensor and decides what gets sent. With a data budget set,
 * high priority goes first and low priority is dropped when the budget runs out. Commands and basic
 * telemetry don't pass through here, so they can never be starved by sensors or video.
 */
class SensorHub(private val file: File?, private val clock: () -> Long = System::currentTimeMillis) {
    private class Slot(val info: SensorInfo, var cfg: SensorConfig) {
        var values: DoubleArray? = null
        var fresh = false
        var lastSent = 0L
        var sentCount = 0L
        var dropped = 0L
        var updates = 0L
        var lastStatAt = 0L
        var lastStatSent = 0L
        var lastStatUpdates = 0L
        var sentHz = 0.0
        var inputHz = 0.0
    }

    private val slots = linkedMapOf<String, Slot>()
    private val saved = HashMap<String, SensorConfig>()
    private var bucket = 0.0
    private var lastRefill = 0L
    private var bytesSentWindow = 0L
    private var videoBytesWindow = 0L
    private var windowStart = 0L
    private val csv = ArrayDeque<String>()

    @Volatile var budgetKbps = 0
        private set
    @Volatile var camera = CameraConfig()
        private set
    /** Whether AI agents may read camera and location. Off unless an operator grants it. */
    @Volatile var aiPrivateAccess = false
        private set
    @Volatile var version = 0L
        private set
    @Volatile var sensorKbps = 0.0
        private set
    @Volatile var videoKbps = 0.0
        private set
    /** Called when the hardware should be reconfigured. */
    @Volatile var onConfigChanged: () -> Unit = {}

    init { load() }

    @Synchronized fun register(info: SensorInfo, default: SensorConfig) {
        val cfg = if (info.unavailableReason != null) default.copy(enabled = false) else saved[info.id] ?: default
        slots[info.id] = Slot(info, cfg)
        version++
    }

    @Synchronized fun valueOf(id: String, axis: Int): Double? = slots[id]?.values?.getOrNull(axis)

    @Synchronized fun catalog(): List<Pair<SensorInfo, SensorConfig>> = slots.values.map { it.info to it.cfg }
    @Synchronized fun config(id: String): SensorConfig? = slots[id]?.cfg

    fun update(id: String, values: FloatArray) = update(id, DoubleArray(values.size) { values[it].toDouble() })

    @Synchronized fun update(id: String, values: DoubleArray) {
        val s = slots[id] ?: return
        s.values = values
        s.fresh = true
        s.updates++
    }

    /** Returns an error message, or null when applied. */
    @Synchronized fun configure(id: String, enabled: Boolean?, rateHz: Double?, priority: Int?): String? {
        val s = slots[id] ?: return "unknown sensor"
        if (enabled == true && s.info.unavailableReason != null) return s.info.unavailableReason
        if (rateHz != null && rateHz !in 0.2..50.0) return "rate must be 0.2 to 50 Hz"
        if (priority != null && priority !in 1..3) return "priority must be 1 to 3"
        s.cfg = s.cfg.copy(enabled = enabled ?: s.cfg.enabled, rateHz = rateHz ?: s.cfg.rateHz, priority = priority ?: s.cfg.priority)
        changed()
        return null
    }

    @Synchronized fun setBudget(kbps: Int): String? {
        if (kbps !in 0..100_000) return "budget must be 0 (unlimited) to 100000 kbps"
        budgetKbps = kbps
        bucket = 0.0
        changed()
        return null
    }

    @Synchronized fun setCamera(c: CameraConfig): String? {
        if (c.width !in 80..4096 || c.height !in 60..4096) return "unsupported resolution"
        if (c.fps !in 0.2..30.0) return "frame rate must be 0.2 to 30"
        if (c.quality !in 5..95) return "quality must be 5 to 95"
        if (c.priority !in 1..3) return "priority must be 1 to 3"
        camera = c
        changed()
        return null
    }

    @Synchronized fun setAiPrivateAccess(allowed: Boolean) {
        aiPrivateAccess = allowed
        changed()
    }

    private fun changed() {
        version++
        save()
        onConfigChanged()
    }

    private fun refill(now: Long) {
        val rate = budgetKbps * 125.0 // Bytes a second.
        if (lastRefill != 0L) bucket = (bucket + rate * (now - lastRefill) / 1000.0).coerceAtMost(rate)
        lastRefill = now
    }

    /** One scheduling round. Returns null when nothing is due. */
    @Synchronized fun tick(): SensorPayload? {
        val now = clock()
        refill(now)
        rollStats(now)
        val due = slots.values
            .filter { it.cfg.enabled && it.fresh && it.values != null && now - it.lastSent >= 1000.0 / it.cfg.rateHz }
            .sortedBy { it.cfg.priority }
        if (due.isEmpty()) return null
        val full = StringBuilder()
        val public = StringBuilder()
        var dropped = 0
        for (s in due) {
            val entry = "\"${s.info.id}\":[" + s.values!!.joinToString(",") { fmt(it, s.info.decimals) } + "]"
            if (budgetKbps > 0) {
                if (bucket < entry.length) {
                    // Over budget: the reading stays fresh so it can be sent if room appears.
                    s.dropped++
                    dropped++
                    continue
                }
                bucket -= entry.length
            }
            if (full.isNotEmpty()) full.append(',')
            full.append(entry)
            if (!s.info.isPrivate) {
                if (public.isNotEmpty()) public.append(',')
                public.append(entry)
            }
            s.fresh = false
            s.lastSent = now
            s.sentCount++
            bytesSentWindow += entry.length
            if (csv.size >= CSV_ROWS) csv.removeFirst()
            csv.addLast("$now,${s.info.id},${s.values!!.joinToString(";") { fmt(it, s.info.decimals) }}")
        }
        if (full.isEmpty()) return null
        return SensorPayload(wrap(now, full, dropped), wrap(now, public, dropped))
    }

    private fun wrap(t: Long, body: CharSequence, dropped: Int) = "{\"type\":\"sensors\",\"t\":$t,\"drop\":$dropped,\"d\":{$body}}"

    /**
     * May a video frame of this size go now? High-priority sensors keep what they need first, so
     * video only uses what's left, unless video is itself set to high priority.
     */
    @Synchronized fun takeVideo(bytes: Int, priority: Int): Boolean {
        val now = clock()
        refill(now)
        if (budgetKbps > 0) {
            val perSecond = budgetKbps * 125.0
            val reserve = if (priority == Priority.HIGH) 0.0 else perSecond * 0.25
            // A frame larger than one second's budget could never pass, so it's skipped, not queued.
            if (bucket - reserve < bytes) return false
            bucket -= bytes
        }
        videoBytesWindow += bytes
        return true
    }

    private fun rollStats(now: Long) {
        if (windowStart == 0L) windowStart = now
        val elapsed = now - windowStart
        if (elapsed < 1000) return
        sensorKbps = bytesSentWindow * 8.0 / elapsed
        videoKbps = videoBytesWindow * 8.0 / elapsed
        bytesSentWindow = 0
        videoBytesWindow = 0
        windowStart = now
        for (s in slots.values) {
            s.sentHz = (s.sentCount - s.lastStatSent) * 1000.0 / elapsed
            s.inputHz = (s.updates - s.lastStatUpdates) * 1000.0 / elapsed
            s.lastStatSent = s.sentCount
            s.lastStatUpdates = s.updates
        }
    }

    private fun fmt(v: Double, d: Int): String {
        if (!v.isFinite()) return "null"
        val p = when (d) { 0 -> 1.0; 1 -> 10.0; 2 -> 100.0; 3 -> 1000.0; 7 -> 1e7; else -> 1000.0 }
        val r = Math.round(v * p) / p
        return if (r == r.toLong().toDouble() && d == 0) r.toLong().toString() else r.toString()
    }

    /** Latest readings for tools and agents. Private sensors are left out unless allowed. */
    @Synchronized fun readings(includePrivate: Boolean) = buildJsonObject {
        for (s in slots.values) {
            if (!s.cfg.enabled || (s.info.isPrivate && !includePrivate)) continue
            val v = s.values ?: continue
            putJsonObject(s.info.id) {
                put("name", s.info.name)
                put("unit", s.info.unit)
                putJsonArray("axes") { s.info.axes.forEach { add(it) } }
                putJsonArray("values") { v.forEach { add(if (it.isFinite()) JsonPrimitive(it) else JsonNull) } }
            }
        }
    }

    @Synchronized fun stats(includePrivate: Boolean = true) = buildJsonObject {
        put("type", "sensorStats")
        put("sensorKbps", sensorKbps)
        put("videoKbps", videoKbps)
        putJsonObject("s") {
            for (s in slots.values) {
                if (s.info.isPrivate && !includePrivate) continue
                put(s.info.id, buildJsonArray { add(Math.round(s.sentHz * 10) / 10.0); add(Math.round(s.inputHz * 10) / 10.0); add(s.dropped) })
            }
        }
    }

    @Synchronized fun catalogJson(includePrivate: Boolean) = buildJsonArray {
        for (s in slots.values) {
            if (s.info.isPrivate && !includePrivate) continue
            addJsonObject {
                put("id", s.info.id); put("name", s.info.name); put("group", s.info.group); put("unit", s.info.unit)
                put("vendor", s.info.vendor); put("private", s.info.isPrivate); put("unavailable", s.info.unavailableReason)
                putJsonArray("axes") { s.info.axes.forEach { add(it) } }
                put("enabled", s.cfg.enabled); put("rateHz", s.cfg.rateHz); put("priority", s.cfg.priority)
            }
        }
    }

    fun cameraJson() = buildJsonObject {
        val c = camera
        put("enabled", c.enabled); put("cameraId", c.cameraId); put("width", c.width); put("height", c.height)
        put("fps", c.fps); put("quality", c.quality); put("priority", c.priority)
    }

    @Synchronized fun csv(): String = "t,sensor,values\n" + csv.joinToString("\n")

    private fun save() {
        val f = file ?: return
        val json = buildJsonObject {
            put("budgetKbps", budgetKbps)
            put("aiPrivateAccess", aiPrivateAccess)
            put("camera", cameraJson())
            putJsonObject("sensors") {
                for (s in slots.values) putJsonArray(s.info.id) { add(s.cfg.enabled); add(s.cfg.rateHz); add(s.cfg.priority) }
            }
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val o = Json.parseToJsonElement(f.readText()).jsonObject
            budgetKbps = o["budgetKbps"]?.jsonPrimitive?.intOrNull ?: 0
            // Camera always starts off: it must be turned on again on purpose after every restart.
            o["camera"]?.jsonObject?.let { c ->
                camera = CameraConfig(
                    false, c["cameraId"]?.jsonPrimitive?.content ?: "",
                    c["width"]?.jsonPrimitive?.intOrNull ?: 320, c["height"]?.jsonPrimitive?.intOrNull ?: 240,
                    c["fps"]?.jsonPrimitive?.doubleOrNull ?: 5.0, c["quality"]?.jsonPrimitive?.intOrNull ?: 50,
                    c["priority"]?.jsonPrimitive?.intOrNull ?: Priority.LOW,
                )
            }
            aiPrivateAccess = o["aiPrivateAccess"]?.jsonPrimitive?.booleanOrNull ?: false
            o["sensors"]?.jsonObject?.forEach { (id, v) ->
                val a = v.jsonArray
                saved[id] = SensorConfig(a[0].jsonPrimitive.boolean, a[1].jsonPrimitive.double, a[2].jsonPrimitive.int)
            }
        }
    }

    private companion object { const val CSV_ROWS = 20_000 }
}

/** Implemented by the Android layer. Lets tools get a frame without knowing about Camera2. */
interface CameraAccess {
    fun catalog(): JsonArray
    fun snapshot(timeoutMs: Long): ByteArray?
    fun status(): String
    fun refresh() {}
}
