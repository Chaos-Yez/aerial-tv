package app.orchard.tv.system

/**
 * XGIMI's own TV middleware, reached by reflection.
 *
 * The class `com.xgimi.gmpf.api.GmTvManager` lives in `/system/framework/com.xgimi.api.jar`, which
 * is in the boot class path, so it is on every XGIMI firmware and nowhere else — it cannot be a
 * compile-time dependency. Its native half is `/system/lib/libgmtvmanager_jni.xgimi.so` (32-bit),
 * loaded by the class itself; the TV runs 32-bit (`armeabi-v7a`), so an app there is 32-bit too
 * and the library loads.
 *
 * Why it is needed: the standard TV-input way to show a passthrough picture — a session on the
 * input, tuned to its channel URI, with the TvView's surface handed over — is not enough here.
 * The MediaTek TV input HAL puts the frame on a sideband display plane and the chip keeps
 * showing whatever input it had selected, so a tuned session draws black. Switching the chip to
 * the port (`switchInputSource`) and telling it where on the screen the picture goes
 * (`setDisplayArea`) is what makes the signal appear; the vendor's own player does exactly that
 * (`InputActivity.setDisplayArea`, measured on `pig_chivalrous`, GMUI 9).
 */
object XgimiTvSource {

    private const val GM_TV_MANAGER = "com.xgimi.gmpf.api.GmTvManager"

    /** The input source number the chip counts HDMI 1 as. */
    const val HDMI1 = 1

    /** The source Android itself draws on: no port, so the chip shows nothing of its own. */
    const val ANDROID_SOURCE = 0

    /** The signal status the chip reports for a port with a picture on it. */
    const val SIGNAL_OK = 3

    private fun managerClass(): Class<*>? =
        runCatching { Class.forName(GM_TV_MANAGER) }.getOrNull()

    private fun manager(): Any? = managerClass()?.let { c ->
        runCatching { c.getMethod("getInstance").invoke(null) }.getOrNull()
    }

    /** Whether the firmware has the middleware at all: XGIMI's, and nowhere else. */
    fun available(): Boolean = manager() != null

    private fun callBoolean(name: String, vararg args: Pair<Class<*>, Any>): Boolean {
        val c = managerClass() ?: return false
        val m = manager() ?: return false
        return runCatching {
            c.getMethod(name, *args.map { it.first }.toTypedArray())
                .invoke(m, *args.map { it.second }.toTypedArray()) as? Boolean ?: false
        }.getOrDefault(false)
    }

    /** [int] as the reflection type of a native `int` parameter. */
    private val intType: Class<*> = Int::class.javaPrimitiveType!!

    private fun callInt(name: String): Int {
        val c = managerClass() ?: return -1
        val m = manager() ?: return -1
        return runCatching {
            (c.getMethod(name).invoke(m) as? Number)?.toInt() ?: -1
        }.getOrDefault(-1)
    }

    /**
     * Points the chip at an input source. The numbers are the chip's own: HDMI 1 is
     * [HDMI1], and the input service's names (`Hdmi1TvInputService`, `Hdmi2TvInputService`, …)
     * count the same way.
     *
     * Not idempotent, and it shows: called while the chip already sits on that port it drops the
     * chip back to no source and the picture goes away (measured on `pig_chivalrous`). So read
     * [currentInputSource] first, and only call this when it is somewhere else — which is what
     * [ensureSource] does.
     */
    fun switchInputSource(source: Int): Boolean =
        callBoolean("switchInputSource", intType to source)

    /**
     * Puts the chip on [source]: switches only when it is somewhere else, and reports whether the
     * chip was already there or has just been switched to it.
     *
     * Not idempotent — see [switchInputSource] — so the chip is read first.
     */
    fun ensureSource(source: Int): Boolean {
        if (source <= 0) return false
        if (currentInputSource() == source) return true
        return switchInputSource(source)
    }

    /**
     * Puts the chip back on the source Android itself draws on, so the input's picture stops
     * covering Home. Only when it is on a port: the switch is not idempotent, and asking for the
     * source Android is already on would flip the chip the other way.
     */
    fun releaseSource(): Boolean {
        if (currentInputSource() == ANDROID_SOURCE) return true
        return switchInputSource(ANDROID_SOURCE)
    }

    /**
     * Where on the screen the picture goes, in screen pixels. A rectangle that is not the whole
     * screen is honoured, but only right after a source switch: called later it drops the chip
     * back to no source and the picture goes away (measured).
     *
     * Left alone for the whole-screen picture. Asking the chip for the full screen resets its
     * signal and the picture goes black, while the chip draws the whole screen by itself once it
     * is on the port (measured).
     */
    fun setDisplayArea(left: Int, top: Int, width: Int, height: Int): Boolean =
        callBoolean(
            "setDisplayArea",
            intType to left,
            intType to top,
            intType to width,
            intType to height,
        )

    /** The source the chip is on, or -1 when it cannot be read. */
    fun currentInputSource(): Int = callInt("getCurrentInputSource")

    /** The port's signal status; [SIGNAL_OK] means there is a picture on it. */
    fun signalStatus(): Int = callInt("getSignalStatus")

    /** Unmutes the source's audio, which the chip mutes for a source it is not showing. */
    fun unmute(source: Int): Boolean =
        callBoolean("setVideoUnmute", intType to source)

    /**
     * The chip's source number for a TV input id, or 0 when the input is not one of XGIMI's HDMI
     * ports. The input service names its ports `Hdmi1TvInputService`, `Hdmi2TvInputService` and
     * so on, and the chip counts them the same way.
     */
    fun sourceOf(inputId: String): Int {
        val name = Regex("Hdmi(\\d+)", RegexOption.IGNORE_CASE).find(inputId)?.groupValues?.get(1)
        return name?.toIntOrNull()?.takeIf { it > 0 } ?: 0
    }
}
