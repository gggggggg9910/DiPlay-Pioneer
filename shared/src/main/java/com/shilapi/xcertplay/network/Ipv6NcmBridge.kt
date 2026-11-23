package com.shilapi.xcertplay.network

import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.diagnostics.VideoPipelineTrace
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Moves IPv6 packets between an Android VpnService tun and the iPhone NCM Ethernet link.
 *
 * NCM carries Ethernet frames while the tun is a layer-3 device, so this bridge strips and
 * restores the Ethernet II header. Link-local neighbor discovery stays in the Android kernel,
 * mirroring LIVI's reliance on the host kernel for NDP on its TAP interface.
 */
class Ipv6NcmBridge(
    private val ncm: NcmUsbBridge,
    private val tun: ParcelFileDescriptor?,
    private val hostMac: ByteArray,
    private val hostAddress: ByteArray,
    private val onError: (Throwable) -> Unit,
    /** Userspace mode: terminate the iPhone's TCP here instead of relying on the (dead, on
     * Android 7.x firmware) kernel stack of a VpnService tun. */
    private val userspace: Boolean = false,
) : Closeable {
    private val userspaceEngine: UserspaceLwipEngine? =
        if (userspace) {
            UserspaceLwipEngine(
                hostAddress = hostAddress,
                sendIpv6 = ::sendUserspaceIpv6,
                sendIpv6Burst = ::sendUserspaceIpv6Burst,
                log = { message ->
                    bridgeDebug(message)
                    if (message.startsWith("UsbOutPerf ")) {
                        Log.i(TAG, message)
                        ncm.reportPerformance(message)
                    }
                },
                onError = onError,
            )
        } else {
            null
        }

    init {
        require(hostMac.size == EthernetIpv6Codec.MAC_BYTES) { "hostMac must be 6 bytes" }
        require(hostAddress.size == 16) { "hostAddress must be 16 bytes" }
        require(userspace || tun != null) { "kernel mode requires a tun descriptor" }
    }

    @Volatile
    private var peerMac: ByteArray? = null
    private var loggedInbound = false
    private var loggedOutbound = false
    private var loggedWaitingForPeer = false
    private var loggedUserspaceNa = false
    private var multicastSkipBudget = 8
    private var inboundLogBudget = 16
    private var outboundLogBudget = 24
    private val running = AtomicBoolean(false)
    private lateinit var ncmToTunThread: Thread
    private lateinit var tunToNcmThread: Thread

    fun start() {
        check(running.compareAndSet(false, true)) { "bridge is already started" }
        userspaceEngine?.start()
        ncmToTunThread = Thread(::runNcmToTun, "ncm-ipv6-in").apply {
            isDaemon = true
            start()
        }
        if (userspaceEngine == null) {
            tunToNcmThread = Thread(::runTunToNcm, "ncm-ipv6-out").apply {
                isDaemon = true
                start()
            }
            probeKernelTunDelivery()
        }
    }

    /**
     * Writes a synthetic ICMPv6 echo request into the tun addressed to our own link-local
     * address. If the kernel processes packets written into this VPN tunnel it answers with an
     * echo reply that runTunToNcm logs ("tun-read ... icmp6=129"); on kernels that ignore them
     * (Android 7.x VPN tuns on some firmware) nothing comes back, which is the marker for the
     * "kernel black hole" that breaks the whole wired transport.
     */
    private fun probeKernelTunDelivery() {
        try {
            val source = ByteArray(16).also { hostAddress.copyInto(it, 0, 0, 16) }
            // Fake a different source address so the kernel does not shortcut the reply locally.
            source[15] = (source[15].toInt() and 0xff xor 0x5a).toByte()
            val probe = EthernetIpv6Codec.buildEchoRequestIpv6(source, hostAddress)
            val descriptor = tun?.fileDescriptor ?: return
            android.system.Os.write(descriptor, probe, 0, probe.size)
            bridgeDebug("kernel probe: echo request injected into tun (${probe.size} bytes), watching for icmp6=129 reply")
            val ipv4 = EthernetIpv6Codec.buildEchoRequestIpv4(IPV4_PROBE_PEER, IPV4_PROBE_LOCAL)
            android.system.Os.write(descriptor, ipv4, 0, ipv4.size)
            bridgeDebug(
                "kernel probe: ipv4 echo request injected into tun (${ipv4.size} bytes) " +
                    "src=10.73.0.2 dst=10.73.0.1, watching for tun-read icmp4=0",
            )
        } catch (error: Exception) {
            bridgeDebug("kernel probe failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        userspaceEngine?.close()
        ncm.close()
        tun?.close()
        join(ncmToTunThread)
        if (userspaceEngine == null) join(tunToNcmThread)
    }

    private fun runNcmToTun() {
        VideoPipelineTrace.registerThread("usb-in")
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        val output = tun?.let { FileOutputStream(it.fileDescriptor) }
        try {
            while (running.get()) {
                // Pooled view: valid only until the next recvView call; every use below stays
                // inside this iteration, and onIpv6Packet copies synchronously in native code.
                val view = ncm.recvView(READ_TIMEOUT_MILLIS) ?: continue
                val frame = view.buffer
                val ipv6 = EthernetIpv6Codec.parseIpv6View(frame, view.length) ?: continue
                peerMac = ipv6.sourceMac
                if (!loggedInbound) {
                    loggedInbound = true
                    Log.i(
                        TAG,
                        "ncm first inbound ipv6 bytes=${ipv6.payloadLength} " +
                            "peer=${ipv6.sourceMac.macString()}",
                    )
                    bridgeDebug("first inbound ipv6 bytes=${ipv6.payloadLength} peer=${ipv6.sourceMac.macString()}")
                }
                if (inboundLogBudget > 0) {
                    inboundLogBudget--
                    Log.i(TAG, "ncm inbound ${frame.summary(ipv6.payloadOffset, view.length)}")
                    bridgeDebug("inbound ${frame.summary(ipv6.payloadOffset, view.length)}")
                }
                // The Android 7.x kernel never answers neighbor solicitations on a VpnService tun
                // (#557 family): the phone's probes go unanswered, it never opens the CarPlay
                // session, and the whole link stalls with toIphone=0. Answer here, in userspace,
                // on every Android version; kernels that do reply simply see a duplicate NA.
                EthernetIpv6Codec.parseNeighborSolicitation(frame, view.length, hostAddress)?.let { solicitation ->
                    val advertisement =
                        EthernetIpv6Codec.buildNeighborAdvertisement(hostMac, hostAddress, solicitation)
                    if (!loggedUserspaceNa) {
                        loggedUserspaceNa = true
                        Log.i(
                            TAG,
                            "ncm answered neighbor solicitation in userspace " +
                                "requester=${solicitation.requesterMac.macString()}",
                        )
                        bridgeDebug("answered neighbor solicitation requester=${solicitation.requesterMac.macString()}")
                    }
                    ncm.send(advertisement, WRITE_TIMEOUT_MILLIS)
                }
                if (userspaceEngine != null) {
                    userspaceEngine.onIpv6Packet(frame, ipv6.payloadOffset, ipv6.payloadLength)
                    continue
                }
                // Hop-by-hop (next header 0) and multicast frames written into a VpnService tun
                // stall packet processing on some Android 7.x kernels, and everything unicast
                // written afterwards is dropped with them. Keep the kernel fed with clean
                // unicast only; the userspace responder above already covers multicast NDP.
                if (EthernetIpv6Codec.isMulticastOrHopByHop(frame, view.length, ipv6.payloadOffset)) {
                    if (multicastSkipBudget > 0) {
                        multicastSkipBudget--
                        bridgeDebug("skipped multicast/hop-by-hop frame before tun write ${frame.summary(ipv6.payloadOffset, view.length)}")
                    }
                    continue
                }
                output?.write(frame, ipv6.payloadOffset, ipv6.payloadLength)
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    private fun runTunToNcm() {
        val input = tun?.let { FileInputStream(it.fileDescriptor) } ?: return
        val buffer = ByteArray(TUN_READ_BYTES)
        try {
            while (running.get()) {
                val length = input.read(buffer)
                if (length == -1) {
                    if (running.get()) onError(IOException("NCM IPv6 tunnel closed"))
                    return
                }
                // Android's TUN fd may transiently report a zero-byte read while its network is
                // being registered. It is neither EOF (-1) nor an IPv6 packet.
                if (length == 0) {
                    LockSupport.parkNanos(ZERO_READ_BACKOFF_NANOS)
                    continue
                }
                val tunPacket = buffer.copyOf(length)
                if (tunPacket.isNotEmpty() && (tunPacket[0].toInt() ushr 4 and 0x0f) == 4) {
                    bridgeDebug("tun-read ${tunPacket.ipv4Summary()}")
                    continue
                }
                val ipv6 = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(tunPacket, hostMac)
                if (ipv6.size != tunPacket.size) {
                    Log.i(TAG, "ncm added target-link-layer option to neighbor advertisement")
                }
                if (outboundLogBudget > 0) {
                    outboundLogBudget--
                    Log.i(TAG, "ncm outbound ${ipv6.summary(0)}")
                    bridgeDebug("tun-read ${ipv6.summary(0)}")
                }
                val multicastMac = EthernetIpv6Codec.multicastDestinationMac(ipv6)
                val mac = multicastMac ?: peerMac
                if (mac == null) {
                    if (!loggedWaitingForPeer) {
                        loggedWaitingForPeer = true
                        Log.i(TAG, "ncm deferred outbound unicast bytes=$length until peer MAC is learned")
                        bridgeDebug("deferred outbound unicast bytes=$length until peer MAC is learned")
                    }
                    continue
                }
                if (!loggedOutbound) {
                    loggedOutbound = true
                    Log.i(
                        TAG,
                        "ncm first outbound ipv6 bytes=$length destination=${mac.macString()} multicast=${multicastMac != null}",
                    )
                    bridgeDebug("first outbound ipv6 bytes=$length destination=${mac.macString()} multicast=${multicastMac != null}")
                }
                val frame = EthernetIpv6Codec.build(hostMac, mac, ipv6)
                ncm.send(frame, WRITE_TIMEOUT_MILLIS)
            }
        } catch (error: IOException) {
            if (running.get()) {
                bridgeDebug("tun-to-ncm IOException: ${error.message}")
                onError(error)
            }
        } catch (error: RuntimeException) {
            if (running.get()) {
                bridgeDebug("tun-to-ncm RuntimeException: ${error.javaClass.simpleName}: ${error.message}")
                onError(error)
            }
        }
    }

    /** Fallback when no burst writer is installed. Pooled lwIP buffers must not be passed here. */
    private fun sendUserspaceIpv6(ipv6: ByteArray) {
        val mac = EthernetIpv6Codec.multicastDestinationMac(ipv6) ?: peerMac
        if (mac == null) {
            if (!loggedWaitingForPeer) {
                loggedWaitingForPeer = true
                bridgeDebug("deferred userspace unicast bytes=${ipv6.size} until peer MAC is learned")
            }
            return
        }
        if (outboundLogBudget > 0) {
            outboundLogBudget--
            bridgeDebug("userspace out ${ipv6.summary(0)}")
        }
        val writeAt = System.nanoTime()
        ncm.send(EthernetIpv6Codec.build(hostMac, mac, ipv6), WRITE_TIMEOUT_MILLIS)
        VideoPipelineTrace.slow("usb-write", System.nanoTime() - writeAt)
    }

    /**
     * Packets the USB thread already has. They share one NCM transfer when they fit, and their
     * pooled buffers are released once the Ethernet frames have been copied.
     */
    private fun sendUserspaceIpv6Burst(packets: List<OutPacket>) {
        val frames = ArrayList<ByteArray>(packets.size)
        try {
            for (packet in packets) {
                if (packet.length < 40) continue
                val ipv6 = packet.bytes
                val mac = EthernetIpv6Codec.multicastDestinationMac(ipv6) ?: peerMac
                if (mac == null) {
                    if (!loggedWaitingForPeer) {
                        loggedWaitingForPeer = true
                        bridgeDebug("deferred userspace unicast bytes=${packet.length} until peer MAC is learned")
                    }
                    continue
                }
                if (outboundLogBudget > 0) {
                    outboundLogBudget--
                    bridgeDebug("userspace out ${ipv6.summary(0, packet.length)}")
                }
                frames.add(EthernetIpv6Codec.build(hostMac, mac, ipv6, packet.length))
            }
        } finally {
            for (packet in packets) UserspaceLwip.releaseEventBuffer(packet.bytes)
        }
        if (frames.isEmpty()) return
        val writeAt = System.nanoTime()
        ncm.sendFrames(frames, WRITE_TIMEOUT_MILLIS)
        VideoPipelineTrace.slow("usb-write", System.nanoTime() - writeAt)
    }

    /** Appends a line to a persistent file that survives cable pulls and reboots. */
    private fun bridgeDebug(line: String) {
        try {
            val file = java.io.File(
                "/sdcard/Android/data/jp.pioneer.ceam.test/files/bridge_debug.log",
            )
            file.parentFile?.mkdirs()
            java.io.FileWriter(file, true).use { writer ->
                writer.append(System.currentTimeMillis().toString())
                    .append(' ')
                    .append(line)
                    .append('\n')
            }
        } catch (_: Exception) {
        }
    }

    private fun join(thread: Thread) {
        if (thread === Thread.currentThread()) return
        try {
            thread.join(JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) thread.interrupt()
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ByteArray.ipv4Summary(): String {
        if (size < 20) return "ipv4 truncated bytes=$size"
        val headerLength = (this[0].toInt() and 0x0f) * 4
        val total = u16(2)
        val protocol = this[9].toInt() and 0xff
        val source = InetAddress.getByAddress(copyOfRange(12, 16)).hostAddress
        val destination = InetAddress.getByAddress(copyOfRange(16, 20)).hostAddress
        val icmp = if (protocol == 1 && size > headerLength) {
            " icmp4=${this[headerLength].toInt() and 0xff}"
        } else {
            ""
        }
        return "ipv4 bytes=$total src=$source dst=$destination proto=$protocol$icmp"
    }

    private fun ByteArray.summary(offset: Int): String = summary(offset, size)

    /** Length-aware variant for frames held in a larger (pooled) buffer. */
    private fun ByteArray.summary(offset: Int, length: Int): String {
        val payloadBytes = length - offset
        if (payloadBytes < 40) return "truncated bytes=$payloadBytes"
        val source = InetAddress.getByAddress(copyOfRange(offset + 8, offset + 24)).hostAddress
        val destination = InetAddress.getByAddress(copyOfRange(offset + 24, offset + 40)).hostAddress
        val nextHeader = this[offset + 6].toInt() and 0xff
        val detail = when {
            nextHeader == 6 && payloadBytes >= 44 -> " tcp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 17 && payloadBytes >= 44 -> " udp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 58 && payloadBytes >= 41 -> " icmp6=${this[offset + 40].toInt() and 0xff}"
            else -> ""
        }
        return "bytes=$payloadBytes src=$source dst=$destination next=$nextHeader$detail"
    }

    private fun ByteArray.u16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private companion object {
        const val TAG = "xcertplay-usb"
        const val READ_TIMEOUT_MILLIS = 1_000L
        const val WRITE_TIMEOUT_MILLIS = 2_000
        const val TUN_READ_BYTES = 4_096
        const val ZERO_READ_BACKOFF_NANOS = 1_000_000L
        const val JOIN_TIMEOUT_MILLIS = 2_000L
        val IPV4_PROBE_LOCAL = byteArrayOf(10, 73, 0, 1)
        val IPV4_PROBE_PEER = byteArrayOf(10, 73, 0, 2)
    }
}
