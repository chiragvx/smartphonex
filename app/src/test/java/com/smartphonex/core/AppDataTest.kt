package com.smartphonex.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppDataTest {
    private fun tmp(): File = Files.createTempDirectory("spx").toFile()

    private fun phone06(): File = tmp().apply {
        File(this, "profiles.json").writeText("""{"active":"car"}""")
        File(this, "token.txt").writeText("old-token")
        File(this, "llm-key").writeText("secret")
        File(this, "relay.json").writeText("""{"url":"wss://x","key":"k"}""")
        File(this, "devices").mkdirs(); File(this, "devices/arm.json").writeText("{}")
        File(this, "logs").mkdirs(); File(this, "logs/run1.csv").writeText("t,x\n1,2\n")
        File(this, "photos").mkdirs(); File(this, "photos/p.jpg").writeBytes(ByteArray(10))
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun stage(d: File, b: ByteArray) = AppData.stage(d, ByteArrayInputStream(b))

    private fun entries(b: ByteArray): Set<String> {
        val d = tmp()
        assertNull(stage(d, b))
        val s = File(d, ".restore")
        return s.walkTopDown().filter { it.isFile }.map { it.relativeTo(s).invariantSeparatorsPath }.toSet() - "smartphonex-backup.json"
    }

    private val manifest = """{"format":"smartphonex.backup/1","dataVersion":1,"app":"t","created":1,"media":false}""".toByteArray()

    @Test fun `files from 0_6 are version 1 and nothing is touched`() {
        val d = phone06()
        val r = AppData.prepare(d, "0.7")
        assertEquals(Pair(1, 1), r.from to r.to)
        assertNull(r.problem)
        assertEquals(1, AppData.version(d))
        assertEquals("""{"active":"car"}""", File(d, "profiles.json").readText())
    }

    @Test fun `migrations run in order and record the version`() {
        val d = phone06()
        val seen = mutableListOf<Int>()
        val steps = mapOf<Int, (File) -> Unit>(1 to { f -> seen += 1; File(f, "profiles.json").writeText("v2") }, 2 to { _ -> seen += 2 })
        val r = AppData.prepare(d, "x", steps, current = 3)
        assertNull(r.problem)
        assertEquals(listOf(1, 2), seen)
        assertEquals(3, AppData.version(d))
        assertEquals("v2", File(d, "profiles.json").readText())
        assertEquals(3, AppData.prepare(d, "x", steps, current = 3).to)
        assertEquals(listOf(1, 2), seen)
    }

    @Test fun `a failed migration is undone and stops there`() {
        val d = phone06()
        val steps = mapOf<Int, (File) -> Unit>(1 to { f -> File(f, "profiles.json").writeText("half"); File(f, "new.json").writeText("x"); error("boom") })
        val r = AppData.prepare(d, "x", steps, current = 2)
        assertTrue(r.problem!!.contains("undone"))
        assertEquals(1, AppData.version(d))
        assertEquals("""{"active":"car"}""", File(d, "profiles.json").readText())
        assertFalse(File(d, "new.json").exists())
        assertEquals("old-token", File(d, "token.txt").readText())
        assertTrue(File(d, "photos/p.jpg").exists())
    }

    @Test fun `files from a newer app are left alone`() {
        val d = phone06()
        File(d, "data-version.json").writeText("""{"version":9}""")
        val r = AppData.prepare(d, "x")
        assertTrue(r.problem!!.contains("newer"))
        assertEquals(9, AppData.version(d))
    }

    @Test fun `backup leaves out secrets and media unless asked`() {
        val d = phone06()
        val out = ByteArrayOutputStream().also { AppData.backup(d, it, "0.7", media = false) }
        assertEquals(setOf("profiles.json", "devices/arm.json", "logs/run1.csv"), entries(out.toByteArray()))
        val all = ByteArrayOutputStream().also { AppData.backup(d, it, "0.7", media = true) }
        assertEquals(setOf("profiles.json", "devices/arm.json", "logs/run1.csv", "photos/p.jpg"), entries(all.toByteArray()))
    }

    @Test fun `restore round trip onto a fresh install keeps the new token`() {
        val old = phone06()
        val out = ByteArrayOutputStream().also { AppData.backup(old, it, "0.6", media = true) }
        val fresh = tmp().apply { File(this, "token.txt").writeText("new-token"); File(this, "profiles.json").writeText("{}") }
        AppData.prepare(fresh, "0.7")
        assertNull(stage(fresh, out.toByteArray()))
        assertTrue(AppData.staged(fresh))
        assertEquals("{}", File(fresh, "profiles.json").readText())
        val r = AppData.prepare(fresh, "0.7")
        assertNull(r.problem)
        assertTrue(r.restored)
        assertFalse(AppData.staged(fresh))
        assertEquals("""{"active":"car"}""", File(fresh, "profiles.json").readText())
        assertEquals("t,x\n1,2\n", File(fresh, "logs/run1.csv").readText())
        assertEquals(10, File(fresh, "photos/p.jpg").length())
        assertEquals("new-token", File(fresh, "token.txt").readText())
        assertFalse(File(fresh, "llm-key").exists())
        assertFalse(File(fresh, "relay.json").exists())
        assertEquals("{}", File(fresh, ".pre-restore/profiles.json").readText())
        assertEquals(1, AppData.version(fresh))
    }

    @Test fun `restore without media keeps the photos already there`() {
        val d = phone06()
        assertNull(stage(d, zip("smartphonex-backup.json" to manifest, "profiles.json" to "{\"active\":\"boat\"}".toByteArray())))
        AppData.prepare(d, "x")
        assertEquals("{\"active\":\"boat\"}", File(d, "profiles.json").readText())
        assertTrue(File(d, "photos/p.jpg").exists())
        assertFalse(File(d, "devices").exists())
        assertTrue(File(d, ".pre-restore/devices/arm.json").exists())
    }

    @Test fun `hostile or wrong zips are refused and leave nothing behind`() {
        for (bad in listOf("../evil", "/abs", "logs/../../x", "a\\b", "C:/x", "token.txt", "relay.json", ".restore/x", "logs/.hidden", "llm-key", "data-version.json")) {
            val d = tmp()
            assertNotNull(bad, stage(d, zip("smartphonex-backup.json" to manifest, bad to ByteArray(1))))
            assertFalse(bad, AppData.staged(d))
            assertEquals(bad, 0, d.list()!!.size)
        }
        assertNotNull(stage(tmp(), zip("profiles.json" to ByteArray(1))))
        assertNotNull(stage(tmp(), "not a zip".toByteArray()))
        assertNotNull(stage(tmp(), zip("smartphonex-backup.json" to """{"format":"other/1","dataVersion":1}""".toByteArray())))
        val newer = """{"format":"smartphonex.backup/1","dataVersion":99}""".toByteArray()
        assertTrue(stage(tmp(), zip("smartphonex-backup.json" to newer))!!.contains("newer"))
    }
}
