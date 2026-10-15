package dev.kifranei.ampp.monet

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Colours derived from one cover; every value is opaque ARGB unless noted. */
internal data class MonetScheme(
    val seed: Int,
    val accent: Int,
    val accentPressed: Int,
    val accentRollover: Int,
    val accentGradientStart: Int,
    /** Near-white tinted with the cover hue; callers re-apply their own alpha. */
    val playerTint: Int,
    val onAccent: Int,
    val night: Boolean,
)

/**
 * Android-free Monet-style derivation: a stable hue-sector quantiser picks the seed, then a
 * fixed-hue/chroma tonal ramp in Oklab produces roles with WCAG contrast guards.
 * Pure functions only, so it is safe to run on any background thread and to unit-test.
 */
internal object MonetCoverPalette {
    const val MIN_SEED_CHROMA = 0.045
    const val MIN_CHROMATIC_SHARE = 0.04
    private const val SECTORS = 24
    private const val NIGHT_BACKGROUND = 0xFF000000.toInt()
    private const val LIGHT_BACKGROUND = 0xFFFFFFFF.toInt()
    private const val MIN_CONTRAST = 4.5

    /**
     * Picks a seed colour from (already downscaled) ARGB pixels, or null when the image is
     * empty, transparent or effectively greyscale — callers then keep the native colours.
     */
    fun seedFromPixels(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        val weight = DoubleArray(SECTORS)
        val sumX = DoubleArray(SECTORS)
        val sumY = DoubleArray(SECTORS)
        val sumC = DoubleArray(SECTORS)
        var counted = 0
        var chromatic = 0
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            counted++
            val lch = toOklch(p)
            // Ignore near-black / near-white pixels: they carry no usable hue.
            if (lch[0] < 0.12 || lch[0] > 0.97 || lch[1] < MIN_SEED_CHROMA) continue
            chromatic++
            val sector = ((lch[2] / 360.0) * SECTORS).toInt().coerceIn(0, SECTORS - 1)
            // Vivid mid-tone pixels matter more than washed-out ones.
            val w = lch[1] * (1.0 - abs(lch[0] - 0.65))
            weight[sector] += w
            val rad = Math.toRadians(lch[2])
            sumX[sector] += cos(rad) * w
            sumY[sector] += sin(rad) * w
            sumC[sector] += lch[1] * w
        }
        if (counted == 0 || chromatic.toDouble() / counted < MIN_CHROMATIC_SHARE) return null
        // A sector plus its neighbours smooths hue-boundary splits.
        var best = -1
        var bestScore = 0.0
        for (s in 0 until SECTORS) {
            val score = weight[s] + 0.5 * (weight[(s + 1) % SECTORS] + weight[(s + SECTORS - 1) % SECTORS])
            if (score > bestScore) { bestScore = score; best = s }
        }
        if (best < 0 || weight[best] <= 0.0) return null
        val hue = (Math.toDegrees(atan2(sumY[best], sumX[best])) + 360.0) % 360.0
        val chroma = sumC[best] / weight[best]
        return fromOklch(0.65, chroma, hue)
    }

    /** Builds the role colours for [seed]; never throws, returns null for an invalid seed. */
    fun scheme(seed: Int?, night: Boolean): MonetScheme? {
        seed ?: return null
        val lch = toOklch(seed)
        val hue = lch[2]
        val chroma = lch[1].coerceIn(0.10, 0.17)
        val background = if (night) NIGHT_BACKGROUND else LIGHT_BACKGROUND
        val accent = guarded(if (night) 0.76 else 0.55, chroma, hue, background)
        val accentL = toOklch(accent)[0]
        val step = if (night) 0.06 else -0.06
        val pressed = fromOklch((accentL + step).coerceIn(0.2, 0.95), chroma, hue)
        val rollover = fromOklch((accentL + 2 * step).coerceIn(0.2, 0.95), chroma, hue)
        val gradientStart = fromOklch((accentL + 0.08).coerceIn(0.2, 0.95), chroma * 0.9, hue)
        val tint = fromOklch(0.96, min(lch[1], 0.035), hue)
        val onAccent = if (contrast(accent, 0xFF000000.toInt()) >= contrast(accent, 0xFFFFFFFF.toInt())) {
            0xFF000000.toInt()
        } else {
            0xFFFFFFFF.toInt()
        }
        return MonetScheme(seed, accent, pressed, rollover, gradientStart, tint, onAccent, night)
    }

    /** Moves lightness away from [background] until WCAG contrast is at least [MIN_CONTRAST]. */
    private fun guarded(startL: Double, chroma: Double, hue: Double, background: Int): Int {
        val towardsLight = background == NIGHT_BACKGROUND
        var l = startL
        var color = fromOklch(l, chroma, hue)
        var guard = 0
        while (contrast(color, background) < MIN_CONTRAST && guard++ < 60) {
            l = if (towardsLight) min(0.98, l + 0.01) else max(0.02, l - 0.01)
            color = fromOklch(l, chroma, hue)
        }
        return color
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    fun lerp(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return (a + (b - a) * f).toInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun luminance(argb: Int): Double {
        fun lin(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) +
            0.0722 * lin(argb and 0xFF)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    // --- Oklab ------------------------------------------------------------------------------

    /** Returns [L, C, hueDegrees]. */
    internal fun toOklch(argb: Int): DoubleArray {
        fun lin(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = lin((argb shr 16) and 0xFF)
        val g = lin((argb shr 8) and 0xFF)
        val b = lin(argb and 0xFF)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val bigL = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        val chroma = sqrt(a * a + bb * bb)
        val hue = (Math.toDegrees(atan2(bb, a)) + 360.0) % 360.0
        return doubleArrayOf(bigL, chroma, hue)
    }

    /** Converts to opaque sRGB, shrinking chroma until the colour is inside the sRGB gamut. */
    internal fun fromOklch(l: Double, chroma: Double, hue: Double): Int {
        var c = chroma
        repeat(24) {
            val rgb = oklchToLinear(l, c, hue)
            if (rgb.all { it in -0.0005..1.0005 }) return pack(rgb)
            c *= 0.9
        }
        return pack(oklchToLinear(l, 0.0, hue))
    }

    private fun oklchToLinear(l: Double, c: Double, hue: Double): DoubleArray {
        val rad = Math.toRadians(hue)
        val a = c * cos(rad)
        val b = c * sin(rad)
        val l_ = l + 0.3963377774 * a + 0.2158037573 * b
        val m_ = l - 0.1055613458 * a - 0.0638541728 * b
        val s_ = l - 0.0894841775 * a - 1.2914855480 * b
        val ll = l_ * l_ * l_
        val mm = m_ * m_ * m_
        val ss = s_ * s_ * s_
        return doubleArrayOf(
            4.0767416621 * ll - 3.3077115913 * mm + 0.2309699292 * ss,
            -1.2684380046 * ll + 2.6097574011 * mm - 0.3413193965 * ss,
            -0.0041960863 * ll - 0.7034186147 * mm + 1.7076147010 * ss,
        )
    }

    private fun pack(linear: DoubleArray): Int {
        fun enc(v: Double): Int {
            val x = v.coerceIn(0.0, 1.0)
            val s = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
            return (s * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (enc(linear[0]) shl 16) or (enc(linear[1]) shl 8) or enc(linear[2])
    }
}
