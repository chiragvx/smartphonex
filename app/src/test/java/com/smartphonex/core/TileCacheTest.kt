package com.smartphonex.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TileCacheTest {
    @Test fun aTileLookedAtOnceIsThereOffline() {
        val dir = Files.createTempDirectory("tiles").toFile()
        try {
            var online = true
            val asked = ArrayList<String>()
            val cache = TileCache(dir) { url -> asked += url; if (online) "tile:$url".toByteArray() else null }
            val first = cache.get("street", 15, 23140, 14820)!!
            assertEquals(1, asked.size)
            assertTrue(asked[0] == "https://tile.openstreetmap.org/15/23140/14820.png")
            assertArrayEquals("kept: not fetched again", first, cache.get("street", 15, 23140, 14820))
            assertEquals(1, asked.size)
            online = false
            assertArrayEquals("offline, from the phone", first, cache.get("street", 15, 23140, 14820))
            assertNull("never seen and offline: nothing", cache.get("street", 15, 23141, 14820))
            assertNull("not a tile", cache.get("street", 3, 9, 0))
            assertNull("not a source", cache.get("../x", 1, 0, 0))
            assertTrue(asked.none { "../" in it })
        } finally { dir.deleteRecursively() }
    }

    @Test fun oldTilesAreRefreshedAndTheCacheStaysUnderItsLimit() {
        val dir = Files.createTempDirectory("tiles").toFile()
        try {
            var n = 0
            val cache = TileCache(dir, maxBytes = 10_000) { ByteArray(1000) { (n++).toByte() } }
            cache.get("satellite", 10, 1, 1)
            val f = File(dir, "satellite/10/1/1")
            f.setLastModified(System.currentTimeMillis() - (TileCache.FRESH_DAYS + 1) * 86_400_000L)
            val before = n
            cache.get("satellite", 10, 1, 1)
            assertTrue("stale: fetched again when there's internet", n > before)
            repeat(20) { cache.get("satellite", 12, it, 1) }
            assertTrue("evicted down under the limit: ${cache.sizeBytes()}", cache.sizeBytes() <= 10_000)
            assertTrue("the newest stay", File(dir, "satellite/12/19/1").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun theCacheNeverGoesIntoABackup() {
        assertTrue("tiles" in AppData.NEVER)
    }
}
