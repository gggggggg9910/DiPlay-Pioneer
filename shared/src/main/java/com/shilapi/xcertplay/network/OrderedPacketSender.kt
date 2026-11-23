package com.shilapi.xcertplay.network

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB output with two lanes. Packets that carry data stay in order with each other, and a
 * pure TCP ACK stays in order with other ACKs. A data packet already waiting may leave before
 * ACKs that were queued ahead of it, so a touch report is not stuck behind a burst of video
 * acknowledgements. Nothing is dropped to make room.
 */
internal class OutPacket(val bytes: ByteArray, val length: Int)

internal class OrderedPacketSender(
    private val write: (ByteArray) -> Unit,
    private val onError: (Exception) -> Unit,
    private val onStart: () -> Unit = {},
    capacity: Int = 256,
    /** When set, packets already queued leave together. [write] is not called. */
    private val writeBurst: ((List<OutPacket>) -> Unit)? = null,
) : Closeable {
    private class Packet(
        val bytes: ByteArray,
        val length: Int,
        val offeredNs: Long = System.nanoTime(),
    )
    private val interactive = ArrayBlockingQueue<Packet>(capacity)
    private val acknowledgements = ArrayBlockingQueue<Packet>(capacity)
    private val running = AtomicBoolean(false)
    private val metricsLock = Any()
    private var count = 0
    private var maxDepth = 0
    private var maxQueueNs = 0L
    private var maxWriteNs = 0L
    private var backpressure = 0
    private var interactiveRun = 0
    private var batchMax = 0
    private val thread = Thread(::run, "userspace-usb-out").apply { isDaemon = true }

    fun start() {
        check(running.compareAndSet(false, true))
        thread.start()
    }

    /** Ownership of [bytes] transfers to this sender; callers must not modify it. */
    fun send(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val packet = Packet(bytes, length)
        val lane = if (isIpv6TcpPureAck(bytes, length)) acknowledgements else interactive
        while (running.get()) {
            if (lane.offer(packet, 25, TimeUnit.MILLISECONDS)) {
                if (!running.get()) return if (lane.remove(packet)) false else true
                synchronized(metricsLock) {
                    maxDepth = maxOf(maxDepth, interactive.size + acknowledgements.size)
                }
                return true
            }
            synchronized(metricsLock) { backpressure++ }
        }
        return false
    }

    private fun run() {
        try {
            onStart()
            while (running.get()) {
                val burst = awaitBurst() ?: continue
                if (!running.get()) break
                val start = System.nanoTime()
                val burstWrite = writeBurst
                if (burstWrite != null) {
                    burstWrite(burst.map { OutPacket(it.bytes, it.length) })
                } else {
                    burst.forEach { write(it.bytes) }
                }
                val end = System.nanoTime()
                synchronized(metricsLock) {
                    count += burst.size
                    batchMax = maxOf(batchMax, burst.size)
                    for (packet in burst) maxQueueNs = maxOf(maxQueueNs, start - packet.offeredNs)
                    maxWriteNs = maxOf(maxWriteNs, end - start)
                }
            }
        } catch (error: Exception) {
            if (running.getAndSet(false)) onError(error)
        } finally {
            running.set(false)
            interactive.clear()
            acknowledgements.clear()
        }
    }

    /** One packet, or every packet already queued when [writeBurst] is set. Does not wait to fill. */
    private fun awaitBurst(): List<Packet>? {
        val first = awaitPacket() ?: return null
        if (writeBurst == null) return listOf(first)
        val burst = ArrayList<Packet>(MAX_BURST)
        burst.add(first)
        while (burst.size < MAX_BURST) {
            val next = pollReady() ?: break
            burst.add(next)
        }
        return burst
    }

    /**
     * Takes one packet, waiting briefly when both lanes are empty. A touch offered during that
     * wait is taken on the next pass. Every few data packets, a waiting ACK still goes out so
     * the phone's send window moves.
     */
    private fun awaitPacket(): Packet? {
        pollReady()?.let { return it }
        val waited = acknowledgements.poll(1, TimeUnit.MILLISECONDS) ?: return null
        interactiveRun = if (isIpv6TcpPureAck(waited.bytes, waited.length)) 0 else interactiveRun + 1
        return waited
    }

    private fun pollReady(): Packet? {
        val packet = if (interactiveRun >= 2 && acknowledgements.isNotEmpty()) {
            acknowledgements.poll()
        } else {
            interactive.poll() ?: acknowledgements.poll()
        } ?: return null
        interactiveRun = if (isIpv6TcpPureAck(packet.bytes, packet.length)) 0 else interactiveRun + 1
        return packet
    }

    fun summary(): String = synchronized(metricsLock) {
        val line = "UsbOutPerf n=$count depthMax=$maxDepth queueMaxUs=${maxQueueNs / 1000} " +
            "writeMaxUs=${maxWriteNs / 1000} backpressure=$backpressure batchMax=$batchMax"
        count = 0; maxDepth = 0; maxQueueNs = 0; maxWriteNs = 0; backpressure = 0; batchMax = 0
        line
    }

    override fun close() {
        running.set(false)
        thread.interrupt()
        // A synchronous USB write may take its timeout to return; bound teardown before NCM is closed.
        if (Thread.currentThread() !== thread) thread.join(2_500)
        interactive.clear()
        acknowledgements.clear()
    }

    private companion object {
        const val MAX_BURST = 16
    }
}

/** True when [packet] is an IPv6 TCP segment with no payload, such as a video acknowledgement. */
internal fun isIpv6TcpPureAck(packet: ByteArray, length: Int = packet.size): Boolean {
    if (length < 60 || packet.size < length) return false
    if ((packet[0].toInt() ushr 4) != 6) return false
    if ((packet[6].toInt() and 0xff) != 6) return false
    val payloadLength = ((packet[4].toInt() and 0xff) shl 8) or (packet[5].toInt() and 0xff)
    if (payloadLength > length - 40) return false
    val headerWords = (packet[52].toInt() and 0xff) ushr 4
    val headerLength = headerWords * 4
    if (headerLength < 20 || 40 + headerLength > length) return false
    return payloadLength == headerLength
}
