package com.cxm.piano

import com.cxm.piano.audio.Sound
import com.cxm.piano.audio.SoundOut
import com.cxm.piano.midi.Hand
import com.cxm.piano.midi.MidiParser
import com.cxm.piano.midi.Note
import com.cxm.piano.midi.Song
import com.cxm.piano.practice.HandSel
import com.cxm.piano.practice.KeyLayout
import com.cxm.piano.practice.Mode
import com.cxm.piano.practice.PracticeEngine
import com.cxm.piano.practice.ST_HIT
import com.cxm.piano.practice.ST_MISS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

class CoreTest {

    @Before
    fun quiet() {
        Sound.output.value = SoundOut.OFF
    }

    private fun vlq(out: ByteArrayOutputStream, v: Int) {
        val bytes = mutableListOf(v and 0x7F)
        var x = v shr 7
        while (x > 0) { bytes.add(0, (x and 0x7F) or 0x80); x = x shr 7 }
        bytes.forEach { out.write(it) }
    }

    private fun chunk(out: ByteArrayOutputStream, id: String, body: ByteArray) {
        out.write(id.toByteArray())
        val n = body.size
        out.write(n ushr 24); out.write(n ushr 16 and 0xFF); out.write(n ushr 8 and 0xFF); out.write(n and 0xFF)
        out.write(body)
    }

    /** events: (deltaTick, raw bytes) */
    private fun track(vararg events: Pair<Int, IntArray>): ByteArray {
        val o = ByteArrayOutputStream()
        for ((d, bs) in events) { vlq(o, d); bs.forEach { o.write(it) } }
        vlq(o, 0); o.write(0xFF); o.write(0x2F); o.write(0)
        return o.toByteArray()
    }

    private fun midiFile(vararg tracks: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        val tpq = 480
        chunk(o, "MThd", byteArrayOf(0, 1, 0, tracks.size.toByte(), (tpq shr 8).toByte(), (tpq and 0xFF).toByte()))
        for (t in tracks) chunk(o, "MTrk", t)
        return o.toByteArray()
    }

    @Test
    fun parsesTempoChangesHandsAndRunningStatus() {
        val tempo = track(
            0 to intArrayOf(0xFF, 0x51, 3, 0x07, 0xA1, 0x20), // 500000us = 120bpm
            960 to intArrayOf(0xFF, 0x51, 3, 0x0F, 0x42, 0x40), // 1000000us = 60bpm
        )
        // right hand, uses running status and note-on velocity 0 as note-off
        val right = track(
            0 to intArrayOf(0x90, 72, 100),
            480 to intArrayOf(72, 0),
            0 to intArrayOf(74, 90),
            480 to intArrayOf(74, 0),
            480 to intArrayOf(76, 80),
            480 to intArrayOf(0x80, 76, 0),
        )
        val left = track(
            0 to intArrayOf(0x91, 48, 70),
            960 to intArrayOf(0x81, 48, 0),
        )
        val song = MidiParser.parse(midiFile(tempo, right, left), "t")
        assertEquals(4, song.notes.size)
        val byPitch = song.notes.associateBy { it.pitch }
        assertEquals(0.0, byPitch[72]!!.startMs, 0.01)
        assertEquals(500.0, byPitch[74]!!.startMs, 0.01)
        // tick 1440: 960 ticks @120bpm = 1000ms, then 480 ticks @60bpm = 1000ms
        assertEquals(2000.0, byPitch[76]!!.startMs, 0.01)
        assertEquals(3000.0, byPitch[76]!!.endMs, 0.01)
        assertEquals(Hand.RIGHT, byPitch[72]!!.hand)
        assertEquals(Hand.LEFT, byPitch[48]!!.hand)
        assertEquals(3000.0, song.durationMs, 0.01)
    }

    @Test
    fun singleTrackSplitsHandsByPitch() {
        val t = track(
            0 to intArrayOf(0x90, 50, 80), 0 to intArrayOf(0x90, 70, 80),
            480 to intArrayOf(0x80, 50, 0), 0 to intArrayOf(0x80, 70, 0),
        )
        val song = MidiParser.parse(midiFile(t), "t")
        assertEquals(Hand.LEFT, song.notes.first { it.pitch == 50 }.hand)
        assertEquals(Hand.RIGHT, song.notes.first { it.pitch == 70 }.hand)
    }

    private fun simpleSong() = Song(
        "s",
        listOf(
            Note(60, 0.0, 400.0, 80, Hand.RIGHT),
            Note(48, 0.0, 800.0, 80, Hand.LEFT),
            Note(64, 500.0, 900.0, 80, Hand.RIGHT),
            Note(67, 1000.0, 1400.0, 80, Hand.RIGHT),
        ),
    )

    private fun run(e: PracticeEngine, ms: Int) = repeat(ms / 16) { e.tick(16.0) }

    @Test
    fun waitModeHoldsUntilCorrectKeys() {
        val e = PracticeEngine(simpleSong())
        e.mode = Mode.WAIT
        e.hands = HandSel.RIGHT
        e.play()
        run(e, 5000)
        assertTrue(e.waiting)
        assertEquals(0.0, e.time, 0.01)

        e.onKeyDown(61, 0)
        assertEquals(1, e.wrong)
        assertTrue(e.waiting)

        e.onKeyDown(60, 0)
        assertFalse(e.waiting)
        run(e, 3000)
        assertEquals(500.0, e.time, 0.01)
        e.onKeyDown(64, 0)
        run(e, 3000)
        assertEquals(1000.0, e.time, 0.01)
        e.onKeyDown(67, 0)
        run(e, 3000)
        assertTrue(e.finished)
        assertEquals(3, e.hits)
        assertEquals(75, e.accuracy)
    }

    @Test
    fun waitModeBothHandsNeedsWholeChord() {
        val e = PracticeEngine(simpleSong())
        e.mode = Mode.WAIT
        e.hands = HandSel.BOTH
        e.play()
        run(e, 3000)
        e.onKeyDown(60, 0)
        assertTrue(e.waiting)
        e.onKeyDown(48, 0)
        assertFalse(e.waiting)
    }

    @Test
    fun followModeCountsHitsAndMisses() {
        val e = PracticeEngine(simpleSong())
        e.mode = Mode.FOLLOW
        e.hands = HandSel.RIGHT
        e.play()
        run(e, 2000) // reach t~0
        e.onKeyDown(60, 0)
        run(e, 3000)
        assertTrue(e.finished)
        assertEquals(1, e.hits)
        assertEquals(2, e.misses)
        assertEquals(ST_HIT, e.status[e.song.notes.indexOfFirst { it.pitch == 60 }])
        assertEquals(ST_MISS, e.status[e.song.notes.indexOfFirst { it.pitch == 67 }])
    }

    @Test
    fun abLoopJumpsBack() {
        val e = PracticeEngine(simpleSong())
        e.mode = Mode.LISTEN
        e.loopA = 200.0
        e.loopB = 900.0
        e.seek(200.0)
        e.play()
        run(e, 800)
        assertTrue(e.time < 900.0)
        assertFalse(e.finished)
    }

    @Test
    fun expectedHintsFollowSelectedHand() {
        val e = PracticeEngine(simpleSong())
        e.mode = Mode.LISTEN
        e.hands = HandSel.RIGHT
        e.play()
        run(e, 2300) // ~290ms: both sustained notes at t=0 are still sounding
        val out = HashMap<Int, Hand>()
        e.expected(out)
        assertTrue(out.containsKey(60))
        assertFalse(out.containsKey(48))

        e.hands = HandSel.LEFT
        e.expected(out)
        assertTrue(out.containsKey(48))
        assertFalse(out.containsKey(60))
    }

    @Test
    fun keyLayoutCoversRangeAndMapsTouches() {
        val lay = KeyLayout.forRange(60, 72, 1000f, full = false)
        assertTrue(lay.lo <= 60 && lay.hi >= 72)
        assertTrue(lay.hi - lay.lo >= 24)
        assertFalse(com.cxm.piano.ui.isBlack(lay.lo))
        assertFalse(com.cxm.piano.ui.isBlack(lay.hi))
        val full = KeyLayout.forRange(60, 72, 520f, full = true)
        assertEquals(52, full.whiteCount)
        assertEquals(21, full.pitchAt(1f, false))
        assertEquals(108, full.pitchAt(519f, false))
        val cs = 61
        assertEquals(cs, full.pitchAt(full.x(cs) + 1f, true))
        assertEquals(60, full.pitchAt(full.x(60) + full.whiteW / 2, false))
    }

    @Test
    fun blackKeysUseStandardOffsets() {
        val oct = KeyLayout(60, 71, 700f)
        assertEquals(7, oct.whiteCount)
        val ww = oct.whiteW
        // C# starts ~0.56 white keys right of C and stays clear of the next white key's left edge.
        assertTrue(oct.x(61) > oct.x(60) + 0.5f * ww)
        assertTrue(oct.x(61) < oct.x(60) + 0.62f * ww)
        assertTrue(oct.x(61) + oct.blackW < oct.x(62) + 0.2f * ww)
        // Grouping: 2 blacks between C-D-E, 3 between F-G-A-B.
        assertEquals(0.583f, oct.blackW / ww, 0.001f)
        assertTrue(oct.x(66) < oct.x(65) + ww)
        assertTrue(oct.x(68) > oct.x(67))
        assertTrue(oct.x(70) > oct.x(69))
    }

    @Test
    fun adaptiveRangeStaysInside88Keys() {
        val high = KeyLayout.forRange(120, 127, 500f, full = false)
        assertTrue(high.lo in 21..107)
        assertTrue(high.hi in 22..108)
        val low = KeyLayout.forRange(0, 5, 500f, full = false)
        assertTrue(low.lo >= 21 && low.hi <= 108)
        assertTrue(low.hi - low.lo >= 24)
    }
}
