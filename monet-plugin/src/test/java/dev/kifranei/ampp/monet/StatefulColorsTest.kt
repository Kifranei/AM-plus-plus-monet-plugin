package dev.kifranei.ampp.monet

import org.junit.Assert.*
import org.junit.Test

/** Issue #1: the non-glass bottom navigation selector embeds key_color in its checked state. */
class StatefulColorsTest {
    private val seed = MonetCoverPalette.fromOklch(0.65, 0.15, 150.0)
    private val light = checkNotNull(MonetCoverPalette.scheme(seed, false))
    private val keyBlue = 0xFF0A84FF.toInt()
    private val label = 0xFF8E8E93.toInt()

    @Test fun onlyKnownStateColoursChange() {
        val book = ColorRoleBook()
        book.rememberNative(keyBlue, ColorRole.Accent(AccentRole.ACCENT))
        val parts = StatefulColors.Parts(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(keyBlue, label))
        val out = checkNotNull(StatefulColors.remap(parts) { book.replacement(it, light, false, true) })
        assertEquals(light.accent, out[0])
        assertEquals(label, out[1])
    }

    @Test fun untouchedSelectorIsLeftAlone() {
        val parts = StatefulColors.Parts(arrayOf(intArrayOf()), intArrayOf(label))
        assertNull(StatefulColors.remap(parts) { ColorRoleBook().replacement(it, light, false, true) })
    }
}
