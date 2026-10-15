package dev.kifranei.ampp.monet

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import dev.amenhancer.plugin.api.PluginContext
import dev.amenhancer.plugin.api.PluginObservation
import dev.amenhancer.plugin.api.PluginObserver

/**
 * Observes the host's MediaSession metadata (public framework API, so it needs no host symbols) and hands the
 * cover to the background pipeline. The main-thread part is a few bundle reads and one executor submit.
 */
internal class CoverMetadataSource(private val pipeline: CoverColorPipeline) {
    fun install(context: PluginContext): AutoCloseable {
        val method = MediaSession::class.java.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
        return context.hooks.observe(method, object : PluginObserver {
            override fun after(call: PluginObservation) {
                val metadata = call.arguments.firstOrNull() as? MediaMetadata ?: return
                val key = coverKey(
                    mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                    album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                    albumArtist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                        ?: metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                )
                val art: Bitmap? = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                if (art == null || key == null) return
                pipeline.submit(key, art)
            }
        })
    }

    companion object {
        /** Album + artist identify a cover across tracks of one album; the media id is the fallback. */
        fun coverKey(mediaId: String?, album: String?, albumArtist: String?): String? = when {
            !album.isNullOrBlank() -> "album:$album|${albumArtist.orEmpty()}"
            !mediaId.isNullOrBlank() -> "id:$mediaId"
            else -> null
        }
    }
}
