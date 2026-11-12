package com.cxm.piano.ui

import androidx.compose.ui.graphics.Color

object Palette {
    val bg = Color(0xFF121418)
    val panel = Color(0xFF1C1F26)
    val panel2 = Color(0xFF262A33)
    val line = Color(0xFF2E333D)
    val text = Color(0xFFE8EAF0)
    val sub = Color(0xFF9AA0AC)
    val right = Color(0xFF4FC3F7)
    val rightDark = Color(0xFF2B8DBF)
    val left = Color(0xFF8BD17C)
    val leftDark = Color(0xFF5A9E4C)
    val accent = Color(0xFFFFC857)
    val wrong = Color(0xFFFF5A5F)
    val ok = Color(0xFF6EE7A0)
}

private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

fun noteName(p: Int) = NAMES[p % 12] + (p / 12 - 1)

fun isBlack(p: Int) = when (p % 12) { 1, 3, 6, 8, 10 -> true; else -> false }

fun fmtDuration(seconds: Double): String {
    val s = seconds.toInt()
    val m = s / 60
    return when {
        m == 0 -> if (s < 5) "不到 1 分钟" else "${s} 秒"
        m < 60 -> "${m} 分钟"
        else -> "${m / 60} 小时 ${m % 60} 分"
    }
}
