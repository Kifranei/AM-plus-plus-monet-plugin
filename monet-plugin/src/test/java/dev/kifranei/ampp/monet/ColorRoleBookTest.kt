package dev.kifranei.ampp.monet

import org.junit.Assert.*
import org.junit.Test

class ColorRoleBookTest {
    private val seed = MonetCoverPalette.fromOklch(0.65, 0.15, 150.0)
    private val night = checkNotNull(MonetCoverPalette.scheme(seed, true))
    private val white94 = 0xF0FFFFFF.toInt()
    private val nativeRed = 0xFFFA233B.toInt()

    @Test fun accentKeepsNativeAlphaAndUsesRole() {
        assertEquals(night.accent, MonetColors.accent(AccentRole.ACCENT, night, nativeRed))
        assertEquals(MonetCoverPalette.withAlpha(night.accentPressed, 0x33), MonetColors.accent(AccentRole.PRESSED, night, 0x33E00921))
    }

    @Test fun playerOnlyRecoloursWhiteAndKeepsAlpha() {
        val out = MonetColors.player(night, white94)
        assertEquals(0xF0, out ushr 24)
        assertNotEquals(white94, out)
        assertEquals(0xFF112233.toInt(), MonetColors.player(night, 0xFF112233.toInt()))
    }

    @Test fun unknownColoursAreNeverTouched() {
        val book = ColorRoleBook()
        book.rememberNative(nativeRed, ColorRole.Accent(AccentRole.ACCENT))
        assertNull(book.replacement(0xFF123456.toInt(), night, true, true))
        assertNotNull(book.replacement(nativeRed, night, true, true))
    }

    @Test fun scopeFlagsGateRoles() {
        val book = ColorRoleBook()
        book.rememberNative(nativeRed, ColorRole.Accent(AccentRole.ACCENT))
        book.rememberNative(white94, ColorRole.Player)
        assertNull(book.replacement(nativeRed, night, includePlayer = true, includeAccent = false))
        assertNull(book.replacement(white94, night, includePlayer = false, includeAccent = true))
        assertNotNull(book.replacement(white94, night, includePlayer = true, includeAccent = false))
    }

    @Test fun appliedColoursAreRetintedOnNextCover() {
        val book = ColorRoleBook()
        book.rememberNative(nativeRed, ColorRole.Accent(AccentRole.ACCENT))
        val first = checkNotNull(book.replacement(nativeRed, night, true, true))
        book.rememberApplied(first, ColorRole.Accent(AccentRole.ACCENT))
        val other = checkNotNull(MonetCoverPalette.scheme(MonetCoverPalette.fromOklch(0.65, 0.15, 30.0), true))
        assertEquals(other.accent, book.replacement(first, other, true, true))
        // Same scheme again is a no-op: nothing to animate or republish.
        assertNull(book.replacement(night.accent, night, true, true))
    }

    @Test fun appliedMemoryIsBounded() {
        val book = ColorRoleBook(appliedLimit = 4)
        (1..20).forEach { book.rememberApplied(0xFF000000.toInt() or it, ColorRole.Player) }
        assertNull(book.roleOf(0xFF000001.toInt())); assertNotNull(book.roleOf(0xFF000014.toInt()))
    }
}
