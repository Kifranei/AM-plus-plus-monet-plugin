package dev.kifranei.ampp.monet

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MonetSettingsTest {
    @Test fun defaultsToOffAndTolerantDecode() {
        assertEquals(MonetMode.OFF, MonetSettings().mode)
        assertEquals(MonetMode.OFF, MonetMode.decode("garbage"))
        assertEquals(MonetMode.OFF, MonetMode.decode(null))
        assertEquals(MonetMode.OFF, MonetSettings.decode("not json").mode)
        assertEquals(MonetMode.OFF, MonetSettings.decode("{}").mode)
        assertEquals(MonetMode.APP, MonetMode.decode(" APP "))
    }

    @Test fun roundTripsThroughAtomicStore() {
        val dir = Files.createTempDirectory("monet").toFile()
        val store = MonetSettingsStore(dir)
        assertEquals(MonetMode.OFF, store.read().mode)
        MonetMode.values().forEach { mode ->
            store.write(MonetSettings(mode))
            assertEquals(mode, store.read().mode)
        }
        assertFalse(dir.resolve("settings.json.tmp").exists())
    }

    @Test fun modeScopes() {
        assertFalse(MonetMode.OFF.affectsPlayer); assertFalse(MonetMode.OFF.affectsApp)
        assertTrue(MonetMode.PLAYER.affectsPlayer); assertFalse(MonetMode.PLAYER.affectsApp)
        assertTrue(MonetMode.APP.affectsPlayer); assertTrue(MonetMode.APP.affectsApp)
    }

    @Test fun seedStoreRoundTripsNegativeArgb() {
        val dir = Files.createTempDirectory("monet").toFile()
        val store = SeedStore(dir)
        assertNull(store.read())
        store.write(0xFF3A7BD5.toInt())
        assertEquals(0xFF3A7BD5.toInt(), store.read())
    }

    @Test fun textExistsInBothLanguages() {
        assertEquals("莫奈取色", MonetText.forLanguage("zh").name)
        assertEquals("Monet cover color", MonetText.forLanguage("en").name)
        assertEquals("Monet cover color", MonetText.forLanguage("fr").name)
        MonetMode.values().forEach { assertTrue(MonetText.forLanguage("zh").label(it).isNotBlank()) }
    }
}
