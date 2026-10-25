package app.orchard.tv.ui.home

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
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.wallpaper.LocalVideo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The size the film is made at: 720p, stretched to the panel. */
private const val FILM_WIDTH = 1280
private const val FILM_HEIGHT = 720

/** How long the film is given to show its first frame before it is made at its own size. */
private const val FIRST_FRAME_MS = 3_500L

/**
 * The owner's own film, played behind Home (a wallpaper of its own, "video:<id>").
 *
 * The film sits in a SurfaceView under the window, as the aerial films do
 * (ui/home/LiveWallpaper.kt): Compose draws neither the picture nor the plate over it, so the
 * frame budget is the same as on a still Home. The film's first frame is the wallpaper's picture
 * until it moves, and the glass over the grid is made from it.
 *
 * [playing] says whether the film moves now: with "pause on tiles" on, it stops as soon as the
 * tiles take the focus; with it off, it plays on. The film is not released when it stops, so the
 * frame it stopped on stays on the screen.
 *
 * The film plays whole, from its beginning to its end, and the sound fades in when the film starts
 * and out when it stops, so it never begins or ends abruptly.
 *
 * The film is made at 720p and stretched to the panel: the picture the chip has to mix with the
 * window over is two thirds less than at the panel's own size, which is what the film needs to keep
 * up. A chip that will not take it shows no frame at all, and the film is then made at its own
 * size.
 *
 * All of this is the film alone: with any other wallpaper chosen, none of it is here, and Home
 * costs what it always costs.
 */
@Composable
fun VideoWallpaper(
    video: LocalVideo,
    /** Whether the film moves now. */
    playing: Boolean,
    /** Whether it plays with its sound. */
    sound: Boolean,
) {
    val context = LocalContext.current
    // The sound fades only when the film is started or stopped. The film's own going round again
    // is neither: the sound goes on as it is, with no fade in or out at the join.
    val playingNow by rememberUpdatedState(playing)
    val soundNow by rememberUpdatedState(sound)
    val volume = remember(video.id) { Animatable(0f) }
    // Whether the film is made at 720p. Changing it means a new surface and a new player, so
    // the decoder is made again for it.
    var scaled by remember(video.id) { mutableStateOf(true) }
    val rendered = remember(video.id, scaled) { mutableStateOf(false) }
    val player = remember(video.id, scaled) {
        val p = ExoPlayer.Builder(context).build()
        p.apply {
            setMediaItem(
                MediaItem.Builder()
                    .setUri(video.file.toUri())
                    .setMediaId(video.id)
                    .build(),
            )
            repeatMode = Player.REPEAT_MODE_ALL
            // Cut to fill the screen: a wallpaper is the whole picture.
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            // The film starts again from its beginning: the sound goes on as it is, with no fade
            // in and no fade out at the join. Told twice by the player (the item changes, and the
            // position jumps), so it is said once.
            var lastRound = 0L
            fun roundAgain() {
                val now = SystemClock.uptimeMillis()
                if (now - lastRound < 500) return
                lastRound = now
                DiagLog.i("wallpaper video: the film goes round again")
                if (playingNow && soundNow) p.volume = 1f
            }
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    rendered.value = true
                    DiagLog.i("wallpaper video: first frame at ${if (scaled) "720p" else "full size"}")
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) roundAgain()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    // The same film going round again comes through as an automatic transition
                    // as well; the join must not fade the sound either way.
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                        newPosition.mediaItemIndex == oldPosition.mediaItemIndex
                    ) roundAgain()
                }

                override fun onPlayerError(error: PlaybackException) {
                    DiagLog.i("wallpaper video failed (${error.errorCodeName})")
                }
            })
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    // A chip that will not make the film at 720p shows nothing at all: watch for the first frame,
    // and if it does not come, make the film at the panel's own size.
    LaunchedEffect(player, playing) {
        if (!playing || rendered.value) return@LaunchedEffect
        val got = withTimeoutOrNull(FIRST_FRAME_MS) { snapshotFlow { rendered.value }.first { it } }
        if (got == null && scaled) {
            DiagLog.i("wallpaper video: no frame at 720p; the film at its own size")
            scaled = false
        }
    }

    // The sound comes in and goes out with the film, rather than being cut off at either end.
    LaunchedEffect(player, playing, sound) {
        val want = if (playing && sound) 1f else 0f
        if (volume.value != want) {
            // Out first, so a pause does not cut the last word off; in slowly, so it does not
            // startle anyone.
            volume.animateTo(want, tween(if (want > volume.value) 900 else 500, easing = Motion.EaseOut))
        }
        if (playing) {
            // Its decoder was let go for the screensaver or an app.
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.playWhenReady = true
            DiagLog.i("wallpaper video plays")
        } else {
            player.playWhenReady = false
            DiagLog.i("wallpaper video pauses")
        }
    }
    // The volume follows the fade, every step of it.
    LaunchedEffect(player, volume) {
        snapshotFlow { volume.value }.collect { player.volume = it }
    }
    // The sound on or off is the owner's: with it off, the film is silent whatever the fade does.
    LaunchedEffect(sound) {
        if (!sound) volume.snapTo(0f)
    }

    // Keyed by the player, so a new film gets a surface of its own.
    key(player) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).also { sv ->
                    // The film is made at 720p and stretched to the panel: two thirds less for the
                    // chip to mix with the window over. Asked for before the surface is made, so
                    // the very first buffer is already that size.
                    if (scaled) sv.holder.setFixedSize(FILM_WIDTH, FILM_HEIGHT)
                    sv.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            DiagLog.i("wallpaper video: surface up, format $format (${width}x$height)")
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                    })
                    player.setVideoSurfaceView(sv)
                }
            },
            onRelease = { player.clearVideoSurfaceView(it) },
        )
    }
    // A little shade over the film, top and bottom: the clock and the tiles' names sit there,
    // and a bright film would swallow them. Only the two bands, not the whole picture: a shade
    // over the whole picture is blended again over every frame of the film, and the chip is busy
    // with the film itself.
    Column(Modifier.fillMaxSize()) {
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
