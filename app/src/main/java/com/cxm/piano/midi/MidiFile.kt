package com.cxm.piano.midi

import java.io.File

enum class Hand { LEFT, RIGHT }

class Note(
    val pitch: Int,
    val startMs: Double,
    val endMs: Double,
    val velocity: Int,
    val hand: Hand,
)

class Song(val title: String, notes: List<Note>) {
    val notes: List<Note> = notes.sortedWith(compareBy({ it.startMs }, { it.pitch }))
    val durationMs: Double = this.notes.maxOfOrNull { it.endMs } ?: 0.0
    val minPitch: Int = this.notes.minOfOrNull { it.pitch } ?: 60
    val maxPitch: Int = this.notes.maxOfOrNull { it.pitch } ?: 72
}

object MidiParser {

    private class RawNote(val tick: Long, val endTick: Long, val pitch: Int, val vel: Int, val track: Int, val channel: Int)

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun u8(): Int = b[pos++].toInt() and 0xFF
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        fun str4(): String = String(b, pos, 4, Charsets.ISO_8859_1).also { pos += 4 }
        fun vlq(): Long {
            var v = 0L
            repeat(4) {
                val c = u8()
                v = (v shl 7) or (c and 0x7F).toLong()
                if (c and 0x80 == 0) return v
            }
            return v
        }
    }

    fun parse(file: File): Song = parse(file.readBytes(), file.nameWithoutExtension)

    fun parse(bytes: ByteArray, title: String): Song {
        val r = Reader(bytes)
        require(r.str4() == "MThd") { "不是 MIDI 文件" }
        val headerLen = r.u32().toInt()
        val format = r.u16()
        val trackCount = r.u16()
        val division = r.u16()
        r.pos += headerLen - 6

        val tempos = mutableListOf<Pair<Long, Long>>() // tick -> microseconds per quarter
        val raw = mutableListOf<RawNote>()

        var t = 0
        while (t < trackCount && r.pos + 8 <= bytes.size) {
            val id = r.str4()
            val len = r.u32().toInt()
            val end = minOf(r.pos + len, bytes.size)
            if (id == "MTrk") {
                parseTrack(r, end, t, tempos, raw)
                t++
            }
            r.pos = end
        }

        val tickToMs = buildTickConverter(division, tempos)
        val partKey: (RawNote) -> Int =
            if (format == 0 || raw.map { it.track }.distinct().size <= 1) { n -> n.channel } else { n -> n.track }
        val parts = raw.groupBy(partKey)
        val handOf = assignHands(parts.mapValues { (_, ns) -> ns.map { it.pitch }.average() })

        val notes = raw.map { n ->
            val hand = handOf[partKey(n)] ?: if (n.pitch >= 60) Hand.RIGHT else Hand.LEFT
            Note(n.pitch, tickToMs(n.tick), maxOf(tickToMs(n.endTick), tickToMs(n.tick) + 60.0), n.vel, hand)
        }
        require(notes.isNotEmpty()) { "文件里没有音符" }
        val first = notes.minOf { it.startMs }
        return Song(title, notes.map { Note(it.pitch, it.startMs - first, it.endMs - first, it.velocity, it.hand) })
    }

    // Empty result: caller splits by pitch around middle C.
    private fun assignHands(avgPitch: Map<Int, Double>): Map<Int, Hand> {
        if (avgPitch.size < 2) return emptyMap()
        if (avgPitch.size == 2) {
            val (a, b) = avgPitch.entries.toList()
            return if (a.value >= b.value) mapOf(a.key to Hand.RIGHT, b.key to Hand.LEFT)
            else mapOf(a.key to Hand.LEFT, b.key to Hand.RIGHT)
        }
        return avgPitch.mapValues { if (it.value >= 60) Hand.RIGHT else Hand.LEFT }
    }

    private fun parseTrack(r: Reader, end: Int, track: Int, tempos: MutableList<Pair<Long, Long>>, out: MutableList<RawNote>) {
        var tick = 0L
        var status = 0
        val open = HashMap<Int, ArrayDeque<Pair<Long, Int>>>() // (channel<<8|pitch) -> starts
        while (r.pos < end) {
            tick += r.vlq()
            if (r.pos >= end) break
            var b = r.u8()
            if (b < 0x80) {
                if (status == 0) continue
                r.pos--
                b = status
            } else if (b < 0xF0) {
                status = b
            }
            when {
                b == 0xFF -> {
                    val type = r.u8()
                    val len = r.vlq().toInt()
                    if (type == 0x51 && len == 3) {
                        val us = (r.u8().toLong() shl 16) or (r.u8().toLong() shl 8) or r.u8().toLong()
                        tempos += tick to us
                    } else {
                        r.pos += len
                    }
                }
                b == 0xF0 || b == 0xF7 -> r.pos += r.vlq().toInt()
                else -> {
                    val cmd = b and 0xF0
                    val ch = b and 0x0F
                    when (cmd) {
                        0x80, 0x90 -> {
                            val pitch = r.u8() and 0x7F
                            val vel = r.u8() and 0x7F
                            if (ch == 9) continue // percussion
                            val key = (ch shl 8) or pitch
                            if (cmd == 0x90 && vel > 0) {
                                open.getOrPut(key) { ArrayDeque() }.addLast(tick to vel)
                            } else {
                                open[key]?.removeFirstOrNull()?.let { (st, v) ->
                                    out += RawNote(st, tick, pitch, v, track, ch)
                                }
                            }
                        }
                        0xA0, 0xB0, 0xE0 -> r.pos += 2
                        0xC0, 0xD0 -> r.pos += 1
                    }
                }
            }
        }
        for ((key, starts) in open) for ((st, v) in starts) {
            out += RawNote(st, maxOf(tick, st + 1), key and 0xFF, v, track, key shr 8)
        }
    }

    private fun buildTickConverter(division: Int, tempos: List<Pair<Long, Long>>): (Long) -> Double {
        if (division and 0x8000 != 0) {
            val fps = 256 - ((division shr 8) and 0xFF)
            val tpf = division and 0xFF
            val msPerTick = 1000.0 / (fps * tpf)
            return { it * msPerTick }
        }
        val tpq = division.toDouble()
        val sorted = tempos.sortedBy { it.first }.distinctBy { it.first }
        val ticks = LongArray(sorted.size + 1)
        val us = LongArray(sorted.size + 1)
        val ms = DoubleArray(sorted.size + 1)
        ticks[0] = 0; us[0] = 500_000; ms[0] = 0.0
        var n = 1
        for ((tk, u) in sorted) {
            if (tk == 0L) { us[0] = u; continue }
            ticks[n] = tk
            ms[n] = ms[n - 1] + (tk - ticks[n - 1]) * us[n - 1] / tpq / 1000.0
            us[n] = u
            n++
        }
        return { tick ->
            var i = n - 1
            while (i > 0 && ticks[i] > tick) i--
            ms[i] + (tick - ticks[i]) * us[i] / tpq / 1000.0
        }
    }
}

object DemoSongs {
    fun twinkle(): Song {
        val beat = 600.0
        val l1 = listOf(60 to 1, 60 to 1, 67 to 1, 67 to 1, 69 to 1, 69 to 1, 67 to 2, 65 to 1, 65 to 1, 64 to 1, 64 to 1, 62 to 1, 62 to 1, 60 to 2)
        val l2 = listOf(67 to 1, 67 to 1, 65 to 1, 65 to 1, 64 to 1, 64 to 1, 62 to 2, 67 to 1, 67 to 1, 65 to 1, 65 to 1, 64 to 1, 64 to 1, 62 to 2)
        val bass1 = listOf(48, 48, 53, 48, 53, 48, 43, 48)
        val bass2 = listOf(48, 53, 48, 43, 48, 53, 48, 43)
        val notes = mutableListOf<Note>()
        var t = 0.0
        for ((p, d) in l1 + l2 + l1) {
            notes += Note(p, t, t + d * beat * 0.9, 80, Hand.RIGHT)
            t += d * beat
        }
        t = 0.0
        for (p in bass1 + bass2 + bass1) {
            notes += Note(p, t, t + 2 * beat * 0.95, 64, Hand.LEFT)
            t += 2 * beat
        }
        return Song("小星星（内置示例）", notes)
    }
}
