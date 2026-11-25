package com.safad.bmsprobe

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Notifier.createChannels(this)

        val code = intent.getStringExtra("code") ?: "PMCE"
        val name = intent.getStringExtra("name") ?: code
        val target = intent.getStringExtra("target") ?: Prefs.target(this)
        val movie = intent.getStringExtra("movie")?.takeIf { it.isNotBlank() }
        val times = intent.getStringArrayListExtra("times").orEmpty()
        val url = intent.getStringExtra("url")
            ?: Prefs.watchedVenues(this).firstOrNull { it.code == code }?.urlFor(target)
            ?: return

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AlarmScreen(name, target, movie, times, url) { silence() }
            }
        }
    }

    private fun silence() {
        Siren.stop(this)
        Notifier.cancelAlarms(this)
        finish()
    }

    override fun onDestroy() {
        Siren.stop(this)
        super.onDestroy()
    }
}

@Composable
private fun AlarmScreen(
    name: String,
    target: String,
    movie: String?,
    times: List<String>,
    url: String,
    onSilence: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFB3121C))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            movie?.uppercase() ?: humanDateShort(target).uppercase(),
            fontSize = if (movie != null) 34.sp else 44.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (movie != null) "TICKETS ARE LIVE · ${humanDateShort(target).uppercase()}" else "TICKETS ARE OPEN",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFFD9DB),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Text(name, fontSize = 17.sp, color = Color.White, textAlign = TextAlign.Center)
        Text(humanDate(target), fontSize = 13.sp, color = Color(0xFFFFB3B8))
        if (times.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                times.take(5).joinToString(" · "),
                fontSize = 15.sp,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(40.dp))

        Button(
            onClick = {
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                onSilence()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB3121C)),
        ) {
            Text("Open BookMyShow", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onSilence,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF), contentColor = Color.White),
        ) {
            Text("Silence")
        }
    }
}
