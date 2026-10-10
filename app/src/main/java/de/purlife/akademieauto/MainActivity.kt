package de.purlife.akademieauto

import android.app.AlertDialog
import android.content.Intent
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setUpSystemBars()
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
            setOnLongClickListener {
                showDiagnostics()
                true
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
                applyFullscreen(true)
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

    /**
     * Android 15 draws apps edge-to-edge, so keep the page clear of the status bar, navigation buttons and
     * keyboard via padding, except while a video is in fullscreen, where the bars are hidden instead.
     */
    private fun setUpSystemBars() {
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            if (customView != null) {
                view.setPadding(0, 0, 0, 0)
            } else {
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun applyFullscreen(fullscreen: Boolean) {
        val controller = WindowCompat.getInsetsController(window, root)
        if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(root)
    }

    private fun showDiagnostics() {
        val log = DiagLog.dump().ifEmpty { "(leer)" }
        AlertDialog.Builder(this)
            .setTitle("Diagnose")
            .setMessage(log.takeLast(6000))
            .setPositiveButton("Teilen") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log)
                startActivity(Intent.createChooser(send, "Diagnose teilen"))
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun updateSwitchLabel() {
        switchButton.text = getString(R.string.switch_to, site.other.label)
    }

    private fun exitFullscreen() {
        customView?.let { root.removeView(it) }
        customView = null
        customViewCallback = null
        webView.visibility = View.VISIBLE
        switchButton.visibility = View.VISIBLE
        applyFullscreen(false)
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
