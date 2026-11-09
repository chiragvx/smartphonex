package com.smartphonex.core

import java.io.File

/**
 * Map tiles kept on the phone, so places that have been looked at still have a map in the field with no internet:
 * the ground station asks the phone for each tile, and the phone fetches it once from its source and keeps it.
 *
 * Only tiles someone viewed are kept. Bulk-downloading areas isn't offered: the tile providers' terms (OpenStreetMap's
 * tile usage policy, Esri's) don't allow it. Kept tiles are refreshed after [FRESH_DAYS] when there's internet, and
 * served however old when there isn't. The oldest-used go first past [maxBytes].
 */
class TileCache(private val dir: File, private val maxBytes: Long = 200L * 1024 * 1024, private val fetch: (String) -> ByteArray?) {
    /** Where each layer's tiles come from: the same sources as the ground station's map. */
    val sources = mapOf(
        "satellite" to { z: Int, x: Int, y: Int -> "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x" },
        "street" to { z: Int, x: Int, y: Int -> "https://tile.openstreetmap.org/$z/$x/$y.png" },
    )
    private var bytes = -1L

    /** The tile, from the phone if it has it, else fetched and kept. Null for a bad request, or when it can't be had. */
    fun get(layer: String, z: Int, x: Int, y: Int): ByteArray? {
        val url = sources[layer] ?: return null
        if (z !in 0..19 || x < 0 || y < 0 || x >= (1 shl z) || y >= (1 shl z)) return null
        val f = File(dir, "$layer/$z/$x/$y")
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < FRESH_DAYS * 86_400_000L
        if (fresh) return runCatching { f.readBytes() }.getOrNull()?.also { touch(f) }
        val got = runCatching { fetch(url(z, x, y)) }.getOrNull()
        if (got != null && got.isNotEmpty()) { store(f, got); return got }
        // Offline, or the source said no: whatever is kept, however old.
        return if (f.exists()) runCatching { f.readBytes() }.getOrNull() else null
    }

    @Synchronized fun sizeBytes(): Long {
        if (bytes < 0) bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return bytes
    }
    fun count() = dir.walkTopDown().count { it.isFile }
    @Synchronized fun clear() { dir.deleteRecursively(); bytes = 0 }

    // A read moves a tile to the back of the queue for eviction, without changing how fresh it is.
    private val used = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun touch(f: File) { used[f.path] = System.currentTimeMillis() }

    @Synchronized private fun store(f: File, b: ByteArray) {
        val old = if (f.exists()) f.length() else 0L
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(b)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        bytes = sizeBytes() + b.size - old
        touch(f)
        if (bytes > maxBytes) evict()
    }

    /** Down to 90% of the limit, least recently used first. */
    private fun evict() {
        val files = dir.walkTopDown().filter { it.isFile }.sortedBy { used[it.path] ?: it.lastModified() }.toList()
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes * 0.9) break
            total -= f.length(); used.remove(f.path); f.delete()
        }
        bytes = total
    }

    companion object { const val FRESH_DAYS = 30 }
}
