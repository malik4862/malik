package com.example.binancefuturesbot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    private val channelId = "signals_channel"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        val engine = SignalEngine.getInstance(applicationContext)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppShell(engine)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val name = "Trading Signals"
        val descr = "Notifications for new trading signals"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = android.app.NotificationChannel(channelId, name, importance).apply {
            description = descr
            val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            setSound(defaultSound, null)
            enableVibration(true)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(engine: SignalEngine) {
    var selected by remember { mutableStateOf(0) }
    val tabs = listOf("Live", "History", "Settings")

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        TabRow(selectedTabIndex = selected) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = selected == i, onClick = { selected = i }, text = { Text(t) })
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        when (selected) {
            0 -> LiveScreen(engine)
            1 -> HistoryScreen(engine)
            2 -> SettingsScreen(engine)
        }
    }
}

@Composable
fun LiveScreen(engine: SignalEngine) {
    var running by remember { mutableStateOf(engine.isRunning) }
    val signals by engine.signals.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (!engine.isRunning) engine.start()
                running = engine.isRunning
            }) { Text("Start") }
            Button(onClick = {
                engine.stop()
                running = engine.isRunning
            }) { Text("Stop") }
            Text(if (running) "Running" else "Stopped", modifier = Modifier.padding(8.dp))
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text("Recent signals:", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(signals) { s ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${'$'}{s.symbol} — ${'$'}{s.side} @ ${'$'}{s.price}", style = MaterialTheme.typography.titleSmall)
                        Text("Reason: ${'$'}{s.reason}", style = MaterialTheme.typography.bodySmall)
                        Text("Time: ${'$'}{s.time}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(engine: SignalEngine) {
    val history by engine.history.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text("Signal History", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(history) { s ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${'$'}{s.symbol} — ${'$'}{s.side} @ ${'$'}{s.price}", style = MaterialTheme.typography.titleSmall)
                        Text("Reason: ${'$'}{s.reason}", style = MaterialTheme.typography.bodySmall)
                        Text("Time: ${'$'}{s.time}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(engine: SignalEngine) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Indicators: EMA(12,26) + MACD + RSI(14)", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Alerts: Sound + Vibration enabled", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Note: This app only provides signals. No auto-trading included.", style = MaterialTheme.typography.bodySmall)
    }
}
