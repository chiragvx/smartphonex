package com.smartphonex.core

import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The app's saved files as one versioned whole: a data version, ordered migrations, backup and restore.
 *
 * [prepare] runs once at startup, before anything reads a file. It applies a staged restore, then brings
 * the files up to [CURRENT]. Before each migration the small files are copied aside, so a failed one rolls back.
 */
object AppData {
    /** Version 1 is the layout of app 0.6 (build 10). Raise it with each migration added below. */
    const val CURRENT = 1
    const val FORMAT = "smartphonex.backup/1"

    private const val VERSION_FILE = "data-version.json"
    private const val MANIFEST = "smartphonex-backup.json"
    private const val STAGED = ".restore"
    private const val PREVIOUS = ".pre-restore"
    private const val MIGRATE_BACKUP = ".migrate-backup"

    /** Never leave the phone in a backup, and never get overwritten by one: secrets, identity, our own bookkeeping, and the map tile cache. */
    val NEVER = setOf("token.txt", "llm-key", "relay.json", "profileInstalled", "tiles", VERSION_FILE, STAGED, "$STAGED.tmp", PREVIOUS, MIGRATE_BACKUP)
    /** Large and optional. Left out unless asked for. */
    val MEDIA = setOf("photos", "video")
    private val NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9_.\\-]{0,127}$")
    /** Unpacked size limit. Unpacking streams to disk, so memory doesn't limit it. */
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 50000

    /** A migration takes the files directory from version (key) to key + 1. None yet: version 1 is the first. */
    val migrations: Map<Int, (File) -> Unit> = mapOf()

    data class Report(val from: Int, val to: Int, val restored: Boolean, val problem: String?)

    fun version(dir: File): Int? = runCatching {
        (Json.parseToJsonElement(File(dir, VERSION_FILE).readText()) as JsonObject)["version"]!!.jsonPrimitive.int
    }.getOrNull()

    private fun writeVersion(dir: File, v: Int, app: String) {
        File(dir, VERSION_FILE).writeText(buildJsonObject { put("version", v); put("app", app) }.toString())
    }

    /** Startup step. Never throws: a problem is reported, and the files are left as they were. */
    fun prepare(dir: File, app: String, steps: Map<Int, (File) -> Unit> = migrations, current: Int = CURRENT): Report {
        dir.mkdirs()
        var restored = false
        val staged = File(dir, STAGED)
        if (staged.isDirectory) {
            val p = runCatching { applyStaged(dir, staged) }.exceptionOrNull()
            if (p != null) return Report(version(dir) ?: 1, version(dir) ?: 1, false, "restore failed: ${p.message}")
            restored = true
        }
        // No version file: either a fresh install or data from 0.6, which had none. Both are version 1.
        val from = version(dir) ?: 1
        if (from > current) return Report(from, from, restored, "these files are from a newer app (data version $from). Update the app; nothing was changed")
        var v = from
        while (v < current) {
            val step = steps[v] ?: return Report(from, v, restored, "no migration from data version $v")
            val snap = File(dir, "$MIGRATE_BACKUP/v$v")
            val failed = runCatching {
                snap.deleteRecursively()
                copySmall(dir, snap)
                step(dir)
            }.exceptionOrNull()
            if (failed != null) {
                runCatching { restoreSmall(snap, dir) }
                writeVersion(dir, v, app)
                return Report(from, v, restored, "migration from data version $v failed and was undone: ${failed.message}")
            }
            v++
            writeVersion(dir, v, app)
        }
        if (version(dir) == null || restored) writeVersion(dir, v, app)
        return Report(from, v, restored, null)
    }

    /** Settings, packs and logs: everything except media, secrets and bookkeeping. */
    private fun copySmall(dir: File, to: File) {
        to.mkdirs()
        dir.listFiles()?.filter { it.name !in NEVER && it.name !in MEDIA }?.forEach { it.copyRecursively(File(to, it.name), overwrite = true) }
    }

    private fun restoreSmall(snap: File, dir: File) {
        dir.listFiles()?.filter { it.name !in NEVER && it.name !in MEDIA }?.forEach { it.deleteRecursively() }
        snap.listFiles()?.forEach { it.copyRecursively(File(dir, it.name), overwrite = true) }
    }

    /** Writes a backup zip. Media only when asked, because a few runs of saved frames can be hundreds of MB. */
    fun backup(dir: File, out: OutputStream, app: String, media: Boolean, now: Long = System.currentTimeMillis()) {
        ZipOutputStream(out).use { zip ->
            val manifest = buildJsonObject {
                put("format", FORMAT); put("dataVersion", version(dir) ?: 1); put("app", app); put("created", now); put("media", media)
            }
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            val top = dir.listFiles()?.filter { it.name !in NEVER && (media || it.name !in MEDIA) && NAME.matches(it.name) }?.sortedBy { it.name } ?: emptyList()
            for (f in top) f.walkTopDown().filter { it.isFile && it.relativeTo(dir).invariantSeparatorsPath.split('/').all { p -> NAME.matches(p) } }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(dir).invariantSeparatorsPath).apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Only plain relative names under the files directory, and never the ones that stay on the phone. */
    fun pathProblem(name: String): String? {
        if (name.isEmpty() || name.length > 512) return "bad name"
        if (name.startsWith("/") || name.contains('\\') || name.contains(':')) return "not a relative path"
        val parts = name.split('/')
        if (parts.any { !NAME.matches(it) }) return "bad path"
        if (parts[0] in NEVER) return "kept on the phone"
        return null
    }

    /**
     * Checks a backup while unpacking it beside the live files, ready for the next start. Nothing the app is using is
     * written. Returns why it was refused, or null once it's staged.
     */
    fun stage(dir: File, input: InputStream, current: Int = CURRENT): String? {
        val tmp = File(dir, "$STAGED.tmp")
        tmp.deleteRecursively()
        val why = runCatching { unpack(input, tmp, current) }.getOrElse { "not a readable backup (${it.message ?: it.javaClass.simpleName})" }
        if (why != null) { tmp.deleteRecursively(); return why }
        val staged = File(dir, STAGED)
        staged.deleteRecursively()
        if (!tmp.renameTo(staged)) { tmp.deleteRecursively(); return "couldn't stage the restore" }
        return null
    }

    /** True when a restore is waiting for the next start. */
    fun staged(dir: File) = File(dir, STAGED).isDirectory

    private fun unpack(input: InputStream, to: File, current: Int): String? {
        to.mkdirs()
        val root = to.canonicalPath + File.separator
        val names = HashSet<String>()
        var manifest: JsonObject? = null
        var total = 0L
        val buf = ByteArray(64 * 1024)
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                if (names.size >= MAX_ENTRIES) return "backup has too many files"
                if (e.name == MANIFEST) {
                    val text = zip.readBytes()
                    if (text.size > 65536) return "backup manifest is too large"
                    manifest = Json.parseToJsonElement(text.decodeToString()) as? JsonObject
                    continue
                }
                pathProblem(e.name)?.let { return "refused ${e.name.take(80)}: $it" }
                if (!names.add(e.name)) return "backup lists ${e.name.take(80)} twice"
                val f = File(to, e.name)
                // Belt and braces: the name check already rules this out.
                if (!f.canonicalPath.startsWith(root)) return "refused ${e.name.take(80)}: outside the folder"
                f.parentFile?.mkdirs()
                f.outputStream().use { out ->
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return "backup unpacks to over ${MAX_BYTES / 1024 / 1024} MB"
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        val m = manifest ?: return "not a SmartPhoneX backup: it has no $MANIFEST"
        if (m["format"]?.jsonPrimitive?.contentOrNull != FORMAT) return "unknown backup format ${m["format"]}"
        val dv = m["dataVersion"]?.jsonPrimitive?.intOrNull ?: return "backup has no data version"
        if (dv > current) return "this backup is from a newer app (data version $dv). Update the app first"
        if (dv < 1) return "bad data version $dv"
        File(to, MANIFEST).writeText(m.toString())
        return null
    }

    /**
     * Swaps in a staged restore. The current files move to [PREVIOUS] first, so the state before a restore can be recovered.
     * Media already on the phone stays if the backup has none.
     */
    private fun applyStaged(dir: File, staged: File) {
        val manifest = Json.parseToJsonElement(File(staged, MANIFEST).readText()) as JsonObject
        val hasMedia = manifest["media"]?.jsonPrimitive?.booleanOrNull == true
        val prev = File(dir, PREVIOUS)
        prev.deleteRecursively(); prev.mkdirs()
        fun move(f: File, to: File) { if (!f.renameTo(to)) { f.copyRecursively(to, overwrite = true); f.deleteRecursively() } }
        dir.listFiles()?.filter { it.name !in NEVER && (hasMedia || it.name !in MEDIA) }?.forEach { move(it, File(prev, it.name)) }
        File(staged, MANIFEST).delete()
        staged.listFiles()?.forEach { move(it, File(dir, it.name)) }
        staged.deleteRecursively()
        writeVersion(dir, manifest["dataVersion"]?.jsonPrimitive?.intOrNull ?: 1, manifest["app"]?.jsonPrimitive?.contentOrNull ?: "")
    }
}
