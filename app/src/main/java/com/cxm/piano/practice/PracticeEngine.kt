package com.cxm.piano.practice

import com.cxm.piano.audio.Sound
import com.cxm.piano.midi.Hand
import com.cxm.piano.midi.Song

enum class Mode(val label: String) { WAIT("等待"), FOLLOW("跟弹"), LISTEN("欣赏") }

enum class HandSel(val label: String) {
    RIGHT("右手"), LEFT("左手"), BOTH("双手");

    fun includes(h: Hand) = this == BOTH || (this == RIGHT && h == Hand.RIGHT) || (this == LEFT && h == Hand.LEFT)
}

const val ST_PENDING = 0
const val ST_HIT = 1
const val ST_MISS = 2

/**
 * Single-threaded (main thread) playback/judging engine. UI reads its fields each frame.
 */
class PracticeEngine(val song: Song) {
    private val notes = song.notes
    val status = IntArray(notes.size)

    var mode = Mode.WAIT
        set(v) { field = v; seek(time) }
    var hands = HandSel.BOTH
        set(v) { field = v; seek(time) }
    var speed = 1.0
    var playing = false
        private set
    var time = LEAD_IN
        private set
    var waiting = false
        private set
    var loopA: Double? = null
    var loopB: Double? = null

    var hits = 0; private set
    var misses = 0; private set
    var wrong = 0; private set
    var finished = false; private set

    /** pitch -> wall-clock ms of last wrong press, for red flashes on the keyboard. */
    val wrongFlash = HashMap<Int, Long>()

    private var nextOn = 0
    private val sounding = ArrayList<Int>() // indices of auto-played notes currently on

    // wait mode: current chord of practiced notes
    private var chordStart = 0
    private var chordEnd = 0
    private val pending = HashSet<Int>()

    // follow mode: first practiced note index not yet past the judge window
    private var followIdx = 0

    private fun practiced(i: Int) = mode != Mode.LISTEN && hands.includes(notes[i].hand)

    fun play() {
        if (finished) restart()
        playing = true
    }

    fun pause() {
        playing = false
        silence()
    }

    fun restart() {
        status.fill(ST_PENDING)
        seek(loopA ?: LEAD_IN)
        playing = true
    }

    fun seek(t: Double) {
        silence()
        time = t.coerceIn(LEAD_IN, song.durationMs)
        finished = false
        waiting = false
        nextOn = lowerBound(time)
        for (i in notes.indices) if (notes[i].startMs >= time) status[i] = ST_PENDING
        hits = 0; misses = 0; wrong = 0
        for (s in status) if (s == ST_HIT) hits++ else if (s == ST_MISS) misses++
        followIdx = nextOn
        chordStart = nextOn
        chordEnd = nextOn
        pending.clear()
        advanceChord()
    }

    private fun lowerBound(t: Double): Int {
        var lo = 0; var hi = notes.size
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (notes[m].startMs < t - 1) lo = m + 1 else hi = m
        }
        return lo
    }

    private fun advanceChord() {
        if (mode != Mode.WAIT) return
        var i = chordEnd
        while (i < notes.size && !practiced(i)) i++
        chordStart = i
        pending.clear()
        if (i >= notes.size) { chordEnd = i; return }
        val t0 = notes[i].startMs
        var j = i
        while (j < notes.size && notes[j].startMs - t0 < CHORD_MS) {
            if (practiced(j)) pending += j
            j++
        }
        chordEnd = j
    }

    fun tick(dtMs: Double) {
        if (!playing) return
        var next = time + dtMs * speed

        if (mode == Mode.WAIT && pending.isNotEmpty()) {
            val due = notes[chordStart].startMs
            if (next >= due) {
                next = due
                waiting = true
            }
        }
        time = next

        while (nextOn < notes.size && notes[nextOn].startMs <= time) {
            val n = notes[nextOn]
            if (!practiced(nextOn)) {
                Sound.noteOn(n.pitch, n.velocity)
                sounding += nextOn
            }
            nextOn++
        }
        val it = sounding.iterator()
        while (it.hasNext()) {
            val i = it.next()
            if (notes[i].endMs <= time) { Sound.noteOff(notes[i].pitch); it.remove() }
        }

        if (mode == Mode.FOLLOW) {
            while (followIdx < notes.size && notes[followIdx].startMs + JUDGE_MS < time) {
                if (practiced(followIdx) && status[followIdx] == ST_PENDING) {
                    status[followIdx] = ST_MISS
                    misses++
                }
                followIdx++
            }
        }

        val b = loopB
        if (b != null && time >= b) {
            seek(loopA ?: LEAD_IN)
            return
        }
        if (time >= song.durationMs + 500 || (nextOn >= notes.size && sounding.isEmpty() && pending.isEmpty() && time >= song.durationMs)) {
            finished = true
            playing = false
            silence()
        }
    }

    fun onKeyDown(pitch: Int, wallMs: Long) {
        when (mode) {
            Mode.LISTEN -> Unit
            Mode.WAIT -> {
                if (pending.isEmpty()) return
                val due = notes[chordStart].startMs
                if (time < due - EARLY_MS) return
                val match = pending.firstOrNull { notes[it].pitch == pitch }
                if (match != null) {
                    pending -= match
                    status[match] = ST_HIT
                    hits++
                    if (pending.isEmpty()) {
                        waiting = false
                        advanceChord()
                    }
                } else {
                    wrong++
                    wrongFlash[pitch] = wallMs
                }
            }
            Mode.FOLLOW -> {
                var best = -1
                var bestD = Double.MAX_VALUE
                var i = followIdx
                while (i < notes.size && notes[i].startMs <= time + JUDGE_MS) {
                    if (practiced(i) && status[i] == ST_PENDING && notes[i].pitch == pitch) {
                        val d = kotlin.math.abs(notes[i].startMs - time)
                        if (d < bestD) { bestD = d; best = i }
                    }
                    i++
                }
                if (best >= 0) {
                    status[best] = ST_HIT
                    hits++
                } else {
                    wrong++
                    wrongFlash[pitch] = wallMs
                }
            }
        }
    }

    /** Pitches the player should be pressing right now (for keyboard hints). */
    fun expected(out: MutableMap<Int, Hand>) {
        out.clear()
        if (mode == Mode.WAIT) {
            if (pending.isNotEmpty() && time >= notes[chordStart].startMs - EARLY_MS) {
                for (i in pending) out[notes[i].pitch] = notes[i].hand
            }
            return
        }
        var i = maxOf(0, nextOn - 64)
        while (i < notes.size && notes[i].startMs <= time) {
            val n = notes[i]
            if (n.endMs > time && hands.includes(n.hand)) out[n.pitch] = n.hand
            i++
        }
    }

    val accuracy: Int
        get() {
            val total = hits + misses + wrong
            return if (total == 0) 0 else (hits * 100 / total)
        }

    fun silence() {
        for (i in sounding) Sound.noteOff(notes[i].pitch)
        sounding.clear()
        Sound.allOff()
    }

    companion object {
        const val LEAD_IN = -2000.0
        const val CHORD_MS = 40.0
        const val EARLY_MS = 500.0
        const val JUDGE_MS = 220.0
    }
}
