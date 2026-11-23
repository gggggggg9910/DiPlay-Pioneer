package com.shilapi.xcertplay.media
import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal interface PioneerMediaRoute {
 val ready: Boolean
 val token: String
 fun acquire(id: Any)
 fun release(id: Any)
 fun interrupt(id: Any, active: Boolean)
 fun close()
}

/** Native source205 remains owned by the factory Bluetooth service. */
internal class PioneerBluetoothSession(private val hw: PioneerUsbHardware) {
 private var previous = 0
 private var owned = false
 private var interrupted = false
 var ready = false; private set
 fun start(active: () -> Boolean): Boolean {
  if (!active() || hw.current() in PioneerUsbSession.PHONE_SOURCES) return false
  check(hw.status() == 3) { "Bluetooth source205 unavailable; pair a phone with the factory Bluetooth first" }
  check(!hw.storagePresent()) { "Pause the factory Bluetooth player before starting DiPlay" }
  previous = hw.current()
  try {
   owned = true
   hw.select(205)
   check(hw.awaitSource(205) && active()) { "Bluetooth source activation refused or cancelled" }
   ready = true
   return true
  } catch (e: Exception) { close(); throw e }
 }
 fun tick(suspended: Boolean = false): Boolean {
  if (!owned) return false
  if (hw.storagePresent()) { close(); return false }
  val current = hw.current()
  if (suspended || current in PioneerUsbSession.PHONE_SOURCES) { interrupted = true; ready = false; return true }
  if (interrupted && current in setOf(0, 300) && hw.status() == 3) { hw.select(205); hw.awaitSource(205) }
  ready = hw.current() == 205 && hw.status() == 3
  if (!ready) { close(); return false }
  interrupted = false
  return true
 }
 fun close() {
  ready = false
  if (owned && hw.current() == 205 && previous != 205 && !hw.storagePresent()) hw.select(previous)
  owned = false
 }
}
private class AndroidPioneerBluetoothHardware : PioneerUsbHardware {
 private val cls = Class.forName("jp.pioneer.ceam.MediaManager.MediaController")
 private val controller = requireNotNull(cls.getMethod("getInstance").invoke(null))
 private val audioClass = Class.forName("jp.pioneer.ceam.bluetooth.BluetoothAudio")
 private val audio = requireNotNull(audioClass.getMethod("getDefault").invoke(null))
 override fun current() = (cls.getMethod("getCurSource", Int::class.javaPrimitiveType).invoke(controller,1) as Number).toInt()
 override fun status() = (cls.getMethod("getSourceStatus", Int::class.javaPrimitiveType).invoke(controller,205) as Number).toInt()
 // A live factory music stream shares this token; yield instead of mixing two players.
 override fun storagePresent() = (audioClass.getMethod("getConnectionState").invoke(audio) as Number).toInt() == 3 &&
  (audioClass.getMethod("getPlayState").invoke(audio) as Number).toInt() == 10
 override fun select(source: Int) { check((cls.getMethod("change2Source", Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).invoke(controller,1,source) as Number).toInt() == 0) }
 override fun awaitSource(source: Int): Boolean { repeat(10) { if(current()==source)return true;Thread.sleep(100) };return current()==source }
 override fun status(value: Int) = error("Native Bluetooth availability must not be changed")
 override fun watchdog(): PioneerUsbWatchdog = error("Native Bluetooth route needs no MCU helper")
}
internal class PioneerBluetoothRoute(context: Context, private val report: (String) -> Unit) : PioneerMediaRoute {
    override val token = BLUETOOTH_TOKEN
    private val hardware = AndroidPioneerBluetoothHardware()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "diplay-bt-source") }
    private val streams = mutableSetOf<Any>()
    private val interruptions = mutableSetOf<Any>()
    @Volatile private var wanted = false
    @Volatile private var closed = false
    @Volatile private var suspended = false
    @Volatile private var playbackGeneration = 0L
    private var observedGeneration = 0L
    @Volatile override var ready = false; private set
    private var session: PioneerBluetoothSession? = null
    private var yielded = false

    init { worker.scheduleWithFixedDelay({ update() }, 0, 500, TimeUnit.MILLISECONDS) }
    @Synchronized override fun acquire(id: Any) {
        if (!closed) { if (streams.isEmpty()) playbackGeneration++; streams.add(id); wanted = true }
    }
    @Synchronized override fun release(id: Any) { streams.remove(id); wanted = streams.isNotEmpty() }
    @Synchronized override fun interrupt(id: Any, active: Boolean) {
        if (active) interruptions.add(id) else interruptions.remove(id)
        suspended = interruptions.isNotEmpty()
        if (suspended) ready = false
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; wanted = false; streams.clear(); ready = false
        worker.execute { update() }
        worker.shutdown()
    }

    private fun update() {
        try {
            if (!wanted || closed) {
                ready = false
                session?.close(); session = null; yielded = false
                return
            }
            if (observedGeneration != playbackGeneration) { observedGeneration = playbackGeneration; yielded = false }
            if (yielded) return
            if (session == null) {
                if (suspended) return
                val next = PioneerBluetoothSession(hardware)
                if (!next.start { wanted && !closed }) return
                session = next
                report("Audio: Pioneer Bluetooth205 acquired token=$BLUETOOTH_TOKEN; no 401 media focus")
            } else if (session?.tick(suspended) == false) {
                session = null; yielded = true
                report("Audio: Pioneer Bluetooth205 yielded to external source/device; resume playback to reacquire")
            }
            ready = session?.ready == true
        } catch (error: Exception) {
            ready = false
            runCatching { session?.close() }; session = null; yielded = true
            runCatching { report("Audio: Pioneer Bluetooth205 failed ${error.javaClass.simpleName}: ${error.message}") }
        }
    }

    companion object { const val BLUETOOTH_TOKEN = "mediamanager.source.name.bt-audio" }
}
