package com.safad.bmsprobe

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class LoadResult(
    val ok: Boolean = false,
    val codes: List<String> = emptyList(),
    val all: List<String> = emptyList(),
    val title: String = "",
    val body: String = "",
    val challenge: Boolean = false,
    val elapsedMs: Long = 0,
    val error: String = "",
    val href: String = "",
)

private const val READ_JS = """
(function () {
  try {
    var s = window.__INITIAL_STATE__;
    var vn = s && s.venueShowtimesNew;
    var sd = (vn && vn.showDates) || null;
    return {
      has: !!s,
      href: String(location.href),
      codes: sd ? sd.filter(function (d) { return !d.isDisabled; }).map(function (d) { return d.DateCode; }) : [],
      all: sd ? sd.map(function (d) { return d.DateCode + (d.isDisabled ? 'D' : ''); }) : [],
      title: document.title || '',
      body: (document.body ? document.body.innerText : '').replace(/\s+/g, ' ').slice(0, 220)
    };
  } catch (e) {
    return { has: false, href: String(location.href), codes: [], all: [], title: '', body: 'ERR ' + e };
  }
})()
"""

/**
 * Owns one WebView, keeps cookies forever, and reads the venue's date list out of
 * window.__INITIAL_STATE__. Never clears cache or cookies on purpose: the cf_clearance
 * cookie earned on the first load is what keeps every later poll cheap.
 */
@SuppressLint("SetJavaScriptEnabled")
class PollEngine(context: Context) {

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val webView: WebView? = runCatching { WebView(app) }.getOrNull()

    init {
        webView?.let { wv ->
            with(wv.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                blockNetworkImage = true
                mediaPlaybackRequiresUserGesture = true
                userAgentString = defaultUserAgent(app).replace("; wv", "")
            }
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            runCatching { wv.resumeTimers() }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
            }
        }
    }

    val available: Boolean get() = webView != null

    suspend fun load(url: String, expectedCode: String, timeoutMs: Long = 45000): LoadResult {
        val wv = webView ?: return LoadResult(error = "WebView unavailable in this process")
        return withContext(Dispatchers.Main.immediate) {
            runCatching { wv.resumeTimers() }
            suspendCancellableCoroutine { cont ->
                val started = SystemClock.elapsedRealtime()
                val done = AtomicBoolean(false)
                var status: Int? = null
                var netError = ""
                var lastTitle = ""
                var lastBody = ""
                var lastHref = ""

                fun finish(r: LoadResult) {
                    if (!done.compareAndSet(false, true)) return
                    handler.removeCallbacksAndMessages(null)
                    runCatching { wv.stopLoading() }
                    wv.webViewClient = WebViewClient()
                    // Unload the BookMyShow document between polls. The renderer stays warm
                    // (cookies, TLS session) but the page's DOM and JS heap are freed, which
                    // is most of the 200 MB it otherwise holds while idling in the background.
                    runCatching {
                        wv.loadUrl("about:blank")
                        wv.pauseTimers()
                    }
                    if (cont.isActive) cont.resume(r)
                }

                fun poll() {
                    if (done.get()) return
                    val elapsed = SystemClock.elapsedRealtime() - started
                    if (elapsed > timeoutMs) {
                        val challenge = lastTitle.contains("Just a moment", true) ||
                            lastTitle.contains("Attention Required", true) ||
                            lastBody.contains("Verifying you are human", true) ||
                            lastBody.contains("have been blocked", true)
                        finish(
                            LoadResult(
                                ok = false,
                                title = lastTitle,
                                body = lastBody,
                                challenge = challenge,
                                elapsedMs = elapsed,
                                error = if (challenge) "challenge" else netError.ifBlank { "timeout" },
                                href = lastHref,
                            )
                        )
                        return
                    }
                    wv.evaluateJavascript(READ_JS) { raw ->
                        if (done.get()) return@evaluateJavascript
                        val o = runCatching { JSONObject(raw ?: "") }.getOrNull()
                        if (o != null) {
                            val href = o.optString("href")
                            // ignore reads from the previous page while navigating
                            val rightPage = href.contains("/buytickets/$expectedCode", true)
                            val has = o.optBoolean("has")
                            val codes = o.optJSONArray("codes").toStringList()
                            val all = o.optJSONArray("all").toStringList()
                            if (href.isNotBlank()) lastHref = href
                            if (rightPage && has && codes.isNotEmpty()) {
                                val title = o.optString("title")
                                finish(
                                    LoadResult(
                                        ok = true,
                                        codes = codes,
                                        all = all,
                                        title = title,
                                        body = o.optString("body"),
                                        elapsedMs = SystemClock.elapsedRealtime() - started,
                                        href = href,
                                    )
                                )
                                return@evaluateJavascript
                            }
                            val t = o.optString("title")
                            if (t.isNotBlank()) lastTitle = t
                            val b = o.optString("body")
                            if (b.isNotBlank()) lastBody = b
                        }
                        handler.postDelayed({ poll() }, 700L)
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        handler.post { poll() }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        response: WebResourceResponse,
                    ) {
                        if (request.isForMainFrame) status = response.statusCode
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) netError = error.description?.toString() ?: ""
                    }
                }

                cont.invokeOnCancellation { runCatching { wv.stopLoading() } }
                if (status == null) Unit
                wv.loadUrl(url)
            }
        }
    }

    fun close() {
        runCatching {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.pauseTimers()
            webView?.destroy()
        }
    }
}

private fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList { for (i in 0 until length()) add(optString(i)) }
}

fun defaultUserAgent(context: Context): String = runCatching {
    WebSettings.getDefaultUserAgent(context)
}.getOrDefault("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
