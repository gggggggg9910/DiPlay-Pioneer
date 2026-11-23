package com.shilapi.xcertplay.transport

/**
 * Minimal NTB16 codec for the iPhone NCM data link.
 *
 * Only the 16-bit transfer-block layout used by LIVI's `iap2-usbmux/src/ntb.rs` is implemented:
 * one NTH16 header, one NDP16 table with one datagram, and the USB short-packet pad byte. There
 * is no NTB32, alignment, CRC, or NCM control-plane handling here.
 */
object Ntb16Codec {
    const val NTH16_SIG = 0x484d434e
    const val NDP16_SIG = 0x304d434e

    private const val NTH_LENGTH = 12
    private const val NDP_LENGTH = 16
    private const val DATAGRAM_INDEX = 28
    private const val USB_PACKET_SIZE = 512

    /**
     * Largest outbound NTB handed to one USB transfer. A single CarPlay packet already fits
     * (Ethernet MTU 1500 plus the 28-byte header). Several small packets that are already queued
     * share one transfer up to this cap; nothing waits to fill it. Larger than a known-good
     * single frame, small enough to stay near the sizes the iPhone already accepts.
     */
    internal const val MAX_OUT_BLOCK = 2048

    // Same bound as the bridge's frame queue, which fails the session past it.
    private const val MAX_ENTRIES_PER_BLOCK = 256

    /** Wraps one Ethernet frame in one NTB16 block, padding an exact 512-byte boundary. */
    fun build(frame: ByteArray, sequence: Int): ByteArray {
        require(frame.isNotEmpty()) { "frame must not be empty" }
        require(frame.size <= MAX_DATAGRAM_BYTES) { "frame exceeds the NTB16 length field" }
        require(sequence in 0..0xffff) { "sequence must fit in u16" }

        val blockLength = DATAGRAM_INDEX + frame.size
        val block = ByteArray(blockLength)
        putU32(block, 0, NTH16_SIG)
        putU16(block, 4, NTH_LENGTH)
        putU16(block, 6, sequence)
        putU16(block, 8, blockLength)
        putU16(block, 10, NTH_LENGTH)

        putU32(block, 12, NDP16_SIG)
        putU16(block, 16, NDP_LENGTH)
        putU16(block, 18, 0)
        putU16(block, 20, DATAGRAM_INDEX)
        putU16(block, 22, frame.size)
        putU16(block, 24, 0)
        putU16(block, 26, 0)

        frame.copyInto(block, DATAGRAM_INDEX)
        // A transfer that ends exactly on a USB packet boundary is read as a short packet.
        return if (block.size % USB_PACKET_SIZE == 0) block + byteArrayOf(0) else block
    }

    /** One NTB16 for every datagram in [frames]. One frame matches [build]. */
    fun build(frames: List<ByteArray>, sequence: Int): ByteArray {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        if (frames.size == 1) return build(frames[0], sequence)
        val length = encodedLength(frames.map { it.size })
        val block = ByteArray(length)
        val written = write(frames, sequence, block)
        check(written == length)
        return block
    }

    /** USB length of an NTB carrying datagrams of [datagramLengths], including a short-packet pad. */
    internal fun encodedLength(datagramLengths: List<Int>): Int = plan(datagramLengths).usbLength

    /**
     * Splits [frames] into the NTB groups [send] will write. Each group is at most
     * [maxBlock] bytes except a single datagram that is already larger.
     */
    internal fun chunks(frames: List<ByteArray>, maxBlock: Int = MAX_OUT_BLOCK): List<List<ByteArray>> {
        val groups = ArrayList<List<ByteArray>>()
        var start = 0
        while (start < frames.size) {
            var end = start + 1
            while (end < frames.size && encodedLength((start..end).map { frames[it].size }) <= maxBlock) end++
            groups.add(frames.subList(start, end))
            start = end
        }
        return groups
    }

    /**
     * Writes one NTB16 into [dest] and returns the USB length. Does not allocate.
     * One datagram matches [build], including the short-packet pad.
     */
    internal fun write(frames: List<ByteArray>, sequence: Int, dest: ByteArray): Int {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        require(sequence in 0..0xffff) { "sequence must fit in u16" }
        frames.forEach { require(it.isNotEmpty()) { "frame must not be empty" } }
        val layout = plan(frames.map { it.size })
        require(dest.size >= layout.usbLength) { "destination is shorter than the NTB" }
        putU32(dest, 0, NTH16_SIG)
        putU16(dest, 4, NTH_LENGTH)
        putU16(dest, 6, sequence)
        putU16(dest, 8, layout.blockLength)
        putU16(dest, 10, NTH_LENGTH)
        putU32(dest, 12, NDP16_SIG)
        putU16(dest, 16, layout.ndpLength)
        putU16(dest, 18, 0)
        for (index in frames.indices) {
            val entry = 20 + index * 4
            putU16(dest, entry, layout.indexes[index])
            putU16(dest, entry + 2, frames[index].size)
        }
        val terminator = 20 + frames.size * 4
        putU16(dest, terminator, 0)
        putU16(dest, terminator + 2, 0)
        var written = terminator + 4
        for (index in frames.indices) {
            val at = layout.indexes[index]
            while (written < at) dest[written++] = 0
            frames[index].copyInto(dest, at)
            written = at + frames[index].size
        }
        while (written < layout.usbLength) dest[written++] = 0
        return layout.usbLength
    }

    private class Plan(val blockLength: Int, val usbLength: Int, val ndpLength: Int, val indexes: IntArray)

    private fun plan(datagramLengths: List<Int>): Plan {
        require(datagramLengths.isNotEmpty()) { "frames must not be empty" }
        val count = datagramLengths.size
        val ndpLength = 8 + 4 * (count + 1)
        var cursor = NTH_LENGTH + ndpLength
        val indexes = IntArray(count)
        for (index in datagramLengths.indices) {
            val aligned = cursor + (4 - cursor % 4) % 4
            indexes[index] = aligned
            cursor = aligned + datagramLengths[index]
            require(cursor <= 0xffff) { "frame exceeds the NTB16 length field" }
        }
        val usbLength = if (cursor % USB_PACKET_SIZE == 0) cursor + 1 else cursor
        return Plan(cursor, usbLength, ndpLength, indexes)
    }

    /**
     * Extracts the Ethernet frames carried by one NTB16 block.
     *
     * Wire garbage returns an empty list instead of throwing; malformed entries are skipped where
     * a bounds check fails. A step guard stops a malformed NDP pointer chain from cycling.
     */
    fun parse(block: ByteArray): List<ByteArray> = parse(block, 0, block.size)

    /** Parses one NTB16 block from [offset] without copying the containing buffer. */
    fun parse(block: ByteArray, offset: Int, length: Int): List<ByteArray> {
        val datagrams = ArrayList<ByteArray>(1)
        parseRanges(block, offset, length) { start, end ->
            datagrams.add(block.copyOfRange(start, end))
        }
        return datagrams
    }

    /**
     * Streams the `(start, end)` source range of every datagram in one NTB16 block, without
     * allocating frame copies. Same tolerance of wire garbage as [parse]: the block signature
     * and every entry are bounds-checked before [emit] fires. A block that repeats an NDP offset,
     * lists more than [MAX_ENTRIES_PER_BLOCK] entries or yields more datagram bytes than its own
     * length (entries that overlap) is dropped whole: nothing is emitted.
     */
    fun parseRanges(block: ByteArray, offset: Int, length: Int, emit: (start: Int, end: Int) -> Unit) {
        require(offset >= 0 && length >= 0 && offset <= block.size - length) {
            "NTB16 range is outside the source buffer"
        }
        val end = offset + length
        if (length < NTH_LENGTH || readU32(block, offset) != NTH16_SIG) return

        // First pass: validate the whole NDP chain and collect ranges, so a block that loops or
        // overruns its own bounds emits nothing at all instead of a prefix.
        val ranges = ArrayList<Pair<Int, Int>>(1)
        val visitedNdps = HashSet<Int>()
        var entries = 0
        var totalBytes = 0
        var ndpOffset = offset + readU16(block, offset + 10)
        while (ndpOffset != 0) {
            if (ndpOffset < offset || ndpOffset + 12 > end) break
            if (!visitedNdps.add(ndpOffset)) return
            if (readU32(block, ndpOffset) and 0x00ffffff != NDP16_SIG and 0x00ffffff) break
            val ndpLength = readU16(block, ndpOffset + 4)
            val nextNdp = readU16(block, ndpOffset + 6)
            var entry = ndpOffset + 8
            val ndpEnd = minOf(ndpOffset + ndpLength, end)
            while (entry + 4 <= ndpEnd) {
                val datagramIndex = readU16(block, entry)
                val datagramLength = readU16(block, entry + 2)
                if (datagramIndex == 0 || datagramLength == 0) break
                if (++entries > MAX_ENTRIES_PER_BLOCK) return
                val datagramStart = offset + datagramIndex
                val datagramEnd = datagramStart + datagramLength
                if (datagramStart >= offset && datagramStart <= end && datagramEnd <= end) {
                    totalBytes += datagramLength
                    if (totalBytes > length) return
                    ranges.add(datagramStart to datagramEnd)
                }
                entry += 4
            }
            ndpOffset = if (nextNdp == 0) 0 else offset + nextNdp
        }
        for (range in ranges) emit(range.first, range.second)
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    private const val MAX_DATAGRAM_BYTES = 0xffff - DATAGRAM_INDEX
}
