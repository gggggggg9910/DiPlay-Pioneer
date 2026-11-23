package com.shilapi.xcertplay.network

import java.net.InetAddress

/**
 * The phone address and UDP send path for the userspace stack.
 *
 * TCP relay sockets are accepted from loopback, so NTP and the microphone must not trust
 * `socket.inetAddress` when this link is active. They send through [sendUdp] instead, and lwIP
 * writes the datagram back out the USB NCM interface.
 */
internal object UserspaceLink {
    /**
     * Touch writes share this TCP connection with every other event. A 256 KB receive buffer let a
     * swipe queue seconds of stale points before [java.net.Socket.getOutputStream] blocked. 8 KB is
     * about one send window, so the touch queue keeps the newest point instead.
     */
    const val SMALL_RECEIVE_BUFFER_BYTES = 8 * 1024

    private val smallReceivePorts = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    @Volatile var phone: InetAddress? = null
        private set

    @Volatile private var sender: ((Int, InetAddress, Int, ByteArray) -> Boolean)? = null

    fun useSmallReceiveBuffer(port: Int) {
        smallReceivePorts.add(port)
    }

    fun clearSmallReceiveBuffer(port: Int) {
        smallReceivePorts.remove(port)
    }

    fun wantsSmallReceiveBuffer(port: Int): Boolean = port in smallReceivePorts

    fun isActive(): Boolean = sender != null

    fun notePhone(address: InetAddress) {
        if (address.isMulticastAddress || address.isAnyLocalAddress || address.isLoopbackAddress) return
        phone = address
    }

    fun activate(send: (Int, InetAddress, Int, ByteArray) -> Boolean) {
        sender = send
    }

    fun deactivate() {
        sender = null
        phone = null
    }

    /** @return true when the datagram was handed to the userspace stack. */
    fun sendUdp(sourcePort: Int, destination: InetAddress, destinationPort: Int, payload: ByteArray): Boolean {
        val send = sender ?: return false
        val phoneAddress = if (destination.isLoopbackAddress) phone ?: return false else destination
        return send(sourcePort, phoneAddress, destinationPort, payload)
    }
}
