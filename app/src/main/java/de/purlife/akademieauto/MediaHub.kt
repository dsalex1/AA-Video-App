package de.purlife.akademieauto

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

        private fun String.cleanTitle() = removeSuffix(" - YouTube").replace(Regex(" (from .+ )?on Vimeo$"), "").trim()
    }
}
