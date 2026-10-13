package de.purlife.akademieauto

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The web page, rendered onto the car's virtual display. Dark theme, so pages see prefers-color-scheme: dark
 * (YouTube goes dark).
 */
class CarPresentation(
    outerContext: Context,
    display: Display,
    private val initialSite: Site,
    private val onSwitchSite: () -> Unit,
    private val onPageChanged: () -> Unit,
) :
    Presentation(outerContext, display, android.R.style.Theme_Material_NoActionBar_Fullscreen) {
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    lateinit var webView: WebView
        private set

    private var settingsView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: android.webkit.WebChromeClient.CustomViewCallback? = null
    private lateinit var buttons: LinearLayout
    private lateinit var siteButton: ImageButton
    private val hideButtons = Runnable { buttons.animate().alpha(0f) }
    private var videoFullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        content = FrameLayout(context)
        webView = BackgroundWebView(context)
        content.addView(webView, matchParent())
        // Android Auto's content card sits top left; the page starts halfway down it and the buttons.
        root.addView(content, matchParent().apply { topMargin = dp(TOP_DP) })
        buttons = LinearLayout(context).apply {
            addView(roundButton(R.drawable.ic_forward) { toggleSkipSilenceSettings() })
            siteButton = roundButton(0) { onSwitchSite() }
            addView(siteButton)
        }
        root.addView(buttons, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(CARD_DP)).apply {
            gravity = Gravity.TOP or Gravity.END
            marginEnd = dp(8)
        })
        setSite(initialSite)
        setContentView(root)

        Web.configure(webView)
        webView.webChromeClient = object : Web.ChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) = onPageChanged()

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                customViewCallback = callback
                // Fullscreen video gets the same area as the page, below the buttons.
                root.addView(view, root.indexOfChild(buttons), matchParent().apply { topMargin = dp(TOP_DP) })
                content.visibility = View.GONE
                showButtons()
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customViewCallback = null
                content.visibility = View.VISIBLE
                showButtons()
            }
        }
        webView.loadUrl(initialSite.homeUrl)
    }

    /** Our full-screen video styling (see [Web.fullscreenJs]); off also leaves the page's own fullscreen. */
    fun setVideoFullscreen(on: Boolean, key: String? = null) {
        if (!on) exitFullscreen()
        if (videoFullscreen != on) {
            videoFullscreen = on
            showButtons()
        }
        webView.evaluateJavascript(Web.fullscreenJs(on, key), null)
    }

    /** The switch shows the site it switches to. */
    fun setSite(site: Site) {
        siteButton.setImageResource(if (site.other == Site.YOUTUBE) R.drawable.ic_site_youtube else R.drawable.ic_site_akademie)
    }

    /** Full-screen video: the page uses the whole screen and the buttons fade out until the next tap. */
    private fun showButtons() {
        buttons.removeCallbacks(hideButtons)
        buttons.animate().cancel()
        buttons.alpha = 1f
        if (customView != null || videoFullscreen) buttons.postDelayed(hideButtons, HIDE_MS)
    }

    fun tap(x: Float, y: Float) {
        if (buttons.alpha < 1f) {
            showButtons() // the first tap only brings the buttons back
            return
        }
        showButtons()
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

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun roundButton(icon: Int, onClick: () -> Unit) = ImageButton(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xE6202124.toInt())
            setStroke(dp(1), 0xFF5F6368.toInt())
        }
        if (icon != 0) setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = dp(8)
        }
    }

    private companion object {
        /** Height of Android Auto's content card incl. its margins, at the car screen's density. */
        const val CARD_DP = 92

        /** Where the page starts: about half the buttons (centred on the card) are over it. */
        const val TOP_DP = 46
        const val BUTTON_DP = 52
        const val HIDE_MS = 4000L
    }

    private fun matchParent() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}
