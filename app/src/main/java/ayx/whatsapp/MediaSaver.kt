package ayx.whatsapp

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

/** Saves media bytes to the public gallery/Downloads via MediaStore (Android 10+). */
object MediaSaver {
    fun save(ctx: Context, bytes: ByteArray, name: String, type: String): Boolean {
        return try {
            val resolver = ctx.contentResolver
            val (collection, relPath, mime) = when (type) {
                "image", "sticker" -> Triple(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    Environment.DIRECTORY_PICTURES + "/AyX WhatsApp",
                    "image/jpeg"
                )
                "video" -> Triple(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    Environment.DIRECTORY_MOVIES + "/AyX WhatsApp",
                    "video/mp4"
                )
                "audio" -> Triple(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    Environment.DIRECTORY_MUSIC + "/AyX WhatsApp",
                    "audio/ogg"
                )
                else -> Triple(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    Environment.DIRECTORY_DOWNLOADS + "/AyX WhatsApp",
                    "application/octet-stream"
                )
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            }
            val uri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }
}
