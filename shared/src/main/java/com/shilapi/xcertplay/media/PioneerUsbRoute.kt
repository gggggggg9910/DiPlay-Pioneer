package com.shilapi.xcertplay.media

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal interface PioneerUsbWatchdog {
    fun heartbeat()
    fun stop(relinquish: Boolean)
}

internal interface PioneerUsbHardware {
    /** Wait out a still-live previous watchdog, or recover only our own stale lease. */
    fun prepare(): Boolean = true
    fun current(): Int
    fun status(): Int
    fun storagePresent(): Boolean
    fun select(source: Int)
    fun status(value: Int)
    fun watchdog(): PioneerUsbWatchdog
    fun awaitSource(source: Int): Boolean
}

/** One temporary USB lease. Never registers over the native MSC owner. */
internal class PioneerUsbSession(private val hw: PioneerUsbHardware) {
    private var watchdog: PioneerUsbWatchdog? = null
    private var previous = 0
    private var owned = false
    private var interrupted = false
    var ready = false; private set

    fun start(active: () -> Boolean): Boolean {
        if (!active() || hw.current() in PHONE_SOURCES) return false
        if (!hw.prepare()) return false
        check(!hw.storagePresent() && hw.status() == 2) { "real USB media or another USB lease owns source202" }
        previous = hw.current()
        try {
            watchdog = hw.watchdog()
            check(active()) { "playback stopped during activation" }
            owned = true
            hw.status(3)
            check(hw.status() == 3 && !hw.storagePresent()) { "USB source state changed during activation" }
            check(active() && hw.current() !in PHONE_SOURCES) { "yielding activation" }
            hw.select(202)
            check(hw.awaitSource(202) && active()) { "USB source activation refused or cancelled" }
            ready = true
            return true
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    /** False means an external source has taken over; do not keep stealing it back. */
    fun tick(suspended: Boolean = false): Boolean {
        if (!owned) return false
        if (hw.storagePresent()) { close(); return false }
        watchdog?.heartbeat()
        val current = hw.current()
        if (suspended || current in PHONE_SOURCES) { interrupted = true; ready = false; return true }
        if (interrupted && current in setOf(0, 300) && hw.status() == 3) {
            hw.select(202)
            hw.awaitSource(202)
        }
        ready = hw.current() == 202 && hw.status() == 3
        if (!ready) { close(); return false }
        interrupted = false
        return true
    }

    fun close() {
        ready = false
        val realStorage = hw.storagePresent()
        try {
            if (owned && !realStorage) {
                if (hw.current() == 202) { hw.select(0); hw.awaitSource(0) }
                hw.status(2)
                // Restore an available original source only while still OFF, never over a call/user switch.
                if (hw.current() == 0 && previous != 0 && previous != 202 && previous !in PHONE_SOURCES) {
                    hw.select(previous)
                }
            }
        } finally {
            owned = false
            try { watchdog?.stop(realStorage) } finally { watchdog = null }
        }
    }

    companion object { val PHONE_SOURCES = setOf(206, 207, 208, 302) }
}

/** All controller/process work runs off the audio and UI threads. */
internal class PioneerUsbRoute(context: Context, private val report: (String) -> Unit) : PioneerMediaRoute {
    override val token = USB_TOKEN
    private val hardware = AndroidPioneerUsbHardware(context)
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "diplay-usb-source") }
    private val streams = mutableSetOf<Any>()
    private val interruptions = mutableSetOf<Any>()
    @Volatile private var wanted = false
    @Volatile private var closed = false
    @Volatile private var suspended = false
    @Volatile private var playbackGeneration = 0L
    private var observedGeneration = 0L
    @Volatile override var ready = false; private set
    private var session: PioneerUsbSession? = null
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
                val next = PioneerUsbSession(hardware)
                if (!next.start { wanted && !closed }) return
                session = next
                report("Audio: Pioneer USB202 acquired token=$USB_TOKEN; no 401 media focus")
            } else if (session?.tick(suspended) == false) {
                session = null; yielded = true
                report("Audio: Pioneer USB202 yielded to external source/device; resume playback to reacquire")
            }
            ready = session?.ready == true
        } catch (error: Exception) {
            ready = false
            runCatching { session?.close() }; session = null; yielded = true
            runCatching { report("Audio: Pioneer USB202 failed ${error.javaClass.simpleName}: ${error.message}") }
        }
    }

    companion object {
        const val USB_TOKEN = "mediamanager.source.name.usb"
        fun supported(context: Context): Boolean = runCatching {
            val lib = File("/system/lib/libSourceSts.so")
            File(context.applicationInfo.nativeLibraryDir, "libdiplay_usb_route.so").canExecute() &&
                lib.isFile && MessageDigest.getInstance("SHA-256").digest(lib.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) } ==
                "ac7989b3db2223284cc407ccc6ac135d4973744e287a4cd028a815649b4ec7b8"
        }.getOrDefault(false)
    }
}

private class AndroidPioneerUsbHardware(private val context: Context) : PioneerUsbHardware {
    private val marker = File(context.filesDir, "pioneer-usb-source.lease")
    private val cls = Class.forName("jp.pioneer.ceam.MediaManager.MediaController")
    private val controller = requireNotNull(cls.getMethod("getInstance").invoke(null))
    private val callback = Binder()
    private val source: IBinder = run {
        val service = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "mediamanager.sourcemanagerservice") as IBinder
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("jp.pioneer.ceam.mediamanager.ISourceManagerService")
            data.writeInt(android.os.Process.myPid()); data.writeStrongBinder(callback)
            check(service.transact(1, data, reply, 0))
            requireNotNull(reply.readStrongBinder()).also {
                check(it.interfaceDescriptor == "jp.pioneer.ceam.mediamanager.ISource")
            }
        } finally { data.recycle(); reply.recycle() }
    }
    private fun processStamp(pid: Int): String? = runCatching {
        File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19]
    }.getOrNull()

    override fun prepare(): Boolean {
        if (!marker.isFile) return true
        val parts = marker.readText().trim().split(' ')
        val pid = parts.firstOrNull()?.toIntOrNull() ?: error("invalid USB lease marker")
        if (File("/proc/$pid").exists() && (parts.getOrNull(1) == "unknown" || processStamp(pid) == parts.getOrNull(1))) {
            return false // The old watchdog still owns its six-second crash recovery window.
        }
        if (!storagePresent()) {
            if (current() == 202) { select(0); awaitSource(0) }
            status(2)
        }
        check(marker.delete()) { "could not retire stale USB lease" }
        return true
    }
    override fun current() = (cls.getMethod("getCurSource", Int::class.javaPrimitiveType).invoke(controller, 1) as Number).toInt()
    override fun status() = (cls.getMethod("getSourceStatus", Int::class.javaPrimitiveType).invoke(controller, 202) as Number).toInt()
    override fun select(source: Int) {
        check((cls.getMethod("change2Source", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(controller, 1, source) as Number).toInt() == 0) { "source select refused: $source" }
    }
    override fun status(value: Int) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("jp.pioneer.ceam.mediamanager.ISource")
            data.writeInt(202); data.writeInt(value)
            check(source.transact(5, data, reply, 0) && reply.readInt() == 0) { "USB status refused" }
        } finally { data.recycle(); reply.recycle() }
    }
    override fun storagePresent(): Boolean {
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        return usb?.deviceList?.values?.any { d ->
            d.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE ||
                (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
        } == true
    }
    override fun awaitSource(source: Int): Boolean {
        repeat(10) { if (current() == source) return true; Thread.sleep(100) }
        return current() == source
    }
    override fun watchdog(): PioneerUsbWatchdog {
        val helper = File(context.applicationInfo.nativeLibraryDir, "libdiplay_usb_route.so")
        val process = ProcessBuilder(helper.absolutePath).redirectErrorStream(true).start()
        // The helper emits readiness after loading the original firmware entry and setting MCU status.
        val deadline = android.os.SystemClock.elapsedRealtime() + 1500
        val input = process.inputStream
        val output = StringBuilder()
        while (!output.contains("USB_READY") && android.os.SystemClock.elapsedRealtime() < deadline) {
            if (input.available() > 0) output.append(ByteArray(input.available()).let { input.read(it); String(it) })
            else Thread.sleep(25)
        }
        if (!output.contains("USB_READY")) { process.destroy(); error("USB watchdog startup failed: $output") }
        val pid = Regex("USB_READY (\\d+)").find(output)?.groupValues?.get(1)?.toInt()
            ?: run { process.destroy(); error("USB watchdog identity missing") }
        try {
            val atomic = android.util.AtomicFile(marker)
            val file = atomic.startWrite()
            try {
                file.write("$pid ${processStamp(pid) ?: "unknown"}".toByteArray())
                atomic.finishWrite(file)
            } catch (error: Exception) { atomic.failWrite(file); throw error }
        } catch (error: Exception) { process.destroy(); throw error }
        return object : PioneerUsbWatchdog {
            override fun heartbeat() { process.outputStream.write('h'.code); process.outputStream.flush() }
            override fun stop(relinquish: Boolean) {
                runCatching { process.outputStream.write(if (relinquish) 'r'.code else 'q'.code); process.outputStream.flush() }
                process.outputStream.close()
                var exited = false
                for (attempt in 0 until 20) {
                    try { process.exitValue(); exited = true; break } catch (_: IllegalThreadStateException) { Thread.sleep(50) }
                }
                if (!exited) process.destroy()
                if (exited && (relinquish || status() == 2)) marker.delete()
            }
        }
    }
}
