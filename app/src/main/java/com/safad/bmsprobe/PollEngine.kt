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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class Show(
    val group: String,
    val code: String,
    val title: String,
    val language: String,
    val time: String,
    val status: String,
    val session: String,
    val screen: String,
)

data class EventSummary(
    val group: String,
    val title: String,
    val shows: Int,
)

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
    val venueName: String = "",
    val venueCode: String = "",
    val shows: List<Show> = emptyList(),
    val events: List<EventSummary> = emptyList(),
)

data class ResolvedMovie(
    val code: String,
    val group: String,
    val title: String,
    val slug: String,
    val region: String,
)

/**
 * Owns one WebView, keeps cookies forever, and reads BookMyShow's own state:
 *
 *  - venueShowtimesNew.showDates[].DateCode           -> which dates the cinema lists
 *  - …venueShowtimesFunctionalApi…showDetailsTransformed.Event[]
 *      EventGroup / ChildEvents[].EventCode / ShowTimes[] -> what is actually playing
 *
 * Never clears cache or cookies on purpose: the cf_clearance cookie earned on the
 * first load is what keeps every later poll cheap.
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

    suspend fun load(
        url: String,
        expectedCode: String,
        timeoutMs: Long = 45_000,
        targetDate: String = "",
        movies: List<Movie> = emptyList(),
    ): LoadResult {
        val wv = webView ?: return LoadResult(error = "WebView unavailable in this process")
        val needShows = movies.isNotEmpty() && targetDate.isNotBlank()
        return withContext(Dispatchers.Main.immediate) {
            runCatching { wv.resumeTimers() }
            suspendCancellableCoroutine { cont ->
                val started = SystemClock.elapsedRealtime()
                val done = AtomicBoolean(false)
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

                    val js = readJs(expectedCode, targetDate, movies, needShows)
                    wv.evaluateJavascript(js) { raw ->
                        if (done.get()) return@evaluateJavascript
                        val o = runCatching { JSONObject(raw ?: "") }.getOrNull()
                        if (o != null) {
                            val href = o.optString("href")
                            // ignore reads from the previous page while navigating
                            val rightPage = href.contains("/buytickets/$expectedCode", true)
                            val has = o.optBoolean("has")
                            val hasDates = o.optBoolean("hasDates")
                            val ready = o.optBoolean("ready", true)
                            val codes = o.optJSONArray("codes").toStringList()
                            val all = o.optJSONArray("all").toStringList()
                            if (href.isNotBlank()) lastHref = href
                            if (rightPage && has && hasDates && ready) {
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
                                        venueName = o.optString("venueName"),
                                        venueCode = o.optString("venueCode"),
                                        shows = showsFrom(o.optJSONArray("shows")),
                                        events = eventsFrom(o.optJSONArray("events")),
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
                        if (request.isForMainFrame) netError = "HTTP ${response.statusCode}"
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
                wv.loadUrl(url)
            }
        }
    }

    /**
     * BookMyShow's movie page carries a movie-level EventGroup (EG…) in its
     * analytics state. The ET… code in the URL is only one language/format,
     * so the group is the accurate id to watch.
     */
    suspend fun resolveMovie(movie: Movie, timeoutMs: Long = 35_000): ResolvedMovie? {
        val wv = webView ?: return null
        return withContext(Dispatchers.Main.immediate) {
            runCatching { wv.resumeTimers() }
            suspendCancellableCoroutine { cont ->
                val started = SystemClock.elapsedRealtime()
                val done = AtomicBoolean(false)
                var lastTitle = ""

                fun finish(r: ResolvedMovie?) {
                    if (!done.compareAndSet(false, true)) return
                    handler.removeCallbacksAndMessages(null)
                    runCatching { wv.stopLoading() }
                    wv.webViewClient = WebViewClient()
                    runCatching {
                        wv.loadUrl("about:blank")
                        wv.pauseTimers()
                    }
                    if (cont.isActive) cont.resume(r)
                }

                fun poll() {
                    if (done.get()) return
                    if (SystemClock.elapsedRealtime() - started > timeoutMs) {
                        finish(null)
                        return
                    }
                    wv.evaluateJavascript(MOVIE_JS) { raw ->
                        if (done.get()) return@evaluateJavascript
                        val o = runCatching { JSONObject(raw ?: "") }.getOrNull()
                        if (o != null) {
                            val m = o.optJSONObject("movie")
                            val group = m?.optString("group").orEmpty()
                            val code = m?.optString("code").orEmpty()
                            val doc = o.optString("title")
                            if (doc.isNotBlank()) lastTitle = doc
                            if (m != null && group.isNotBlank() && code.isNotBlank()) {
                                val href = o.optString("href")
                                val (region, slug) = slugFromHref(href, movie)
                                finish(
                                    ResolvedMovie(
                                        code = code,
                                        group = group,
                                        title = cleanTitle(m.optString("title"), doc),
                                        slug = slug,
                                        region = region,
                                    )
                                )
                                return@evaluateJavascript
                            }
                        }
                        handler.postDelayed({ poll() }, 500L)
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        handler.post { poll() }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) lastTitle = error.description?.toString() ?: ""
                    }
                }

                cont.invokeOnCancellation { runCatching { wv.stopLoading() } }
                wv.loadUrl(movie.url())
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

    private fun slugFromHref(href: String, fallback: Movie): Pair<String, String> {
        val m = Regex("""/movies/([a-z0-9-]+)/([a-z0-9-]+)/ET\d+""", RegexOption.IGNORE_CASE)
            .find(href)
        return if (m != null) m.groupValues[1].lowercase() to m.groupValues[2].lowercase()
        else fallback.region to fallback.slug
    }

    private fun cleanTitle(raw: String, doc: String): String {
        val stripped = raw.replace(Regex("""\s*-\s*\([^)]*\)\s*$"""), "").trim()
        if (stripped.isNotBlank()) return stripped
        return doc.substringBefore(" (").substringBefore(" - Movie").trim()
    }
}

private fun readJs(
    expectedCode: String,
    targetDate: String,
    movies: List<Movie>,
    needShows: Boolean,
): String {
    val groups = JSONArray()
    val codes = JSONArray()
    val titles = JSONArray()
    movies.forEach {
        if (it.group.isNotBlank()) groups.put(it.group)
        if (it.code.isNotBlank()) codes.put(it.code)
        if (it.title.isNotBlank()) titles.put(it.titleKey())
    }
    val watch = JSONObject()
        .put("date", targetDate)
        .put("needShows", needShows)
        .put("groups", groups)
        .put("codes", codes)
        .put("titles", titles)
        .toString()

    return READ_JS
        .replace("__WATCH__", watch)
        .replace("__CODE__", JSONObject.quote(expectedCode))
}

private const val READ_JS = """
(function () {
  var W = __WATCH__;
  var CODE = __CODE__;
  function norm(s) { return String(s || '').toLowerCase().replace(/[^a-z0-9]/g, ''); }
  try {
    var s = window.__INITIAL_STATE__;
    var vn = s && s.venueShowtimesNew;
    var sd = (vn && vn.showDates) || null;
    var hasDates = Array.isArray(sd);
    var codes = sd ? sd.filter(function (d) { return !d.isDisabled; }).map(function (d) { return d.DateCode; }) : [];
    var all = sd ? sd.map(function (d) { return d.DateCode + (d.isDisabled ? 'D' : ''); }) : [];
    var vd = (vn && vn.venueDetails) || null;
    var q = s && s.venueShowtimesFunctionalApi && s.venueShowtimesFunctionalApi.queries;
    var qk = null;
    if (q) {
      var exact = 'getShowtimesByVenue-' + CODE + '-' + W.date;
      if (q[exact]) qk = exact;
      else {
        var keys = Object.keys(q);
        for (var i = 0; i < keys.length; i++) {
          if (keys[i].indexOf('getShowtimesByVenue-' + CODE + '-') === 0) { qk = keys[i]; break; }
        }
      }
    }
    var data = qk && q[qk] && q[qk].data;
    var t = data && data.showDetailsTransformed;
    var shows = [];
    var events = [];
    if (t && t.Event) {
      t.Event.forEach(function (e) {
        var kids = e.ChildEvents || [];
        var hit = W.groups.indexOf(e.EventGroup) >= 0 || W.titles.indexOf(norm(e.EventTitle)) >= 0;
        if (!hit) {
          for (var j = 0; j < kids.length; j++) {
            if (W.codes.indexOf(kids[j].EventCode) >= 0) { hit = true; break; }
          }
        }
        var count = 0;
        function push(c, list) {
          (list || []).forEach(function (sh) {
            count++;
            if (hit && sh.ShowDateCode === W.date) {
              shows.push({
                group: e.EventGroup || '', code: c.EventCode || '', title: e.EventTitle || '',
                language: c.EventLanguage || '', time: sh.ShowTime || '', status: String(sh.AvailStatus || ''),
                session: String(sh.SessionId || ''), screen: sh.ScreenName || ''
              });
            }
          });
        }
        if (e.ShowTimes) push({}, e.ShowTimes);
        kids.forEach(function (c) { push(c, c.ShowTimes); });
        events.push({ group: e.EventGroup || '', title: e.EventTitle || '', shows: count });
      });
    }
    var day = vn && vn[W.date];
    var ready = !W.needShows || !hasDates || codes.indexOf(W.date) < 0 ||
      !!(day && (day.showtimesFound === false || (t && t.Event)));
    return {
      has: !!s, hasDates: hasDates, href: String(location.href), codes: codes, all: all,
      title: document.title || '',
      body: (document.body ? document.body.innerText : '').replace(/\s+/g, ' ').slice(0, 220),
      venueName: (vd && vd.VenueName) || '', venueCode: (vd && vd.VenueCode) || '',
      shows: shows.slice(0, 100), events: events.slice(0, 40), ready: ready
    };
  } catch (e) {
    return { has: false, hasDates: false, href: String(location.href), codes: [], all: [], title: '',
      body: 'ERR ' + e, shows: [], events: [], ready: true };
  }
})()
"""

private const val MOVIE_JS = """
(function () {
  var s = window.__INITIAL_STATE__;
  var q = s && s.synopsisMoviesApi && s.synopsisMoviesApi.queries;
  var out = null;
  if (q) {
    var keys = Object.keys(q);
    for (var i = 0; i < keys.length; i++) {
      var a = q[keys[i]] && q[keys[i]].data && q[keys[i]].data.eventState && q[keys[i]].data.eventState.analytics;
      if (a && a.event_code) {
        out = { group: a.event_group || '', code: a.event_code || '', title: a.title || '' };
        break;
      }
    }
  }
  return { has: !!s, href: String(location.href), title: document.title || '', movie: out };
})()
"""

private fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList { for (i in 0 until length()) add(optString(i)) }
}

private fun showsFrom(arr: JSONArray?): List<Show> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                Show(
                    group = o.optString("group"),
                    code = o.optString("code"),
                    title = o.optString("title"),
                    language = o.optString("language"),
                    time = o.optString("time"),
                    status = o.optString("status"),
                    session = o.optString("session"),
                    screen = o.optString("screen"),
                )
            )
        }
    }
}

private fun eventsFrom(arr: JSONArray?): List<EventSummary> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                EventSummary(
                    group = o.optString("group"),
                    title = o.optString("title"),
                    shows = o.optInt("shows"),
                )
            )
        }
    }
}

fun defaultUserAgent(context: Context): String = runCatching {
    WebSettings.getDefaultUserAgent(context)
}.getOrDefault("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
