package com.cxm.piano.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cxm.piano.stats.DayRecord
import com.cxm.piano.stats.PracticeStats
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun StatsScreen(wide: Boolean = false) {
    val version by PracticeStats.version.collectAsState()
    val days = remember(version) { PracticeStats.snapshot() }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var detail by remember { mutableStateOf<LocalDate?>(null) }
    val today = LocalDate.now()

    val todaySec = days[today]?.seconds ?: 0.0
    val monthDays = days.filterKeys { YearMonth.from(it) == month }
    val monthPracticeDays = monthDays.count { it.value.seconds >= PracticeStats.MIN_DAY_SECONDS }
    val monthSec = monthDays.values.sumOf { it.seconds }
    val totalSec = days.values.sumOf { it.seconds }
    val streak = run {
        var d = if ((days[today]?.seconds ?: 0.0) >= PracticeStats.MIN_DAY_SECONDS) today else today.minusDays(1)
        var n = 0
        while ((days[d]?.seconds ?: 0.0) >= PracticeStats.MIN_DAY_SECONDS) { n++; d = d.minusDays(1) }
        n
    }
    val songTotals = monthDays.values.flatMap { it.songs.entries }
        .groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        .entries.sortedByDescending { it.value }.take(8)

    if (wide) {
        Row(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TodayCard(todaySec)
                SummaryRow(streak, monthPracticeDays, monthSec, totalSec)
                MonthCard(month, days, today, { month = it }, { detail = it })
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WeekCard(days, today)
                SongsCard(songTotals)
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TodayCard(todaySec)
            SummaryRow(streak, monthPracticeDays, monthSec, totalSec)
            MonthCard(month, days, today, { month = it }, { detail = it })
            WeekCard(days, today)
            SongsCard(songTotals)
        }
    }

    detail?.let { d -> DayDialog(d, days[d]) { detail = null } }
}

@Composable
private fun TodayCard(todaySec: Double) {
    Card {
        Text("今天已练习", color = Palette.sub, fontSize = 13.sp)
        Text(fmtDuration(todaySec), color = Palette.accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("只计算实际弹奏的时间，停下超过 20 秒不计入", color = Palette.sub, fontSize = 11.sp)
    }
}

@Composable
private fun SummaryRow(streak: Int, monthPracticeDays: Int, monthSec: Double, totalSec: Double) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatBox("连续打卡", "$streak 天", Modifier.weight(1f))
        StatBox("本月练习", "$monthPracticeDays 天", Modifier.weight(1f))
        StatBox("本月时长", fmtDuration(monthSec), Modifier.weight(1f))
        StatBox("累计", fmtDuration(totalSec), Modifier.weight(1f))
    }
}

@Composable
private fun MonthCard(
    month: YearMonth,
    days: Map<LocalDate, DayRecord>,
    today: LocalDate,
    onMonth: (YearMonth) -> Unit,
    onDay: (LocalDate) -> Unit,
) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onMonth(month.minusMonths(1)) }) { Text("‹", color = Palette.text, fontSize = 18.sp) }
            Text(
                "${month.year}年${month.monthValue}月", color = Palette.text, fontSize = 15.sp,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
            )
            TextButton(onClick = { onMonth(month.plusMonths(1)) }, enabled = month < YearMonth.now()) {
                Text("›", color = if (month < YearMonth.now()) Palette.text else Palette.line, fontSize = 18.sp)
            }
        }
        Calendar(month, days, today, onDay)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("少", color = Palette.sub, fontSize = 10.sp)
            for (lv in 0..4) Box(Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(2.dp)).background(levelColor(lv)).padding(5.dp))
            Text("多", color = Palette.sub, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("点日期看当天曲目", color = Palette.sub, fontSize = 10.sp)
        }
    }
}

@Composable
private fun WeekCard(days: Map<LocalDate, com.cxm.piano.stats.DayRecord>, today: LocalDate) {
    Card {
        Text("最近 7 天（分钟）", color = Palette.text, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        WeekBars((6 downTo 0).map { today.minusDays(it.toLong()) }.map { it to ((days[it]?.seconds ?: 0.0) / 60) })
    }
}

@Composable
private fun SongsCard(songTotals: List<Map.Entry<String, Double>>) {
    Card {
        Text("本月练得最多的曲子", color = Palette.text, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        if (songTotals.isEmpty()) Text("还没有记录", color = Palette.sub, fontSize = 12.sp)
        for ((title, sec) in songTotals) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(title, color = Palette.text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text(fmtDuration(sec), color = Palette.sub, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.panel).padding(12.dp)) { content() }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Palette.panel).padding(vertical = 10.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Palette.sub, fontSize = 11.sp)
    }
}

private fun levelColor(lv: Int) = when (lv) {
    0 -> Palette.panel2
    1 -> Color(0xFF1E4D3A)
    2 -> Color(0xFF2A7A55)
    3 -> Color(0xFF3FAE75)
    else -> Color(0xFF6EE7A0)
}

private fun level(sec: Double) = when {
    sec < PracticeStats.MIN_DAY_SECONDS -> 0
    sec < 10 * 60 -> 1
    sec < 30 * 60 -> 2
    sec < 60 * 60 -> 3
    else -> 4
}

@Composable
private fun Calendar(month: YearMonth, days: Map<LocalDate, DayRecord>, today: LocalDate, onDay: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        for (w in listOf("一", "二", "三", "四", "五", "六", "日")) {
            Text(w, color = Palette.sub, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
    }
    val first = month.atDay(1)
    val offset = first.dayOfWeek.value - 1
    val cells = offset + month.lengthOfMonth()
    val rows = (cells + 6) / 7
    for (r in 0 until rows) {
        Row(Modifier.fillMaxWidth()) {
            for (c in 0 until 7) {
                val idx = r * 7 + c - offset
                Box(Modifier.weight(1f).heightIn(max = 54.dp).aspectRatio(1.25f).padding(2.dp), contentAlignment = Alignment.Center) {
                    if (idx in 0 until month.lengthOfMonth()) {
                        val d = month.atDay(idx + 1)
                        val rec = days[d]
                        val s = rec?.seconds ?: 0.0
                        val lv = level(s)
                        Box(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp)).background(levelColor(lv))
                                .then(if (rec != null) Modifier.clickable { onDay(d) } else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${idx + 1}", fontSize = 12.sp,
                                    color = if (d == today) Palette.accent else if (lv >= 3) Color.Black else Palette.text,
                                    fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                                )
                                if (s >= 60) Text("${(s / 60).toInt()}′", fontSize = 9.sp, color = if (lv >= 3) Color.Black else Palette.sub)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayDialog(date: LocalDate, rec: DayRecord?, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.widthIn(max = 460.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.panel)
                .padding(16.dp),
        ) {
            Text(
                "${date.monthValue} 月 ${date.dayOfMonth} 日",
                color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
            Text("共练习 ${fmtDuration(rec?.seconds ?: 0.0)}", color = Palette.sub, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))

            val songs = rec?.songs?.entries?.sortedByDescending { it.value }.orEmpty()
            if (songs.isEmpty()) {
                Text("这天还没有弹琴记录", color = Palette.sub, fontSize = 13.sp)
            } else {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    for ((title, sec) in songs) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(title, color = Palette.text, fontSize = 14.sp, maxLines = 1)
                                Text(fmtDuration(sec), color = Palette.sub, fontSize = 11.sp)
                            }
                            val a = rec?.acc?.get(title)
                            Text(
                                if (a == null) "正确率 —" else "正确率 $a%",
                                color = when {
                                    a == null -> Palette.sub
                                    a >= 90 -> Color(0xFF6EE7A0)
                                    a >= 70 -> Palette.accent
                                    else -> Color(0xFFFF8A80)
                                },
                                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("关闭", color = Palette.accent) }
            }
        }
    }
}

@Composable
private fun WeekBars(data: List<Pair<LocalDate, Double>>) {
    val max = maxOf(data.maxOf { it.second }, 10.0)
    Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((d, m) in data) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (m >= 1) "${m.toInt()}" else "", color = Palette.sub, fontSize = 10.sp)
                Canvas(Modifier.fillMaxWidth().weight(1f)) {
                    val h = (size.height * (m / max)).toFloat().coerceAtLeast(2f)
                    drawRoundRect(
                        if (d == LocalDate.now()) Palette.accent else Palette.right,
                        Offset(size.width * 0.2f, size.height - h), Size(size.width * 0.6f, h), CornerRadius(4f, 4f),
                    )
                }
                Text(
                    if (d == LocalDate.now()) "今天" else "周" + "一二三四五六日"[d.dayOfWeek.value - 1],
                    color = Palette.sub, fontSize = 10.sp,
                )
            }
        }
    }
}
