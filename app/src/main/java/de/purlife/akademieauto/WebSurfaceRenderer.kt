package de.purlife.akademieauto

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.os.HandlerCompat

/**
 * Mirrors a [CarPresentation] onto the map surface Android Auto hands to the app (MapWithContentTemplate), via a
 * private virtual display. The page fills the whole surface, like a map, with the buttons floating on top. Car touch
 * input is forwarded to the page. Follows the phone via [CarSync].
 */
class WebSurfaceRenderer(private val context: Context) : SurfaceCallback, CarSync.Car {
    private val main = Handler(Looper.getMainLooper())
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: CarPresentation? = null
    private var width = 0
    private var height = 0

    var site = Site.load(context)
        private set

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        width = surfaceContainer.width
        height = surfaceContainer.height
        val existing = virtualDisplay
        if (existing != null) {
            // Keep the same WebView alive so playback isn't interrupted.
            existing.resize(width, height, surfaceContainer.dpi)
            existing.surface = surface
        } else {
            val display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "AkademieAuto", width, height, surfaceContainer.dpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            virtualDisplay = display
            presentation = CarPresentation(context, display.display, site, ::switchSite).also { it.show() }
            CarSync.car = this
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        // Detach only; the page (and its audio) keeps running until the session ends.
        virtualDisplay?.surface = null
    }

    override fun onClick(x: Float, y: Float) {
        presentation?.tap(x, y)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        presentation?.scrollBy(distanceX, distanceY)
    }

    fun toggleSkipSilenceSettings() {
        presentation?.toggleSkipSilenceSettings()
    }

    /** The car only; the phone keeps its page (and the car follows it again on its next navigation). */
    fun switchSite() {
        site = site.other
        Site.save(context, site)
        presentation?.setSite(site)
        setFullscreen(false)
        presentation?.webView?.loadUrl(site.homeUrl)
    }

    override fun load(url: String) {
        val p = presentation ?: return
        if (p.webView.url == url) return
        setFullscreen(false)
        p.webView.loadUrl(url)
    }

    /**
     * Shows the phone's video full screen and continues it. Repeated for a while: right after [load] the page or
     * the (lazily loaded) player may not exist yet.
     */
    override fun showVideo(key: String, positionS: Double) {
        DiagLog.add("car: show video $key at $positionS s")
        setFullscreen(true, key)
        val start = SystemClock.elapsedRealtime()
        repeat(RETRIES) { i ->
            HandlerCompat.postDelayed(main, {
                val p = presentation ?: return@postDelayed
                val at = positionS + (SystemClock.elapsedRealtime() - start) / 1000.0
                p.setVideoFullscreen(true, key)
                p.webView.evaluateJavascript(
                    "window.__aaMedia && window.__aaMedia('*', ${org.json.JSONObject.quote("playat:$key|$at")})", null
                )
            }, RETRY_TOKEN, (i + 1) * 1000L)
        }
    }

    override fun exitVideo() = setFullscreen(false)

    private fun setFullscreen(on: Boolean, key: String? = null) {
        if (!on) main.removeCallbacksAndMessages(RETRY_TOKEN)
        presentation?.setVideoFullscreen(on, key)
    }

    fun release() {
        main.removeCallbacksAndMessages(RETRY_TOKEN)
        if (CarSync.car === this) CarSync.car = null
        presentation?.destroy()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private companion object {
        const val RETRIES = 10
        val RETRY_TOKEN = Any()
    }
}
