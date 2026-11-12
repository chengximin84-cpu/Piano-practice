package com.cxm.piano.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cxm.piano.data.SongEntry
import com.cxm.piano.data.SongLibrary
import com.cxm.piano.midi.LinkStatus
import com.cxm.piano.midi.PianoMidi
import com.cxm.piano.stats.PracticeStats

@Composable
fun LibraryScreen(onOpen: (SongEntry) -> Unit, onGoConnect: () -> Unit, toast: (String) -> Unit, columns: Int = 1) {
    val ctx = LocalContext.current
    val entries by SongLibrary.entries.collectAsState()
    val link by PianoMidi.link.collectAsState()
    val version by PracticeStats.version.collectAsState()
    var renaming by remember { mutableStateOf<SongEntry?>(null) }
    var deleting by remember { mutableStateOf<SongEntry?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        var ok = 0
        val errors = mutableListOf<String>()
        for (u in uris) {
            runCatching { SongLibrary.import(ctx, u) }
                .onSuccess { ok++ }
                .onFailure { errors += (it.message ?: "未知错误") }
        }
        if (uris.isNotEmpty()) toast(if (errors.isEmpty()) "已导入 $ok 首" else "导入 $ok 首，失败 ${errors.size} 首：${errors.first()}")
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(8.dp)).background(Palette.panel)
                .clickable(onClick = onGoConnect).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (dot, msg) = when (link.status) {
                LinkStatus.CONNECTED -> Palette.ok to "已连接：${link.deviceName}"
                LinkStatus.CONNECTING -> Palette.accent to "正在连接 ${link.deviceName}…"
                LinkStatus.DISCONNECTED -> Palette.sub to "未连接钢琴，点这里连接（也可以用屏幕键盘练）"
            }
            Box(Modifier.clip(RoundedCornerShape(50)).background(dot).padding(4.dp))
            Spacer(Modifier.width(8.dp))
            Text(msg, color = Palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("曲库（${entries.size}）", color = Palette.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Button(
                onClick = { picker.launch(arrayOf("audio/midi", "audio/x-midi", "audio/mid", "application/x-midi", "application/octet-stream", "*/*")) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.right),
            ) { Text("+ 导入 MIDI", color = Palette.bg) }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { it.file?.path ?: "demo" }) { e ->
                SongRow(e, version, onOpen = { onOpen(e) }, onRename = { renaming = e }, onDelete = { deleting = e })
                HorizontalDivider(color = Palette.line)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "支持 .mid / .midi。也可以在文件管理器或微信里用“其他应用打开”选择本应用直接导入。",
                    color = Palette.sub, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }

    renaming?.let { e ->
        var name by remember(e) { mutableStateOf(e.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val old = e.title
                    if (SongLibrary.rename(e, name)) {
                        PracticeStats.renameSong(old, name.trim())
                        renaming = null
                    } else toast("名字无效或已存在")
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除曲子") },
            text = { Text("从曲库删除“${e.title}”？练习时长记录会保留。") },
            confirmButton = { TextButton(onClick = { SongLibrary.delete(e); deleting = null }) { Text("删除", color = Palette.wrong) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SongRow(e: SongEntry, version: Int, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val sec = remember(e.title, version) { PracticeStats.songSeconds(e.title) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("♪", color = Palette.right, fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = Palette.text, fontSize = 14.sp, maxLines = 1)
            Text(if (sec >= 60) "已练 ${fmtDuration(sec)}" else "还没练过", color = Palette.sub, fontSize = 11.sp)
        }
        if (!e.isDemo) {
            Box {
                Text("⋮", color = Palette.sub, fontSize = 20.sp, modifier = Modifier.clickable { menu = true }.padding(horizontal = 12.dp))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
