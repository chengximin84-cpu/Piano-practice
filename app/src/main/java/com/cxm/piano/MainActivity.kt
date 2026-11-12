package com.cxm.piano

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cxm.piano.audio.Synth
import com.cxm.piano.data.SongEntry
import com.cxm.piano.data.SongLibrary
import com.cxm.piano.midi.PianoMidi
import com.cxm.piano.midi.Song
import com.cxm.piano.practice.PracticeScreen
import com.cxm.piano.stats.PracticeStats
import com.cxm.piano.ui.ConnectScreen
import com.cxm.piano.ui.LibraryScreen
import com.cxm.piano.ui.Palette
import com.cxm.piano.ui.StatsScreen
import com.cxm.piano.ui.hasBlePermissions

private val Tabs = listOf("♫" to "曲库", "▦" to "统计", "ᛒ" to "连接")

class MainActivity : ComponentActivity() {
    private var song by mutableStateOf<Song?>(null)
    private var tab by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        if (hasBlePermissions(this)) PianoMidi.reconnectLast()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.right, background = Palette.bg, surface = Palette.panel)) {
                Box(Modifier.fillMaxSize().background(Palette.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    val s = song
                    if (s != null) {
                        PracticeScreen(s, onBack = { song = null })
                    } else BoxWithConstraints(Modifier.fillMaxSize()) {
                        // Tablets and unfolded devices get a persistent side rail and denser content.
                        val wide = maxWidth >= 840.dp
                        val open: (SongEntry) -> Unit = { e ->
                            runCatching { SongLibrary.load(e) }
                                .onSuccess { song = it }
                                .onFailure { toast("打不开：${it.message}") }
                        }
                        val body: @Composable () -> Unit = {
                            when (tab) {
                                0 -> LibraryScreen(open, onGoConnect = { tab = 2 }, toast = ::toast, columns = if (wide) 2 else 1)
                                1 -> StatsScreen(wide = wide)
                                else -> ConnectScreen(toast = ::toast)
                            }
                        }
                        if (wide) {
                            Row(Modifier.fillMaxSize()) {
                                NavigationRail(containerColor = Palette.panel, header = {
                                    Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("♬", color = Palette.right, fontSize = 24.sp)
                                    }
                                }) {
                                    Tabs.forEachIndexed { i, (icon, label) ->
                                        NavigationRailItem(
                                            selected = tab == i, onClick = { tab = i },
                                            icon = { Text(icon, fontSize = 18.sp) },
                                            label = { Text(label, fontSize = 11.sp) },
                                            colors = NavigationRailItemDefaults.colors(
                                                selectedIconColor = Palette.right, selectedTextColor = Palette.right,
                                                unselectedIconColor = Palette.sub, unselectedTextColor = Palette.sub,
                                                indicatorColor = Palette.panel2,
                                            ),
                                        )
                                    }
                                }
                                Box(Modifier.weight(1f)) { body() }
                            }
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f)) { body() }
                                NavigationBar(containerColor = Palette.panel) {
                                    val colors = NavigationBarItemDefaults.colors(
                                        selectedTextColor = Palette.right, unselectedTextColor = Palette.sub,
                                        selectedIconColor = Palette.right, unselectedIconColor = Palette.sub,
                                        indicatorColor = Palette.panel2,
                                    )
                                    Tabs.forEachIndexed { i, (icon, label) ->
                                        NavigationBarItem(
                                            selected = tab == i, onClick = { tab = i },
                                            icon = { Text(icon, fontSize = 18.sp) }, label = { Text(label) }, colors = colors,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        runCatching { SongLibrary.import(this, uri) }
            .mapCatching { SongLibrary.load(it) }
            .onSuccess { song = it; toast("已导入：${it.title}") }
            .onFailure { toast("导入失败：${it.message}") }
    }

    override fun onPause() {
        super.onPause()
        PracticeStats.flush()
    }

    override fun onStop() {
        super.onStop()
        if (song == null) Synth.stop()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
