package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.diagnostics.VideoPipelineTrace
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Relays the iPhone's IPv6 TCP and UDP, terminated by lwIP, onto the sockets CarPlay already bound.
 *
 * TCP connections are phone-initiated. Each one is spliced to `127.0.0.1:port` or `[::1]:port`,
 * whichever is actually listening. UDP datagrams are injected at `[::1]:port`. Datagrams the
 * accessory sends (clock sync, microphone) go back through [UserspaceLink] so they leave on NCM
 * with the phone's real address, not the loopback address of the splice.
 */
internal class UserspaceLwipEngine(
    private val hostAddress: ByteArray,
    private val sendIpv6: (ByteArray) -> Unit,
    private val log: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val sendIpv6Burst: ((List<OutPacket>) -> Unit)? = null,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val relays = ConcurrentHashMap<Int, Relay>()
    private val udpSeen = ConcurrentHashMap.newKeySet<Int>()
    private var injector: DatagramSocket? = null
    private var pollThread: Thread? = null
    private var lastPerfNs = System.nanoTime()
    private val packetSender = OrderedPacketSender(sendIpv6, { error ->
        log("userspace USB output failed: ${error.javaClass.simpleName}")
        onError(error)
    }, onStart = {
        VideoPipelineTrace.registerThread("usb-out")
        // Same urgency as usb-in. ACKs on this thread are what let the phone send the next video.
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) }
    }, writeBurst = sendIpv6Burst)

    fun start() {
        check(running.compareAndSet(false, true)) { "userspace stack is already started" }
        if (!UserspaceLwip.ensureLoaded() || !UserspaceLwip.nativeStart(hostAddress)) {
            running.set(false)
            log("userspace lwip failed to start")
            return
        }
        injector = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("::1"), 0))
            }
        }.getOrElse { error ->
            log("userspace udp injector failed: ${error.message}")
            null
        }
        packetSender.start()
        UserspaceLink.activate(::sendUdp)
        pollThread = Thread(::pollLoop, "userspace-lwip").apply {
            isDaemon = true
            start()
        }
        log("userspace lwip started address=${InetAddress.getByAddress(hostAddress).hostAddress}")
    }

    fun onIpv6Packet(packet: ByteArray, offset: Int = 0, available: Int = packet.size - offset) {
        if (!running.get()) return
        val length = Ipv6Datagram.length(packet, offset, available) ?: return
        if (UserspaceLink.phone == null && length >= 40) {
            val source = InetAddress.getByAddress(packet.copyOfRange(offset + 8, offset + 24))
            val before = UserspaceLink.phone
            UserspaceLink.notePhone(source)
            if (before == null && UserspaceLink.phone != null) {
                log("userspace peer ${UserspaceLink.phone?.hostAddress}")
            }
        }
        if (length < 40 || !sameAddress(packet, offset + 24, hostAddress)) return
        if (isNeighborDiscovery(packet, offset, length)) return
        UserspaceLwip.nativeInputRange(packet, offset, length)
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        UserspaceLink.deactivate()
        UserspaceLwip.wake()
        relays.values.forEach { it.closeLocal() }
        relays.clear()
        runCatching { injector?.close() }
        injector = null
        if (Thread.currentThread() !== pollThread) pollThread?.join(1_000)
        packetSender.close()
        if (UserspaceLwip.ensureLoaded()) runCatching { UserspaceLwip.nativeStop() }
    }

    private fun pollLoop() {
        VideoPipelineTrace.registerThread("lwip-poll")
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        val event = UserspaceLwip.Event()
        while (running.get()) {
            try {
                drain(event)
                UserspaceLwip.nativeCheckTimeouts()
                val now = System.nanoTime()
                if (now - lastPerfNs >= 5_000_000_000L) {
                    log(packetSender.summary())
                    lastPerfNs = now
                }
                if (running.get()) UserspaceLwip.await(TIMER_WAIT_MILLIS)
            } catch (_: InterruptedException) {
                return
            } catch (error: Exception) {
                log("userspace poll ${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    private fun drain(event: UserspaceLwip.Event) {
        while (running.get() && UserspaceLwip.nativePoll(event)) {
            when (event.type) {
                UserspaceLwip.EVENT_ACCEPT -> acceptTcp(event.id, event.generation, event.localPort)
                UserspaceLwip.EVENT_DATA -> {
                    val relay = relays[event.id]?.takeIf { it.generation == event.generation }
                    if (relay != null) relay.offer(event.data, event.dataLength)
                    else UserspaceLwip.releaseEventBuffer(event.data)
                }
                UserspaceLwip.EVENT_CLOSE -> {
                    val relay = relays[event.id]
                    if (relay != null && relay.generation == event.generation) {
                        relays.remove(event.id, relay)
                        relay.closeLocal()
                    }
                }
                UserspaceLwip.EVENT_UDP -> deliverUdp(event)
                // The buffer is pooled and may be longer than the packet. Ownership moves with dataLength.
                UserspaceLwip.EVENT_PACKET -> if (!packetSender.send(event.data, event.dataLength)) {
                    UserspaceLwip.releaseEventBuffer(event.data)
                    return
                }
            }
            val nowNs = System.nanoTime()
            udpOutStats.forEach { (port, probe) ->
                if (probe.summaryIfDue(nowNs) { line -> log("userspace udp-out port=$port $line") }) {
                    // keep only ports that stay quiet-empty; a finished stream's probe is harmless to retain
                }
            }
        }
    }

    private fun acceptTcp(id: Int, generation: Int, port: Int) {
        val relay = Relay(id, generation)
        relays[id] = relay
        Thread({
            val socket = try {
                connectLocal(port)
            } catch (error: Exception) {
                log("userspace tcp relay failed port=$port: ${error.message}")
                if (relay.markClosed()) {
                    relays.remove(id, relay)
                    UserspaceLwip.nativeAbort(id, generation)
                }
                return@Thread
            }
            if (!relay.attachSocket(socket)) {
                runCatching { socket.close() }
                return@Thread
            }
            log(
                "userspace tcp accept id=$id port=$port via=${socket.inetAddress?.hostAddress} " +
                    "rcvBuf=${socket.receiveBufferSize}",
            )
            relay.upstream = Thread({ pumpUpstream(relay) }, "userspace-tcp-up-$port").apply {
                isDaemon = true
                start()
            }
            relay.downstream = Thread({ pumpDownstream(relay) }, "userspace-tcp-down-$port").apply {
                isDaemon = true
                start()
            }
        }, "userspace-tcp-accept-$port").apply {
            isDaemon = true
            start()
        }
    }

    private fun connectLocal(port: Int): Socket {
        var failure: Exception? = null
        val deadline = System.nanoTime() + CONNECT_RETRY_WINDOW_NANOS
        while (true) {
            for (target in arrayOf("127.0.0.1", "::1")) {
                val socket = Socket()
                try {
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.sendBufferSize = 256 * 1024
                    // Receive buffer is the app-to-phone direction. Only the event port is small,
                    // so a swipe cannot fill 256 KB of stale touch reports. Video keeps 256 KB.
                    socket.receiveBufferSize = if (UserspaceLink.wantsSmallReceiveBuffer(port)) {
                        UserspaceLink.SMALL_RECEIVE_BUFFER_BYTES
                    } else {
                        256 * 1024
                    }
                    socket.connect(InetSocketAddress(InetAddress.getByName(target), port), CONNECT_TIMEOUT_MILLIS)
                    return socket
                } catch (error: Exception) {
                    failure = error
                    runCatching { socket.close() }
                }
            }
            if (System.nanoTime() >= deadline) break
            Thread.sleep(CONNECT_RETRY_PAUSE_MILLIS)
        }
        throw failure ?: IllegalStateException("no local listener")
    }

    private fun pumpUpstream(relay: Relay) {
        VideoPipelineTrace.registerThread("relay-up")
        // Phone-to-app video: keep pace with usb-in and lwip-poll, or a busy head unit stalls the
        // splice and the screen read behind it bursts.
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        try {
            val output = relay.socket?.getOutputStream() ?: return
            while (running.get() && !relay.closed) {
                val chunk = relay.inbound.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if (chunk.length <= 0) return
                try {
                    val writeAt = System.nanoTime()
                    output.write(chunk.bytes, 0, chunk.length)
                    output.flush()
                    VideoPipelineTrace.slow("relay-up-write", System.nanoTime() - writeAt)
                } finally {
                    UserspaceLwip.releaseEventBuffer(chunk.bytes)
                }
                // Window credit is returned in on_recv, when lwIP copies the bytes out.
                // Crediting again here would advertise space the stack has not received.
            }
        } catch (_: Exception) {
        } finally {
            finishRelay(relay)
        }
    }

    private fun pumpDownstream(relay: Relay) {
        VideoPipelineTrace.registerThread("relay-down")
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
        val buffer = ByteArray(DOWNSTREAM_CHUNK)
        try {
            val input = relay.socket?.getInputStream() ?: return
            while (running.get() && !relay.closed) {
                val length = input.read(buffer)
                if (length < 0) {
                    relay.cleanShutdown = true
                    UserspaceLwip.nativeShutdown(relay.id, relay.generation)
                    return
                }
                var offset = 0
                while (offset < length && running.get() && !relay.closed) {
                    val written = UserspaceLwip.nativeWrite(relay.id, relay.generation, buffer, offset, length - offset)
                    if (written < 0) return
                    if (written == 0) Thread.sleep(2) else offset += written
                }
            }
        } catch (_: Exception) {
        } finally {
            finishRelay(relay)
        }
    }

    private fun finishRelay(relay: Relay) {
        if (!relay.markClosed()) return
        relays.remove(relay.id, relay)
        relay.closeLocal()
        if (!relay.cleanShutdown) runCatching { UserspaceLwip.nativeAbort(relay.id, relay.generation) }
    }

    private fun deliverUdp(event: UserspaceLwip.Event) {
        val socket = injector ?: return
        if (udpSeen.add(event.localPort)) {
            log("userspace udp port=${event.localPort} first bytes=${event.dataLength}")
        }
        try {
            if (event.dataLength > 0) {
                socket.send(DatagramPacket(
                    event.data, event.dataLength, InetAddress.getByName("::1"), event.localPort,
                ))
            }
        } catch (error: Exception) {
            log("userspace udp inject port=${event.localPort} failed: ${error.message}")
        } finally {
            UserspaceLwip.releaseEventBuffer(event.data)
        }
    }

    private fun sendUdp(
        sourcePort: Int,
        destination: InetAddress,
        destinationPort: Int,
        payload: ByteArray,
    ): Boolean {
        if (!running.get() || destination.address.size != 16) return false
        val startedNs = System.nanoTime()
        val sent = UserspaceLwip.nativeSendUdp(sourcePort, destination.address, destinationPort, payload, 0, payload.size)
        noteUdpOut(sourcePort, startedNs)
        return sent
    }

    /**
     * Uplink cadence evidence: per source port, the wall time between successive native sends and
     * the native send's own duration (lwIP lock wait plus the NCM write). A microphone uplink that
     * leaves here in bursts shows it here, whatever the AudioRecord cadence was.
     */
    private fun noteUdpOut(sourcePort: Int, startedNs: Long) {
        val stats = udpOutStats.computeIfAbsent(sourcePort) { UdpOutProbe() }
        val endedNs = System.nanoTime()
        stats.note(endedNs - startedNs, endedNs)
    }

    private class UdpOutProbe {
        var lastNs = 0L
        var sent = 0
        var maxSendNs = 0L
        val gaps = LongArray(64)
        var gapCount = 0
        var windowStartNs = 0L

        fun note(sendNs: Long, nowNs: Long) {
            sent++
            maxSendNs = maxOf(maxSendNs, sendNs)
            if (lastNs != 0L && gapCount < gaps.size) gaps[gapCount++] = nowNs - lastNs
            lastNs = nowNs
            if (windowStartNs == 0L) windowStartNs = nowNs
        }

        fun summaryIfDue(nowNs: Long, report: (String) -> Unit): Boolean {
            if (windowStartNs == 0L || nowNs - windowStartNs < 2_000_000_000L) return false
            java.util.Arrays.sort(gaps, 0, gapCount)
            val p50 = if (gapCount > 0) gaps[gapCount / 2] / 1000 else -1
            val p95 = if (gapCount > 0) gaps[(gapCount * 95) / 100] / 1000 else -1
            val maxGap = if (gapCount > 0) gaps[gapCount - 1] / 1000 else -1
            report("UdpOutPerf sent=$sent sendMaxUs=${maxSendNs / 1000} gapP50Us=$p50 gapP95Us=$p95 gapMaxUs=$maxGap")
            sent = 0; maxSendNs = 0; gapCount = 0; windowStartNs = nowNs
            return true
        }
    }
    private val udpOutStats = java.util.concurrent.ConcurrentHashMap<Int, UdpOutProbe>()

    private fun sameAddress(packet: ByteArray, offset: Int, expected: ByteArray): Boolean {
        if (packet.size < offset + expected.size) return false
        for (index in expected.indices) {
            if (packet[offset + index] != expected[index]) return false
        }
        return true
    }

    private fun isNeighborDiscovery(packet: ByteArray, offset: Int, length: Int): Boolean {
        if ((packet[offset + 6].toInt() and 0xff) != ICMPV6 || length < 41) return false
        return (packet[offset + 40].toInt() and 0xff) in ICMPV6_ROUTER_SOLICITATION..ICMPV6_NEIGHBOR_ADVERTISEMENT
    }

    private class Relay(
        val id: Int,
        val generation: Int,
    ) {
        val inbound = LinkedBlockingQueue<Chunk>()
        private val finished = AtomicBoolean(false)

        @Volatile var closed = false
        @Volatile var socket: Socket? = null
        var upstream: Thread? = null
        var downstream: Thread? = null

        @Volatile var cleanShutdown = false

        fun attachSocket(value: Socket): Boolean {
            socket = value
            if (closed) {
                runCatching { value.close() }
                return false
            }
            return true
        }

        fun offer(bytes: ByteArray, length: Int) {
            if (closed || length <= 0) {
                UserspaceLwip.releaseEventBuffer(bytes)
                return
            }
            try {
                inbound.put(Chunk(bytes, length))
            } catch (_: InterruptedException) {
                UserspaceLwip.releaseEventBuffer(bytes)
                Thread.currentThread().interrupt()
            }
        }

        fun markClosed(): Boolean {
            if (!finished.compareAndSet(false, true)) return false
            closed = true
            inbound.offer(Chunk(ByteArray(0), 0))
            return true
        }

        fun closeLocal() {
            closed = true
            finished.set(true)
            val leftover = ArrayList<Chunk>()
            inbound.drainTo(leftover)
            for (chunk in leftover) UserspaceLwip.releaseEventBuffer(chunk.bytes)
            runCatching { socket?.close() }
            upstream?.interrupt()
            downstream?.interrupt()
        }
    }

    private class Chunk(val bytes: ByteArray, val length: Int)

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 200
        const val CONNECT_RETRY_WINDOW_NANOS = 500_000_000L
        const val CONNECT_RETRY_PAUSE_MILLIS = 20L
        const val TIMER_WAIT_MILLIS = 25L
        const val DOWNSTREAM_CHUNK = 16 * 1024
        const val ICMPV6 = 58
        const val ICMPV6_ROUTER_SOLICITATION = 133
        const val ICMPV6_NEIGHBOR_ADVERTISEMENT = 136
    }
}
