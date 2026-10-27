package app.orchard.tv.ui.home

import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.viewinterop.AndroidView
import app.orchard.tv.system.DiagLog
import app.orchard.tv.system.XgimiTvSource
import app.orchard.tv.ui.theme.Motion
import kotlinx.coroutines.delay

/**
 * A TV input's picture behind Home (issue #267): the set-top box on HDMI 1, the way a TV's own home
 * screen shows it. Adapted from @chikwok's tv-preview branch, verified there on Xiaomi MiTV-MFTR0
 * (Android 14): a passthrough TvView tuned to the input.
 *
 * What a passthrough picture is shapes the rest: a hardware layer, so nothing can blur it or copy it
 * (the glass and the grid use a dark built-in picture instead, Wallpapers.SOURCE_PICTURE); one at a
 * time, so the view is let go whenever Home is covered: by an app (the input opened full screen
 * among them), or by the screensaver, whose film fights it for the same video path on MediaTek TVs
 * and wins. The picture plays on as long as Home is in front — no key stops it — and comes back by
 * itself a moment after the screensaver has gone.
 */
@Composable
fun SourceWallpaper(
    inputId: String,
    /** Home is in front with no screensaver over it. */
    show: Boolean,
    /** How far the picture shows through: HomeBackground draws the dark picture under it until it comes. */
    cover: Animatable<Float, *>,
) {
    // On XGIMI the picture is on the chip's own plane: the session's callbacks say nothing about
    // it, and the service announces a tune by sending WEAK_SIGNAL first and VIDEO_AVAILABLE
    // right behind it (measured in HdmiSession.onTune). So the picture counts as there from the
    // start on that firmware, and no reason sends the view back to the backoff — resetting it
    // hands the surface back and the picture goes.
    val xgimi = XgimiTvSource.available()
    var available by remember(inputId) { mutableStateOf(xgimi) }
    // Bumped to tune from scratch: an input that had nothing to show is asked again.
    var generation by remember(inputId) { mutableIntStateOf(0) }
    // Whether the picture may be taken up now: a press of Back no longer puts it away, but the
    // screensaver does, and the video path it held is left alone for a moment after that.
    var ready by remember(inputId) { mutableStateOf(false) }
    // Whether the picture was up before: only then is the wait after the screensaver needed. A
    // cold start puts it up at once, as before.
    var wasUp by remember(inputId) { mutableStateOf(false) }
    var tries by remember(inputId) { mutableIntStateOf(0) }
    var weak by remember(inputId) { mutableStateOf(false) }

    LaunchedEffect(show) {
        if (!show) {
            ready = false
            return@LaunchedEffect
        }
        if (wasUp) delay(RESUME_DELAY_MS)
        wasUp = true
        ready = true
    }
    LaunchedEffect(available, show, ready) {
        when {
            // Covering (an app, the screensaver) or still waiting: the dark picture under it, at once.
            !show || !ready -> cover.snapTo(0f)
            available -> cover.animateTo(1f, tween(900, easing = Motion.EaseOut))
            else -> cover.animateTo(0f, tween(400, easing = Motion.EaseOut))
        }
    }
    // A box woken by the same press takes tens of seconds to put a picture on HDMI, and an input
    // asked too early says WEAK_SIGNAL and is never asked again by itself. Ask again on a backoff
    // for a minute and a half, then leave it (a port with nothing on it).
    LaunchedEffect(weak, show, ready, generation) {
        if (!weak || !show || !ready || tries >= RETRY_DELAYS_MS.size) return@LaunchedEffect
        delay(RETRY_DELAYS_MS[tries])
        tries++
        weak = false
        generation++
    }
    if (!show || !ready) {
        // Covered (an app, the screensaver) or waiting out the moment after it: the view is let go
        // and Home starts from a clean slate when the picture comes back.
        LaunchedEffect(Unit) { available = xgimi; tries = 0; weak = false }
        return
    }
    AndroidView(
        factory = { context ->
            TvView(context).apply {
                // A TvView takes focus by default and would take the D-pad from the tiles.
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setCallback(object : TvView.TvInputCallback() {
                    override fun onVideoAvailable(id: String) {
                        available = true
                        tries = 0
                        weak = false
                    }

                    override fun onVideoUnavailable(id: String, reason: Int) {
                        DiagLog.i("input wallpaper: no picture from $id (reason $reason)")
                        if (xgimi) return
                        available = false
                        weak = reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL
                    }

                    override fun onConnectionFailed(id: String) {
                        DiagLog.i("input wallpaper: could not connect to $id")
                        available = false
                    }

                    override fun onDisconnected(id: String) {
                        available = false
                    }
                })
            }
        },
        modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
        update = { view ->
            val key = "$inputId#$generation"
            if (view.tag != key) {
                view.tag = key
                view.reset()
                // Tuned before the view is attached, the picture stays black.
                view.postDelayed({
                    if (view.tag == key) view.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId))
                }, TUNE_DELAY_MS)
            }
        },
        onRelease = { view ->
            view.tag = null
            view.reset()
            // The chip goes back to what Android draws on, or it would keep the port's picture on
            // screen over whatever comes next.
            if (XgimiTvSource.available()) XgimiTvSource.releaseSource()
        },
    )
    // XGIMI's chip: the picture is on a hardware plane, and the chip only puts it on screen once
    // it is pointed at the port. It takes a session on the input for that (without one the
    // picture stays black whatever the chip is told), so this waits for the tune to have landed.
    // The switch is not idempotent — asking again while the chip already sits on the port drops
    // it back to no source — so the chip is read first and only switched when it is elsewhere.
    LaunchedEffect(available, generation, inputId) {
        if (!available) return@LaunchedEffect
        if (!XgimiTvSource.available()) return@LaunchedEffect
        val port = XgimiTvSource.sourceOf(inputId)
        if (port <= 0) {
            DiagLog.i("input wallpaper: $inputId is not a port of the chip")
            return@LaunchedEffect
        }
        // The surface has to be with the input service before the chip has anything to show.
        delay(SWITCH_DELAY_MS)
        var on = false
        for (attempt in 1..SWITCH_TRIES) {
            XgimiTvSource.ensureSource(port)
            // The chip takes a moment to settle on the port; until then it reports no source.
            delay(SETTLE_DELAY_MS)
            on = XgimiTvSource.currentInputSource() == port
            if (on) break
            DiagLog.i("input wallpaper: chip not on port $port (try $attempt)")
        }
        DiagLog.i("input wallpaper: chip on port $port = $on, signal ${XgimiTvSource.signalStatus()}")
        // The chip's own audio path (setVideoUnmute) is deliberately not touched: it unmutes the
        // hardware plane's sound straight onto the built-in speaker, and with an external audio
        // device chosen (a sound bar, Bluetooth, line out) the picture's sound then comes out of
        // the projector itself while the external one goes silent. Left alone, the input session's
        // audio goes the ordinary way and follows whatever output the TV is set to.
    }
}

private const val TUNE_DELAY_MS = 800L
/** How long the surface needs to be with the input service before the chip has something to show. */
private const val SWITCH_DELAY_MS = 600L
/** How long the chip takes to settle on the port it was pointed at. */
private const val SETTLE_DELAY_MS = 1500L
/** How many times the chip is asked for the port before it is left alone. */
private const val SWITCH_TRIES = 4
/**
 * How long the input is left alone after the screensaver has gone, before the picture is asked for
 * again: the screensaver's film has just let go of the video path, and the chip wants a moment
 * before it is pointed at a port again. One second, as asked: the chip needs no longer than that
 * here, and the switch below is tried again if it is not on the port yet.
 */
private const val RESUME_DELAY_MS = 1_000L
private val RETRY_DELAYS_MS = longArrayOf(3_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L)
