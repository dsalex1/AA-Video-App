package de.purlife.akademieauto

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

/**
 * Mirrors a [CarPresentation] onto the surface Android Auto hands to navigation apps,
 * via a private virtual display. Car touch input is forwarded to the page.
 */
class WebSurfaceRenderer(private val context: Context) : SurfaceCallback {
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: CarPresentation? = null
    private var width = 0
    private var height = 0
    private var visibleArea: Rect? = null

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
            presentation = CarPresentation(context, display.display, site).also { it.show() }
        }
        applyInsets()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        // Detach only; the page (and its audio) keeps running until the session ends.
        virtualDisplay?.surface = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        applyInsets()
    }

    override fun onClick(x: Float, y: Float) {
        presentation?.tap(x, y)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        presentation?.scrollBy(distanceX, distanceY)
    }

    fun goBack() {
        val p = presentation ?: return
        if (!p.exitFullscreen() && p.webView.canGoBack()) p.webView.goBack()
    }

    fun loadHome() {
        presentation?.loadSite(site)
    }

    fun switchSite() {
        site = site.other
        Site.save(context, site)
        loadHome()
    }

    fun toggleFullscreen() {
        presentation?.toggleFullscreen()
    }

    fun reload() {
        presentation?.webView?.reload()
    }

    fun scrollPage(direction: Int) {
        presentation?.scrollBy(0f, direction * height * 0.6f)
    }

    fun release() {
        presentation?.destroy()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private fun applyInsets() {
        val area = visibleArea ?: return
        if (width == 0 || height == 0) return
        presentation?.setInsets(area.left, area.top, width - area.right, height - area.bottom)
    }
}
