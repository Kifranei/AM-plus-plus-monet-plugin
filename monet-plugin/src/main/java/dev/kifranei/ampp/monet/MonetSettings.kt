package dev.kifranei.ampp.monet

import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * [OFF] is the default: nothing is hooked and Apple Music is untouched.
 * [PLAYER] recolours the player-page colour resources (`player_*`).
 * [APP] additionally recolours the app accent family and already-inflated accent views.
 * A change takes effect after Apple Music restarts.
 */
internal enum class MonetMode(val storage: String) {
    OFF("off"), PLAYER("player"), APP("app");

    val affectsPlayer get() = this != OFF
    val affectsApp get() = this == APP

    companion object {
        fun decode(raw: String?): MonetMode = values().firstOrNull { it.storage.equals(raw?.trim(), ignoreCase = true) } ?: OFF
    }
}

internal data class MonetSettings(val mode: MonetMode = MonetMode.OFF) {
    fun encode(): String = JSONObject().put("mode", mode.storage).toString()

    companion object {
        fun decode(text: String): MonetSettings =
            runCatching { MonetSettings(MonetMode.decode(JSONObject(text).optString("mode"))) }.getOrDefault(MonetSettings())
    }
}

internal class MonetSettingsStore(private val directory: File) {
    private val file get() = File(directory, "settings.json")
    fun read(): MonetSettings = if (file.isFile) MonetSettings.decode(file.readText(Charsets.UTF_8)) else MonetSettings()

    @Synchronized
    fun write(value: MonetSettings) = atomicWrite(directory, "settings.json", value.encode())
}

/** Last derived seed, persisted so the very first inflate after a cold start already has the colour. */
internal class SeedStore(private val directory: File) {
    private val file get() = File(directory, "last-seed.txt")
    fun read(): Int? = runCatching { if (file.isFile) file.readText().trim().toLong(16).toInt() else null }.getOrNull()

    @Synchronized
    fun write(seed: Int) = runCatching { atomicWrite(directory, "last-seed.txt", Integer.toHexString(seed)) }.let { }
}

internal fun atomicWrite(directory: File, name: String, text: String) {
    check(directory.isDirectory || directory.mkdirs())
    val target = File(directory, name)
    val temporary = File(directory, "$name.tmp")
    try {
        temporary.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temporary.delete()
    }
}
