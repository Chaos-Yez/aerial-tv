package app.orchard.tv.ui.home

import android.view.SurfaceHolder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.VIDEO_VOLUME_STEPS
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.components.FilmSlot
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.wallpaper.ScreensaverVideo
import app.orchard.tv.wallpaper.ScreensaverVideos
import app.orchard.tv.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext

/** A local, silent playlist, shared by Home and AerialDream. An empty or broken set falls back. */
@Composable
internal fun VideoSaver(steps: SharedFlow<Int>, now: java.time.LocalDateTime, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val films by produceState<List<ScreensaverVideo>?>(null) {
        value = withContext(Dispatchers.IO) { ScreensaverVideos.all(context).shuffled() }
    }
    LocalFilms(films, steps, now, onUnavailable)
}

/**
 * The sound the owner's own videos have in the screensaver: the very switch and the very level
 * they have behind Home ([AerialSettings.videoSound], [AerialSettings.videoVolume]). Silent until
 * the first frames are up, then it fades in, as it does on Home; going round the films again keeps
 * the level as it is, no fade at the join.
 */

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun LocalFilms(films: List<ScreensaverVideo>?, steps: SharedFlow<Int>, now: java.time.LocalDateTime, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        // As AerialSaver does: Home's wallpaper film starts again as Home resumes for a moment
        // under the dream, and would hold the decoder this film needs (TCL MT5879).
        LiveFilm.onHome?.letDecoderGo()
        ExoPlayer.Builder(context).setLoadControl(
            DefaultLoadControl.Builder().setTargetBufferBytes(12 * 1024 * 1024)
                .setBufferDurationsMs(5_000, 15_000, 500, 1_000).build(),
        ).build().apply {
                volume = 0f
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !AerialSettings.videoSound).build()
            repeatMode = Player.REPEAT_MODE_ALL
            // Filled to the edges whatever its shape, exactly as the same film is behind Home
            // (ui/home/VideoWallpaper.kt): with the two made differently, going from the aerials
            // into the screensaver showed the film a shade smaller than it was a moment before.
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        }
    }
    var attempt by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    // Whether this session has had a picture at all. A film that has drawn once is never given up
    // for a join between its own loops: only a film that never drew anything falls back to the
    // photos, which are not what the owner chose.
    var drewOnce by remember { mutableStateOf(false) }
    // One film may fail on a moment's decoder trouble; it is tried again before the photos.
    var retries by remember { mutableIntStateOf(0) }
    // The sound, as loud as the wallpaper's own switch and level say: it comes in behind the
    // picture, which is already moving.
    val sound = AerialSettings.videoSound
    val level = AerialSettings.videoVolume.coerceIn(1, VIDEO_VOLUME_STEPS).toFloat() / VIDEO_VOLUME_STEPS
    val volume = remember { Animatable(0f) }
    val levelNow by rememberUpdatedState(level)

    fun skip(step: Int) {
        if (player.mediaItemCount == 0) return
        if (step < 0) player.seekToPreviousMediaItem() else player.seekToNextMediaItem()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }
    fun failed() {
        playing = false
        // One film: the owner chose videos, so a decoder error is tried again before the screen is
        // handed to the photos. Only when it keeps failing does the screensaver fall back.
        if (player.mediaItemCount <= 1) {
            if (retries < 2) {
                retries++
                DiagLog.i("local screensaver: retry $retries")
                player.prepare()
                player.playWhenReady = true
                attempt++
                return
            }
            onUnavailable(); return
        }
        // Only this session's queue changes. Never delete a film because one decoder rejected it.
        player.removeMediaItem(player.currentMediaItemIndex)
        player.prepare()
        attempt++
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                playing = true
                drewOnce = true
                retries = 0
                DiagLog.i("local screensaver first frame: ${player.currentMediaItem?.mediaId}")
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Not a lost picture: with one film this fires on every loop round (reason REPEAT),
                // and the player stays READY, so nothing would ever set playing back to true. The
                // guard below then took it for a film that never drew and handed the screen to the
                // photos eleven seconds in. The attempt still restarts the guard's clock.
                attempt++
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                // Buffering is not a lost picture either: a loop round buffers for a moment, and
                // clearing this kept a single film "not playing" for good.
                if (playbackState == Player.STATE_READY && player.isPlaying) playing = true
                else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) playing = false
            }
            override fun onPlayerError(error: PlaybackException) {
                DiagLog.i("local screensaver failed: ${error.errorCodeName}")
                failed()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    // The list is read off the main thread, but the film's view is there from the first frame: a
    // SurfaceView added to the dream's window after its first frame never got a surface, and the
    // film played into nothing, a black screen (TCL MT5879, Android 11, issue #609). AerialSaver's
    // view is in the first frame, which is why the aerial screensaver showed.
    LaunchedEffect(films) {
        val list = films ?: return@LaunchedEffect
        DiagLog.i("local screensaver: ${list.size} films")
        if (list.isEmpty()) { onUnavailable(); return@LaunchedEffect }
        player.setMediaItems(list.map { MediaItem.Builder().setUri(it.file.toUri()).setMediaId(it.id).build() })
        // Where Home's own playing of them stopped, when it is not carried on: the film being
        // watched goes on from there rather than from its beginning.
        Wallpapers.videoSpot()?.let { spot ->
            val index = list.indexOfFirst { it.id == spot.first }
            if (index >= 0) player.seekTo(index, spot.second)
        }
        player.prepare()
        player.playWhenReady = true
    }
    // The sound's fade, every step of it, and the track itself when the owner turns it on or off.
    LaunchedEffect(player) { snapshotFlow { volume.value }.collect { player.volume = it } }
    LaunchedEffect(player, sound) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !sound).build()
        // Off: silent at once, whatever the fade was doing.
        if (!sound) volume.snapTo(0f)
    }
    // In once the picture is there. Going round the films again leaves the level alone: no fade at
    // the join either.
    LaunchedEffect(player, playing, sound) {
        if (!playing || !sound) return@LaunchedEffect
        volume.animateTo(levelNow, tween(900, easing = Motion.EaseOut))
    }
    // A step of the level is heard at once: the ten steps are for setting, not for a fade.
    LaunchedEffect(level) {
        if (!playing || !sound || volume.value <= 0f) return@LaunchedEffect
        volume.snapTo(level)
    }
    LaunchedEffect(steps) { steps.collect { skip(it) } }
    // A film that has not drawn anything eight seconds in is a broken one (a stale file, a decoder
    // that would not open); then the queue moves on, or with a single film the retry above runs.
    // Once a picture has been up, this is done with: the joins between loops are its own business.
    LaunchedEffect(attempt, playing, drewOnce, films != null) {
        if (films != null && !playing && !drewOnce) { delay(8_000); if (!playing && !drewOnce) failed() }
    }
    // 极米: 屏保的影片走硬件视频平面，首帧出来之前那个平面是绿的（MTK 没初始化它是绿的）。
    // 跟 AerialSaver 一个做法：盖一层黑，真的出帧了再慢慢放出来——从航拍壁纸切进「我的视频」
    // 屏保时，看到的才是画面淡进来，而不是先绿一下。
    val veil by animateFloatAsState(
        if (playing) 0f else 1f,
        tween(if (playing) 1_500 else 400, easing = Motion.EaseOut),
        label = "local-film-veil",
    )
    Box(Modifier.fillMaxSize()) {
        // Full screen, as the same film is behind Home: the surface is the window's, and the
        // picture is cropped to it rather than letterboxed into a shape of its own.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FilmSlot(ctx).also {
                    // 极米: 与 Home 的壁纸同一个尺寸（720p，就是影片上传时定的那个）。
                    // 两处尺寸一样，从壁纸进屏保、从屏保回桌面，画面才一格都不动。
                    it.surface.holder.setFixedSize(FILM_WIDTH, FILM_HEIGHT)
                    it.surface.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = DiagLog.i("local screensaver surface created")
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) = DiagLog.i("local screensaver surface destroyed")
                    })
                    player.setVideoSurfaceView(it.surface)
                }
            },
            onRelease = { player.clearVideoSurfaceView(it.surface) },
        )
        // The time over the film, as the aerial and photo screensavers have it. Home's own clock is
        // not there to hand over: it only carries these films on when their own wallpaper is the
        // one set, and from any other wallpaper (an input's picture, a photo, the aerials) Home is
        // covered whole and the screensaver brings the time itself.
        // 极米: 屏保里「我的视频」左下角那行字（手机端填的），位置与航拍的地名一样。
        val cap = AerialSettings.videoCaption.ifBlank { null }
        LaunchedEffect(cap) { DiagLog.i("local screensaver caption: ${cap ?: "none"}") }
        // 极米: 只放字，不压暗——那两条黑边是为航拍的亮天空准备的，这里挡住的是自己的影片。
        Labels(cap, now, shade = false)
        if (veil > 0.002f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = veil)))
    }
}
