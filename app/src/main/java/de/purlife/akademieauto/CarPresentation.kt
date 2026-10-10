package de.purlife.akademieauto

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * The web page, rendered onto the car's virtual display. Dark theme, so pages see prefers-color-scheme: dark
 * (YouTube goes dark).
 */
class CarPresentation(outerContext: Context, display: Display, private val initialSite: Site) :
    Presentation(outerContext, display, android.R.style.Theme_Material_NoActionBar_Fullscreen) {
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    lateinit var webView: WebView
        private set

    private var settingsView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: android.webkit.WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        content = FrameLayout(context)
        webView = BackgroundWebView(context)
        content.addView(webView, matchParent())
        root.addView(content, matchParent())
        setContentView(root)

        Web.configure(webView)
        webView.webChromeClient = object : Web.ChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                customViewCallback = callback
                // Fullscreen video covers the whole car screen, ignoring the action-strip insets.
                root.addView(view, matchParent())
                content.visibility = View.GONE
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customViewCallback = null
                content.visibility = View.VISIBLE
            }
        }
        webView.loadUrl(initialSite.homeUrl)
    }

    /** Our full-screen video styling (see [Web.fullscreenJs]); off also leaves the page's own fullscreen. */
    fun setVideoFullscreen(on: Boolean, key: String? = null) {
        if (!on) exitFullscreen()
        webView.evaluateJavascript(Web.fullscreenJs(on, key), null)
    }

    fun tap(x: Float, y: Float) {
        val target = window?.decorView ?: return
        val downTime = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            target.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    fun scrollBy(dx: Float, dy: Float) {
        val settings = settingsView
        when {
            settings != null -> settings.scrollBy(dx.toInt(), dy.toInt())
            customView == null -> webView.scrollBy(dx.toInt(), dy.toInt())
        }
    }

    /** Shows/hides the Skip Silence settings popup on top of the page. */
    fun toggleSkipSilenceSettings() {
        val open = settingsView
        if (open != null) {
            root.removeView(open)
            SkipSilence.closeSettings(open)
            settingsView = null
            return
        }
        SkipSilence.pageUrl = webView.url
        val view = SkipSilence.createSettingsView(context)
        val density = context.resources.displayMetrics.density
        root.addView(view, FrameLayout.LayoutParams((356 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })
        settingsView = view
    }

    fun exitFullscreen(): Boolean {
        val callback = customViewCallback ?: return false
        callback.onCustomViewHidden()
        return true
    }

    fun destroy() {
        settingsView?.let(SkipSilence::closeSettings)
        webView.destroy()
        dismiss()
    }

    private fun matchParent() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}
