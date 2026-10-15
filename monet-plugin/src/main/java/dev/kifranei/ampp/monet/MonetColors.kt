package dev.kifranei.ampp.monet

/**
 * Pure mapping from a native colour to its Monet replacement. Android-free so it is unit-tested.
 *  - accent roles take the scheme colour (opaque; alpha of the native value is kept),
 *  - player roles are white-with-alpha natively: keep the alpha, swap white for the cover-tinted near-white.
 */
internal object MonetColors {
    fun accent(role: AccentRole, scheme: MonetScheme, native: Int): Int {
        val rgb = when (role) {
            AccentRole.ACCENT -> scheme.accent
            AccentRole.PRESSED -> scheme.accentPressed
            AccentRole.ROLLOVER -> scheme.accentRollover
            AccentRole.GRADIENT_START -> scheme.accentGradientStart
        }
        return MonetCoverPalette.withAlpha(rgb, native ushr 24)
    }

    /** Only recolours plain white; anything else the host customised is left alone. */
    fun player(scheme: MonetScheme, native: Int): Int =
        if ((native and 0x00FFFFFF) == 0x00FFFFFF) MonetCoverPalette.withAlpha(scheme.playerTint, native ushr 24) else native
}
