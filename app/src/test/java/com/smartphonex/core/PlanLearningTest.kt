package com.smartphonex.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PlanLearningTest {
    private val settings = mapOf("PLAN_CLIMB" to 2.5, "PLAN_SPEED" to 15.0, "PLAN_GLIDE" to 8.0)

    @Test fun measurementsBecomeCautiousSuggestions() {
        val l = PlanLearning(null)
        listOf(2.1, 2.0, 2.2, 2.1).forEach { l.climb("plane", it) }
        assertTrue("two legs aren't enough", l.suggestions("plane") { settings[it]!! }.none { it.param == "PLAN_SPEED" }.also { listOf(0.8, 0.82).forEach { l.pace("plane", it) } })
        l.pace("plane", 0.79)
        l.glide("plane", 6.0)
        val s = l.suggestions("plane") { settings[it]!! }.associateBy { it.param }
        assertEquals("90% of the measured 2.1 m/s", 1.9, s["PLAN_CLIMB"]!!.suggested, 1e-9)
        assertEquals("legs took about 25% longer: plan slower", 12.0, s["PLAN_SPEED"]!!.suggested, 0.1)
        assertTrue(s["PLAN_SPEED"]!!.basis, "longer" in s["PLAN_SPEED"]!!.basis)
        assertEquals("85% of the 6:1 it glided", 5.1, s["PLAN_GLIDE"]!!.suggested, 1e-9)
        // Within 10% of the setting: nothing to suggest.
        val close = PlanLearning(null).also { q -> listOf(2.7, 2.8, 2.75).forEach { q.climb("plane", it) } }
        assertTrue(close.suggestions("plane") { settings[it]!! }.isEmpty())
        // Nonsense isn't learned.
        l.climb("plane", -3.0); l.pace("plane", 40.0)
        assertEquals(Triple(4, 3, 1), l.counts("plane"))
    }

    @Test fun keptPerVehicleAcrossARestart() {
        val dir = Files.createTempDirectory("learn").toFile()
        try {
            val f = java.io.File(dir, "learning.json")
            PlanLearning(f).apply { listOf(2.0, 2.0, 2.0).forEach { climb("plane", it) }; climb("plane-sim", 3.0) }
            val again = PlanLearning(f)
            assertEquals(Triple(3, 0, 0), again.counts("plane"))
            assertEquals(Triple(1, 0, 0), again.counts("plane-sim"))
            again.forget("plane")
            assertEquals(Triple(0, 0, 0), PlanLearning(f).counts("plane"))
        } finally { dir.deleteRecursively() }
    }
}
