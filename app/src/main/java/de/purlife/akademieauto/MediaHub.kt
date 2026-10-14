package de.purlife.akademieauto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Tracks what's playing in any frame of the app's WebViews (reported by [Web.MEDIA_JS]) and sends play/pause/seek
 * commands back to that frame. Feeds the media notification in [PlaybackService].
 */
object MediaHub {
    data class State(
        val frameId: String,
        val playing: Boolean,
        val ended: Boolean,
        val title: String,
        val artist: String,
        val positionMs: Long,
        val durationMs: Long,
        /** Which video this is, the same in the phone's and the car's WebView (see [Web.MEDIA_JS]). */
        val key: String,
        val updatedAt: Long,
        /** The WebView it plays in (identity hash), see [pageChanged]. */
        val owner: Int,
    )

    private val main = Handler(Looper.getMainLooper())
    private val webViews = mutableListOf<WeakReference<WebView>>()
    private val listeners = mutableSetOf<() -> Unit>()

    var current: State? = null
        private set

    private val ZOOMS = listOf(1f, 4f / 3, 16f / 9)

    /** How far the album-art stills are zoomed in, see [Web.MEDIA_JS]. Cycles through [ZOOMS]. */
    @Volatile
    var zoom = ZOOMS[0]
        private set

    /** Blank title/artist in the media session, so Android Auto's text doesn't cover the picture. */
    var hideCarText = false
        private set

    fun toggleCarText() {
        hideCarText = !hideCarText
        listeners.toList().forEach { it() }
    }

    /** Whether the album art shows stills of the video at all (they cost a little battery); off by default. */
    @Volatile
    var stillsOn = false
        private set

    fun toggleStills() {
        stillsOn = !stillsOn
        if (!stillsOn) still = null
        listeners.toList().forEach { it() }
    }

    fun nextZoom() {
        zoom = ZOOMS[(ZOOMS.indexOf(zoom) + 1) % ZOOMS.size]
    }

    /** Latest still of the current frame's video, see [Bridge.frame]. */
    var still: Bitmap? = null
        private set

    fun register(webView: WebView) {
        webViews.removeAll { it.get() == null }
        webViews += WeakReference(webView)
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** "play", "pause", "skip:<seconds>" or "seek:<seconds>", sent to the frame that last reported. */
    fun command(command: String) {
        val frameId = current?.frameId ?: return
        val js = "window.__aaMedia && window.__aaMedia(${JSONObject.quote(frameId)}, ${JSONObject.quote(command)})"
        webViews.forEach { it.get()?.evaluateJavascript(js, null) }
    }

    /** [webView] went to another page, so its video stopped (without reporting a pause). */
    fun pageChanged(webView: WebView) {
        val state = current ?: return
        if (state.owner != System.identityHashCode(webView) || !state.playing) return
        current = state.copy(playing = false)
        listeners.toList().forEach { it() }
    }

    /** Returns true when the video just finished (see [Progress.update]). */
    private fun onUpdate(state: State, pageUrl: String?): Boolean {
        val previous = current
        // Follow whichever frame is playing; ignore pauses from frames that aren't the current one.
        if (!state.playing && previous != null && previous.frameId != state.frameId) return false
        if (previous?.frameId != state.frameId) still = null
        current = state
        val finished = Progress.update(state, pageUrl)
        listeners.toList().forEach { it() }
        return finished
    }

    /** The JavaScript interface "AAMedia" of one WebView (see [Web.MEDIA_JS]); knows the page it reports from. */
    class Bridge(webView: WebView) {
        private val webView = WeakReference(webView)

        @JavascriptInterface
        fun update(frameId: String, json: String) {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return
            val state = State(
                frameId = frameId,
                playing = o.optBoolean("playing"),
                ended = o.optBoolean("ended"),
                title = o.optString("title").cleanTitle(),
                artist = o.optString("artist"),
                positionMs = (o.optDouble("position", 0.0) * 1000).toLong(),
                durationMs = (o.optDouble("duration", 0.0) * 1000).toLong(),
                key = o.optString("key"),
                updatedAt = SystemClock.elapsedRealtime(),
                owner = System.identityHashCode(webView.get()),
            )
            main.post {
                val view = webView.get()
                if (onUpdate(state, view?.url)) view?.evaluateJavascript(Web.markDoneJs(state.key), null)
            }
        }

        /** Where the video [key] was left off, see [Progress]. */
        @JavascriptInterface
        fun resumeAt(key: String): Double = Progress.positionS(key)

        @JavascriptInterface
        fun zoom(): Float = zoom

        @JavascriptInterface
        fun stillsOn(): Boolean = stillsOn

        @JavascriptInterface
        fun frame(frameId: String, dataUrl: String) {
            val bytes = runCatching { Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT) }.getOrNull() ?: return
            // RGB_565 halves the size; the bitmap travels over binder to every media controller (1 MB limit).
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return
            main.post {
                if (current?.frameId != frameId) return@post
                still = bitmap
                listeners.toList().forEach { it() }
            }
        }

        private fun String.cleanTitle() = removeSuffix(" - YouTube").replace(Regex(" (from .+ )?on Vimeo$"), "").trim()
    }
}
