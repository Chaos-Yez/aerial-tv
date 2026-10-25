package app.orchard.tv.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A film the owner uploaded, to be played behind Home.
 *
 * The film plays whole, from its beginning to its end: the owner picks a file and it is on the
 * wall, with nothing to trim and no length to keep an eye on.
 */
data class LocalVideo(
    val id: String,
    val name: String,
    val file: File,
)

/**
 * The owner's own films for the desktop wallpaper. Separate from the screensaver's pool
 * (ScreensaverVideos): those play when the TV is idle, these play behind Home.
 *
 * Nothing is bundled and nothing is fetched from a third-party host: the films come from the
 * phone page, and are played from the TV's own storage.
 */
object LocalVideos {
    /** One film may be this big. The phone page says so, and asks for no more. */
    const val MAX_FILE_BYTES = 200L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_VIDEOS = 8
    private val idPattern = Regex("[a-f0-9]{32}")
    private const val TAG = "AerialLocalVideos"

    private fun directory(context: Context) = File(context.filesDir, "wallpaper-videos").apply { mkdirs() }

    /** Call off the main thread, including when assembling the phone page's state. */
    @Synchronized
    fun all(context: Context): List<LocalVideo> = directory(context).listFiles().orEmpty()
        .filter { it.isDirectory && idPattern.matches(it.name) }
        .mapNotNull { dir ->
            runCatching {
                val file = File(dir, "video.mp4").takeIf { it.isFile && it.length() > 0 } ?: return@runCatching null
                val info = JSONObject(File(dir, "info.json").readText())
                LocalVideo(
                    id = dir.name,
                    name = info.getString("name"),
                    file = file,
                )
            }.getOrNull()
        }.sortedBy { it.id }

    /**
     * The upload is already on disk and is taken over. Validate it first, and publish a complete
     * directory, so a film that cannot be played never shows up on the desktop.
     *
     * The film plays as it is: there is no trim to choose and no length to keep to.
     */
    @Synchronized
    fun add(
        context: Context,
        upload: File,
        name: String,
    ): LocalVideo? {
        val root = directory(context)
        // Only abandoned imports, never a film that has been published or one being copied now.
        root.listFiles()?.filter { it.name.startsWith("import-") && it.lastModified() < System.currentTimeMillis() - 3_600_000 }
            ?.forEach { it.deleteRecursively() }
        val size = upload.length()
        val films = all(context)
        if (size <= 0 || size > MAX_FILE_BYTES || films.size >= MAX_VIDEOS ||
            films.sumOf { it.file.length() } + size > MAX_TOTAL_BYTES || root.usableSpace < size + 16L * 1024 * 1024) return null
        val title = name.substringAfterLast('/').substringAfterLast('\\').take(120)
        if (title.substringAfterLast('.', "").lowercase() !in setOf("mp4", "mov")) return null
        val probe = probe(upload) ?: return null
        // A wallpaper is a wide picture: a film shot upright is turned on its side by the phone
        // camera, and it would show a person sideways.
        if (!probe.landscape) return null

        val id = UUID.randomUUID().toString().replace("-", "")
        val staging = File(root, "import-$id")
        val target = File(root, id)
        return try {
            check(staging.mkdir())
            // The upload is the phone server's temp file in the cache, on the same volume: moving it
            // saves writing up to 200 MB again on a TV's slow storage. Copy where it cannot move.
            val film = File(staging, "video.mp4")
            if (!upload.renameTo(film)) upload.copyTo(film)
            File(staging, "info.json").writeText(JSONObject().put("name", title).toString())
            check(staging.renameTo(target))
            LocalVideo(id, title, File(target, "video.mp4"))
        } catch (e: Exception) {
            Log.w(TAG, "could not store a wallpaper video", e)
            null
        } finally { staging.deleteRecursively() }
    }

    @Synchronized
    fun delete(context: Context, id: String): Boolean {
        if (!idPattern.matches(id)) return false
        val dir = File(directory(context), id)
        return dir.isDirectory && dir.deleteRecursively()
    }

    /** What the film is: how wide it is, and whether it turns out sideways. */
    data class Probe(val durationMs: Long, val width: Int, val height: Int, val rotation: Int = 0) {
        /**
         * Whether the film is wider than it is tall as it is shown: a film shot upright and turned
         * by the phone is 90° around, and shows sideways on the wall.
         */
        val landscape: Boolean
            get() = when {
                width <= 0 || height <= 0 -> false
                rotation == 90 || rotation == 270 -> height > width
                else -> width > height
            }
    }

    /** What the film is: its length, its size on the screen, and how it is turned. Off the main thread. */
    private fun probe(file: File): Probe? {
        val metadata = MediaMetadataRetriever()
        return try {
            metadata.setDataSource(file.absolutePath)
            val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (duration <= 0L || width <= 0 || height <= 0) null
            else Probe(duration, width, height, rotation)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { metadata.release() }
        }
    }

    /**
     * The film's first frame, at [w]×[h]: the picture on the desktop before the film moves, the
     * frosted glass over it, and the thumbnail on the phone page. Off the main thread.
     */
    fun firstFrame(file: File, w: Int, h: Int, atMs: Long = 0L): Bitmap? = runCatching {
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.absolutePath)
            val us = atMs.coerceAtLeast(0L) * 1000
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                metadata.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
            } else {
                metadata.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            frame?.let {
                if (it.width == w && it.height == h) it else Bitmap.createScaledBitmap(it, w, h, true)
            }
        } finally {
            runCatching { metadata.release() }
        }
    }.onFailure { Log.w(TAG, "could not read a film's first frame", it) }.getOrNull()
}
