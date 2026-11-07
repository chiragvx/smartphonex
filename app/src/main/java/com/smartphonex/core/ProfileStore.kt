package com.smartphonex.core

import kotlinx.serialization.json.*
import java.io.File

/** Vehicle profiles persisted as JSON, so saved settings survive an app restart. */
class ProfileStore(private val file: File?) {
    private val profiles = linkedMapOf<String, VehicleProfile>()
    /** Commands run while holding the control loop's lock, so slow flash storage must not be waited on. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "profile-writer").apply { isDaemon = true } }
    @Volatile var activeId: String
        private set

    init {
        val defaults = listOf(
            VehicleProfile("sim-buggy", "Sim Buggy"),
            VehicleProfile("sim-gentle", "Sim Gentle", maxThrottle = 0.4, maxSpeedMps = 1.5, kp = 0.25, ki = 0.15),
        )
        (defaults + Presets.all).forEach { profiles[it.id] = it }
        // A real device starts on the output preview. The simulator remains a choice in the list.
        activeId = if (file != null) "car" else defaults.first().id
        load()
    }

    val active: VehicleProfile @Synchronized get() = profiles.getValue(activeId)
    @Synchronized fun list(): List<VehicleProfile> = profiles.values.toList()

    @Synchronized fun select(id: String): Boolean {
        if (id !in profiles) return false
        activeId = id; save(); return true
    }

    /** Adds or replaces a device-backed entry. It's rebuilt from the device file at each start. */
    @Synchronized fun addProfile(p: VehicleProfile) { profiles[p.id] = p }

    @Synchronized fun updateActive(p: VehicleProfile) {
        profiles[p.id] = p; save()
    }

    private fun save() {
        val f = file ?: return
        val json = buildJsonObject {
            put("active", activeId)
            put("schema", 2)
            putJsonArray("profiles") { profiles.values.forEach { add(Codec.profile(it)) } }
        }
        val text = json.toString()
        writer.execute {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** Waits for pending saves to reach the disk. */
    fun flush() { writer.submit {}.get() }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val root = Json.parseToJsonElement(f.readText()).jsonObject
            root["profiles"]!!.jsonArray.forEach { val p = Codec.parseProfile(it.jsonObject); profiles[p.id] = p }
            // Files older than schema 2 were saved when only the simulator existed: keep the new default.
            if (root["schema"] != null) root["active"]?.jsonPrimitive?.content?.takeIf { it in profiles }?.let { activeId = it }
        } catch (_: Exception) {
            // A corrupt settings file must never stop the vehicle controller from starting.
        }
    }
}
