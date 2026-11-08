package dev.kifranei.ampp.monet

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonetCoverPaletteTest {
    private fun solid(argb: Int, n: Int = 1024) = IntArray(n) { argb }

    private fun mixed(vararg parts: Pair<Int, Int>): IntArray =
        parts.flatMap { (c, n) -> List(n) { c } }.toIntArray()

    @Test
    fun emptyOrTransparentOrGreyscaleImagesFallBack() {
        assertNull(MonetCoverPalette.seedFromPixels(IntArray(0)))
        assertNull(MonetCoverPalette.seedFromPixels(solid(0x00FF0000)))
        assertNull(MonetCoverPalette.seedFromPixels(solid(0xFF808080.toInt())))
        assertNull(MonetCoverPalette.seedFromPixels(solid(0xFF000000.toInt())))
        assertNull(MonetCoverPalette.seedFromPixels(solid(0xFFFFFFFF.toInt())))
        assertNull(MonetCoverPalette.scheme(null, night = true))
    }

    @Test
    fun seedFollowsTheDominantChromaticHueIgnoringBlackAndWhite() {
        val pixels = mixed(
            0xFF000000.toInt() to 600,
            0xFFFFFFFF.toInt() to 200,
            0xFF2E7D32.toInt() to 300, // green dominates the chromatic pixels
            0xFFD32F2F.toInt() to 40,
        )
        val seed = checkNotNull(MonetCoverPalette.seedFromPixels(pixels))
        val hue = MonetCoverPalette.toOklch(seed)[2]
        // Oklab green hues sit around 140-160 degrees; red around 25.
        assertTrue("hue=$hue", hue in 120.0..175.0)
    }

    @Test
    fun accentKeepsReadableContrastInBothThemesForEveryHue() {
        for (hue in 0 until 360 step 10) {
            val seed = MonetCoverPalette.fromOklch(0.65, 0.15, hue.toDouble())
            val night = checkNotNull(MonetCoverPalette.scheme(seed, night = true))
            val light = checkNotNull(MonetCoverPalette.scheme(seed, night = false))
            assertTrue("night hue=$hue", MonetCoverPalette.contrast(night.accent, 0xFF000000.toInt()) >= 4.5)
            assertTrue("light hue=$hue", MonetCoverPalette.contrast(light.accent, 0xFFFFFFFF.toInt()) >= 4.5)
            assertTrue("onAccent hue=$hue", MonetCoverPalette.contrast(night.accent, night.onAccent) >= 4.5)
            assertTrue("player tint is near white", MonetCoverPalette.luminance(night.playerTint) > 0.7)
        }
    }

    @Test
    fun pressedStatesMoveTheNativeDirection() {
        val seed = MonetCoverPalette.fromOklch(0.65, 0.15, 250.0)
        val night = checkNotNull(MonetCoverPalette.scheme(seed, night = true))
        val light = checkNotNull(MonetCoverPalette.scheme(seed, night = false))
        assertTrue(MonetCoverPalette.luminance(night.accentPressed) > MonetCoverPalette.luminance(night.accent))
        assertTrue(MonetCoverPalette.luminance(light.accentPressed) < MonetCoverPalette.luminance(light.accent))
    }

    @Test
    fun lerpAndAlphaHelpersAreExactAtTheEnds() {
        assertEquals(0xFF102030.toInt(), MonetCoverPalette.lerp(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), MonetCoverPalette.lerp(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 1f))
        assertEquals(0x60FB5C74, MonetCoverPalette.withAlpha(0xFFFB5C74.toInt(), 0x60))
    }

    /** Set MONET_PREVIEW_PNG=/path/preview.png to render sample palettes (no device needed). */
    @Test
    fun renderPreviewWhenRequested() {
        val target = System.getenv("MONET_PREVIEW_PNG") ?: return
        val samples = listOf(
            "Red" to 0xFFD32F2F.toInt(), "Orange" to 0xFFF57C00.toInt(), "Gold" to 0xFFFBC02D.toInt(),
            "Green" to 0xFF2E7D32.toInt(), "Teal" to 0xFF00897B.toInt(), "Blue" to 0xFF1976D2.toInt(),
            "Purple" to 0xFF7B1FA2.toInt(), "Pink" to 0xFFEC407A.toInt(),
        )
        val cell = 64
        val image = BufferedImage(cell * 8, cell * samples.size * 2, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        samples.forEachIndexed { row, (_, cover) ->
            val seed = MonetCoverPalette.seedFromPixels(solid(cover)) ?: cover
            listOf(true, false).forEachIndexed { half, night ->
                val s = checkNotNull(MonetCoverPalette.scheme(seed, night))
                val y = (row * 2 + half) * cell
                g.color = java.awt.Color(if (night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), true)
                g.fillRect(0, y, cell * 8, cell)
                listOf(cover, s.accent, s.accentPressed, s.accentRollover, s.accentGradientStart,
                    s.playerTint, s.onAccent, s.seed).forEachIndexed { i, c ->
                    g.color = java.awt.Color(c, true)
                    g.fillRect(i * cell + 6, y + 6, cell - 12, cell - 12)
                }
            }
        }
        g.dispose()
        ImageIO.write(image, "png", File(target))
    }
}
