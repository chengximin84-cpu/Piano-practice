package com.cxm.piano.practice

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cxm.piano.audio.Sound
import com.cxm.piano.audio.SoundOut
import com.cxm.piano.midi.Hand
import com.cxm.piano.midi.LinkStatus
import com.cxm.piano.midi.PianoMidi
import com.cxm.piano.midi.Song
import com.cxm.piano.stats.PracticeStats
import com.cxm.piano.ui.Palette
import com.cxm.piano.ui.isBlack
import com.cxm.piano.ui.noteName

private object LastSettings {
    var mode = Mode.WAIT
    var hands = HandSel.BOTH
    var speed = 1.0
    // A standard piano has 88 keys; the adaptive range is opt-in for close-up practice.
    var full = true
}

private const val VISIBLE_MS = 2600.0

@Composable
fun PracticeScreen(song: Song, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val engine = remember(song) {
        PracticeEngine(song).apply {
            mode = LastSettings.mode
            hands = LastSettings.hands
            speed = LastSettings.speed
        }
    }
    // frame is read only in the draw phase, so the canvas redraws without recomposing;
    // the control bar refreshes at ~10Hz via uiTick.
    val frame = remember { mutableIntStateOf(0) }
    var uiTick by remember { mutableIntStateOf(0) }
    var fullKeyboard by remember { mutableStateOf(LastSettings.full) }
    var showResult by remember { mutableStateOf(false) }
    val link by PianoMidi.link.collectAsState()
    val held = PianoMidi.held.collectAsState()
    val out by Sound.output.collectAsState()

    val recordAcc = {
        if (engine.mode != Mode.LISTEN && engine.hits + engine.misses + engine.wrong > 0) {
            PracticeStats.recordAccuracy(song.title, engine.accuracy)
        }
    }

    DisposableEffect(song) {
        val act = ctx as Activity
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PracticeStats.currentSong = song.title
        onDispose {
            engine.pause()
            recordAcc()
            PracticeStats.currentSong = null
            PracticeStats.flush()
            act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(engine) {
        var last = 0L
        var wasFinished = false
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last != 0L) engine.tick((now - last) / 1e6)
                last = now
                frame.intValue++
                if (frame.intValue % 6 == 0) uiTick++
                if (engine.finished && !wasFinished) {
                    recordAcc()
                    showResult = engine.mode != Mode.LISTEN
                }
                wasFinished = engine.finished
            }
        }
    }

    LaunchedEffect(engine) {
        PianoMidi.events.collect { e ->
            if (!e.fromPiano) {
                if (e.down) Sound.noteOn(e.pitch, e.velocity) else Sound.noteOff(e.pitch)
            }
            if (e.down) engine.onKeyDown(e.pitch, SystemClock.uptimeMillis())
        }
    }

    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().background(Palette.bg)) {
        ControlBar(
            engine = engine,
            frame = uiTick,
            title = song.title,
            linked = link.status == LinkStatus.CONNECTED,
            out = out,
            fullKeyboard = fullKeyboard,
            onFull = { fullKeyboard = it; LastSettings.full = it },
            onBack = onBack,
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Waterfall(engine, frame, held, fullKeyboard)
            if (showResult) ResultOverlay(engine, onAgain = { showResult = false; engine.restart() }, onBack = onBack)
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Palette.right.copy(alpha = 0.25f) else Palette.panel2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = when { !enabled -> Palette.sub.copy(alpha = 0.4f); selected -> Palette.right; else -> Palette.text },
        )
    }
}

@Composable
private fun ControlBar(
    engine: PracticeEngine,
    frame: Int,
    title: String,
    linked: Boolean,
    out: SoundOut,
    fullKeyboard: Boolean,
    onFull: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") frame
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    Column(Modifier.fillMaxWidth().background(Palette.panel).padding(horizontal = 6.dp, vertical = 3.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Chip("‹ 返回", onClick = onBack)
            Text(
                title, color = Palette.text, fontSize = 13.sp, maxLines = 1,
                modifier = Modifier.padding(horizontal = 6.dp).widthIn(max = if (wide) 240.dp else 110.dp),
            )
            Chip(if (engine.playing) "❚❚ 暂停" else "▶ 开始", selected = true) {
                if (engine.playing) engine.pause() else engine.play()
            }
            Chip("↺ 重来") { engine.restart() }
            Spacer(Modifier.width(6.dp))
            for (m in Mode.entries) Chip(m.label, engine.mode == m) { engine.mode = m; LastSettings.mode = m }
            Spacer(Modifier.width(6.dp))
            for (h in HandSel.entries) Chip(h.label, engine.hands == h) { engine.hands = h; LastSettings.hands = h }
            Spacer(Modifier.width(6.dp))
            Chip("−") { engine.speed = (engine.speed - 0.1).coerceAtLeast(0.2); LastSettings.speed = engine.speed }
            Text("${Math.round(engine.speed * 100)}%", color = Palette.text, fontSize = 13.sp, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Chip("+") { engine.speed = (engine.speed + 0.1).coerceAtMost(2.0); LastSettings.speed = engine.speed }
            Spacer(Modifier.width(6.dp))
            Chip("A", engine.loopA != null) { engine.loopA = if (engine.loopA == null) engine.time.coerceAtLeast(0.0) else null }
            Chip("B", engine.loopB != null) {
                val a = engine.loopA ?: 0.0
                engine.loopB = if (engine.loopB == null && engine.time > a + 500) engine.time else null
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmtClock(engine.time), color = Palette.sub, fontSize = 11.sp, modifier = Modifier.width(40.dp))
            Slider(
                value = (engine.time / engine.song.durationMs).toFloat().coerceIn(0f, 1f),
                onValueChange = { engine.seek(it * engine.song.durationMs) },
                modifier = Modifier.weight(1f).height(20.dp),
                colors = SliderDefaults.colors(thumbColor = Palette.right, activeTrackColor = Palette.right, inactiveTrackColor = Palette.line),
            )
            Text(fmtClock(engine.song.durationMs), color = Palette.sub, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp).width(40.dp))
            if (engine.mode != Mode.LISTEN) {
                Text(
                    "✓${engine.hits} ✗${engine.misses + engine.wrong} ${engine.accuracy}%" + if (engine.waiting) " 等你弹…" else "",
                    color = if (engine.waiting) Palette.accent else Palette.sub, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Chip("声:" + out.label) {
                val next = when (out) {
                    SoundOut.PHONE -> if (linked && PianoMidi.canSendToPiano) SoundOut.PIANO else SoundOut.OFF
                    SoundOut.PIANO -> SoundOut.OFF
                    SoundOut.OFF -> SoundOut.PHONE
                }
                Sound.setOutput(next)
            }
            Chip(if (fullKeyboard) "88键" else "自适应") { onFull(!fullKeyboard) }
            Text(
                if (linked) "● 已连琴" else "○ 未连琴",
                color = if (linked) Palette.ok else Palette.sub, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

private fun fmtClock(ms: Double): String {
    val s = (ms.coerceAtLeast(0.0) / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

private fun handColor(h: Hand, black: Boolean): Color =
    if (h == Hand.RIGHT) (if (black) Palette.rightDark else Palette.right) else (if (black) Palette.leftDark else Palette.left)

private class Geo(var layout: KeyLayout? = null, var keyTop: Float = 0f, var full: Boolean = false)

@Composable
private fun Waterfall(engine: PracticeEngine, frame: IntState, heldState: State<Set<Int>>, full: Boolean) {
    val textMeasurer = rememberTextMeasurer()
    val expected = remember { HashMap<Int, Hand>() }
    val geo = remember { Geo() }
    val labelStyle = TextStyle(color = Color(0xFF888888), fontSize = 9.sp)

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(full) {
                val active = HashMap<PointerId, Int>()
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    // The initial down event itself must be handled, otherwise a quick tap
                    // (down+up before the next move event) never plays a note.
                    fun handle(id: PointerId, pos: Offset, pressed: Boolean) {
                        val lay = geo.layout ?: return
                        val keyTop = geo.keyTop
                        val p = if (pressed && pos.y >= keyTop) {
                            val blackZone = pos.y < keyTop + (size.height - keyTop) * 0.62f
                            lay.pitchAt(pos.x, blackZone)
                        } else null
                        val prev = active[id]
                        if (p != prev) {
                            prev?.let { PianoMidi.localUp(it) }
                            if (p != null) { active[id] = p; PianoMidi.localDown(p) } else active.remove(id)
                        }
                    }
                    handle(first.id, first.position, true)
                    do {
                        val ev = awaitPointerEvent()
                        for (c in ev.changes) handle(c.id, c.position, c.pressed)
                    } while (ev.changes.any { it.pressed })
                    active.values.forEach { PianoMidi.localUp(it) }
                    active.clear()
                }
            },
    ) {
        frame.intValue
        val held = heldState.value
        val w = size.width
        val h = size.height
        val lay = geo.layout?.takeIf { it.width == w && geo.full == full }
            ?: KeyLayout.forRange(engine.song.minPitch, engine.song.maxPitch, w, full).also { geo.layout = it; geo.full = full }
        val kbH = (h * 0.24f).coerceIn(70f, 320f)
        val hitY = h - kbH
        geo.keyTop = hitY
        val pxPerMs = (hitY / VISIBLE_MS).toFloat()
        val t = engine.time

        // octave guide lines
        for (p in lay.lo..lay.hi) if (p % 12 == 0) {
            drawLine(Palette.line, Offset(lay.x(p), 0f), Offset(lay.x(p), hitY), 1f)
        }

        clipRect(0f, 0f, w, hitY) {
            val notes = engine.song.notes
            val horizon = t + hitY / pxPerMs
            for (i in notes.indices) {
                val n = notes[i]
                if (n.startMs > horizon) break
                if (n.endMs < t - 200) continue
                if (!lay.contains(n.pitch)) continue
                // only the selected hand(s) are shown; the other hand still auto-plays
                if (!engine.hands.includes(n.hand)) continue
                val black = isBlack(n.pitch)
                val yBottom = hitY - ((n.startMs - t) * pxPerMs).toFloat()
                val yTop = hitY - ((n.endMs - t) * pxPerMs).toFloat()
                var c = handColor(n.hand, black)
                when (engine.status[i]) {
                    ST_HIT -> c = Color(0xFFFFE08A)
                    ST_MISS -> c = Color(0xFF7A3B3F)
                }
                val x = lay.x(n.pitch) + 1.5f
                val nw = lay.w(n.pitch) - 3f
                drawRoundRect(c, Offset(x, yTop), Size(nw, (yBottom - yTop).coerceAtLeast(4f)), CornerRadius(5f, 5f))
            }
        }
        // hit line
        drawRect(Palette.accent.copy(alpha = if (engine.waiting) 0.9f else 0.5f), Offset(0f, hitY - 2f), Size(w, 3f))

        engine.expected(expected)
        drawKeyboard(lay, hitY, kbH, held, expected, engine.wrongFlash, textMeasurer, labelStyle)
    }
}

private fun DrawScope.drawKeyboard(
    lay: KeyLayout,
    top: Float,
    kbH: Float,
    held: Set<Int>,
    expected: Map<Int, Hand>,
    wrong: Map<Int, Long>,
    tm: androidx.compose.ui.text.TextMeasurer,
    labelStyle: TextStyle,
) {
    val now = SystemClock.uptimeMillis()
    drawRect(Color(0xFF0B0C0F), Offset(0f, top), Size(size.width, kbH))
    fun fill(p: Int, black: Boolean): Color {
        val isWrong = (wrong[p]?.let { now - it < 350 } ?: false)
        val exp = expected[p]
        return when {
            p in held && isWrong -> Palette.wrong
            p in held && exp != null -> handColor(exp, black)
            p in held -> Color(0xFF9FB4C8)
            exp != null -> handColor(exp, black).copy(alpha = 0.45f).compositeOverSimple(if (black) Color(0xFF222222) else Color.White)
            else -> if (black) Color(0xFF1A1A1A) else Color(0xFFF4F4F4)
        }
    }
    for (p in lay.lo..lay.hi) if (!isBlack(p)) {
        drawRoundRect(fill(p, false), Offset(lay.x(p) + 1f, top + 2f), Size(lay.whiteW - 2f, kbH - 3f), CornerRadius(4f, 4f))
        if (p % 12 == 0 && lay.whiteW > 14f) {
            val r = tm.measure(noteName(p), labelStyle)
            drawText(r, topLeft = Offset(lay.x(p) + (lay.whiteW - r.size.width) / 2, top + kbH - r.size.height - 4f))
        }
    }
    val bh = kbH * 0.62f
    for (p in lay.lo..lay.hi) if (isBlack(p)) {
        drawRoundRect(fill(p, true), Offset(lay.x(p), top + 2f), Size(lay.blackW, bh), CornerRadius(3f, 3f))
    }
}

private fun Color.compositeOverSimple(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

@Composable
private fun ResultOverlay(engine: PracticeEngine, onAgain: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.panel).padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("练习完成", color = Palette.text, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            Text("${engine.accuracy}%", color = Palette.accent, fontSize = 40.sp)
            Text("准确率", color = Palette.sub, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("弹对 ${engine.hits}", color = Palette.ok, fontSize = 14.sp)
                Text("漏弹 ${engine.misses}", color = Palette.sub, fontSize = 14.sp)
                Text("错音 ${engine.wrong}", color = Palette.wrong, fontSize = 14.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAgain, colors = ButtonDefaults.buttonColors(containerColor = Palette.right)) { Text("再来一遍") }
                TextButton(onClick = onBack) { Text("返回曲库", color = Palette.sub) }
            }
        }
    }
}
