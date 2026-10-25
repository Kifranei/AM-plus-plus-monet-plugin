package dev.kifranei.ampp.monet

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MonetProfileTest {
    private val assets = File("src/main/assets")
    private val entries get() = ProfileIndex.parse(File(assets, "profiles.json").readText())

    @Test fun shipsExactlyTheSupportedBuilds() {
        assertEquals(listOf(1606L, 1607L, 1609L, 1611L, 1613L), entries.map { it.versionCode })
        assertTrue(entries.all { it.packageName == "com.apple.android.music" && it.versionName == "7.0.0-beta" })
    }

    @Test fun selectionIsExactWithNoNearestFallback() {
        val e = entries
        assertNotNull(ProfileIndex.select(e, "com.apple.android.music", "7.0.0-beta", 1611))
        assertNotNull(ProfileIndex.select(e, "com.apple.android.music", "7.0.0-beta", 1613))
        assertNull(ProfileIndex.select(e, "com.apple.android.music", "7.0.0-beta", 1612))
        assertNull(ProfileIndex.select(e, "com.apple.android.music", "7.0.0-beta", 1614))
        assertNull(ProfileIndex.select(e, "com.apple.android.music", "7.0.0", 1611))
        assertNull(ProfileIndex.select(e, "other.package", "7.0.0-beta", 1611))
    }

    @Test fun everyProfileParsesAndDescribesItself() {
        entries.forEach { entry ->
            val text = File(assets, entry.asset).readText()
            val json = org.json.JSONObject(text)
            assertEquals(entry.versionCode, json.getLong("versionCode"))
            val profile = MonetProfile.parse(text)
            assertTrue(profile.accent.any { it.name == "key_color" })
            assertTrue(profile.player.contains("player_vibrant_primary"))
            assertEquals("player_root", profile.playerRootId)
            assertEquals(profile.accent.size, profile.accent.map { it.name }.toSet().size)
        }
    }

    @Test fun versionsWithAliasedAccentsUseSingleRole() {
        fun roles(code: Int) = MonetProfile.parse(File(assets, "applemusic-$code.json").readText()).accent.map { it.role }.toSet()
        assertEquals(setOf(AccentRole.ACCENT), roles(1611))
        // 1613 keeps 1611's colour resources and aliasing unchanged (aapt2 values identical).
        assertEquals(MonetProfile.parse(File(assets, "applemusic-1611.json").readText()),
            MonetProfile.parse(File(assets, "applemusic-1613.json").readText()))
        assertTrue(roles(1609).containsAll(setOf(AccentRole.PRESSED, AccentRole.ROLLOVER)))
    }

    @Test fun rejectsBrokenProfiles() {
        assertThrows(Exception::class.java) { MonetProfile.parse("""{"monet":{"schemaVersion":2}}""") }
        assertThrows(Exception::class.java) {
            MonetProfile.parse("""{"monet":{"schemaVersion":1,"playerRootId":"x","accent":[{"name":"a","role":"zzz"}],"player":[{"name":"p"}]}}""")
        }
    }

    @Test fun diagnosticPluginBuildMatchesPluginJson() {
        val manifest = org.json.JSONObject(File("plugin.json").readText())
        assertEquals("${manifest.getString("versionName")}/${manifest.getInt("versionCode")}", PLUGIN_BUILD)
    }
}
