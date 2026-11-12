package com.cxm.piano

import android.app.Application
import com.cxm.piano.audio.Sound
import com.cxm.piano.data.SongLibrary
import com.cxm.piano.midi.PianoMidi
import com.cxm.piano.stats.PracticeStats

class PianoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PracticeStats.init(this)
        SongLibrary.init(this)
        Sound.init(this)
        PianoMidi.init(this)
    }
}
