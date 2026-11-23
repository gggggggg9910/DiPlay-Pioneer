package com.shilapi.xcertplay.transport

/**
 * Untagged Ethernet II framing around IPv6 payloads, the shape NCM datagrams carry.
 *
 * The NCM data path moves raw Ethernet frames. This codec is the seam the later Android VPN layer
 * uses to exchange IPv6 packets with the phone; it does not create or own a network interface.
 */
object EthernetIpv6Codec {
    const val ETHERTYPE_IPV6 = 0x86dd
    const val MAC_BYTES = 6

    private const val HEADER_BYTES = 14
    private const val IPV4_HEADER_BYTES = 20
    private const val IPV4_ICMP_ECHO_BYTES = 8
    private const val IPV4_ECHO_BYTES = IPV4_HEADER_BYTES + IPV4_ICMP_ECHO_BYTES
    private const val IPV4_CHECKSUM_OFFSET = 10
    private const val IPV4_PROTO_ICMP = 1
    private const val IPV4_ICMP_ECHO_REQUEST = 8

    data class Ipv6Frame(
        val sourceMac: ByteArray,
        val destinationMac: ByteArray,
        val ipv6: ByteArray,
    )

    data class Ipv6FrameView(
        val sourceMac: ByteArray,
        val payloadOffset: Int,
        val payloadLength: Int,
    )

    /** Returns null for tagged frames, non-IPv6 frames, or truncated input. */
    fun parseIpv6(frame: ByteArray): Ipv6Frame? {
        if (frame.size < HEADER_BYTES || readU16(frame, 12) != ETHERTYPE_IPV6) return null
        return Ipv6Frame(
            sourceMac = frame.copyOfRange(6, 12),
            destinationMac = frame.copyOfRange(0, 6),
            ipv6 = frame.copyOfRange(HEADER_BYTES, frame.size),
        )
    }

    /** Returns an allocation-light view for callers that can consume the payload in place. */
    fun parseIpv6View(frame: ByteArray): Ipv6FrameView? = parseIpv6View(frame, frame.size)

    /**
     * Length-aware variant for frames that live in a larger (pooled) buffer: [length] names the
     * valid datagram bytes, not `frame.size`.
     */
    fun parseIpv6View(frame: ByteArray, length: Int): Ipv6FrameView? {
        if (length <= HEADER_BYTES || readU16(frame, 12) != ETHERTYPE_IPV6) return null
        return Ipv6FrameView(
            sourceMac = frame.copyOfRange(6, 12),
            payloadOffset = HEADER_BYTES,
            payloadLength = length - HEADER_BYTES,
        )
    }

    fun build(
        sourceMac: ByteArray,
        destinationMac: ByteArray,
        ipv6: ByteArray,
        ipv6Length: Int = ipv6.size,
    ): ByteArray {
        require(sourceMac.size == MAC_BYTES) { "sourceMac must be $MAC_BYTES bytes" }
        require(destinationMac.size == MAC_BYTES) { "destinationMac must be $MAC_BYTES bytes" }
        require(ipv6Length in 1..ipv6.size) { "ipv6 payload must not be empty" }

        val frame = ByteArray(HEADER_BYTES + ipv6Length)
        destinationMac.copyInto(frame, 0)
        sourceMac.copyInto(frame, 6)
        putU16(frame, 12, ETHERTYPE_IPV6)
        ipv6.copyInto(frame, HEADER_BYTES, 0, ipv6Length)
        return frame
    }

    /** Maps an IPv6 multicast destination to its Ethernet 33:33:xx:xx:xx:xx address. */
    fun multicastDestinationMac(ipv6: ByteArray): ByteArray? {
        if (ipv6.size < IPV6_HEADER_BYTES || (ipv6[0].toInt() ushr 4 and 0x0f) != 6) return null
        if ((ipv6[IPV6_DESTINATION_OFFSET].toInt() and 0xff) != 0xff) return null
        return byteArrayOf(
            0x33,
            0x33,
            ipv6[IPV6_DESTINATION_OFFSET + 12],
            ipv6[IPV6_DESTINATION_OFFSET + 13],
            ipv6[IPV6_DESTINATION_OFFSET + 14],
            ipv6[IPV6_DESTINATION_OFFSET + 15],
        )
    }

    /**
     * A TUN interface has no layer-2 address, so Android emits Neighbor Advertisements without a
     * Target Link-Layer Address option. Add the option required by the Ethernet peer and repair
     * the IPv6 payload length and ICMPv6 checksum.
     */
    fun addNeighborAdvertisementTargetMac(ipv6: ByteArray, hostMac: ByteArray): ByteArray {
        require(hostMac.size == MAC_BYTES) { "hostMac must be $MAC_BYTES bytes" }
        if (ipv6.size < ICMPV6_NA_MIN_BYTES || (ipv6[0].toInt() ushr 4 and 0x0f) != 6) return ipv6
        if ((ipv6[IPV6_NEXT_HEADER_OFFSET].toInt() and 0xff) != ICMPV6_NEXT_HEADER ||
            (ipv6[IPV6_HEADER_BYTES].toInt() and 0xff) != ICMPV6_NEIGHBOR_ADVERTISEMENT
        ) return ipv6

        val payloadBytes = readU16(ipv6, IPV6_PAYLOAD_LENGTH_OFFSET)
        if (payloadBytes < ICMPV6_NA_BYTES || IPV6_HEADER_BYTES + payloadBytes > ipv6.size) return ipv6
        var optionOffset = IPV6_HEADER_BYTES + ICMPV6_NA_BYTES
        val payloadEnd = IPV6_HEADER_BYTES + payloadBytes
        while (optionOffset + 2 <= payloadEnd) {
            val optionBytes = (ipv6[optionOffset + 1].toInt() and 0xff) * 8
            if (optionBytes == 0 || optionOffset + optionBytes > payloadEnd) break
            if ((ipv6[optionOffset].toInt() and 0xff) == ICMPV6_TARGET_LINK_LAYER_OPTION &&
                optionBytes >= 8
            ) {
                return ipv6.copyOf().also { result ->
                    hostMac.copyInto(result, optionOffset + 2)
                    writeIcmpv6Checksum(result)
                }
            }
            optionOffset += optionBytes
        }

        val result = ipv6.copyOf(payloadEnd + 8)
        result[IPV6_PAYLOAD_LENGTH_OFFSET] = ((payloadBytes + 8) ushr 8).toByte()
        result[IPV6_PAYLOAD_LENGTH_OFFSET + 1] = (payloadBytes + 8).toByte()
        result[payloadEnd] = ICMPV6_TARGET_LINK_LAYER_OPTION.toByte()
        result[payloadEnd + 1] = 1
        hostMac.copyInto(result, payloadEnd + 2)
        writeIcmpv6Checksum(result)
        return result
    }

    /** Details of a Neighbor Solicitation that targets this host's address. */
    data class NeighborSolicitation(
        val requesterMac: ByteArray,
        val requesterAddress: ByteArray,
        val targetAddress: ByteArray,
    )

    /**
     * Parses an ICMPv6 Neighbor Solicitation aimed at [hostAddress]. Returns null for other
     * traffic, truncated input, or a solicitation for a different address. The Android kernel
     * answers these itself on most VPN tunnels; Android 7.x never does (#557 family), so the
     * bridge needs the raw request to answer in userspace.
     */
    fun parseNeighborSolicitation(frame: ByteArray, hostAddress: ByteArray): NeighborSolicitation? =
        parseNeighborSolicitation(frame, frame.size, hostAddress)

    /** Length-aware variant for frames held in a larger (pooled) buffer. */
    fun parseNeighborSolicitation(frame: ByteArray, length: Int, hostAddress: ByteArray): NeighborSolicitation? {
        if (hostAddress.size != IPV6_ADDRESS_BYTES) return null
        val parsed = parseIpv6View(frame, length) ?: return null
        if (parsed.payloadLength < IPV6_HEADER_BYTES + NS_ICMPV6_BYTES) return null
        val icmpOffset = parsed.payloadOffset + IPV6_HEADER_BYTES
        if ((frame[icmpOffset].toInt() and 0xff) != ICMPV6_NEIGHBOR_SOLICITATION) return null
        val targetOffset = icmpOffset + ICMPV6_TARGET_OFFSET
        if (!frame.copyOfRange(targetOffset, targetOffset + IPV6_ADDRESS_BYTES).contentEquals(hostAddress)) return null
        val sourceOffset = parsed.payloadOffset + IPV6_SOURCE_OFFSET
        return NeighborSolicitation(
            requesterMac = frame.copyOfRange(6, 12),
            requesterAddress = frame.copyOfRange(sourceOffset, sourceOffset + IPV6_ADDRESS_BYTES),
            targetAddress = hostAddress.copyOf(),
        )
    }

    /**
     * Builds a solicited Neighbor Advertisement answering [solicitation] from [hostMac] at
     * [hostAddress]. Carries the Target Link-Layer Address option and a full ICMPv6 checksum.
     */
    fun buildNeighborAdvertisement(
        hostMac: ByteArray,
        hostAddress: ByteArray,
        solicitation: NeighborSolicitation,
    ): ByteArray {
        require(hostMac.size == MAC_BYTES) { "hostMac must be $MAC_BYTES bytes" }
        require(hostAddress.size == IPV6_ADDRESS_BYTES) { "hostAddress must be $IPV6_ADDRESS_BYTES bytes" }
        val frame = ByteArray(HEADER_BYTES + IPV6_HEADER_BYTES + NA_ICMPV6_BYTES)
        solicitation.requesterMac.copyInto(frame, 0)
        hostMac.copyInto(frame, 6)
        putU16(frame, 12, ETHERTYPE_IPV6)
        val ipv6Offset = HEADER_BYTES
        frame[ipv6Offset] = 0x60
        putU16(frame, ipv6Offset + IPV6_PAYLOAD_LENGTH_OFFSET, NA_ICMPV6_BYTES)
        frame[ipv6Offset + IPV6_NEXT_HEADER_OFFSET] = ICMPV6_NEXT_HEADER.toByte()
        frame[ipv6Offset + IPV6_HOP_LIMIT_OFFSET] = 255.toByte()
        hostAddress.copyInto(frame, ipv6Offset + IPV6_SOURCE_OFFSET)
        solicitation.requesterAddress.copyInto(frame, ipv6Offset + IPV6_DESTINATION_OFFSET)
        val icmpOffset = ipv6Offset + IPV6_HEADER_BYTES
        frame[icmpOffset] = ICMPV6_NEIGHBOR_ADVERTISEMENT.toByte()
        frame[icmpOffset + 4] = NEIGHBOR_ADVERTISEMENT_SOLICITED_OVERRIDE
        solicitation.targetAddress.copyInto(frame, icmpOffset + ICMPV6_TARGET_OFFSET)
        val optionOffset = icmpOffset + ICMPV6_TARGET_OFFSET + IPV6_ADDRESS_BYTES
        frame[optionOffset] = ICMPV6_TARGET_LINK_LAYER_OPTION.toByte()
        frame[optionOffset + 1] = 1
        hostMac.copyInto(frame, optionOffset + 2)
        writeIcmpv6Checksum(frame, ipv6Offset)
        return frame
    }

    /**
     * Builds a bare IPv6 ICMPv6 echo request packet (no Ethernet header) from [source] to
     * [destination], used to probe whether the kernel processes packets written into the VPN tun.
     */
    fun buildEchoRequestIpv6(source: ByteArray, destination: ByteArray): ByteArray {
        require(source.size == IPV6_ADDRESS_BYTES && destination.size == IPV6_ADDRESS_BYTES)
        val message = ByteArray(IPV6_HEADER_BYTES + ECHO_ICMPV6_BYTES)
        message[0] = 0x60
        putU16(message, IPV6_PAYLOAD_LENGTH_OFFSET, ECHO_ICMPV6_BYTES)
        message[IPV6_NEXT_HEADER_OFFSET] = ICMPV6_NEXT_HEADER.toByte()
        message[IPV6_HOP_LIMIT_OFFSET] = 64.toByte()
        source.copyInto(message, IPV6_SOURCE_OFFSET)
        destination.copyInto(message, IPV6_DESTINATION_OFFSET)
        message[IPV6_HEADER_BYTES] = ICMPV6_ECHO_REQUEST.toByte()
        message[IPV6_HEADER_BYTES + 4] = 0x12
        message[IPV6_HEADER_BYTES + 5] = 0x34
        message[IPV6_HEADER_BYTES + 6] = 0x56
        message[IPV6_HEADER_BYTES + 7] = 0x78
        writeIcmpv6Checksum(message, 0)
        return message
    }

    /**
     * IPv4 ICMP echo request used to test whether this firmware's VpnService tun delivers IPv4
     * after it has been shown to accept IPv6 writes without ever delivering them.
     * [source] and [destination] are 4-byte addresses. The checksum fields cover the IPv4 header
     * and the ICMP message separately.
     */
    fun buildEchoRequestIpv4(source: ByteArray, destination: ByteArray): ByteArray {
        require(source.size == 4 && destination.size == 4) {
            "IPv4 echo addresses must be 4 bytes"
        }
        val packet = ByteArray(IPV4_ECHO_BYTES)
        packet[0] = 0x45
        packet[3] = IPV4_ECHO_BYTES.toByte()
        packet[8] = 64
        packet[9] = IPV4_PROTO_ICMP.toByte()
        source.copyInto(packet, 12)
        destination.copyInto(packet, 16)
        writeInternetChecksum(packet, 0, IPV4_HEADER_BYTES, IPV4_CHECKSUM_OFFSET)
        packet[IPV4_HEADER_BYTES] = IPV4_ICMP_ECHO_REQUEST.toByte()
        packet[IPV4_HEADER_BYTES + 4] = 0x12
        packet[IPV4_HEADER_BYTES + 5] = 0x34
        packet[IPV4_HEADER_BYTES + 7] = 1
        writeInternetChecksum(packet, IPV4_HEADER_BYTES, IPV4_ICMP_ECHO_BYTES, IPV4_HEADER_BYTES + 2)
        return packet
    }

    /**
     * True for IPv6 traffic that some Android 7.x kernels choke on when written into a
     * VpnService tun: multicast destinations (ff00::/8, including MLD and mDNS) and
     * hop-by-hop (next header 0) carriers.
     */
    fun isMulticastOrHopByHop(frame: ByteArray, payloadOffset: Int): Boolean =
        isMulticastOrHopByHop(frame, frame.size, payloadOffset)

    /** Length-aware variant for frames held in a larger (pooled) buffer. */
    fun isMulticastOrHopByHop(frame: ByteArray, frameLength: Int, payloadOffset: Int): Boolean {
        if (frameLength <= payloadOffset + IPV6_DESTINATION_OFFSET) return true
        val destinationFirst = (frame[payloadOffset + IPV6_DESTINATION_OFFSET].toInt() and 0xff)
        if (destinationFirst == 0xff) return true
        if (frameLength <= payloadOffset + IPV6_NEXT_HEADER_OFFSET) return true
        return (frame[payloadOffset + IPV6_NEXT_HEADER_OFFSET].toInt() and 0xff) == 0
    }

    private fun writeInternetChecksum(packet: ByteArray, start: Int, length: Int, checksumOffset: Int) {
        packet[checksumOffset] = 0
        packet[checksumOffset + 1] = 0
        var sum = 0
        var offset = start
        val end = start + length
        while (offset + 1 < end) {
            sum += ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)
            offset += 2
        }
        if (offset < end) sum += (packet[offset].toInt() and 0xff) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        val checksum = sum.inv() and 0xffff
        packet[checksumOffset] = (checksum ushr 8).toByte()
        packet[checksumOffset + 1] = checksum.toByte()
    }

    private fun writeIcmpv6Checksum(buffer: ByteArray, offset: Int = 0) {
        val payloadBytes = readU16(buffer, offset + IPV6_PAYLOAD_LENGTH_OFFSET)
        buffer[offset + IPV6_HEADER_BYTES + 2] = 0
        buffer[offset + IPV6_HEADER_BYTES + 3] = 0
        var sum = 0L
        fun addWords(start: Int, bytes: Int) {
            var wordOffset = start
            val end = start + bytes
            while (wordOffset + 1 < end) {
                sum += readU16(buffer, wordOffset).toLong()
                wordOffset += 2
            }
            if (wordOffset < end) sum += (buffer[wordOffset].toInt() and 0xff).toLong() shl 8
        }
        addWords(offset + IPV6_SOURCE_OFFSET, 32)
        sum += (payloadBytes ushr 16).toLong()
        sum += (payloadBytes and 0xffff).toLong()
        sum += ICMPV6_NEXT_HEADER.toLong()
        addWords(offset + IPV6_HEADER_BYTES, payloadBytes)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        val checksum = sum.inv().toInt() and 0xffff
        buffer[offset + IPV6_HEADER_BYTES + 2] = (checksum ushr 8).toByte()
        buffer[offset + IPV6_HEADER_BYTES + 3] = checksum.toByte()
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private const val IPV6_HEADER_BYTES = 40
    private const val IPV6_PAYLOAD_LENGTH_OFFSET = 4
    private const val IPV6_NEXT_HEADER_OFFSET = 6
    private const val IPV6_HOP_LIMIT_OFFSET = 7
    private const val IPV6_SOURCE_OFFSET = 8
    private const val IPV6_DESTINATION_OFFSET = 24
    private const val IPV6_ADDRESS_BYTES = 16
    private const val ICMPV6_NEXT_HEADER = 58
    private const val ICMPV6_NEIGHBOR_SOLICITATION = 135
    private const val ICMPV6_NEIGHBOR_ADVERTISEMENT = 136
    private const val ICMPV6_TARGET_OFFSET = 8
    private const val ICMPV6_NA_BYTES = 24
    private const val ICMPV6_NA_MIN_BYTES = IPV6_HEADER_BYTES + ICMPV6_NA_BYTES
    private const val ICMPV6_TARGET_LINK_LAYER_OPTION = 2
    private const val NS_ICMPV6_BYTES = 24
    private const val NA_ICMPV6_BYTES = 32
    private const val ECHO_ICMPV6_BYTES = 16
    private const val ICMPV6_ECHO_REQUEST = 128
    private const val NEIGHBOR_ADVERTISEMENT_SOLICITED_OVERRIDE = 0x60.toByte()
}
