package de.purlife.akademieauto

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * Phone screen: the same pages as on the car, used to log in once (there is no keyboard on the car screen).
 */
class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var switchButton: Button
    private lateinit var site: Site
    private var customView: View? = null
    private var customViewCallback: android.webkit.WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = Site.load(this)
        root = FrameLayout(this)
        webView = WebView(this)
        root.addView(webView, matchParent())

        val density = resources.displayMetrics.density
        switchButton = Button(this).apply {
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = 24 * density
            }
            setOnClickListener {
                site = site.other
                Site.save(this@MainActivity, site)
                updateSwitchLabel()
                webView.loadUrl(site.homeUrl)
            }
        }
        root.addView(
            switchButton,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                val margin = (16 * density).toInt()
                setMargins(margin, margin, margin, (64 * density).toInt())
            },
        )
        updateSwitchLabel()
        setContentView(root)

        Web.configure(webView)
        webView.webChromeClient = object : Web.ChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                customViewCallback = callback
                root.addView(view, matchParent())
                webView.visibility = View.GONE
                switchButton.visibility = View.GONE
            }

            override fun onHideCustomView() = exitFullscreen()
        }

        if (savedInstanceState == null) webView.loadUrl(site.homeUrl)
        else webView.restoreState(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> customViewCallback?.onCustomViewHidden()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })
    }

    private fun updateSwitchLabel() {
        switchButton.text = "→ ${site.other.label}"
    }

    private fun exitFullscreen() {
        customView?.let { root.removeView(it) }
        customView = null
        customViewCallback = null
        webView.visibility = View.VISIBLE
        switchButton.visibility = View.VISIBLE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun matchParent() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}
