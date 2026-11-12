package com.cxm.piano.stats

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

class DayRecord(
    var seconds: Double = 0.0,
    val songs: HashMap<String, Double> = HashMap(),
    val acc: HashMap<String, Int> = HashMap()
)

/**
 * Practice time = time spent actually playing. Each key press extends the session;
 * gaps longer than IDLE_GAP_MS are treated as a break and not counted.
 */
object PracticeStats {
    private const val IDLE_GAP_MS = 20_000L
    private const val TAIL_MS = 3_000L

    private lateinit var file: File
    private val days = HashMap<LocalDate, DayRecord>()
    private var lastActivity = 0L
    private var lastSave = 0L

    @Volatile
    var currentSong: String? = null

    /** Bumped on every change so Compose screens can refresh. */
    val version = MutableStateFlow(0)

    fun init(context: Context) {
        file = File(context.filesDir, "practice_stats.json")
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            for (k in root.keys()) {
                val o = root.getJSONObject(k)
                val rec = DayRecord(o.optDouble("s", 0.0))
                o.optJSONObject("songs")?.let { songs -> for (t in songs.keys()) rec.songs[t] = songs.getDouble(t) }
                o.optJSONObject("acc")?.let { acc -> for (t in acc.keys()) rec.acc[t] = acc.getInt(t) }
                days[LocalDate.parse(k)] = rec
            }
        }
    }

    @Synchronized
    fun markActivity() {
        val now = SystemClock.uptimeMillis()
        val gap = now - lastActivity
        val add = if (lastActivity != 0L && gap in 0..IDLE_GAP_MS) gap else TAIL_MS
        lastActivity = now
        val rec = days.getOrPut(LocalDate.now()) { DayRecord() }
        val sec = add / 1000.0
        rec.seconds += sec
        currentSong?.let { rec.songs[it] = (rec.songs[it] ?: 0.0) + sec }
        if (now - lastSave > 10_000) {
            lastSave = now
            saveLocked()
            version.value++
        }
    }

    @Synchronized
    fun flush() {
        saveLocked()
        version.value++
    }

    private fun saveLocked() {
        val root = JSONObject()
        for ((d, r) in days) {
            val songs = JSONObject()
            for ((t, s) in r.songs) songs.put(t, s)
            val acc = JSONObject()
            for ((t, a) in r.acc) acc.put(t, a)
            root.put(d.toString(), JSONObject().put("s", r.seconds).put("songs", songs).put("acc", acc))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    @Synchronized
    fun snapshot(): Map<LocalDate, DayRecord> =
        days.mapValues { (_, r) -> DayRecord(r.seconds, HashMap(r.songs), HashMap(r.acc)) }

    /** Last measured accuracy per song for today; shown in the day detail dialog. */
    @Synchronized
    fun recordAccuracy(song: String, accuracy: Int) {
        val rec = days.getOrPut(LocalDate.now()) { DayRecord() }
        rec.acc[song] = accuracy.coerceIn(0, 100)
        saveLocked()
        version.value++
    }

    @Synchronized
    fun songSeconds(title: String): Double = days.values.sumOf { it.songs[title] ?: 0.0 }

    @Synchronized
    fun renameSong(from: String, to: String) {
        for (r in days.values) {
            r.songs.remove(from)?.let { r.songs[to] = (r.songs[to] ?: 0.0) + it }
            r.acc.remove(from)?.let { r.acc[to] = it }
        }
        saveLocked()
        version.value++
    }

    /** Days with at least this much playing count as a "practice day". */
    const val MIN_DAY_SECONDS = 60.0
}
