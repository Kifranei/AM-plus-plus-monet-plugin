package dev.kifranei.ampp.monet

import java.util.concurrent.ConcurrentHashMap

/** What a colour found in a view means: an accent role or a player white-alpha tone. */
internal sealed interface ColorRole {
    data class Accent(val role: AccentRole) : ColorRole
    data object Player : ColorRole
}

/**
 * Remembers which ARGB values are the host's native colours (learned when a profiled resource is read)
 * and which were produced by this plugin, so already-inflated views can be re-tinted later without
 * guessing: a view colour that is in neither set is never touched. Thread-safe and Android-free.
 */
internal class ColorRoleBook(private val appliedLimit: Int = 96) {
    private val native = ConcurrentHashMap<Int, ColorRole>()
    private val applied = object : LinkedHashMap<Int, ColorRole>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ColorRole>) = size > appliedLimit
    }

    fun rememberNative(argb: Int, role: ColorRole) { native.putIfAbsent(argb, role) }

    fun rememberApplied(argb: Int, role: ColorRole) {
        if (native.containsKey(argb)) return
        synchronized(applied) { applied[argb] = role }
    }

    fun roleOf(argb: Int): ColorRole? = native[argb] ?: synchronized(applied) { applied[argb] }

    /** The replacement for a view's current colour under [scheme], or null when it is not ours to change. */
    fun replacement(argb: Int, scheme: MonetScheme, includePlayer: Boolean, includeAccent: Boolean): Int? {
        val next = when (val role = roleOf(argb) ?: return null) {
            is ColorRole.Accent -> if (includeAccent) MonetColors.accent(role.role, scheme, argb) else return null
            ColorRole.Player -> if (includePlayer) MonetColors.player(scheme, argb) else return null
        }
        return next.takeIf { it != argb }
    }
}
