package dev.kifranei.ampp.monet

import android.content.res.ColorStateList

/**
 * Stateful colour lists (selectors such as the native bottom-navigation `selector_bottom_navigation_text`, whose
 * checked state is `@color/key_color`) resolve their item colours inside the framework without going through
 * Resources.getColor, so they are rebuilt here: every state keeps its spec, only colours the [ColorRoleBook]
 * knows are swapped. Enumeration uses ColorStateList's own (hidden) accessors; when they are unavailable the
 * list is returned untouched (fail-open).
 */
internal object StatefulColors {
    private val accessors: Pair<(ColorStateList) -> Array<IntArray>, (ColorStateList) -> IntArray>? by lazy {
        runCatching {
            val states = ColorStateList::class.java.getDeclaredMethod("getStates").apply { isAccessible = true }
            val colors = ColorStateList::class.java.getDeclaredMethod("getColors").apply { isAccessible = true }
            Pair<(ColorStateList) -> Array<IntArray>, (ColorStateList) -> IntArray>(
                { list -> @Suppress("UNCHECKED_CAST") (states.invoke(list) as Array<IntArray>) },
                { list -> colors.invoke(list) as IntArray },
            )
        }.recoverCatching {
            val states = ColorStateList::class.java.getDeclaredField("mStateSpecs").apply { isAccessible = true }
            val colors = ColorStateList::class.java.getDeclaredField("mColors").apply { isAccessible = true }
            Pair<(ColorStateList) -> Array<IntArray>, (ColorStateList) -> IntArray>(
                { list -> @Suppress("UNCHECKED_CAST") (states.get(list) as Array<IntArray>) },
                { list -> colors.get(list) as IntArray },
            )
        }.onFailure { MonetDiag.event("stateful", "ColorStateList states unavailable; selectors keep native colours", it, key = null) }
            .getOrNull()
    }

    class Parts(val states: Array<IntArray>, val colors: IntArray)

    fun parts(list: ColorStateList): Parts? {
        val (states, colors) = accessors ?: return null
        val s = runCatching { states(list) }.getOrNull() ?: return null
        val c = runCatching { colors(list) }.getOrNull() ?: return null
        return if (s.size == c.size && c.isNotEmpty()) Parts(s.map { it.copyOf() }.toTypedArray(), c.copyOf()) else null
    }

    /** New colours for [parts] (same order), or null when no colour of the list is ours to change. */
    fun remap(parts: Parts, replace: (Int) -> Int?): IntArray? {
        var changed = false
        val out = IntArray(parts.colors.size) { i ->
            val from = parts.colors[i]
            val to = replace(from)
            if (to != null && to != from) { changed = true; to } else from
        }
        return out.takeIf { changed }
    }

    fun build(parts: Parts, colors: IntArray) = ColorStateList(parts.states, colors)

    fun lerp(parts: Parts, to: IntArray, t: Float) =
        ColorStateList(parts.states, IntArray(to.size) { MonetCoverPalette.lerp(parts.colors[it], to[it], t) })
}
