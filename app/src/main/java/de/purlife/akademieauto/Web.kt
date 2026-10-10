package de.purlife.akademieauto

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

enum class Site(val label: String, val homeUrl: String) {
    AKADEMIE("Akademie", "https://pur-life.de/akademie"),
    YOUTUBE("YouTube", "https://m.youtube.com/");

    val other get() = if (this == AKADEMIE) YOUTUBE else AKADEMIE

    companion object {
        private const val PREFS = "settings"
        private const val KEY_SITE = "site"

        fun load(context: Context): Site =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SITE, null)
                ?.let { name -> entries.firstOrNull { it.name == name } } ?: AKADEMIE

        fun save(context: Context, site: Site) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SITE, site.name).apply()
        }
    }
}

/** WebView setup shared by the phone screen and the car screen (they share cookies, so one login covers both). */
object Web {
    private const val TAG = "Web"

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        if (webView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true) // chrome://inspect from a computer
        }
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Present as regular Chrome: some players and logins treat the WebView marker differently.
            userAgentString = userAgentString.replace("; wv)", ")").replace(" Version/4.0", "")
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
    }

    /** Base chrome client: allows DRM-protected video and logs page errors. Subclasses add fullscreen handling. */
    open class ChromeClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            Log.d(TAG, "${message.messageLevel()} ${message.sourceId()}:${message.lineNumber()} ${message.message()}")
            return true
        }
    }

    /**
     * Toggles the largest video (or video iframe) on the page to fill the whole screen, by styling it rather
     * than via the Fullscreen API, which needs a real tap on the page's own button.
     */
    const val TOGGLE_FULLSCREEN_JS = """
(function () {
  var ID = '__aa_fs_style';
  var current = document.querySelector('[data-aa-fs]');
  if (current) {
    current.removeAttribute('data-aa-fs');
    document.querySelectorAll('[data-aa-fs-anc],[data-aa-fs-in]').forEach(function (e) {
      e.removeAttribute('data-aa-fs-anc'); e.removeAttribute('data-aa-fs-in');
    });
    var old = document.getElementById(ID); if (old) old.remove();
    return 'off';
  }
  var best = null, bestArea = 0;
  document.querySelectorAll('video, iframe').forEach(function (e) {
    var r = e.getBoundingClientRect(), a = r.width * r.height;
    if (r.width > 80 && a > bestArea) { best = e; bestArea = a; }
  });
  if (!best) return 'none';
  var el = best;
  while (el.parentElement && el.parentElement !== document.body) {
    var p = el.parentElement.getBoundingClientRect(), r = el.getBoundingClientRect();
    if (Math.abs(p.width - r.width) > 4 || Math.abs(p.height - r.height) > 4) break;
    el = el.parentElement;
  }
  el.setAttribute('data-aa-fs', '1');
  for (var c = best; c !== el; c = c.parentElement) c.setAttribute('data-aa-fs-in', '1');
  for (var a = el.parentElement; a; a = a.parentElement) a.setAttribute('data-aa-fs-anc', '1');
  var st = document.createElement('style');
  st.id = ID;
  st.textContent =
    '[data-aa-fs]{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;' +
    'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;' +
    'z-index:2147483647!important;background:#000!important;border-radius:0!important;aspect-ratio:auto!important;transform:none!important}' +
    '[data-aa-fs-in]{width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;aspect-ratio:auto!important}' +
    '[data-aa-fs] video{object-fit:contain!important}' +
    '[data-aa-fs-anc]{transform:none!important;filter:none!important;contain:none!important;z-index:2147483647!important}';
  document.head.appendChild(st);
  return 'on';
})();
"""
}
