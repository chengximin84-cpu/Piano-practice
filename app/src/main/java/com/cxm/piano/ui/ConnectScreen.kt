package com.cxm.piano.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cxm.piano.audio.Sound
import com.cxm.piano.audio.SoundOut
import com.cxm.piano.midi.LinkStatus
import com.cxm.piano.midi.PianoMidi

fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

fun hasBlePermissions(ctx: android.content.Context) =
    blePermissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun ConnectScreen(toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val link by PianoMidi.link.collectAsState()
    val found by PianoMidi.found.collectAsState()
    val scanning by PianoMidi.scanning.collectAsState()
    val log by PianoMidi.log.collectAsState()
    val out by Sound.output.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    var systemDevices by remember { mutableStateOf(PianoMidi.systemDevices()) }
    val recent = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        PianoMidi.events.collect { e ->
            if (e.down && e.fromPiano) {
                recent.add(0, "${noteName(e.pitch)}(${e.velocity})")
                while (recent.size > 16) recent.removeAt(recent.lastIndex)
            }
        }
    }

    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        PianoMidi.startScan(!showAll)
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) {
            startScanChecked(ctx, showAll) { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        } else toast("需要“附近设备”权限才能搜索蓝牙钢琴")
    }

    fun scan() {
        if (!hasBlePermissions(ctx)) perms.launch(blePermissions())
        else startScanChecked(ctx, showAll) { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
    }

    fun connectTo(action: () -> Unit) {
        when (link.status) {
            LinkStatus.CONNECTED -> toast("已连接：${link.deviceName}")
            LinkStatus.CONNECTING -> toast("正在连接，请稍候…")
            LinkStatus.DISCONNECTED -> action()
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Section {
                val (c, s) = when (link.status) {
                    LinkStatus.CONNECTED -> Palette.ok to "已连接：${link.deviceName}"
                    LinkStatus.CONNECTING -> Palette.accent to "正在连接：${link.deviceName}…"
                    LinkStatus.DISCONNECTED -> Palette.sub to "未连接"
                }
                Text(s, color = c, fontSize = 16.sp)
                if (!PianoMidi.midiSupported) Text("这台手机不支持 Android MIDI", color = Palette.wrong, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (scanning) PianoMidi.stopScan() else scan() },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.right),
                    ) { Text(if (scanning) "停止搜索" else "搜索蓝牙钢琴", color = Palette.bg) }
                    if (link.status != LinkStatus.DISCONNECTED) {
                        OutlinedButton(onClick = { PianoMidi.disconnect() }) { Text("断开", color = Palette.text) }
                    } else PianoMidi.lastDeviceName?.let { n ->
                        OutlinedButton(onClick = { if (hasBlePermissions(ctx)) PianoMidi.reconnectLast() else perms.launch(blePermissions()) }) {
                            Text("重连 $n", color = Palette.text, maxLines = 1)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = showAll, onCheckedChange = { showAll = it })
                    Text(
                        "显示所有蓝牙设备（搜不到钢琴时打开）", color = Palette.sub, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
    
            if (found.isNotEmpty() || scanning) {
                Section {
                    Text(if (scanning) "搜索中…（请确认钢琴蓝牙已打开）" else "搜索结果", color = Palette.text, fontSize = 14.sp)
                    for (d in found) {
                        Row(
                            Modifier.fillMaxWidth().clickable { connectTo { PianoMidi.connectBle(d.address, d.name) } }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name, color = Palette.text, fontSize = 14.sp)
                                Text(d.address, color = Palette.sub, fontSize = 11.sp)
                            }
                            if (d.isMidiService) Text("MIDI", color = Palette.ok, fontSize = 12.sp)
                            Text("  连接 ›", color = Palette.right, fontSize = 13.sp)
                        }
                    }
                }
            }
    
            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("系统里的 MIDI 设备（USB 线连接等）", color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("刷新", color = Palette.right, fontSize = 13.sp, modifier = Modifier.clickable { systemDevices = PianoMidi.systemDevices() })
                }
                if (systemDevices.isEmpty()) Text("无", color = Palette.sub, fontSize = 12.sp)
                for (info in systemDevices) {
                    val name = info.properties.getString(android.media.midi.MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI 设备 ${info.id}"
                    Row(
                        Modifier.fillMaxWidth().clickable { connectTo { PianoMidi.connectSystem(info) } }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("连接 ›", color = Palette.right, fontSize = 13.sp)
                    }
                }
            }
    
            Section {
                Text("按键测试", color = Palette.text, fontSize = 14.sp)
                Text("连上后在钢琴上随便弹几个音，这里应该显示音名（括号里是力度）", color = Palette.sub, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text(if (recent.isEmpty()) "—" else recent.joinToString("  "), color = Palette.accent, fontSize = 14.sp)
            }
    
            Section {
                Text("示范音从哪里发声", color = Palette.text, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    for (o in SoundOut.entries) {
                        val sel = o == out
                        Text(
                            o.label, fontSize = 13.sp, color = if (sel) Palette.bg else Palette.text,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (sel) Palette.right else Palette.panel2)
                                .clickable { Sound.setOutput(o) }.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
                Text("选“钢琴”会把伴奏/示范发给钢琴自己弹出来（需要琴支持 MIDI 输入）", color = Palette.sub, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
    
            if (log.isNotEmpty()) {
                Section {
                    Text("连接日志", color = Palette.text, fontSize = 14.sp)
                    for (l in log.take(12)) Text(l, color = Palette.sub, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun startScanChecked(ctx: android.content.Context, showAll: Boolean, requestEnable: () -> Unit) {
    val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    if (adapter == null) return
    if (!adapter.isEnabled) requestEnable() else PianoMidi.startScan(!showAll)
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.panel).padding(12.dp)) { content() }
}
