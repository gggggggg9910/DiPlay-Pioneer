package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.diagnostics.FrameTimeline
import com.shilapi.xcertplay.diagnostics.VideoPipelineTrace
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(
    private val key: ByteArray,
    private val expectedPeer: InetAddress,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onFrame(naluBytes: ByteArray) {}
        /**
         * A frame with the iPhone's frame time ([senderNanos], from the header) and when its body finished
         * arriving ([arrivalNanos], System.nanoTime). Listeners that do not pace frames ignore both.
         */
        fun onFrame(naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) = onFrame(naluBytes)
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val firstFrameLogged = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = ServerSocket()
        bound.reuseAddress = true
        bound.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        server = bound
        thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        safeClose(server)
        thread?.interrupt()
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.acceptFrom(expectedPeer) { onDiagnostic("Video: rejected sender outside the control session") }
            accepted.tcpNoDelay = true
            accepted.receiveBufferSize = 256 * 1024
            accepted.sendBufferSize = 256 * 1024
            socket = accepted
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) listener.onClosed(error)
        }
    }

    private fun run(sock: Socket) {
        val failure = AtomicReference<Throwable?>()
        val stats = StreamReceiveStats("video", onDiagnostic)
        val probe = VideoPipelineTrace.open(onDiagnostic)
        VideoPipelineTrace.registerThread("screen-read")
        // The tail of the relay chain (usb-in and lwip-poll already run at this level): at default
        // priority a busy head unit starved this read for up to a second while the socket held data.
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        val timeline = FrameTimeline(onDiagnostic)
        onDiagnostic(NativeChacha.comparisonReport())
        // Decrypt stays off this thread. A 30–60 ms open used to stop the socket read, the 64 KB
        // window filled, and the phone paused for a few hundred milliseconds.
        val incoming = ArrayBlockingQueue<Incoming?>(16)
        val worker = Thread({
            VideoPipelineTrace.registerThread("screen-decrypt")
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) }
            try {
                while (true) {
                    val item = incoming.poll(100, TimeUnit.MILLISECONDS)
                    if (item != null) {
                        val waitNs = System.nanoTime() - item.arrivalNanos
                        stats.queued(waitNs, incoming.size)
                        VideoPipelineTrace.slow("decrypt-queue", waitNs)
                        onMessage(item.header, item.body, stats, item.arrivalNanos)
                    }
                    else if (closed.get() && incoming.isEmpty()) break
                }
            } catch (error: Exception) {
                failure.compareAndSet(null, error)
                safeClose(sock)
            }
        }, "airplay-screen-decrypt").apply { isDaemon = true; start() }
        try {
            val input = sock.getInputStream()
            while (!closed.get()) {
                stats.reading()
                val header = readFully(input, HEADER_LEN) ?: break
                val headerDone = System.nanoTime()
                val bodySize = readU32Le(header, 0)
                if (bodySize > MAX_BODY) break
                val body = readFully(input, bodySize) ?: break
                val arrivalNanos = System.nanoTime()
                VideoPipelineTrace.slow("body-read", arrivalNanos - headerDone)
                if ((header[OPCODE_OFFSET].toInt() and 0xff) == OP_VIDEO_FRAME && bodySize > 0) {
                    timeline.frame(ScreenCodec.senderNanos(header), arrivalNanos)
                }
                stats.received(HEADER_LEN + bodySize)
                incoming.put(Incoming(header, body, arrivalNanos))
                VideoPipelineTrace.slow("receive-backpressure", System.nanoTime() - arrivalNanos)
                stats.processed()
            }
        } catch (error: Exception) {
            failure.compareAndSet(null, error)
        } finally {
            val stoppedByUser = closed.get()
            closed.set(true)
            safeClose(sock)
            worker.join(2_000)
            timeline.flush(System.nanoTime())
            stats.flush(ended = true)
            probe.close()
            if (socket === sock) socket = null
            if (!stoppedByUser) listener.onClosed(failure.get())
        }
    }

    private class Incoming(val header: ByteArray, val body: ByteArray, val arrivalNanos: Long)

    private fun onMessage(header: ByteArray, body: ByteArray, stats: StreamReceiveStats, arrivalNanos: Long) {
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                if (body.size < ScreenCodec.TAG_SIZE) return
                val start = System.nanoTime()
                val payload = ScreenCodec.decryptFrame(key, frameCounter.get(), header, body)
                    .also {
                        val duration = System.nanoTime() - start
                        stats.decrypted(duration, body.size)
                        VideoPipelineTrace.slow("decrypt", duration)
                        frameCounter.incrementAndGet()
                    }
                if (firstFrameLogged.compareAndSet(false, true)) {
                    onDiagnostic(
                        "video first decrypted frame sealed=${body.size} plain=${payload.size} " +
                            "chacha=${AirPlayCrypto.chachaImplementation}",
                    )
                    Log.i(
                        TAG,
                        "video first decrypted frame sealed=${body.size} plain=${payload.size} " +
                        "head=${payload.hexPrefix(16)} chacha=${AirPlayCrypto.chachaImplementation}",
                    )
                }
                listener.onFrame(ScreenCodec.lengthPrefixedToAnnexB(payload), ScreenCodec.senderNanos(header), arrivalNanos)
            }
            OP_VIDEO_CONFIG -> {
                val (codec, codecData) = ScreenCodec.detectConfig(body)
                Log.i(TAG, "video codec config codec=$codec body=${body.size} data=${codecData.size}")
                listener.onCodec(codec)
                listener.onConfig(codecData)
            }
        }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray? {
        if (length < 0) return null
        val output = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(output, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return output
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
    }
}

private fun ByteArray.hexPrefix(length: Int): String =
    take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    /**
     * The frame time in a screen header: bytes 8..15, a little-endian NTP 32.32 value, in nanoseconds.
     * On a Tang with iOS 27 consecutive main-screen frames are exactly 1/60 s apart (1/30 s on the
     * cluster stream), and skipped frames leave whole multiples.
     */
    fun senderNanos(header: ByteArray): Long {
        if (header.size < SENDER_TIME_OFFSET + 8) return 0L
        var raw = 0L
        for (index in 7 downTo 0) raw = (raw shl 8) or (header[SENDER_TIME_OFFSET + index].toLong() and 0xff)
        return (raw ushr 32) * 1_000_000_000L + ((raw and 0xffff_ffffL) * 1_000_000_000L ushr 32)
    }

    private const val SENDER_TIME_OFFSET = 8

    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray =
        AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)

    /**
     * Replaces each four-byte NAL length with an Annex B start code in place.
     *
     * The payload is left untouched unless every length-prefixed NAL is valid, so malformed
     * input keeps its original bytes for the normal decoder error path.
     */
    fun lengthPrefixedToAnnexB(payload: ByteArray): ByteArray {
        if (payload.size < 4 || payload.startsWithStartCode()) return payload

        var offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            offset += 4
            if (length <= 0 || offset + length > payload.size) return payload
            offset += length
        }
        if (offset != payload.size) return payload

        offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            payload[offset] = 0
            payload[offset + 1] = 0
            payload[offset + 2] = 0
            payload[offset + 3] = 1
            offset += 4 + length
        }
        return payload
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        for (index in 4..payload.size - 4) {
            val fourcc = String(payload, index, 4, Charsets.US_ASCII)
            when (fourcc) {
                "hvcC" -> return VideoCodec.H265 to payload.copyOfRange(index + 4, payload.size)
                "avcC" -> return VideoCodec.H264 to payload.copyOfRange(index + 4, payload.size)
            }
        }
        return if (looksLikeAvcC(payload)) VideoCodec.H264 to payload else VideoCodec.H265 to payload
    }

    private fun looksLikeAvcC(payload: ByteArray): Boolean {
        if (payload.size < 9) return false
        if ((payload[5].toInt() and 0x1f) < 1) return false
        val spsLength = readU16Be(payload, 6)
        if (8 + spsLength > payload.size) return false
        return (payload[8].toInt() and 0x1f) == 7
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
}

private fun ByteArray.startsWithStartCode(): Boolean =
    size >= 4 &&
        this[0] == 0.toByte() &&
        this[1] == 0.toByte() &&
        this[2] == 0.toByte() &&
        this[3] == 1.toByte()

private fun readU32Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
