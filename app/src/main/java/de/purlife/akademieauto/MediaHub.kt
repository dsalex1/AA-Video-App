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
        val title: String,
        val artist: String,
        val positionMs: Long,
        val durationMs: Long,
        val updatedAt: Long,
    )

    private val main = Handler(Looper.getMainLooper())
    private val webViews = mutableListOf<WeakReference<WebView>>()
    private val listeners = mutableSetOf<() -> Unit>()

    var current: State? = null
        private set

    /** Latest still of the current frame's video, see [JsBridge.frame]. */
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

    private fun onUpdate(state: State) {
        val previous = current
        // Follow whichever frame is playing; ignore pauses from frames that aren't the current one.
        if (!state.playing && previous != null && previous.frameId != state.frameId) return
        if (previous?.frameId != state.frameId) still = null
        current = state
        listeners.toList().forEach { it() }
    }

    object JsBridge {
        @JavascriptInterface
        fun update(frameId: String, json: String) {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return
            val state = State(
                frameId = frameId,
                playing = o.optBoolean("playing"),
                title = o.optString("title").cleanTitle(),
                artist = o.optString("artist"),
                positionMs = (o.optDouble("position", 0.0) * 1000).toLong(),
                durationMs = (o.optDouble("duration", 0.0) * 1000).toLong(),
                updatedAt = SystemClock.elapsedRealtime(),
            )
            main.post { onUpdate(state) }
        }

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
