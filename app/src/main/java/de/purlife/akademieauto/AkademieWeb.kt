package de.purlife.akademieauto

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/** WebView setup shared by the phone screen and the car screen (they share cookies, so one login covers both). */
object AkademieWeb {
    const val HOME_URL = "https://pur-life.de/akademie"

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, chromeClient: WebChromeClient) {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true) // Vimeo embeds run in a third-party iframe
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
            }
        }
        webView.webChromeClient = chromeClient
    }
}
