package com.cxm.piano.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.cxm.piano.midi.PianoMidi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

enum class SoundOut(val label: String) { PHONE("手机"), PIANO("钢琴"), OFF("静音") }

/** Routes demo / accompaniment notes to the phone synth or to the connected piano. */
object Sound {
    val output = MutableStateFlow(SoundOut.PHONE)
    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("sound", 0)
        output.value = runCatching { SoundOut.valueOf(prefs.getString("out", "PHONE")!!) }.getOrDefault(SoundOut.PHONE)
    }

    fun setOutput(o: SoundOut) {
        allOff()
        output.value = o
        prefs.edit().putString("out", o.name).apply()
    }

    fun noteOn(pitch: Int, vel: Int) = when (output.value) {
        SoundOut.PHONE -> Synth.noteOn(pitch, vel)
        SoundOut.PIANO -> PianoMidi.playOn(pitch, vel)
        SoundOut.OFF -> Unit
    }

    fun noteOff(pitch: Int) = when (output.value) {
        SoundOut.PHONE -> Synth.noteOff(pitch)
        SoundOut.PIANO -> PianoMidi.playOff(pitch)
        SoundOut.OFF -> Unit
    }

    fun allOff() {
        Synth.allOff()
        PianoMidi.allNotesOff()
    }
}

/** Small additive piano-ish synth on a low-latency AudioTrack. */
object Synth {
    private const val RATE = 44100
    private const val TABLE = 4096
    private const val MAX_VOICES = 32
    private const val BLOCK = 256

    private val table = FloatArray(TABLE + 1).also { t ->
        for (i in 0..TABLE) {
            val x = 2 * PI * i / TABLE
            t[i] = (sin(x) + 0.5 * sin(2 * x) + 0.22 * sin(3 * x) + 0.1 * sin(4 * x) + 0.05 * sin(5 * x)).toFloat() / 1.87f
        }
    }

    private class Voice(
        val pitch: Int,
        val inc: Double,
        var amp: Float,
        val decay: Float,
    ) {
        var phase = 0.0
        var released = false
        var attack = 0
    }

    private val voices = ArrayList<Voice>()
    private val lock = Any()
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "synth").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
        synchronized(lock) { voices.clear() }
    }

    fun noteOn(pitch: Int, vel: Int) {
        if (!running) start()
        val freq = 440.0 * 2.0.pow((pitch - 69) / 12.0)
        // lower notes ring longer
        val seconds = (4.0 - (pitch - 21) / 30.0).coerceIn(0.8, 4.0)
        val decay = exp(-5.0 / (seconds * RATE)).toFloat()
        val amp = 0.18f * (vel / 127f).coerceIn(0.15f, 1f)
        synchronized(lock) {
            voices.filter { it.pitch == pitch }.forEach { it.released = true }
            if (voices.size >= MAX_VOICES) voices.removeAt(0)
            voices += Voice(pitch, freq * TABLE / RATE, amp, decay)
        }
    }

    fun noteOff(pitch: Int) {
        synchronized(lock) { voices.filter { it.pitch == pitch }.forEach { it.released = true } }
    }

    fun allOff() {
        synchronized(lock) { voices.forEach { it.released = true } }
    }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, BLOCK * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        val mix = FloatArray(BLOCK)
        val out = ShortArray(BLOCK)
        val releaseMul = exp(-5.0 / (0.12 * RATE)).toFloat()
        while (running) {
            mix.fill(0f)
            synchronized(lock) {
                val it = voices.iterator()
                while (it.hasNext()) {
                    val v = it.next()
                    val mul = if (v.released) releaseMul else v.decay
                    var ph = v.phase
                    var a = v.amp
                    for (i in 0 until BLOCK) {
                        val idx = ph.toInt()
                        val frac = (ph - idx).toFloat()
                        val s = table[idx] + (table[idx + 1] - table[idx]) * frac
                        val env = if (v.attack < 64) (v.attack++ / 64f) else 1f
                        mix[i] += s * a * env
                        a *= mul
                        ph += v.inc
                        if (ph >= TABLE) ph -= TABLE
                    }
                    v.phase = ph
                    v.amp = a
                    if (a < 0.0005f) it.remove()
                }
            }
            for (i in 0 until BLOCK) {
                val x = mix[i]
                val soft = x / (1f + kotlin.math.abs(x)) * 1.3f
                out[i] = (soft.coerceIn(-1f, 1f) * 32000).toInt().toShort()
            }
            track.write(out, 0, BLOCK)
        }
        track.stop()
        track.release()
    }
}
