package de.purlife.akademieauto

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.util.concurrent.atomic.AtomicReference

/**
 * The Skip Silence browser extension (github.com/vantezzen/skip-silence, built from /skip-silence into
 * assets/skipsilence): its content script runs in every frame of every page, and its popup is the settings page.
 * The extension APIs it needs are provided by [Bridge] (see skip-silence/android/imports.ts).
 */
object SkipSilence {
    /** Must match PROCESSOR_PATH in skip-silence/android/imports.ts. */
    private const val PROCESSOR_PATH = "/__aa_skip_silence/volume-processor.js"
    private const val SETTINGS_URL = "https://appassets.androidplatform.net/assets/skipsilence/popup/index.html"

    /** URL of the page the settings apply to (for the "custom settings for this site" switch). */
    @Volatile
    var pageUrl: String? = null

    @Volatile
    private var meterWanted = false
    private val meterSample = AtomicReference<String?>(null)
    private var contentScript: String? = null
    private var bridge: Bridge? = null

    fun contentScript(context: Context): String =
        contentScript ?: context.assets.open("skipsilence/content.js").bufferedReader().use { it.readText() }
            .also { contentScript = it }

    fun bridge(context: Context): Bridge = bridge ?: Bridge(context.applicationContext).also { bridge = it }

    /** Serves the audio worklet on the requesting page's own origin, so CSPs that only allow 'self' accept it. */
    fun intercept(context: Context, request: WebResourceRequest): WebResourceResponse? {
        if (request.url.path != PROCESSOR_PATH) return null
        return WebResourceResponse(
            "text/javascript", "utf-8", 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache"),
            context.assets.open("skipsilence/volume-processor.js"),
        )
    }

    /** A WebView showing the extension's settings popup. Call [closeSettings] when it goes away. */
    @SuppressLint("SetJavaScriptEnabled")
    fun createSettingsView(context: Context): WebView {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        return BackgroundWebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(bridge(context), "AASkip")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    loader.shouldInterceptRequest(request.url)
            }
            loadUrl(SETTINGS_URL)
        }
    }

    fun closeSettings(view: WebView) {
        meterWanted = false
        view.destroy()
    }

    class Bridge(context: Context) {
        private val prefs = context.getSharedPreferences("skipsilence", Context.MODE_PRIVATE)

        @JavascriptInterface
        fun get(key: String): String? = prefs.getString(key, null)

        @JavascriptInterface
        fun set(key: String, json: String) {
            prefs.edit().putString(key, json).apply()
        }

        @JavascriptInterface
        fun pageUrl(): String? = SkipSilence.pageUrl

        @JavascriptInterface
        fun meterWanted(): Boolean = SkipSilence.meterWanted

        @JavascriptInterface
        fun setMeterWanted(wanted: Boolean) {
            SkipSilence.meterWanted = wanted
            if (!wanted) meterSample.set(null)
        }

        @JavascriptInterface
        fun pushMeter(json: String) {
            meterSample.set(json)
        }

        @JavascriptInterface
        fun readMeter(): String? = meterSample.getAndSet(null)
    }
}
