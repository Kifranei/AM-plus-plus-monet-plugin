package dev.kifranei.ampp.monet

import dev.amenhancer.plugin.api.PluginContext
import dev.amenhancer.plugin.api.PluginUnsupportedException
import org.json.JSONObject

/** One shipped host profile: the exact host build it describes and the asset holding its colour contracts. */
internal data class ProfileEntry(val packageName: String, val versionName: String, val versionCode: Long, val asset: String) {
    val displayName get() = "$versionName / $versionCode"
}

/** assets/profiles.json lists every shipped profile; adding a host version is one JSON file plus one entry. */
internal object ProfileIndex {
    const val ASSET = "profiles.json"
    fun parse(text: String): List<ProfileEntry> {
        val root = JSONObject(text)
        val packageName = root.getString("packageName")
        val array = root.getJSONArray("profiles")
        val entries = (0 until array.length()).map {
            val item = array.getJSONObject(it)
            ProfileEntry(packageName, item.getString("versionName"), item.getLong("versionCode"), item.getString("asset"))
        }
        check(entries.map { it.versionCode to it.versionName }.toSet().size == entries.size) { "Duplicate profile version" }
        return entries
    }

    /** Exact (package, versionName, versionCode) match only; no range or nearest-version fallback. */
    fun select(entries: List<ProfileEntry>, packageName: String, versionName: String, versionCode: Long): ProfileEntry? =
        entries.firstOrNull { it.packageName == packageName && it.versionName == versionName && it.versionCode == versionCode }

    fun unsupportedMessage(entries: List<ProfileEntry>) =
        "仅适配 Apple Music " + entries.joinToString("、") { it.displayName } + " / Only Apple Music " +
            entries.joinToString(", ") { it.displayName }
}

internal enum class AccentRole { ACCENT, PRESSED, ROLLOVER, GRADIENT_START }

internal data class AccentResource(val name: String, val role: AccentRole)

/** Colour resource names (not numeric ids, which change per build) the plugin may recolour. */
internal data class MonetProfile(val accent: List<AccentResource>, val player: List<String>, val playerRootId: String) {
    companion object {
        fun parse(text: String): MonetProfile {
            val monet = JSONObject(text).getJSONObject("monet")
            check(monet.getInt("schemaVersion") == 1) { "Unsupported monet schema" }
            val accent = monet.getJSONArray("accent").let { array ->
                (0 until array.length()).map {
                    val item = array.getJSONObject(it)
                    AccentResource(item.getString("name"), when (item.getString("role")) {
                        "accent" -> AccentRole.ACCENT
                        "pressed" -> AccentRole.PRESSED
                        "rollover" -> AccentRole.ROLLOVER
                        "gradientStart" -> AccentRole.GRADIENT_START
                        else -> error("Unknown role")
                    })
                }
            }
            val player = monet.getJSONArray("player").let { array ->
                (0 until array.length()).map { array.getJSONObject(it).getString("name") }
            }
            check(accent.isNotEmpty() && player.isNotEmpty())
            return MonetProfile(accent, player, monet.getString("playerRootId"))
        }

        /** Chooses and loads the profile for the host build; unsupported builds are refused before any hook. */
        fun load(context: PluginContext): MonetProfile {
            val entries = context.openAsset(ProfileIndex.ASSET).bufferedReader().use { ProfileIndex.parse(it.readText()) }
            val entry = ProfileIndex.select(entries, context.hostPackageName, context.hostVersionName, context.hostVersionCode)
                ?: throw PluginUnsupportedException(ProfileIndex.unsupportedMessage(entries) +
                    "；当前为 ${context.hostVersionName} / ${context.hostVersionCode}")
            val text = context.openAsset(entry.asset).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            check(root.getString("packageName") == entry.packageName && root.getString("versionName") == entry.versionName &&
                root.getLong("versionCode") == entry.versionCode) { "Profile ${entry.asset} does not describe ${entry.displayName}" }
            return parse(text)
        }
    }
}
