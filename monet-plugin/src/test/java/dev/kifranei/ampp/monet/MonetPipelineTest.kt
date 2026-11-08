package dev.kifranei.ampp.monet

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class MonetPipelineTest {
    private val direct = Executor { it.run() }
    private fun blue() = IntArray(576) { 0xFF1976D2.toInt() }

    @Test fun publishesOnlyRealChanges() {
        val state = MonetState(null)
        var events = 0
        state.addListener { events++ }
        assertNull(state.scheme(true))
        assertFalse(state.publish(null))
        assertTrue(state.publish(0xFF3366CC.toInt()))
        assertFalse("equal republish must be a no-op", state.publish(0xFF3366CC.toInt()))
        assertEquals(1, events)
        assertNotNull(state.scheme(true)); assertNotNull(state.scheme(false))
        assertSame(state.scheme(true), state.scheme(true))
    }

    @Test fun cachesPerCoverAndSamplesOnce() {
        var samples = 0
        val state = MonetState(null)
        val saved = ArrayList<Int>()
        val pipeline = CoverColorPipeline(direct, SeedCache(), state, { samples++; blue() }, saved::add)
        pipeline.submit("album:A|x", Any())
        pipeline.submit("album:A|x", Any())
        assertEquals(1, samples)
        assertEquals(1, saved.size)
        pipeline.submit("album:B|x", Any())
        pipeline.submit("album:A|x", Any())
        assertEquals(2, samples)
        assertNotNull(state.currentSeed())
    }

    @Test fun greyscaleOrFailedExtractionKeepsPreviousColour() {
        val state = MonetState(0xFF336699.toInt())
        val grey = CoverColorPipeline(direct, SeedCache(), state, { IntArray(576) { 0xFF808080.toInt() } }, {})
        grey.submit("g", Any())
        assertEquals(0xFF336699.toInt(), state.currentSeed())
        val failing = CoverColorPipeline(direct, SeedCache(), state, { error("boom") }, {})
        failing.submit("f", Any()); failing.submit("f", Any())
        assertEquals(0xFF336699.toInt(), state.currentSeed())
        val missing = CoverColorPipeline(direct, SeedCache(), state, { null }, {})
        missing.submit("m", null)
        assertEquals(0xFF336699.toInt(), state.currentSeed())
    }

    @Test fun staleResultsFromRapidSkippingAreDropped() {
        val queue = ArrayList<Runnable>()
        val state = MonetState(null)
        val seeds = mapOf("a" to IntArray(576) { 0xFFD32F2F.toInt() }, "b" to blue())
        val pipeline = CoverColorPipeline({ queue += it }, SeedCache(), state, { seeds.getValue(it as String) }, {})
        pipeline.submit("a", "a"); pipeline.submit("b", "b")
        queue.forEach { it.run() }
        val hue = MonetCoverPalette.toOklch(checkNotNull(state.currentSeed()))[2]
        assertTrue("latest cover (blue) wins, hue=$hue", hue in 200.0..290.0)
    }

    @Test fun seedCacheEvictsOldest() {
        val cache = SeedCache(2)
        cache.put("a", 1); cache.put("b", 2); cache.put("c", 3)
        assertFalse(cache.contains("a")); assertTrue(cache.contains("c"))
        cache.put("n", null)
        assertTrue(cache.contains("n")); assertNull(cache.get("n"))
    }

    @Test fun coverKeyPrefersAlbum() {
        assertEquals("album:X|Y", CoverMetadataSource.coverKey("1", "X", "Y"))
        assertEquals("id:1", CoverMetadataSource.coverKey("1", " ", null))
        assertNull(CoverMetadataSource.coverKey(null, null, null))
    }
}
