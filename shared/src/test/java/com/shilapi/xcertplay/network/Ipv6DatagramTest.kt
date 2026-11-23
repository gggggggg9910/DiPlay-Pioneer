package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ipv6DatagramTest {
    @Test fun readsAnIpv6SliceWithoutIncludingEthernetHeaderOrPadding() {
        val frame = ByteArray(120)
        frame[14] = 0x60
        frame[19] = 8
        assertEquals(48, Ipv6Datagram.length(frame, 14, 60))
        assertNull(Ipv6Datagram.length(frame, 14, 47))
        assertNull(Ipv6Datagram.length(frame, -1, 60))
        assertNull(Ipv6Datagram.length(frame, 90, 60))
        assertNull(Ipv6Datagram.length(frame, Int.MAX_VALUE, 60))
    }

    @Test
    fun usesThePayloadLengthInsteadOfNcmPadding() {
        val packet = ByteArray(80)
        packet[0] = 0x60
        packet[5] = 8
        assertEquals(48, Ipv6Datagram.length(packet))
    }

    @Test
    fun rejectsATruncatedPayloadLength() {
        val packet = ByteArray(40)
        packet[0] = 0x60
        packet[5] = 8
        assertNull(Ipv6Datagram.length(packet))
    }

    @Test
    fun rejectsOtherVersions() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        assertNull(Ipv6Datagram.length(packet))
    }
}
