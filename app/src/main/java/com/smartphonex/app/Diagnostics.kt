package com.smartphonex.app

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import com.smartphonex.core.Ops
import com.smartphonex.core.VehicleRuntime
import kotlinx.serialization.json.*
import java.io.File

/**
 * Why the app stopped last time, crashes, and a report of what this phone can do. Everything stays on the phone:
 * a person downloads a report and decides whether to share it. Reports hold the phone's model, never its location or IDs.
 */
class Diagnostics(private val ctx: Context, private val rt: VehicleRuntime) {
    private val dir = File(rt.dir, "diagnostics").apply { mkdirs() }
    private val exitsFile = File(dir, "exits.json")

    companion object {
        private const val KEEP = 30
        private val NAME = Regex("^crash-\\d+\\.txt$")

        /** Writes a crash to disk before the app dies, then lets Android handle it as usual. Process-wide. */
        fun installCrashHandler(filesDir: File) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is Recorder) return
            Thread.setDefaultUncaughtExceptionHandler(Recorder(File(filesDir, "diagnostics"), previous))
        }

        private class Recorder(val dir: File, val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
            override fun uncaughtException(t: Thread, e: Throwable) {
                runCatching {
                    dir.mkdirs()
                    File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(
                        "SmartPhoneX ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n" +
                            "Thread: ${t.name}\n\n" + e.stackTraceToString().take(60_000))
                    dir.listFiles { f -> NAME.matches(f.name) }?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
                }
                next?.uncaughtException(t, e)
            }
        }

        /** Android's reason codes for a process ending, in plain words. Numbers, so older Android versions can read them. */
        fun reasonText(code: Int): Pair<String, Boolean> = when (code) {
            1 -> "closed itself" to false
            2 -> "stopped by a signal, usually the system" to true
            3 -> "Android needed the memory" to true
            4 -> "crashed" to true
            5 -> "crashed in native code" to true
            6 -> "stopped responding" to true
            7 -> "failed to start" to true
            8 -> "a permission changed" to false
            9 -> "used too much battery or CPU in the background" to true
            10 -> "stopped by the user" to false
            11 -> "stopped by the user" to false
            12 -> "something it depends on stopped" to true
            13 -> "stopped by the system" to true
            14 -> "frozen by the system" to true
            15 -> "app settings changed" to false
            16 -> "updated" to false
            else -> "unknown reason $code" to true
        }
    }

    /** Past exits, newest first, from our own record. */
    fun exits(): List<JsonObject> = runCatching { Json.parseToJsonElement(exitsFile.readText()).jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())

    /** Reads Android's record of how the app ended before, keeps the new ones, and logs anything unexpected. Android 11+. */
    fun recordExits() {
        if (Build.VERSION.SDK_INT < 30) return
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return
        val old = exits()
        val seen = old.mapNotNull { it["t"]?.jsonPrimitive?.longOrNull }.toSet()
        val fresh = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10) }.getOrDefault(emptyList())
            .filter { it.timestamp !in seen }
            .map { x ->
                val (text, unexpected) = reasonText(x.reason)
                buildJsonObject {
                    put("t", x.timestamp); put("reason", x.reason); put("text", text); put("unexpected", unexpected)
                    x.description?.takeIf { it.isNotBlank() }?.let { put("detail", it.take(300)) }
                    put("importance", x.importance); put("memoryKb", x.pss)
                }
            }
        if (fresh.isEmpty()) return
        val all = (fresh + old).sortedByDescending { it["t"]?.jsonPrimitive?.longOrNull ?: 0 }.take(KEEP)
        runCatching { exitsFile.writeText(JsonArray(all).toString()) }
        fresh.firstOrNull()?.let { last ->
            val unexpected = last["unexpected"]?.jsonPrimitive?.booleanOrNull == true
            rt.log.event("app", "restart", if (unexpected) "problem" else "ok", "last time the app ${last["text"]?.jsonPrimitive?.content}")
        }
    }

    fun crashes(): List<File> = dir.listFiles { f -> NAME.matches(f.name) }?.sortedByDescending { it.name } ?: emptyList()

    fun crashText(name: String): String? = if (NAME.matches(name)) File(dir, name).takeIf { it.isFile }?.readText() else null

    fun json() = buildJsonObject {
        putJsonArray("exits") { exits().forEach { add(it) } }
        putJsonArray("crashes") {
            crashes().forEach { f ->
                addJsonObject {
                    put("name", f.name); put("t", f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: 0)
                    put("summary", f.readLines().drop(3).firstOrNull()?.take(200) ?: "")
                }
            }
        }
        put("supported", Build.VERSION.SDK_INT >= 30)
    }

    /** One row for Connected: how the app ended last time, and whether it has crashed recently. */
    fun check(): Ops.Check {
        val day = System.currentTimeMillis() - 86_400_000
        val crashes = crashes().count { (it.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: 0) > day }
        val kills = exits().count { (it["t"]?.jsonPrimitive?.longOrNull ?: 0) > day && it["unexpected"]?.jsonPrimitive?.booleanOrNull == true }
        val last = exits().firstOrNull()?.get("text")?.jsonPrimitive?.content
        return when {
            crashes > 0 -> Ops.Check("app", "App health", "bad", "$crashes crash${if (crashes == 1) "" else "es"} in the last day", "Download the report in Setup, App health, and share it in an issue")
            kills > 0 -> Ops.Check("app", "App health", "warn", "Stopped unexpectedly $kills time${if (kills == 1) "" else "s"} in the last day. Last: $last",
                "Let it run in the background: turn off battery saving for SmartPhoneX in Android's settings")
            else -> Ops.Check("app", "App health", "ok", last?.let { "Last stop: $it" } ?: "No problems recorded")
        }
    }

    /** What this phone can do, for the public compatibility table. No location, serial numbers or account details. */
    fun phoneReport(): JsonObject {
        val pm = ctx.packageManager
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val sm = ctx.getSystemService(SensorManager::class.java)
        val t = rt.controller.latest()
        val slow = runCatching { rt.fc.slowJson(false) }.getOrNull()
        val exits = exits()
        return buildJsonObject {
            put("format", "smartphonex.phone/1")
            put("app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            put("created", System.currentTimeMillis())
            putJsonObject("phone") {
                put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL); put("device", Build.DEVICE)
                put("android", Build.VERSION.RELEASE); put("sdk", Build.VERSION.SDK_INT)
                putJsonArray("abis") { Build.SUPPORTED_ABIS.forEach { add(it) } }
                put("ramMb", mem.totalMem / 1_048_576); put("cores", Runtime.getRuntime().availableProcessors())
                put("heapMb", am?.memoryClass ?: 0)
            }
            putJsonObject("features") {
                put("usbHost", pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST))
                put("gps", pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS))
                put("camera", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
                put("bluetoothLe", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
                put("battery", rt.fc.batteryText() != "No battery: mains power")
            }
            putJsonArray("sensors") {
                sm?.getSensorList(Sensor.TYPE_ALL)?.filter { !it.isWakeUpSensor }?.forEach { s ->
                    addJsonObject {
                        put("type", s.stringType.removePrefix("android.sensor.")); put("name", s.name); put("vendor", s.vendor)
                        put("maxHz", if (s.minDelay > 0) Math.round(1_000_000.0 / s.minDelay) else 0)
                    }
                }
            }
            putJsonObject("gps") {
                rt.fc.gnssSignal?.let { put("signalDbHz", Math.round(it * 10) / 10.0) }
                rt.fc.gpsNow?.let { put("accuracyM", Math.round(it.accuracyM * 10) / 10.0); put("satellites", it.satellites) }
                put("note", "Indoors or outdoors changes these a lot. Say which when sharing.")
            }
            putJsonObject("timing") {
                put("loopHz", Math.round(t.loop.hz * 10) / 10.0); put("stepAvgMs", Math.round(t.loop.dtAvgMs * 100) / 100.0)
                put("stepMaxMs", Math.round(t.loop.dtMaxMs * 100) / 100.0); put("overruns", t.loop.overruns)
                slow?.get("rates")?.let { put("sensorRates", it) }
                slow?.get("timing")?.let { put("flight", it) }
            }
            putJsonObject("background") {
                put("recorded", Build.VERSION.SDK_INT >= 30)
                put("unexpectedStops", exits.count { it["unexpected"]?.jsonPrimitive?.booleanOrNull == true })
                put("of", exits.size)
                putJsonObject("reasons") { exits.groupBy { it["text"]?.jsonPrimitive?.content ?: "?" }.forEach { (k, v) -> put(k, v.size) } }
            }
            put("crashes", crashes().size)
            putJsonObject("realtime") {
                Priority.status.forEach { (k, v) -> put(k, v) }
                put("batteryExempt", Priority.ignoringBatteryLimits(ctx))
                // Android returns NaN when it can't say, or when asked more than once in a while. JSON can't hold NaN.
                if (Build.VERSION.SDK_INT >= 30) runCatching { ctx.getSystemService(android.os.PowerManager::class.java).getThermalHeadroom(10) }.getOrNull()
                    ?.takeIf { it.isFinite() }?.let { put("thermalHeadroom", Math.round(it * 100) / 100.0) }
            }
        }
    }
}
