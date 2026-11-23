package com.shilapi.xcertplay.media

import java.nio.ByteBuffer

/**
 * Bridge to libxcertplay_ffmpeg (a build of FFmpeg with only the H.264 decoder). [available] is
 * false when the library could not be loaded, so callers fall back to the MediaCodec path.
 */
internal object FfmpegNative {
    val available: Boolean by lazy { runCatching { System.loadLibrary("xcertplay_ffmpeg") }.isSuccess }

    /** Opens an H.264 decoder with [threads] frame threads; 0L when it could not be opened. */
    external fun nativeOpen(threads: Int): Long

    /**
     * Decodes one Annex B access unit. When a frame came out, its planes are compacted row by row
     * into [y], [u] and [v] (direct buffers, tight strides: width, width/2, width/2) and [info] is
     * filled as [width, height, colorspace, colorRange]; returns the number of frames produced,
     * 0 when the unit only fed the decoder, negative on a decoder error.
     */
    external fun nativeDecode(
        handle: Long,
        data: ByteArray,
        length: Int,
        y: ByteBuffer,
        u: ByteBuffer,
        v: ByteBuffer,
        info: IntArray,
    ): Int

    /** Drops decoded-reference state after a break in the stream, keeping the decoder open. */
    external fun nativeFlush(handle: Long)

    external fun nativeClose(handle: Long)
}
