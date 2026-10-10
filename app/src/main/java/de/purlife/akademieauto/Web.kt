package de.purlife.akademieauto

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.content.pm.ApplicationInfo
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    fun configure(webView: WebView, onPageFinished: (() -> Unit)? = null) {
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
        val context = webView.context
        webView.addJavascriptInterface(MediaHub.JsBridge, "AAMedia")
        webView.addJavascriptInterface(SkipSilence.bridge(context), "AASkip")
        MediaHub.register(webView)
        interceptServiceWorkers(context)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Injected into every frame, embedded players included.
            for (script in listOf(ALWAYS_VISIBLE_JS, MEDIA_JS, SkipSilence.contentScript(context))) {
                WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
            }
        } else {
            DiagLog.add("document-start scripts unsupported, background playback may pause")
        }
        DiagLog.add("WebView ${WebView.getCurrentWebViewPackage()?.versionName} UA: ${webView.settings.userAgentString}")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                DiagLog.add("page $url")
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                SkipSilence.intercept(context, request)

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                view.evaluateJavascript(VIMEO_HELPER_JS, null)
                onPageFinished?.invoke()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                DiagLog.add("net-error ${error.errorCode} ${error.description} ${frame(request)} ${request.url}")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                DiagLog.add("http ${response.statusCode} ${frame(request)} ${request.url}")
            }
        }
    }

    private var serviceWorkersIntercepted = false

    /** Requests made by a site's service worker (YouTube has one) bypass the WebViewClient. */
    private fun interceptServiceWorkers(context: Context) {
        if (serviceWorkersIntercepted ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
        ) return
        serviceWorkersIntercepted = true
        try {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest) =
                    SkipSilence.intercept(context, request)
            })
        } catch (e: Throwable) {
            DiagLog.add("service worker interception unavailable: $e")
        }
    }

    private fun frame(request: WebResourceRequest) = if (request.isForMainFrame) "[main]" else "[sub]"

    /** Base chrome client: allows DRM-protected video and logs page errors. Subclasses add fullscreen handling. */
    open class ChromeClient : WebChromeClient() {
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onPermissionRequest(request: PermissionRequest) {
            val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            val text = "${message.messageLevel()} ${message.sourceId()}:${message.lineNumber()} ${message.message()}"
            Log.d(TAG, text)
            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                message.messageLevel() == ConsoleMessage.MessageLevel.WARNING ||
                text.contains("vimeo", ignoreCase = true)
            ) {
                DiagLog.add("console $text")
            }
            return true
        }
    }

    /**
     * Runs before page scripts: the page always believes it is visible and focused, so players (YouTube)
     * don't pause when the app is in the background or the screen is off.
     */
    const val ALWAYS_VISIBLE_JS = """
(function () {
  try {
    var def = function (name, value) {
      Object.defineProperty(document, name, { get: function () { return value; }, configurable: true });
    };
    def('visibilityState', 'visible');
    def('webkitVisibilityState', 'visible');
    def('hidden', false);
    def('webkitHidden', false);
    document.hasFocus = function () { return true; };
  } catch (e) {}
  var block = function (e) { e.stopImmediatePropagation(); };
  ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'freeze'].forEach(function (t) {
    window.addEventListener(t, block, true);
    document.addEventListener(t, block, true);
  });
  window.addEventListener('blur', function (e) { if (e.target === window) e.stopImmediatePropagation(); }, true);
})();
"""

    /**
     * Reports the playing media element of each frame to [MediaHub] and executes its play/pause/seek commands.
     * Commands go to the main frame, which relays them to all child frames; only the addressed frame acts.
     */
    const val MEDIA_JS = """
(function () {
  if (window.__aaMediaFrame || typeof AAMedia === 'undefined') return;
  var id = Math.random().toString(36).slice(2);
  window.__aaMediaFrame = id;
  var current = null;
  function meta(field) {
    var m = navigator.mediaSession && navigator.mediaSession.metadata;
    return (m && m[field]) || '';
  }
  function report() {
    var el = current;
    if (!el) return;
    AAMedia.update(id, JSON.stringify({
      playing: !el.paused && !el.ended,
      title: meta('title') || document.title || '',
      artist: meta('artist') || location.hostname.replace(/^www\./, ''),
      position: el.currentTime || 0,
      duration: isFinite(el.duration) ? el.duration : 0
    }));
  }
  ['play', 'playing', 'pause', 'ended', 'seeked', 'loadedmetadata'].forEach(function (type) {
    document.addEventListener(type, function (e) {
      var el = e.target;
      if (!(el instanceof HTMLMediaElement)) return;
      if (type === 'play' || type === 'playing' || !current) current = el;
      if (el === current) report();
    }, true);
  });
  setInterval(function () { if (current && !current.paused) report(); }, 5000);
  // Stills of the playing video for the media session's album art (Android Auto shows that while driving).
  var canvas = document.createElement('canvas');
  canvas.width = canvas.height = 480;
  setInterval(function () {
    var el = current;
    if (!(el instanceof HTMLVideoElement) || el.paused || !el.videoWidth) return;
    // Zoom > 1 crops the sides so the picture fills more of Android Auto's square art box.
    var size = canvas.width, scale = size / Math.max(el.videoWidth, el.videoHeight) * AAMedia.zoom();
    var w = el.videoWidth * scale, h = el.videoHeight * scale;
    var g = canvas.getContext('2d');
    g.fillStyle = '#000';
    g.fillRect(0, 0, size, size);
    try {
      g.drawImage(el, (size - w) / 2, (size - h) / 2, w, h);
      AAMedia.frame(id, canvas.toDataURL('image/jpeg', 0.7));
    } catch (e) {} // a cross-origin video without CORS taints the canvas
  }, 1000);
  function act(command) {
    var el = current;
    if (!el) return;
    if (command === 'play') el.play();
    else if (command === 'pause') el.pause();
    else if (command.indexOf('skip:') === 0) el.currentTime = Math.max(0, el.currentTime + parseFloat(command.slice(5)));
    else if (command.indexOf('seek:') === 0) el.currentTime = parseFloat(command.slice(5));
  }
  function dispatch(target, command) {
    if (target === id) act(command);
    for (var i = 0; i < window.frames.length; i++) {
      try { window.frames[i].postMessage({ __aaMedia: [target, command] }, '*'); } catch (e) {}
    }
  }
  window.addEventListener('message', function (e) {
    var d = e.data && e.data.__aaMedia;
    if (Array.isArray(d)) dispatch(d[0], d[1]);
  });
  window.__aaMedia = dispatch;
})();
"""

    /**
     * Adds a "open directly" button under each embedded Vimeo player, which loads the player as a page of its
     * own. Fallback for embeds that stay black inside the WebView.
     */
    const val VIMEO_HELPER_JS = """
(function () {
  if (window.__aaVimeo) return;
  window.__aaVimeo = true;
  // A course page mounts dozens of Vimeo players at once, which can exhaust the phone's video decoders
  // and leave every player black. Only load a player once it is actually on screen.
  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (en) {
      var f = en.target;
      if (en.isIntersecting && f.dataset.aaSrc && f.getAttribute('src') !== f.dataset.aaSrc) {
        f.setAttribute('src', f.dataset.aaSrc);
        console.log('[aa] vimeo load ' + f.dataset.aaSrc);
      }
    });
  }, { rootMargin: '300px' });
  var PLACEHOLDER = 'https://player.vimeo.com/robots.txt';
  var pairs = [];
  function scan() {
    pairs = pairs.filter(function (p) {
      if (p.frame.isConnected) return true;
      io.unobserve(p.frame);
      p.button.remove();
      return false;
    });
    document.querySelectorAll('iframe[src*="player.vimeo.com"], iframe[src*="vimeo.com/event"]').forEach(function (f) {
      if (f.dataset.aaSrc) return;
      var host = f.parentElement;
      if (!host) return;
      f.dataset.aaSrc = f.src;
      var r = f.getBoundingClientRect();
      var onScreen = r.width > 0 && r.height > 0 && r.bottom > -300 && r.top < innerHeight + 300;
      // The site's Vimeo SDK requires a vimeo.com src (it throws otherwise), so park off-screen players on a
      // tiny Vimeo URL instead of about:blank.
      if (!onScreen) f.setAttribute('src', PLACEHOLDER);
      io.observe(f);
      var b = document.createElement('button');
      b.textContent = '\u2197 Direkt \u00F6ffnen';
      b.style.cssText = 'position:absolute;top:8px;left:8px;z-index:2147483647;padding:6px 12px;border:0;' +
        'border-radius:16px;background:rgba(26,154,214,.9);color:#fff;font:600 13px sans-serif;pointer-events:auto';
      b.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        var u = new URL(f.dataset.aaSrc);
        u.searchParams.set('autoplay', '1');
        location.href = u.toString();
      });
      if (getComputedStyle(host).position === 'static') host.style.position = 'relative';
      host.appendChild(b);
      pairs.push({ frame: f, button: b });
    });
  }
  scan();
  new MutationObserver(scan).observe(document.documentElement, { childList: true, subtree: true });
})();
"""

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

/** Recent page errors and Vimeo activity, viewable and shareable from the phone app for troubleshooting. */
object DiagLog {
    private const val MAX = 300
    private val entries = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)

    @Synchronized
    fun add(line: String) {
        if (entries.size >= MAX) entries.removeFirst()
        entries.addLast("${time.format(Date())} ${line.take(600)}")
    }

    @Synchronized
    fun dump(): String = entries.joinToString("\n")
}
