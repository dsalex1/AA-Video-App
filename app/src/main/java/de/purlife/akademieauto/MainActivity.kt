package de.purlife.akademieauto

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.car.app.connection.CarConnection
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
    private lateinit var switchButton: ImageButton
    private lateinit var buttonRow: LinearLayout
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
        switchButton = roundButton {
            site = site.other
            Site.save(this@MainActivity, site)
            updateSwitchLabel()
            webView.loadUrl(site.homeUrl)
        }.apply {
            setOnLongClickListener {
                showDiagnostics()
                true
            }
        }
        val skipSilenceButton = roundButton { showSkipSilenceSettings() }.apply {
            setImageResource(R.drawable.ic_forward)
            contentDescription = getString(R.string.skip_silence)
        }
        // Only useful while Android Auto shows the video as album art; dimmed while switched off.
        val carTextButton = roundButton {}.apply {
            setImageResource(R.drawable.ic_car_text)
            contentDescription = getString(R.string.car_text)
            setOnClickListener { MediaHub.toggleCarText(); alpha = if (MediaHub.hideCarText) DIMMED else 1f }
        }
        val stillButton = roundButton {}.apply {
            setImageResource(R.drawable.ic_still)
            contentDescription = getString(R.string.car_still)
            setOnClickListener { MediaHub.toggleStills(); alpha = if (MediaHub.stillsOn) 1f else DIMMED }
        }
        CarConnection(this).type.observe(this) { type ->
            val visibility = if (type == CarConnection.CONNECTION_TYPE_PROJECTION) View.VISIBLE else View.GONE
            carTextButton.visibility = visibility
            stillButton.visibility = visibility
        }
        buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val size = (48 * density).toInt()
            for (button in listOf(switchButton, skipSilenceButton, carTextButton, stillButton)) {
                addView(button, LinearLayout.LayoutParams(size, size).apply { marginEnd = (10 * density).toInt() })
            }
        }
        root.addView(
            buttonRow,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                val margin = (16 * density).toInt()
                setMargins(margin, margin, margin, (64 * density).toInt())
            },
        )
        updateSwitchLabel()
        setContentView(root)
        setUpSystemBars() // needs the window's decor view, which setContentView creates

        Web.configure(webView, CarSync::phoneNavigated) { refresh.isRefreshing = false }
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
                buttonRow.visibility = View.GONE
                applyFullscreen(true)
                CarSync.phoneFullscreen(true)
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

    /** Small round button like Android Auto's, with a light ring and a shadow to stand out on any page. */
    private fun roundButton(onClick: () -> Unit) = ImageButton(this).apply {
        val density = resources.displayMetrics.density
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xE6202124.toInt())
            setStroke((1.5f * density).toInt(), 0xB3FFFFFF.toInt())
        }
        elevation = 6 * density
        scaleType = ImageView.ScaleType.CENTER
        setOnClickListener { onClick() }
    }

    /** The Skip Silence extension's own settings popup, for the page currently shown. */
    private fun showSkipSilenceSettings() {
        SkipSilence.pageUrl = webView.url
        val view = SkipSilence.createSettingsView(this)
        val density = resources.displayMetrics.density
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(view)
            setOnDismissListener { SkipSilence.closeSettings(view) }
        }
        dialog.show()
        dialog.window?.setLayout((356 * density).toInt(), (560 * density).toInt())
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
        switchButton.setImageResource(if (site.other == Site.YOUTUBE) R.drawable.ic_site_youtube else R.drawable.ic_site_akademie)
        switchButton.contentDescription = getString(R.string.switch_to, site.other.label)
    }

    private fun exitFullscreen() {
        customView?.let { root.removeView(it) }
        customView = null
        customViewCallback = null
        refresh.visibility = View.VISIBLE
        buttonRow.visibility = View.VISIBLE
        applyFullscreen(false)
        CarSync.phoneFullscreen(false)
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
        const val DIMMED = 0.45f
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
