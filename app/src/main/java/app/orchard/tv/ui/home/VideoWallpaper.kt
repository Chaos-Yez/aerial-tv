package app.orchard.tv.ui.home

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.VideoMode
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.wallpaper.Images
import app.orchard.tv.wallpaper.ScreensaverVideos
import app.orchard.tv.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether the owner's videos are playing this moment, for Home to take the top row's glass away
 * while they do, as it does for the aerial films (ui/home/HomeScreen.kt).
 */
var videosPlaying by mutableStateOf(false)
    private set

/**
 * The videos' player behind Home, while there is one. The screensaver carries it on rather than
 * starting the films again from the beginning: whether they are moving or stopped on a frame, one
 * goes on where the other left off (ui/home/Screensaver.kt). Left and right step between the films
 * while the screensaver is up, as they do over the aerial ones.
 */
object HomeVideos {
    var player: ExoPlayer? = null

    /** One film along, as a key on the pad does over the screensaver's own films. */
    fun skip(step: Int) {
        val p = player ?: return
        if (p.mediaItemCount == 0) return
        if (step < 0) p.seekToPreviousMediaItem() else p.seekToNextMediaItem()
        p.playWhenReady = true
    }
}

/** The size a film is made at: 720p, stretched to the panel. */
private const val FILM_WIDTH = 1280
private const val FILM_HEIGHT = 720

/** How long a film is given to show its first frame before it is made at its own size. */
private const val FIRST_FRAME_MS = 3_500L

/** "Pause on tiles": how still Home must be before the film plays again after a key. */
private const val TILE_IDLE_MS = 3_000L

/**
 * The owner's own videos behind Home (issues #410, #583), sent from the phone page as for the video
 * screensaver: one after another, filling the screen. They move when the aerial wallpaper would
 * (LiveWallpaper): on a still top row, or all the time when set to keep moving. A key that
 * stops them leaves the frame they stopped on as the wallpaper's picture, read off the video plane
 * where the TV allows it and otherwise decoded from the file, which is on the TV.
 *
 * Their sound is the owner's choice and off by default: it fades in as they start and out as they
 * stop, so it never begins or ends abruptly. Going round again is neither a start nor a stop, so
 * the join between the end and the beginning keeps the sound as it is.
 *
 * They are made at 720p and stretched to the panel: the picture the chip has to mix with the window
 * over is two thirds less than at the panel's own size, which is what a film needs to keep up. A
 * chip that will not take it shows no frame at all, and the films are then made at their own size.
 */
@Composable
fun VideoWallpaper(
    moving: Boolean,
    /** False once the screensaver is up or Home is behind an app: the decoder goes at once. */
    keep: Boolean,
    lastInputAt: Long,
    cover: Animatable<Float, *>,
) {
    val context = LocalContext.current
    val mode = AerialSettings.videoMode
    val sound = AerialSettings.videoSound
    val modeNow by rememberUpdatedState(mode)
    val soundNow by rememberUpdatedState(sound)
    val keepNow by rememberUpdatedState(keep)
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    /** The size the player behind [player] was made for. */
    var playerScaled by remember { mutableStateOf(true) }
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    var showing by remember { mutableStateOf(false) }
    var idle by remember { mutableStateOf(false) }
    // 720p, or a film's own size on a chip that will not have it.
    var scaled by remember { mutableStateOf(true) }
    // The sound's own fade, kept apart from the player so the join of a film going round again
    // can leave it alone.
    val volume = remember { Animatable(0f) }
    LaunchedEffect(lastInputAt, moving, mode) {
        idle = false
        if (!moving) return@LaunchedEffect
        // "After 10 seconds still" is how the aerial films do it; the others start sooner.
        delay(if (mode == VideoMode.AfterIdle) START_AFTER_MS else TILE_IDLE_MS)
        idle = true
    }
    val play = moving && (mode == VideoMode.Always || idle)

    fun release() {
        player?.release()
        player = null
        HomeVideos.player = null
        showing = false
        videosPlaying = false
    }

    suspend fun open(): ExoPlayer? {
        val films = withContext(Dispatchers.IO) { ScreensaverVideos.all(context) }
        if (films.isEmpty()) return null
        // Where the picture on screen came from, so moving again does not cut to another place.
        val spot = Wallpapers.videoSpot()
        val first = films.indexOfFirst { it.id == spot?.first }
        var rendered = false
        val built = ExoPlayer.Builder(context).build()
        HomeVideos.player = built
        return built.apply {
            built.volume = 0f
            // The sound, when the owner wants it. Silent, the audio track is not decoded at all,
            // which is a chip's work saved on a TV that never asked for it.
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !soundNow).build()
            repeatMode = Player.REPEAT_MODE_ALL
            // Filled to the edges whatever its shape: a wallpaper has no bars.
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    rendered = true
                    showing = isPlaying
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    showing = isPlaying && rendered
                }

                // A film going round again, or the next film starting, is not a start or a stop:
                // the sound goes on as it is, with no fade in or out at the join.
                var lastJoin = 0L
                fun joinAgain() {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastJoin < 500) return
                    lastJoin = now
                    if (isPlaying && soundNow && volume.value > 0f) built.volume = AerialSettings.videoVolume / 10f
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) joinAgain()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                        newPosition.mediaItemIndex == oldPosition.mediaItemIndex
                    ) joinAgain()
                }

                override fun onPlayerError(error: PlaybackException) {
                    DiagLog.i("video wallpaper failed (${error.errorCodeName})")
                    // One film this TV cannot decode; the others may still play.
                    if (mediaItemCount > 1) {
                        removeMediaItem(currentMediaItemIndex)
                        prepare()
                    }
                }
            })
            setMediaItems(
                films.map { MediaItem.Builder().setUri(it.file.toUri()).setMediaId(it.id).build() },
                first.coerceAtLeast(0),
                if (first >= 0) spot?.second ?: 0L else 0L,
            )
            surface?.let { setVideoSurfaceView(it) }
            prepare()
        }
    }

    LaunchedEffect(play, sound, scaled) {
        // The size the films are made at changed: the decoder is made again for it.
        if (player != null && playerScaled != scaled) release()
        if (play) {
            val p = player ?: open()?.also { player = it; playerScaled = scaled } ?: return@LaunchedEffect
            DiagLog.i("video wallpaper starts (${p.currentMediaItem?.mediaId} at ${p.currentPosition} ms, idle $idle)")
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.playWhenReady = true
            videosPlaying = true
            snapshotFlow { showing }.first { it }
            cover.animateTo(1f, tween(1200, easing = Motion.EaseOut))
            // The sound comes in behind the picture, which is already moving.
            if (soundNow) volume.animateTo(AerialSettings.videoVolume / 10f, tween(900, easing = Motion.EaseOut))
        } else {
            val p = player ?: return@LaunchedEffect
            // Out first, so stopping does not cut the last of it off.
            if (volume.value > 0f) volume.animateTo(0f, tween(500, easing = Motion.EaseOut))
            if (!keepNow) {
                cover.snapTo(0f)
                videosPlaying = false
                release()
                return@LaunchedEffect
            }
            p.pause()
            videosPlaying = false
            DiagLog.i("video wallpaper stops at ${p.currentPosition} ms")
            if (cover.value > 0f) {
                val film = p.currentMediaItem?.mediaId
                val at = p.currentPosition
                val w = surface?.width?.takeIf { it > 0 } ?: Wallpapers.screenW
                val h = surface?.height?.takeIf { it > 0 } ?: Wallpapers.screenH
                val frame = surface?.let { copyFrame(it) }
                    ?: film?.let { id -> withContext(Dispatchers.IO) { fileFrame(context, id, at, w, h) } }
                if (frame != null && film != null) Wallpapers.useLiveFrame(frame, film, at)
                cover.animateTo(0f, tween(if (frame != null) 320 else 600, easing = Motion.EaseOut))
            }
            delay(KEEP_STOPPED_MS)
            release()
        }
    }
    LaunchedEffect(keep) {
        if (!keep && !play && player != null && cover.value == 0f) release()
    }
    // The sound's fade, every step of it, and the track itself when the owner turns it on or off.
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        snapshotFlow { volume.value }.collect { p.volume = it }
    }
    LaunchedEffect(player, sound) {
        val p = player ?: return@LaunchedEffect
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !sound).build()
        // Off: silent at once, whatever the fade was doing.
        if (!sound) volume.snapTo(0f)
    }
    // A step of the sound is heard at once: the ten steps are for setting, not for a fade.
    LaunchedEffect(AerialSettings.videoVolume, sound) {
        if (sound && volume.value > 0f) volume.snapTo(AerialSettings.videoVolume / 10f)
    }
    // A chip that will not make the films at 720p shows no frame at all: watch for the first one,
    // and if it does not come, make them at the panel's own size.
    LaunchedEffect(player, play, scaled) {
        if (!play || showing) return@LaunchedEffect
        val got = withTimeoutOrNull(FIRST_FRAME_MS) { snapshotFlow { showing }.first { it } }
        if (got == null && scaled) {
            DiagLog.i("video wallpaper: no frame at 720p; the films at their own size")
            scaled = false
        }
    }
    DisposableEffect(Unit) { onDispose { release() } }

    Box(Modifier.fillMaxSize()) {
        // Keyed by the size: a film at its own size needs a surface of its own.
        key(scaled) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).also { sv ->
                        // Made at 720p and stretched to the panel: two thirds less for the chip to
                        // mix with the window over. Asked for before the surface is made, so the
                        // very first buffer is already that size.
                        if (scaled) sv.holder.setFixedSize(FILM_WIDTH, FILM_HEIGHT)
                        sv.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = Unit

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                DiagLog.i("video wallpaper: surface up, format $format (${width}x$height)")
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                        })
                        surface = sv
                        player?.setVideoSurfaceView(sv)
                    }
                },
                onRelease = { player?.clearVideoSurfaceView(it); if (surface === it) surface = null },
            )
        }
        // A little shade over the film, top and bottom: the clock and the tiles' names sit there,
        // and a bright film would swallow them. Only the two bands, not the whole picture: a shade
        // over the whole picture is blended again over every frame of the film, and the chip is
        // busy with the film itself. Set to play on whatever is on screen, the film is left as it
        // is: there the owner asked for the picture itself, unshaded.
        if (mode != VideoMode.Always) Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.34f),
                            1f to Color.Transparent,
                        ),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(130.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.42f),
                        ),
                    )
                }
            }
        }
    }
}

/** Frame [atMs] of the owner's video [id], screen-sized and drawable on, or null. */
private fun fileFrame(context: android.content.Context, id: String, atMs: Long, w: Int, h: Int): Bitmap? {
    val film = ScreensaverVideos.all(context).firstOrNull { it.id == id } ?: return null
    val mmr = MediaMetadataRetriever()
    return try {
        mmr.setDataSource(film.file.path)
        val frame = mmr.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        Images.cover(frame, w, h).let {
            if (it.isMutable && it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, true)
        }
    } catch (e: Exception) {
        DiagLog.i("video wallpaper frame not decoded: ${e.javaClass.simpleName}")
        null
    } finally {
        runCatching { mmr.release() }
    }
}
