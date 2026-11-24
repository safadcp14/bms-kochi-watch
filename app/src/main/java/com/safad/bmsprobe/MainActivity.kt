package com.safad.bmsprobe

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

// ---------------------------------------------------------------- model

data class Venue(val code: String, val label: String, val url: String)

data class ProbeResult(
    val code: String,
    val url: String,
    val httpStatus: Int? = null,
    val title: String = "",
    val hasState: Boolean = false,
    val codes: List<String> = emptyList(),
    val bodyStart: String = "",
    val blocked: Boolean = false,
    val elapsedMs: Long = 0,
    val note: String = "",
    val error: String = "",
) {
    val sawTarget: Boolean get() = codes.any { it.startsWith("20261217") }

    val verdict: String
        get() = when {
            blocked -> "BLOCKED — Cloudflare refuses this device"
            error.isNotBlank() -> "ERROR — $error"
            hasState && sawTarget -> "WORKS — and 17 Dec is on sale"
            hasState -> "WORKS — page readable, no 17 Dec yet"
            else -> "NO DATA — page loaded but state missing"
        }

    fun toJson(): String = JSONObject().apply {
        put("code", code)
        put("url", url)
        put("httpStatus", httpStatus ?: -1)
        put("title", title)
        put("hasState", hasState)
        put("codes", codes.joinToString(","))
        put("blocked", blocked)
        put("elapsedMs", elapsedMs)
        put("note", note)
        put("bodyStart", bodyStart)
        put("error", error)
    }.toString(2)
}

private val VENUES = listOf(
    Venue("EVMK", "EVM Dolby (control — 17 Dec live)", "https://in.bookmyshow.com/cinemas/kochi/evm-dolby-cinema-fort-kochi/buytickets/EVMK"),
    Venue("PMCE", "Padma (target)", "https://in.bookmyshow.com/cinemas/kochi/padma-cinema-kochi/buytickets/PMCE"),
    Venue("SCKO", "Sarita (target)", "https://in.bookmyshow.com/cinemas/kochi/sarita-cinema-rgb-laser-4k-3d-atmos-kochi/buytickets/SCKO"),
)

// ---------------------------------------------------------------- probe engine

private const val PROBE_JS = """
(function () {
  try {
    var s = window.__INITIAL_STATE__;
    var vn = s && s.venueShowtimesNew;
    var codes = (vn && vn.showDates)
      ? vn.showDates.map(function (d) { return d.DateCode + (d.isDisabled ? '(D)' : ''); })
      : null;
    return {
      has: !!s,
      codes: codes,
      title: document.title || '',
      body: (document.body ? document.body.innerText : '').replace(/\s+/g, ' ').slice(0, 240)
    };
  } catch (e) {
    return { has: false, codes: null, title: '', body: 'JSERR ' + e };
  }
})()
"""

@SuppressLint("SetJavaScriptEnabled")
class ProbeRunner(context: Context) {

    val webView: WebView = WebView(context)
    private val handler = Handler(Looper.getMainLooper())

    init {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mediaPlaybackRequiresUserGesture = true
        s.blockNetworkImage = true
        // strip the "; wv" WebView marker so the UA looks like plain Chrome
        s.userAgentString = defaultUserAgent(context).replace("; wv", "")
        webView.setBackgroundColor(0x00000000)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    suspend fun probe(venue: Venue, timeoutMs: Long = 30000): ProbeResult =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val started = SystemClock.elapsedRealtime()
                val done = AtomicBoolean(false)
                var status: Int? = null
                var netError = ""
                var lastTitle = ""

                fun finish(result: ProbeResult) {
                    if (!done.compareAndSet(false, true)) return
                    handler.removeCallbacksAndMessages(null)
                    webView.stopLoading()
                    webView.webViewClient = WebViewClient()
                    if (cont.isActive) cont.resume(result)
                }

                fun build(
                    has: Boolean,
                    codes: List<String>,
                    body: String,
                    blocked: Boolean,
                    note: String,
                ) = ProbeResult(
                    code = venue.code,
                    url = venue.url,
                    httpStatus = status,
                    title = lastTitle,
                    hasState = has,
                    codes = codes,
                    bodyStart = body,
                    blocked = blocked,
                    elapsedMs = SystemClock.elapsedRealtime() - started,
                    note = note,
                    error = netError,
                )

                fun poll() {
                    if (done.get()) return
                    val elapsed = SystemClock.elapsedRealtime() - started
                    if (elapsed > timeoutMs) {
                        finish(build(false, emptyList(), "", false, "timeout"))
                        return
                    }
                    webView.evaluateJavascript(PROBE_JS) { raw ->
                        if (done.get()) return@evaluateJavascript
                        val obj = runCatching { JSONObject(raw ?: "") }.getOrNull()
                        if (obj != null) {
                            val has = obj.optBoolean("has")
                            val body = obj.optString("body")
                            val title = obj.optString("title")
                            if (title.isNotBlank()) lastTitle = title

                            val blocked = body.contains("have been blocked", true) ||
                                title.contains("Attention Required", true) ||
                                body.contains("Access Denied", true) ||
                                body.contains("Just a moment", true)

                            val arr = obj.optJSONArray("codes")
                            val codes = buildList {
                                if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i))
                            }

                            if (blocked) {
                                finish(build(has, codes, body, true, "blocked"))
                                return@evaluateJavascript
                            }
                            if (has && codes.isNotEmpty()) {
                                finish(build(has, codes, body, false, "ok"))
                                return@evaluateJavascript
                            }
                        }
                        handler.postDelayed({ poll() }, 600L)
                    }
                }

                webView.webViewClient = object : WebViewClient() {
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

                cont.invokeOnCancellation { webView.stopLoading() }

                webView.loadUrl(venue.url)
            }
        }
}

private fun defaultUserAgent(context: Context): String = runCatching {
    WebSettings.getDefaultUserAgent(context)
}.getOrDefault("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

// ---------------------------------------------------------------- UI

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFE23744),
                    surface = Color(0xFF141416),
                    background = Color(0xFF0B0B0C),
                )
            ) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Screen()
                }
            }
        }
    }
}

@Composable
private fun Screen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var runner by remember { mutableStateOf<ProbeRunner?>(null) }
    var results by remember { mutableStateOf<List<ProbeResult>>(emptyList()) }
    var running by remember { mutableStateOf(false) }

    val webViewVersion = remember {
        runCatching {
            val p = WebViewCompat.getCurrentWebViewPackage(context)
            "${p?.packageName} ${p?.versionName}"
        }.getOrDefault("unknown")
    }
    val ua = remember { runner?.webView?.settings?.userAgentString ?: defaultUserAgent(context) }

    Box(Modifier.fillMaxSize()) {

        // Off-screen, attached WebView so it has a real window and lifecycle.
        AndroidView(
            factory = { ctx -> ProbeRunner(ctx).also { runner = it }.webView },
            modifier = Modifier.size(1.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header() }

            item {
                InfoCard(
                    rows = listOf(
                        "Android" to "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})",
                        "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                        "WebView" to webViewVersion,
                    ),
                    ua = ua,
                )
            }

            item {
                Button(
                    onClick = {
                        val r = runner ?: return@Button
                        running = true
                        results = emptyList()
                        scope.launch {
                            val acc = mutableListOf<ProbeResult>()
                            for (v in VENUES) {
                                acc += r.probe(v)
                                results = acc.toList()
                                delay(1200)
                            }
                            running = false
                        }
                    },
                    enabled = !running && runner != null,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Probing…")
                    } else {
                        Text("Run probe", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (results.isEmpty()) {
                item { HintCard() }
            }

            items(results) { r ->
                ResultCard(r) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("probe", r.toJson()))
                }
            }

            item {
                Text(
                    "Reading window.__INITIAL_STATE__.venueShowtimesNew.showDates from the venue page, " +
                        "using the system WebView. No movie-name matching, no seat maps.",
                    fontSize = 11.sp,
                    color = Color(0xFF77777F),
                    modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text("BMS Probe", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "Does the WebView get through Cloudflare, and can it see 17 Dec?",
            fontSize = 13.sp,
            color = Color(0xFF9A9AA2),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun InfoCard(rows: List<Pair<String, String>>, ua: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1E)), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (k, v) ->
                Row {
                    Text(k, fontSize = 12.sp, color = Color(0xFF8A8A92), modifier = Modifier.width(72.dp))
                    Text(v, fontSize = 12.sp, color = Color(0xFFE6E6EA))
                }
            }
            Text(
                "UA: $ua",
                fontSize = 9.sp,
                color = Color(0xFF6A6A72),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun HintCard() {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1E)), shape = RoundedCornerShape(14.dp)) {
        Text(
            "Tap Run probe. It loads three venue pages in the background, one after another. " +
                "EVMK is the control: it has 17 Dec on sale, so if that one fails, the app is being blocked " +
                "and the whole approach needs rethinking.",
            fontSize = 12.sp,
            color = Color(0xFF9A9AA2),
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun ResultCard(r: ProbeResult, onCopy: () -> Unit) {
    val accent = when {
        r.blocked -> Color(0xFFE23744)
        r.error.isNotBlank() -> Color(0xFFE28A37)
        r.hasState -> Color(0xFF35C759)
        else -> Color(0xFF8A8A92)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF17171A)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(accent, RoundedCornerShape(50))
                )
                Spacer(Modifier.width(8.dp))
                Text(r.code, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "HTTP ${r.httpStatus ?: "—"}  ·  ${r.elapsedMs} ms",
                    fontSize = 11.sp,
                    color = Color(0xFF77777F),
                )
            }

            Text(
                r.verdict,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )

            if (r.codes.isNotEmpty()) {
                Text(
                    r.codes.joinToString("  "),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFCFCFD6),
                )
            }

            if (r.title.isNotBlank()) {
                Text("title: ${r.title}", fontSize = 10.sp, color = Color(0xFF77777F))
            }

            if (r.bodyStart.isNotBlank()) {
                Text(
                    r.bodyStart,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF5E5E66),
                    maxLines = 3,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "copy json",
                    fontSize = 11.sp,
                    color = Color(0xFF6EA8FF),
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable { onCopy() },
                )
            }
        }
    }
}
