package com.cxm.piano.midi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.cxm.piano.stats.PracticeStats
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class KeyEvent(val pitch: Int, val velocity: Int, val down: Boolean, val fromPiano: Boolean)

data class FoundDevice(val name: String, val address: String, val isMidiService: Boolean)

enum class LinkStatus { DISCONNECTED, CONNECTING, CONNECTED }

data class LinkState(val status: LinkStatus = LinkStatus.DISCONNECTED, val deviceName: String = "")

@SuppressLint("MissingPermission")
object PianoMidi {
    private val MIDI_SERVICE: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    private const val PREFS = "piano_midi"

    val link = MutableStateFlow(LinkState())
    val held = MutableStateFlow<Set<Int>>(emptySet())
    val events = MutableSharedFlow<KeyEvent>(extraBufferCapacity = 512)
    val found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val scanning = MutableStateFlow(false)
    val log = MutableStateFlow<List<String>>(emptyList())

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var midiManager: MidiManager? = null
    private var device: MidiDevice? = null
    private var outPort: MidiOutputPort? = null
    private var inPort: MidiInputPort? = null
    private var scanCb: ScanCallback? = null
    private val btDevices = HashMap<String, BluetoothDevice>()

    val midiSupported: Boolean get() = midiManager != null

    fun init(context: Context) {
        app = context.applicationContext
        if (app.packageManager.hasSystemFeature("android.software.midi")) {
            midiManager = app.getSystemService(MidiManager::class.java)
            midiManager?.registerDeviceCallback(object : MidiManager.DeviceCallback() {
                override fun onDeviceRemoved(info: MidiDeviceInfo) {
                    if (device?.info?.id == info.id) {
                        addLog("设备已断开：${nameOf(info)}")
                        closeDevice()
                    }
                }
            }, main)
        }
    }

    fun addLog(s: String) {
        log.update { (listOf(s) + it).take(40) }
    }

    private fun nameOf(info: MidiDeviceInfo): String =
        info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI 设备"

    /** MIDI devices the OS already knows about (USB, or BLE opened elsewhere). */
    @Suppress("DEPRECATION")
    fun systemDevices(): List<MidiDeviceInfo> =
        midiManager?.devices?.filter { it.outputPortCount > 0 }.orEmpty()

    fun startScan(onlyMidi: Boolean) {
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            addLog("蓝牙未开启")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: return
        stopScan()
        found.value = emptyList()
        btDevices.clear()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val uuids = result.scanRecord?.serviceUuids.orEmpty()
                val isMidi = uuids.any { it.uuid == MIDI_SERVICE }
                val name = result.scanRecord?.deviceName ?: dev.name
                if (!onlyMidi && name.isNullOrBlank()) return
                if (btDevices.containsKey(dev.address)) return
                btDevices[dev.address] = dev
                found.update {
                    (it + FoundDevice(name ?: "未命名设备", dev.address, isMidi || onlyMidi))
                        .sortedByDescending { d -> d.isMidiService }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                addLog("扫描失败，错误码 $errorCode")
                scanning.value = false
            }
        }
        val filters = if (onlyMidi) listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(MIDI_SERVICE)).build()) else emptyList()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, cb)
        scanCb = cb
        scanning.value = true
        main.postDelayed({ stopScan() }, 15_000)
    }

    fun stopScan() {
        val cb = scanCb ?: return
        scanCb = null
        runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb) }
        scanning.value = false
    }

    fun connectBle(address: String, name: String) {
        val mm = midiManager ?: return addLog("这台手机不支持 MIDI")
        if (link.value.status != LinkStatus.DISCONNECTED) {
            addLog("已经处于${if (link.value.status == LinkStatus.CONNECTED) "已连接" else "连接中"}状态，忽略重复连接")
            return
        }
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        stopScan()
        closeDevice()
        val dev = btDevices[address] ?: adapter.getRemoteDevice(address)
        link.value = LinkState(LinkStatus.CONNECTING, name)
        addLog("正在连接 $name …")
        runCatching {
            mm.openBluetoothDevice(dev, { d ->
                if (d == null) {
                    addLog("连接失败：$name 没有响应 MIDI 服务")
                    link.value = LinkState()
                } else {
                    app.getSharedPreferences(PREFS, 0).edit()
                        .putString("last_addr", address).putString("last_name", name).apply()
                    attach(d, name)
                }
            }, main)
        }.onFailure {
            addLog("连接异常：${it.message}")
            link.value = LinkState()
        }
    }

    fun connectSystem(info: MidiDeviceInfo) {
        val mm = midiManager ?: return
        if (link.value.status != LinkStatus.DISCONNECTED) {
            addLog("已经处于已连接状态，忽略重复连接")
            return
        }
        closeDevice()
        val name = nameOf(info)
        link.value = LinkState(LinkStatus.CONNECTING, name)
        runCatching {
            mm.openDevice(info, { d ->
                if (d == null) {
                    addLog("打开失败：$name")
                    link.value = LinkState()
                } else attach(d, name)
            }, main)
        }.onFailure {
            addLog("打开异常：${it.message}")
            link.value = LinkState()
        }
    }

    fun reconnectLast() {
        if (link.value.status != LinkStatus.DISCONNECTED) return
        val p = app.getSharedPreferences(PREFS, 0)
        val addr = p.getString("last_addr", null) ?: return
        connectBle(addr, p.getString("last_name", null) ?: "上次的钢琴")
    }

    val lastDeviceName: String?
        get() = app.getSharedPreferences(PREFS, 0).getString("last_name", null)

    private fun attach(d: MidiDevice, name: String) {
        device = d
        val info = d.info
        if (info.outputPortCount > 0) {
            outPort = d.openOutputPort(0)?.also { it.connect(Receiver()) }
        }
        if (info.inputPortCount > 0) {
            inPort = d.openInputPort(0)
        }
        if (outPort == null) {
            addLog("$name 没有可读的 MIDI 输出端口")
        }
        link.value = LinkState(LinkStatus.CONNECTED, name)
        addLog("已连接 $name（输入 ${info.outputPortCount} / 输出 ${info.inputPortCount}）")
    }

    fun disconnect() {
        if (link.value.status == LinkStatus.DISCONNECTED && device == null) return
        closeDevice()
        addLog("已断开")
    }

    private fun closeDevice() {
        allNotesOff()
        runCatching { outPort?.close() }
        runCatching { inPort?.close() }
        runCatching { device?.close() }
        outPort = null; inPort = null; device = null
        held.value = emptySet()
        link.value = LinkState()
    }

    // ---- sending to the piano (demo / accompaniment playback) ----

    private val buf = ByteArray(3)

    @Synchronized
    private fun send(a: Int, b: Int, c: Int) {
        val p = inPort ?: return
        buf[0] = a.toByte(); buf[1] = b.toByte(); buf[2] = c.toByte()
        runCatching { p.send(buf, 0, 3) }
    }

    val canSendToPiano: Boolean get() = inPort != null

    fun playOn(pitch: Int, velocity: Int) = send(0x90, pitch, velocity.coerceIn(1, 127))
    fun playOff(pitch: Int) = send(0x80, pitch, 0)
    fun allNotesOff() {
        send(0xB0, 64, 0)
        send(0xB0, 123, 0)
    }

    // ---- input from the on-screen keyboard ----

    fun localDown(pitch: Int) {
        held.update { it + pitch }
        events.tryEmit(KeyEvent(pitch, 90, true, false))
        PracticeStats.markActivity()
    }

    fun localUp(pitch: Int) {
        held.update { it - pitch }
        events.tryEmit(KeyEvent(pitch, 0, false, false))
    }

    private class Receiver : MidiReceiver() {
        private var running = 0
        private var inSysex = false

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            var i = offset
            val end = offset + count
            while (i < end) {
                val b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) { i++; continue } // realtime, e.g. active sensing
                if (inSysex) {
                    if (b == 0xF7) inSysex = false
                    i++; continue
                }
                if (b >= 0xF0) {
                    if (b == 0xF0) inSysex = true
                    running = 0
                    i++; continue
                }
                if (b >= 0x80) {
                    running = b
                    i++
                }
                if (running == 0) { i++; continue }
                val cmd = running and 0xF0
                val need = if (cmd == 0xC0 || cmd == 0xD0) 1 else 2
                if (i + need > end) break
                val d1 = msg[i].toInt() and 0x7F
                val d2 = if (need == 2) msg[i + 1].toInt() and 0x7F else 0
                i += need
                when {
                    cmd == 0x90 && d2 > 0 -> {
                        held.update { it + d1 }
                        events.tryEmit(KeyEvent(d1, d2, true, true))
                        PracticeStats.markActivity()
                    }
                    cmd == 0x80 || cmd == 0x90 -> {
                        held.update { it - d1 }
                        events.tryEmit(KeyEvent(d1, 0, false, true))
                    }
                }
            }
        }
    }
}
