package ink.enoch.glyphplayer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/** Read once on the playlist worker, so every notification track has metadata and artwork. */
@OptIn(UnstableApi::class)
fun readAudioItem(context: Context, uri: Uri): MediaItem {
    val metadata = MediaMetadata.Builder().setIsPlayable(true).setIsBrowsable(false)
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        metadata.setTitle(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
            .setArtist(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            .setAlbumTitle(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
        retriever.embeddedPicture?.let { data ->
            // Bound each artwork bundle before it crosses MediaController's Binder connection.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size,
                BitmapFactory.Options().apply { inSampleSize = sample })
            if (bitmap != null) {
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output)
                bitmap.recycle()
                metadata.setArtworkData(output.toByteArray(), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
        }
    } catch (_: Exception) {
        // A missing tag/cover should not prevent a playable file entering the queue.
    } finally {
        retriever.release()
    }
    if (metadata.build().title.isNullOrBlank()) {
        var name = uri.lastPathSegment ?: "Unknown track"
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0) ?: name
            }
        } catch (_: Exception) { }
        metadata.setTitle(name)
    }
    return MediaItem.Builder().setUri(uri).setMediaId(uri.toString()).setMediaMetadata(metadata.build()).build()
}
