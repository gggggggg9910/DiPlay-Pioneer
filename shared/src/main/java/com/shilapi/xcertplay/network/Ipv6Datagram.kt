package com.shilapi.xcertplay.network

/**
 * Length of one IPv6 datagram inside a buffer that may be padded by NCM.
 * The IPv6 payload-length field is authoritative; bytes after it are not part of the packet.
 */
internal object Ipv6Datagram {
    fun length(packet: ByteArray, offset: Int = 0, available: Int = packet.size - offset): Int? {
        if (offset < 0 || available < IPV6_HEADER_BYTES || offset > packet.size - available) return null
        if ((packet[offset].toInt() ushr 4 and 0x0f) != 6) return null
        val payload = ((packet[offset + PAYLOAD_LENGTH_OFFSET].toInt() and 0xff) shl 8) or
            (packet[offset + PAYLOAD_LENGTH_OFFSET + 1].toInt() and 0xff)
        val total = IPV6_HEADER_BYTES + payload
        if (total > available) return null
        return total
    }

    private const val IPV6_HEADER_BYTES = 40
    private const val PAYLOAD_LENGTH_OFFSET = 4
}
