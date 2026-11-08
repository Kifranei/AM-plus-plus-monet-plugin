package dev.kifranei.ampp.monet

import android.graphics.Bitmap

/** Runs on the background executor only. Never mutates or recycles the host's bitmap. */
internal object ArtworkSampler {
    private const val EDGE = 24

    fun pixels(artwork: Any): IntArray? {
        val source = artwork as? Bitmap ?: return null
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        val temporary = ArrayList<Bitmap>(2)
        try {
            // Hardware bitmaps cannot be read directly; a software copy of a downscale is cheap.
            var readable = source
            if (source.config == Bitmap.Config.HARDWARE) {
                readable = source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                temporary += readable
            }
            val small = Bitmap.createScaledBitmap(readable, EDGE, EDGE, true)
            if (small !== readable) temporary += small
            val out = IntArray(EDGE * EDGE)
            small.getPixels(out, 0, EDGE, 0, 0, EDGE, EDGE)
            return out
        } finally {
            temporary.forEach { if (it !== source) it.recycle() }
        }
    }
}
