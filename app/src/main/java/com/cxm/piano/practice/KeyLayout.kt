package com.cxm.piano.practice

import com.cxm.piano.ui.isBlack

/** Distance from the left edge of the preceding white key to a black key, in white-key widths. */
private fun blackOffset(pc: Int): Float = when (pc) {
    1 -> 0.56f   // C#
    3 -> 0.57f   // D#
    6 -> 0.59f   // F#
    8 -> 0.60f   // G#
    else -> 0.60f // A#
}

/**
 * Horizontal geometry of an on-screen keyboard spanning [lo, hi].
 * White keys are equal width; black keys use the proportions of a real piano
 * (width 0.583 of a white key, sitting just right of the previous white key).
 */
class KeyLayout(val lo: Int, val hi: Int, val width: Float) {
    val whiteCount = (lo..hi).count { !isBlack(it) }
    val whiteW = width / whiteCount
    val blackW = whiteW * 0.583f
    private val left = FloatArray(128)
    private val w = FloatArray(128)

    init {
        var wi = 0
        for (p in lo..hi) {
            if (isBlack(p)) {
                // wi is the index of the white key after p; the one before it is wi - 1.
                left[p] = (wi - 1 + blackOffset(p % 12)) * whiteW
                w[p] = blackW
            } else {
                left[p] = wi * whiteW
                w[p] = whiteW
                wi++
            }
        }
    }

    fun x(p: Int) = left[p]
    fun w(p: Int) = w[p]
    fun contains(p: Int) = p in lo..hi

    /** blackZone: true when the touch is in the upper part of the keys where black keys live. */
    fun pitchAt(x: Float, blackZone: Boolean): Int? {
        if (x < 0 || x >= width) return null
        if (blackZone) {
            for (p in lo..hi) if (isBlack(p) && x >= left[p] && x < left[p] + blackW) return p
        }
        val wi = (x / whiteW).toInt()
        var c = 0
        for (p in lo..hi) if (!isBlack(p)) { if (c == wi) return p; c++ }
        return null
    }

    companion object {
        private const val FIRST_KEY = 21   // A0
        private const val LAST_KEY = 108   // C8

        fun forRange(minPitch: Int, maxPitch: Int, width: Float, full: Boolean): KeyLayout {
            if (full) return KeyLayout(FIRST_KEY, LAST_KEY, width)
            var lo = (minPitch - 2).coerceIn(FIRST_KEY, LAST_KEY)
            var hi = (maxPitch + 2).coerceIn(FIRST_KEY, LAST_KEY)
            if (lo > hi) { val t = lo; lo = hi; hi = t }
            while (hi - lo < 24) {
                if (lo > FIRST_KEY) lo--
                if (hi < LAST_KEY) hi++
                if (lo == FIRST_KEY && hi == LAST_KEY) break
            }
            if (isBlack(lo)) lo--
            if (isBlack(hi)) hi++
            return KeyLayout(lo, hi, width)
        }
    }
}
