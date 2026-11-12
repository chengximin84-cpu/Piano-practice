package com.cxm.piano.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.cxm.piano.midi.DemoSongs
import com.cxm.piano.midi.MidiParser
import com.cxm.piano.midi.Song
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class SongEntry(val title: String, val file: File?, val addedAt: Long) {
    val isDemo get() = file == null
}

object SongLibrary {
    private lateinit var dir: File
    val entries = MutableStateFlow<List<SongEntry>>(emptyList())

    fun init(context: Context) {
        dir = File(context.filesDir, "songs").apply { mkdirs() }
        refresh()
    }

    fun refresh() {
        val files = dir.listFiles { f -> f.extension.lowercase() in setOf("mid", "midi") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { SongEntry(it.nameWithoutExtension, it, it.lastModified()) }
        entries.value = files + SongEntry(DemoSongs.twinkle().title, null, 0)
    }

    fun load(entry: SongEntry): Song =
        if (entry.file == null) DemoSongs.twinkle() else MidiParser.parse(entry.file)

    /** Copies the picked file into app storage; returns the entry or throws with a readable message. */
    fun import(context: Context, uri: Uri): SongEntry {
        val cr = context.contentResolver
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取文件")
        var name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "曲子"
        name = name.substringAfterLast('/').substringBeforeLast('.').replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "曲子" }
        MidiParser.parse(bytes, name)
        var target = File(dir, "$name.mid")
        var n = 2
        while (target.exists()) target = File(dir, "$name ($n).mid").also { n++ }
        target.writeBytes(bytes)
        refresh()
        return entries.value.first { it.file == target }
    }

    fun delete(entry: SongEntry) {
        entry.file?.delete()
        refresh()
    }

    fun rename(entry: SongEntry, newTitle: String): Boolean {
        val f = entry.file ?: return false
        val clean = newTitle.trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
        if (clean.isEmpty()) return false
        val target = File(dir, "$clean.mid")
        if (target.exists()) return false
        val ok = f.renameTo(target)
        refresh()
        return ok
    }
}
