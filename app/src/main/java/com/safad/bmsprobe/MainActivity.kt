package com.safad.bmsprobe

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.core.app.NotificationManagerCompat
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BG = Color(0xFF0B0B0C)
private val CARD = Color(0xFF17171A)
private val SUB = Color(0xFF8A8A92)
private val GREEN = Color(0xFF35C759)
private val RED = Color(0xFFE23744)
private val AMBER = Color(0xFFE2A137)
private val BLUE = Color(0xFF6EA8FF)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.createChannels(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = RED, background = BG, surface = CARD)) {
                Surface(color = BG, modifier = Modifier.fillMaxSize()) {
                    Root()
                }
            }
        }
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    val cfg by WatchState.configVersion.collectAsState()

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            !NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val subtitle = remember(cfg) {
        val venues = Prefs.watchedVenues(ctx)
        val movies = Prefs.movies(ctx)
        buildString {
            append(venues.size)
            append(if (venues.size == 1) " cinema" else " cinemas")
            append(" · ")
            append(
                if (movies.isEmpty()) "any movie"
                else movies.joinToString(", ") { it.title }
            )
            append(" · ")
            append(humanDateShort(Prefs.target(ctx)))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp)) {
            Text("BMS Watch", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(subtitle, fontSize = 12.sp, color = SUB, modifier = Modifier.padding(top = 3.dp))
        }

        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            TabPill("Watch", tab == 0) { tab = 0 }
            Spacer(Modifier.width(8.dp))
            TabPill("Diagnose", tab == 1) { tab = 1 }
        }

        if (tab == 0) WatchScreen() else DiagnoseScreen()
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Color(0xFF2A2A30) else Color(0xFF141416), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 12.sp, color = if (selected) Color.White else SUB)
    }
}

// ------------------------------------------------------------------ watch

@Composable
private fun WatchScreen() {
    val ctx = LocalContext.current
    val running by WatchState.running.collectAsState()
    val venues by WatchState.venues.collectAsState()
    val target by WatchState.targetDate.collectAsState()
    val nextAt by WatchState.nextCycleAt.collectAsState()
    val degraded by WatchState.degraded.collectAsState()
    val cfg by WatchState.configVersion.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var hist by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var confirmStop by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    LaunchedEffect(running, nextAt, cfg) {
        hist = History.recent(ctx, 30)
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop watching?") },
            text = { Text("The watcher stops and you will not be alerted when tickets open.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    WatchService.stop(ctx)
                }) { Text("Stop", color = RED) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text("Keep watching") }
            },
        )
    }

    if (editing) {
        ConfigDialog(
            currentTarget = Prefs.target(ctx),
            currentVenues = Prefs.watchedVenues(ctx),
            currentMovies = Prefs.movies(ctx),
            onDismiss = { editing = false },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(2.dp)) }

        item {
            StatusCard(running, nextAt - now, target, degraded)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (running) confirmStop = true else WatchService.start(ctx)
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (running) Color(0xFF2A2A30) else RED,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(if (running) "Stop" else "Start watching", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { WatchService.pollNow(ctx) },
                    modifier = Modifier.weight(0.6f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CARD, contentColor = Color.White),
                ) {
                    Text("Poll now", fontSize = 12.sp)
                }
            }
        }

        items(venues) { v -> VenueCard(v, target) }

        item { ConfigCard(target) { editing = true } }

        item { BatteryCard() }

        item {
            Button(
                onClick = {
                    val v = Prefs.watchedVenues(ctx).firstOrNull()
                        ?: SEED_VENUES.first()
                    val movie = Prefs.movies(ctx).firstOrNull()
                    val date = Prefs.target(ctx)
                    Siren.start(ctx)
                    Notifier.alarm(
                        ctx,
                        AlertInfo(
                            venue = v,
                            targetDate = date,
                            movie = movie?.title,
                            times = listOf("10:15 AM", "1:30 PM", "9:45 PM"),
                            url = v.urlFor(date),
                            key = "TEST",
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CARD, contentColor = AMBER),
            ) {
                Text("Test the alarm (plays siren for real)", fontSize = 12.sp)
            }
        }

        item {
            Text(
                "HISTORY",
                fontSize = 10.sp,
                color = SUB,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (hist.isEmpty()) {
            item { Text("No polls recorded yet.", fontSize = 12.sp, color = SUB) }
        }

        items(hist) { h ->
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        History.label(h.at),
                        fontSize = 10.sp,
                        color = Color(0xFF6A6A72),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(104.dp),
                    )
                    Text(h.code, fontSize = 11.sp, color = Color(0xFFCFCFD6), modifier = Modifier.width(52.dp))
                    Text(
                        h.status,
                        fontSize = 10.sp,
                        color = statusColor(h.status),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${h.latencyMs} ms", fontSize = 9.sp, color = Color(0xFF5E5E66))
                }
                if (h.detail.isNotBlank()) {
                    Text(
                        h.detail,
                        fontSize = 9.sp,
                        color = Color(0xFF7D7D86),
                        modifier = Modifier.padding(start = 104.dp, top = 1.dp),
                    )
                }
            }
        }

        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun StatusCard(running: Boolean, msToNext: Long, target: String, degraded: Boolean) {
    val ctx = LocalContext.current
    val accent = when {
        degraded -> AMBER
        running -> GREEN
        else -> SUB
    }
    val venues = Prefs.watchedVenues(ctx)
    val movies = Prefs.movies(ctx)
    Card(colors = CardDefaults.cardColors(containerColor = CARD), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(accent, RoundedCornerShape(50)))
                Spacer(Modifier.width(9.dp))
                Text(
                    when {
                        degraded -> "DEGRADED"
                        running -> "WATCHING"
                        else -> "STOPPED"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
                Spacer(Modifier.weight(1f))
                if (running && msToNext > 0) {
                    Text("next ${(msToNext / 1000).coerceAtLeast(0)}s", fontSize = 11.sp, color = SUB)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    append(humanDateShort(target))
                    append(" · ")
                    append(venues.size)
                    append(if (venues.size == 1) " cinema" else " cinemas")
                    append(" · ")
                    append(if (movies.isEmpty()) "date only" else "${movies.size} movie(s)")
                    append(" · every ${Prefs.interval(ctx)}s")
                },
                fontSize = 11.sp,
                color = SUB,
            )
        }
    }
}

@Composable
private fun VenueCard(v: VenueState, target: String) {
    val accent = statusColor(v.status)
    Card(colors = CardDefaults.cardColors(containerColor = CARD), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(accent, RoundedCornerShape(50)))
                Spacer(Modifier.width(8.dp))
                Text(v.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Spacer(Modifier.weight(1f))
                Text(v.code, fontSize = 10.sp, color = SUB, fontFamily = FontFamily.Monospace)
            }

            Spacer(Modifier.height(7.dp))
            Text(
                if (v.codes.isEmpty()) "no dates read yet" else v.codes.joinToString("  "),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = if (v.codes.contains(target)) GREEN else Color(0xFFCFCFD6),
            )

            if (v.matches.any { it.title != "Any movie" }) {
                Spacer(Modifier.height(7.dp))
                v.matches.filter { it.title != "Any movie" }.forEach { m ->
                    Text(
                        buildString {
                            append(m.title)
                            if (m.times.isNotEmpty()) {
                                append(" · ")
                                append(m.times.take(4).joinToString(", "))
                                if (m.times.size > 4) append(" +${m.times.size - 4}")
                            }
                        },
                        fontSize = 11.sp,
                        color = GREEN,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    if (v.lastAt > 0) {
                        append("checked ${((System.currentTimeMillis() - v.lastAt) / 1000)}s ago")
                        append(" · ${v.latencyMs} ms")
                    } else append("waiting for first poll")
                    if (v.failures > 0) append(" · ${v.failures} fails")
                },
                fontSize = 10.sp,
                color = Color(0xFF6A6A72),
            )
        }
    }
}

@Composable
private fun ConfigCard(target: String, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val venues = Prefs.watchedVenues(ctx)
    val movies = Prefs.movies(ctx)

    Card(colors = CardDefaults.cardColors(containerColor = CARD), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WATCH CONFIG", fontSize = 9.sp, color = SUB, letterSpacing = 1.1.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "LOCKED",
                    fontSize = 9.sp,
                    color = Color(0xFF6A6A72),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(Color(0xFF232327), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            Row {
                Text("Date", fontSize = 11.sp, color = SUB, modifier = Modifier.width(60.dp))
                Text(humanDate(target), fontSize = 12.sp, color = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(target, fontSize = 10.sp, color = Color(0xFF6A6A72), fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(5.dp))
            Row {
                Text("Cinemas", fontSize = 11.sp, color = SUB, modifier = Modifier.width(60.dp))
                Text(
                    venues.joinToString("  ·  ") { it.code },
                    fontSize = 12.sp,
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(5.dp))
            Row {
                Text("Movies", fontSize = 11.sp, color = SUB, modifier = Modifier.width(60.dp))
                Text(
                    if (movies.isEmpty()) "Any movie (date only)"
                    else movies.joinToString("  ·  ") { it.title },
                    fontSize = 12.sp,
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(5.dp))
            Row {
                Text("Every", fontSize = 11.sp, color = SUB, modifier = Modifier.width(60.dp))
                Text("${Prefs.interval(ctx)} seconds", fontSize = 12.sp, color = Color.White)
            }

            Spacer(Modifier.height(9.dp))
            Text(
                "Locked so a pocket tap cannot change what it watches.",
                fontSize = 10.sp,
                color = Color(0xFF5E5E66),
            )
            TextButton(onClick = onEdit) {
                Text("Change (needs confirmation)", fontSize = 11.sp, color = BLUE)
            }
        }
    }
}

@Composable
private fun ConfigDialog(
    currentTarget: String,
    currentVenues: List<Venue>,
    currentMovies: List<Movie>,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf(false) }
    var phrase by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(currentTarget) }
    var venues by remember { mutableStateOf(currentVenues) }
    var movies by remember { mutableStateOf(currentMovies) }
    var interval by remember { mutableStateOf(Prefs.interval(ctx)) }
    var venueInput by remember { mutableStateOf("") }
    var venueError by remember { mutableStateOf("") }
    var movieInput by remember { mutableStateOf("") }
    var movieError by remember { mutableStateOf("") }
    var resolving by remember { mutableStateOf(false) }

    if (!unlocked) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Locked") },
            text = {
                Column {
                    Text(
                        "This decides what the watcher looks for and what wakes you up. " +
                            "Type EDIT to unlock it.",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = phrase,
                        onValueChange = { phrase = it },
                        singleLine = true,
                        label = { Text("Type EDIT") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { unlocked = true },
                    enabled = phrase.trim().equals("EDIT", ignoreCase = true),
                ) { Text("Unlock") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }

    val dateOk = date.length == 8 && date.all { it.isDigit() }
    val canSave = dateOk && venues.isNotEmpty() && !resolving

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watch config") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = date,
                    onValueChange = { s -> if (s.length <= 8 && s.all { it.isDigit() }) date = s },
                    singleLine = true,
                    label = { Text("Target date · yyyyMMdd") },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (dateOk) humanDate(date) else "needs 8 digits",
                    fontSize = 11.sp,
                    color = if (dateOk) SUB else AMBER,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (movies.isEmpty()) {
                    Text(
                        "No movie selected: any date opening fires the alarm.",
                        fontSize = 10.sp,
                        color = Color(0xFF5E5E66),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text("Cinemas", fontSize = 11.sp, color = SUB)
                venues.forEach { v ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(v.name, fontSize = 12.sp, color = Color.White)
                            Text(
                                "${v.code} · ${v.region}/${v.slug}",
                                fontSize = 9.sp,
                                color = Color(0xFF6A6A72),
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        TextButton(onClick = { venues = venues.filterNot { it.code == v.code } }) {
                            Text("Remove", fontSize = 10.sp, color = RED)
                        }
                    }
                }
                if (venues.isEmpty()) {
                    Text("At least one cinema has to stay on.", fontSize = 10.sp, color = AMBER)
                }
                OutlinedTextField(
                    value = venueInput,
                    onValueChange = { venueInput = it; venueError = "" },
                    singleLine = true,
                    label = { Text("Cinema URL") },
                    placeholder = { Text("…/cinemas/kochi/name/buytickets/CODE", fontSize = 10.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(venueError, fontSize = 10.sp, color = AMBER, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            val v = BmsLinks.parseVenue(venueInput)
                            if (v == null) {
                                venueError = "Paste the cinema page URL (…/buytickets/CODE)."
                            } else if (venues.any { it.code == v.code }) {
                                venueError = "${v.code} is already watched."
                            } else {
                                venues = venues + v
                                venueInput = ""
                                venueError = ""
                            }
                        },
                        enabled = venueInput.isNotBlank(),
                    ) { Text("Add cinema", fontSize = 11.sp) }
                }

                Spacer(Modifier.height(14.dp))
                Text("Movies (optional)", fontSize = 11.sp, color = SUB)
                movies.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.title, fontSize = 12.sp, color = Color.White)
                            Text(
                                if (m.group.isNotBlank()) "${m.code} → ${m.group}"
                                else "${m.code} · group unresolved",
                                fontSize = 9.sp,
                                color = if (m.group.isNotBlank()) Color(0xFF6A6A72) else AMBER,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        TextButton(onClick = { movies = movies.filterNot { it.key == m.key } }) {
                            Text("Remove", fontSize = 10.sp, color = RED)
                        }
                    }
                }
                if (movies.isNotEmpty()) {
                    Text(
                        "Alarms only when one of these films has a show on the target date.",
                        fontSize = 10.sp,
                        color = Color(0xFF5E5E66),
                    )
                }
                OutlinedTextField(
                    value = movieInput,
                    onValueChange = { movieInput = it; movieError = "" },
                    singleLine = true,
                    label = { Text("Movie URL") },
                    placeholder = { Text("…/movies/kochi/name/ET00523989", fontSize = 10.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(movieError, fontSize = 10.sp, color = AMBER, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            val parsed = BmsLinks.parseMovie(movieInput)
                            if (parsed == null) {
                                movieError = "Paste the movie page URL (…/movies/…/ET…)."
                                return@TextButton
                            }
                            resolving = true
                            movieError = ""
                            scope.launch {
                                // One-off WebView: the ET code in the URL is a single
                                // language/format, so we resolve the movie-level EG group.
                                val engine = PollEngine(ctx)
                                val resolved = try {
                                    runCatching { engine.resolveMovie(parsed) }.getOrNull()
                                } finally {
                                    engine.close()
                                }
                                val movie = if (resolved != null) {
                                    Movie(
                                        code = resolved.code,
                                        group = resolved.group,
                                        title = resolved.title,
                                        slug = resolved.slug,
                                        region = resolved.region,
                                    )
                                } else parsed
                                movies = (movies + movie).distinctBy { it.key }
                                movieError = if (resolved == null) {
                                    "Could not read the page; matching by ET code and title only."
                                } else ""
                                movieInput = ""
                                resolving = false
                            }
                        },
                        enabled = movieInput.isNotBlank() && !resolving,
                    ) { Text(if (resolving) "Resolving…" else "Add movie", fontSize = 11.sp) }
                }

                Spacer(Modifier.height(16.dp))
                Text("Check every", fontSize = 11.sp, color = SUB)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 120, 300).forEach { s ->
                        FilterChip(
                            selected = interval == s,
                            onClick = { interval = s },
                            label = { Text("${s}s", fontSize = 11.sp) },
                        )
                    }
                }
                Text(
                    "30s is the floor: faster gets the phone's IP rate-limited.",
                    fontSize = 10.sp,
                    color = Color(0xFF5E5E66),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    Prefs.setTarget(ctx, date)
                    Prefs.setVenues(ctx, venues)
                    Prefs.setMovies(ctx, movies)
                    Prefs.setInterval(ctx, interval)
                    WatchState.targetDate.value = date
                    WatchState.intervalSec.value = interval
                    WatchState.resetVenues(Prefs.watchedVenues(ctx))
                    WatchState.configVersion.value++
                    onDismiss()
                },
                enabled = canSave,
            ) { Text("Save & lock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BatteryCard() {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    if (ignoring) return
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A1A)), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("Battery optimisation is ON", fontSize = 12.sp, color = Color(0xFFFFB3B8), fontWeight = FontWeight.SemiBold)
            Text(
                "Android will throttle the poller within the hour. Allow unrestricted background use.",
                fontSize = 11.sp,
                color = SUB,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${ctx.packageName}"),
                            )
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = RED, contentColor = Color.White),
            ) { Text("Allow", fontSize = 12.sp) }
        }
    }
}

private fun statusColor(status: String): Color = when (status.uppercase()) {
    "HIT" -> RED
    "OK" -> GREEN
    "CHALLENGE" -> AMBER
    "IDLE" -> SUB
    else -> Color(0xFFB0562F)
}

// ------------------------------------------------------------------ diagnose

@Composable
private fun DiagnoseScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { PollEngine(ctx) }
    var busy by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<Pair<Venue, LoadResult>>>(emptyList()) }
    val cfg by WatchState.configVersion.collectAsState()

    DisposableEffect(Unit) { onDispose { engine.close() } }

    val wv = remember {
        runCatching {
            val p = WebViewCompat.getCurrentWebViewPackage(ctx)
            "${p?.versionName}"
        }.getOrDefault("unknown")
    }

    val venues = remember(cfg) { Prefs.watchedVenues(ctx) }
    val movies = remember(cfg) { Prefs.movies(ctx) }
    val target = remember(cfg) { Prefs.target(ctx) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(2.dp)) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CARD), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Info("Android", "API ${Build.VERSION.SDK_INT}")
                    Info("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    Info("WebView", wv)
                    Info("Date", "$target · ${humanDateShort(target)}")
                    Info("Cinemas", venues.joinToString(", ") { it.code })
                    Info("Movies", if (movies.isEmpty()) "any" else movies.joinToString(", ") { it.title })
                    Text(
                        "ua has ; wv stripped",
                        fontSize = 9.sp,
                        color = Color(0xFF5E5E66),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        item {
            Button(
                onClick = {
                    busy = true
                    rows = emptyList()
                    scope.launch {
                        for (v in venues) {
                            val r = engine.load(
                                v.urlFor(target),
                                v.code,
                                timeoutMs = 45_000,
                                targetDate = target,
                                movies = movies,
                            )
                            rows = rows + (v to r)
                            delay(800)
                        }
                        busy = false
                    }
                },
                enabled = !busy && venues.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RED, contentColor = Color.White),
            ) { Text(if (busy) "Probing…" else "Run probe", fontWeight = FontWeight.SemiBold) }
        }
        item {
            Text(
                "Probes each cinema's page for the target date. A Cloudflare challenge is not a block — " +
                    "the watcher retries on the next cycle.",
                fontSize = 11.sp,
                color = SUB,
            )
        }
        items(rows.size) { i ->
            val (v, r) = rows[i]
            val accent = when {
                r.ok -> GREEN
                r.challenge -> AMBER
                else -> RED
            }
            Card(colors = CardDefaults.cardColors(containerColor = CARD), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(accent, RoundedCornerShape(50)))
                        Spacer(Modifier.width(8.dp))
                        Text(v.code, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${r.elapsedMs} ms", fontSize = 10.sp, color = SUB)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            r.ok -> "READ OK"
                            r.challenge -> "CHALLENGE (Cloudflare, not blocked)"
                            else -> "FAILED · ${r.error}"
                        },
                        fontSize = 12.sp,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (r.codes.isNotEmpty()) {
                        Text(
                            r.codes.joinToString("  "),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (r.codes.contains(target)) GREEN else Color(0xFFCFCFD6),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (r.events.isNotEmpty()) {
                        Text(
                            r.events.take(6).joinToString(" · ") { "${it.title} (${it.shows})" },
                            fontSize = 9.sp,
                            color = Color(0xFF8A8A92),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (movies.isNotEmpty() && r.ok) {
                        Text(
                            if (r.shows.isEmpty()) "no watched movie on this date"
                            else "matched: " + r.shows.map { it.title }.distinct().joinToString(", "),
                            fontSize = 10.sp,
                            color = if (r.shows.isEmpty()) SUB else GREEN,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (r.title.isNotBlank()) {
                        Text(r.title, fontSize = 9.sp, color = Color(0xFF5E5E66), modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun Info(k: String, v: String) {
    Row {
        Text(k, fontSize = 11.sp, color = SUB, modifier = Modifier.width(70.dp))
        Text(v, fontSize = 11.sp, color = Color(0xFFE6E6EA))
    }
}
