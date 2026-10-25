package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import app.orchard.tv.dream.AerialDream

/**
 * The TV's own screensaver: Android's dream where the firmware uses it (TCL, most Android TVs), or
 * the maker's own app where it runs outside it (Xiaomi HyperOS, XGIMI's projectors).
 */
object SystemScreensaver {
    private const val TAG = "AerialScreensaver"
    private const val COMPONENTS = "screensaver_components"
    private const val DEFAULT_COMPONENT = "screensaver_default_component"

    /** Xiaomi's screensaver, which HyperOS starts by its own timer (screen_saver_timeout), not as a dream. */
    const val XIAOMI = "com.xiaomi.mitv.hyper.screensaver"

    /** XGIMI's own scenes (GMUI): an activity, which is what the projector shows on its own. */
    const val XGIMI = "com.xgimi.atmosphere"
    private const val XGIMI_CLASS = "com.xgimi.atmosphere.view.ScreensaverActivity"
    private const val XGIMI_ACTION = "com.xgimi.atmosphere.switch.screensaver"

    /** The dream XGIMI declares to Android, in the app the vendor ships beside the scenes. */
    const val XGIMI_DREAM = "com.xgimi.screensaver"
    private const val XGIMI_DREAM_CLASS = "com.xgimi.screensaver.service.ScreenSaverDreamService"

    /** Android's dream that starts on demand (what "Start now" in Android's settings opens). */
    private val SOMNAMBULATOR = ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")

    private fun dreamsOn(context: Context) =
        runCatching { Settings.Secure.getInt(context.contentResolver, "screensaver_enabled", 0) == 1 }.getOrDefault(false)

    private fun component(context: Context): String =
        runCatching { Settings.Secure.getString(context.contentResolver, COMPONENTS) }.getOrNull().orEmpty()

    /** Aerial's own dream is the TV's screensaver (tools/install.sh sets it where dreams are on). */
    fun isAerials(context: Context): Boolean =
        dreamsOn(context) && component(context).split(',').firstOrNull() == ComponentName(context, AerialDream::class.java).flattenToString()

    /**
     * The dream the firmware came with, or null where it has none on record. XGIMI's where the
     * projector ships one: the record there (screensaver_default_component) is Android's own
     * build default — the DeskClock's screensaver on GMUI 9 — which is not what the TV shows.
     */
    fun firmwareDream(context: Context): String? {
        xgimiDream(context)?.let { return it }
        return runCatching { Settings.Secure.getString(context.contentResolver, DEFAULT_COMPONENT) }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.startsWith(context.packageName + "/") }
    }

    /** XGIMI's dream, where the projector ships it; null elsewhere. */
    fun xgimiDream(context: Context): String? {
        if (runCatching { context.packageManager.getPackageInfo(XGIMI_DREAM, 0) }.isFailure) return null
        return ComponentName(XGIMI_DREAM, XGIMI_DREAM_CLASS).flattenToString()
    }

    /** Starts the TV's dream now, whichever it is; false where that is not possible. */
    fun startDream(context: Context): Boolean {
        if (!dreamsOn(context)) return false
        val intent = Intent(Intent.ACTION_MAIN).setComponent(SOMNAMBULATOR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.packageManager.resolveActivity(intent, 0) }.getOrNull() == null) return false
        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "could not start the dream", it) }.isSuccess
    }

    /**
     * Starts the firmware's own screensaver now, for Back with "System" chosen: XGIMI's scenes where
     * the projector has them, Xiaomi's app where it is installed, else the dream. False where none
     * can be started.
     */
    fun startFirmware(context: Context): Boolean {
        if (startXgimi(context)) return true
        val pm = context.packageManager
        val xiaomi = runCatching { pm.getPackageInfo(XIAOMI, 0) }.isSuccess
        if (xiaomi) {
            // Its MainActivity is the screensaver. HyperOS starts it from a protected broadcast with
            // these extras; without them it opens with its style picker over it (and an ad).
            // MiTV-MFTR0, screensaver 3.04.115.
            val saver = Intent(Intent.ACTION_MAIN).setClassName(XIAOMI, "$XIAOMI.MainActivity")
                .putExtra("show_saver_list", false)
                .putExtra("show_widget_view", false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(saver) }.isSuccess) return true
            Log.w(TAG, "could not open Xiaomi's screensaver")
        }
        return startDream(context)
    }

    /**
     * XGIMI's screensaver on the screen now. GMUI has no Somnambulator to start a dream with, so
     * there is no starting the vendor's dream: the scenes are an exported activity of their own
     * (com.xgimi.atmosphere, single task), and Back leaves them.
     */
    private fun startXgimi(context: Context): Boolean {
        if (runCatching { context.packageManager.getPackageInfo(XGIMI, 0) }.isFailure) return false
        val saver = Intent(XGIMI_ACTION).setClassName(XGIMI, XGIMI_CLASS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(saver) }
            .onSuccess { Log.i(TAG, "started XGIMI's screensaver") }
            .onFailure { Log.w(TAG, "could not open XGIMI's screensaver", it) }
            .isSuccess
    }
}
