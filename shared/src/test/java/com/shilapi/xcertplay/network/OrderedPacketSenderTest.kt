package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class OrderedPacketSenderTest {
    @Test fun slowUsbDoesNotBlockTheFirstQueuedEventsAndPreservesOrder() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(3)
        val result = Collections.synchronizedList(mutableListOf<Int>())
        val errors = Collections.synchronizedList(mutableListOf<Exception>())
        val sender = OrderedPacketSender({ bytes ->
            if (bytes[0].toInt() == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            result.add(bytes[0].toInt()); completed.countDown()
        }, errors::add, capacity = 2)
        sender.start()
        try {
            assertTrue(sender.send(byteArrayOf(1)))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(sender.send(byteArrayOf(2)))
            assertTrue(sender.send(byteArrayOf(3)))
            assertTrue(result.isEmpty())
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2, 3), result)
            assertTrue(errors.isEmpty())
        } finally { release.countDown(); sender.close() }
        assertFalse(sender.send(byteArrayOf(4)))
    }

    @Test fun fullQueueAppliesBackpressureWithoutDroppingPackets() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val complete = CountDownLatch(3)
        val sender = OrderedPacketSender({ bytes ->
            if (bytes[0].toInt() == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            complete.countDown()
        }, { throw AssertionError(it) }, capacity = 1)
        val producer = Executors.newSingleThreadExecutor()
        sender.start()
        try {
            sender.send(byteArrayOf(1))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            sender.send(byteArrayOf(2))
            val third = producer.submit<Boolean> { sender.send(byteArrayOf(3)) }
            try { third.get(100, TimeUnit.MILLISECONDS); fail("full queue must wait") }
            catch (_: TimeoutException) { /* bounded queue, no silent discard */ }
            release.countDown()
            assertTrue(third.get(5, TimeUnit.SECONDS))
            assertTrue(complete.await(5, TimeUnit.SECONDS))
        } finally { release.countDown(); sender.close(); producer.shutdownNow() }
    }

    @Test fun writeFailureIsReportedOnceAndStopsAcceptingPackets() {
        val failed = CountDownLatch(1)
        val sender = OrderedPacketSender({ throw IOException("USB unavailable") }, { failed.countDown() })
        sender.start()
        try {
            sender.send(byteArrayOf(1))
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertFalse(sender.send(byteArrayOf(2)))
        } finally { sender.close() }
    }
    @Test fun touchPassesQueuedVideoAcknowledgements() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(4)
        val result = Collections.synchronizedList(mutableListOf<Int>())
        val sender = OrderedPacketSender({ bytes ->
            if (bytes[0].toInt() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            result.add(if (bytes.size == 1) bytes[0].toInt() else bytes[7].toInt())
            completed.countDown()
        }, { throw AssertionError(it) }, capacity = 8)
        sender.start()
        try {
            assertTrue(sender.send(byteArrayOf(1)))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(sender.send(tcpAck(2)))
            assertTrue(sender.send(tcpAck(3)))
            assertTrue(sender.send(tcpSegment(4, payload = byteArrayOf(9))))
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 4, 2, 3), result)
        } finally {
            release.countDown()
            sender.close()
        }
    }

    @Test fun packetsAlreadyQueuedLeaveTogetherWithoutWaitingAndAcksStayBehindData() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val bursts = Collections.synchronizedList(mutableListOf<List<Int>>())
        val sender = OrderedPacketSender(
            write = { error("single writes are not used when a burst writer is set") },
            onError = { throw AssertionError(it) },
            writeBurst = { packets ->
                val markers = packets.map { packet ->
                    if (packet.bytes.size == 1) packet.bytes[0].toInt() else packet.bytes[7].toInt()
                }
                if (markers.first() == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                bursts.add(markers)
                completed.countDown()
            },
            capacity = 8,
        )
        sender.start()
        try {
            assertTrue(sender.send(byteArrayOf(1)))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(sender.send(tcpAck(2)))
            assertTrue(sender.send(tcpAck(3)))
            assertTrue(sender.send(tcpSegment(4, payload = byteArrayOf(9))))
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(listOf(1), listOf(4, 2, 3)), bursts)
        } finally {
            release.countDown()
            sender.close()
        }
    }

    @Test fun shutdownUnblocksAnEnqueuerAndDiscardsOnlyTheRetiredSession() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sender = OrderedPacketSender({
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }, { throw AssertionError(it) }, capacity = 1)
        val producer = Executors.newSingleThreadExecutor()
        sender.start()
        try {
            sender.send(byteArrayOf(1))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            sender.send(byteArrayOf(2))
            val blocked = producer.submit<Boolean> { sender.send(byteArrayOf(3)) }
            sender.close()
            assertFalse(blocked.get(5, TimeUnit.SECONDS))
        } finally { release.countDown(); sender.close(); producer.shutdownNow() }
    }

}

/** IPv6 TCP segment whose first byte is [marker] so a test can see the send order. */
private fun tcpAck(marker: Int): ByteArray = tcpSegment(marker, payload = ByteArray(0))

private fun tcpSegment(marker: Int, payload: ByteArray): ByteArray {
    val packet = ByteArray(60 + payload.size)
    packet[0] = 0x60
    val payloadLength = 20 + payload.size
    packet[4] = (payloadLength ushr 8).toByte()
    packet[5] = payloadLength.toByte()
    packet[6] = 6
    packet[7] = marker.toByte()
    packet[52] = (5 shl 4).toByte()
    payload.copyInto(packet, 60)
    return packet
}
