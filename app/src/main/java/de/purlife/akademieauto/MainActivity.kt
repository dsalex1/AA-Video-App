package de.purlife.akademieauto

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Phone screen: the same pages as on the car, used to log in once (there is no keyboard on the car screen).
 */
class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var switchButton: Button
    private lateinit var site: Site
    private var customView: View? = null
    private var customViewCallback: android.webkit.WebChromeClient.CustomViewCallback? = null

    /** Whether the touched part of the page is scrolled down, reported from JS (pages often scroll inner divs). */
    @Volatile
    private var pageScrolled = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = Site.load(this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        webView = BackgroundWebView(this)
        refresh = SwipeRefreshLayout(this).apply {
            addView(webView, matchParent())
            setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 || pageScrolled }
            setOnRefreshListener { webView.reload() }
        }
        root.addView(refresh, matchParent())

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
        setUpSystemBars() // needs the window's decor view, which setContentView creates

        Web.configure(webView) { refresh.isRefreshing = false }
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun setScrolled(scrolled: Boolean) {
                pageScrolled = scrolled
            }
        }, "AAPull")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, PULL_STATE_JS, setOf("*"))
        }
        webView.webChromeClient = object : Web.ChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                customViewCallback = callback
                root.addView(view, matchParent())
                refresh.visibility = View.GONE
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        PlaybackService.start(this)
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
        refresh.visibility = View.VISIBLE
        switchButton.visibility = View.VISIBLE
        applyFullscreen(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) PlaybackService.stop(this)
        webView.destroy()
        super.onDestroy()
    }

    private fun matchParent() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private companion object {
        /** Reports on each touch whether the element under the finger (or the page) is scrolled down. */
        const val PULL_STATE_JS = """
(function () {
  if (window.top !== window || typeof AAPull === 'undefined') return;
  document.addEventListener('touchstart', function (e) {
    var scrolled = window.scrollY > 0;
    for (var el = e.target; el && !scrolled; el = el.parentElement) {
      if (el.scrollTop > 0) scrolled = true;
    }
    AAPull.setScrolled(scrolled);
  }, { passive: true, capture: true });
})();
"""
    }
}
