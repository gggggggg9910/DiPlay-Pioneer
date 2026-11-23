package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Plays one tone per Android audio channel and reports whether the head unit's mixer consumed
 * it (the playback head advancing). The no-sound investigation showed this unit's AudioFlinger
 * leaving a PLAYING media track undrained and its focus request refused, so the question is
 * which channel, if any, this firmware lets an ordinary app through on.
 *
 * Each channel gets its own pitch, so a listener can name the working channel by ear:
 * stream-music=A4, usage-media=C#5, usage-navigation=E5, usage-assistant=G5.
 */
object AudioChannelProbe {
    private const val TAG = "xcertplay-usb"
    private const val SAMPLE_RATE = 48_000

    private class Channel(
        val label: String,
        val hz: Double,
        val streamType: Int?,
        val attributes: AudioAttributes?,
    )

    // The last two copy DingDang's TTS recipe on Pioneer units whose mixer refuses ordinary
    // media tracks: the audibility-enforced flag, with and without the sonification usage.
    private val CHANNELS = listOf(
        Channel("stream-music", 440.0, AudioManager.STREAM_MUSIC, null),
        Channel("usage-media", 494.0, null, AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()),
        Channel("usage-navigation", 554.0, null, AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()),
        Channel("usage-assistant", 622.0, null, AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()),
        Channel("enforced-media", 659.0, null, AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()),
        Channel("enforced-sonification", 740.0, null, AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()),
        Channel("oem401-media", 880.0, null, null),
        Channel("oem404-duplex", 988.0, null, null),
        Channel("oem405-tts", 1046.0, null, null),
        Channel("oem406-voice", 1175.0, null, null),
    )

    /** The OEM private stream a probe tone exercises; null for the plain attribute channels. */
    private fun channelOemStream(label: String): Int? = when (label) {
        "oem401-media" -> 401
        "oem404-duplex" -> 404
        "oem405-tts" -> 405
        "oem406-voice" -> EnforcedAudioChannel.BORROWED_VR_FOCUS_STREAM
        else -> null
    }

    /** Runs on the caller's background thread; [onChannel] reports after each channel. */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    fun run(context: Context, onChannel: (String) -> Unit) {
        if (!running.compareAndSet(false, true)) {
            Log.e(TAG, "audio channel test refused: previous run still in progress")
            onChannel("audio channel test refused: previous run still in progress")
            return
        }
        try {
            quotaExhausted = false
            // The unit caps tracks per uid; once creation fails with the quota error, further
            // channels only deepen the confusion, so the round ends at the first dead track.
            CHANNELS.forEach { channel ->
                if (quotaExhausted) {
                    onChannel("audio channel test skipped channel=${channel.label}: track quota exhausted")
                    return@forEach
                }
                runCatching { probe(context, channel, onChannel) }
                    .onFailure { error ->
                        Log.e(TAG, "audio channel test failed channel=${channel.label}", error)
                        onChannel("audio channel test channel=${channel.label} failed: $error")
                    }
            }
        } finally {
            running.set(false)
        }
        Log.e(TAG, "audio channel test finished")
        onChannel("audio channel test finished")
    }

    @Volatile
    private var quotaExhausted = false

    private fun probe(context: Context, channel: Channel, onChannel: (String) -> Unit) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val oemStream = channelOemStream(channel.label)
        // Ask for focus the way a player would, so the firmware's focus policy is in the result.
        var focusGranted: Boolean? = null
        val listener = AudioManager.OnAudioFocusChangeListener { }
        if (audioManager != null) {
            focusGranted = try {
                // OEM streams need matching focus to activate their hardware mix slot.
                val result = audioManager.requestAudioFocus(listener, oemStream ?: AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } catch (error: Exception) {
                Log.w(TAG, "audio channel test focus request failed channel=${channel.label}", error)
                null
            }
        }

        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLE_RATE / 10 * 4)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val builder = AudioTrack.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (oemStream != null) {
            val oem = EnforcedAudioChannel.oemAttributesFor(oemStream)
            if (oem == null) {
                onChannel("audio channel test channel=" + channel.label + " oem attributes unavailable")
                return
            }
            builder.setAudioAttributes(oem)
        } else if (channel.attributes != null) builder.setAudioAttributes(channel.attributes)
        else builder.setAudioAttributes(
            AudioAttributes.Builder()
                .setLegacyStreamType(channel.streamType ?: AudioManager.STREAM_MUSIC)
                .build(),
        )
        val track: AudioTrack = try {
            builder.build()
        } catch (error: Throwable) {
            quotaExhausted = true
            Log.e(TAG, "audio channel test channel=${channel.label} build failed", error)
            onChannel("audio channel test channel=${channel.label} track build failed (quota?): $error")
            runCatching {
                if (audioManager != null && focusGranted == true) audioManager.abandonAudioFocus(listener)
            }
            return
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            quotaExhausted = true
            Log.e(TAG, "audio channel test channel=${channel.label} track init failed state=${track.state}")
            onChannel("audio channel test channel=${channel.label} track init failed (uid track quota?)")
            runCatching {
                if (audioManager != null && focusGranted == true) audioManager.abandonAudioFocus(listener)
            }
            return
        }

        // Blocking writes hang forever on units whose mixer never drains the track, so the
        // tone is fed with non-blocking writes and a channel that makes no progress is skipped.
        val chunkFrames = SAMPLE_RATE / 10
        val chunk = ShortArray(chunkFrames * 2)
        val totalFrames = SAMPLE_RATE.toLong() * TONE_SECONDS
        var writtenFrames = 0L
        var angle = 0.0
        var stalled = false
        onChannel("audio channel test channel=${channel.label} hz=${channel.hz.toInt()} focusGranted=$focusGranted playing")
        try {
            track.play()
            val deadline = android.os.SystemClock.elapsedRealtime() + (TONE_SECONDS + 2) * 1000L
            var lastProgress = android.os.SystemClock.elapsedRealtime()
            while (writtenFrames < totalFrames) {
                val wrote = track.write(chunk, 0, chunk.size, android.media.AudioTrack.WRITE_NON_BLOCKING)
                if (wrote > 0) {
                    writtenFrames += wrote
                    lastProgress = android.os.SystemClock.elapsedRealtime()
                    for (frame in 0 until chunkFrames) {
                        val sample = (AMPLITUDE * 32767 * kotlin.math.sin(angle)).toInt()
                        chunk[frame * 2] = sample.toShort()
                        chunk[frame * 2 + 1] = sample.toShort()
                        angle += 2.0 * Math.PI * channel.hz / SAMPLE_RATE
                    }
                } else if (wrote == 0) {
                    if (android.os.SystemClock.elapsedRealtime() - lastProgress > 1500 ||
                        android.os.SystemClock.elapsedRealtime() > deadline
                    ) {
                        stalled = writtenFrames < totalFrames
                        break
                    }
                    Thread.sleep(20)
                } else {
                    break
                }
            }
            val headWhileFull = track.playbackHeadPosition.toLong()
            Thread.sleep(400)
            val headAfter = track.playbackHeadPosition.toLong()
            Log.e(TAG, "audio channel test channel=${channel.label} consumed=${headAfter > 0} " +
                "written=$writtenFrames head=$headAfter")
            onChannel(
                "audio channel test channel=${channel.label} hz=${channel.hz.toInt()} " +
                    "focusGranted=$focusGranted writtenFrames=$writtenFrames " +
                    "headFrames=$headAfter headDuring=$headWhileFull " +
                    "consumed=${headAfter > 0}${if (stalled) " writeStalled" else ""}",
            )
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            runCatching {
                if (audioManager != null && focusGranted == true) audioManager.abandonAudioFocus(listener)
            }
            if (focusGranted == true && audioManager != null) {
                runCatching { audioManager.abandonAudioFocus(listener) }
            }
        }
    }

    /** Plays the test tone on [track] with the same non-blocking, stall-proof loop. */
    private fun playAndReport(
        track: AudioTrack,
        channel: Channel,
        focusGranted: Boolean?,
        onChannel: (String) -> Unit,
    ) {
        val chunkFrames = SAMPLE_RATE / 10
        val chunk = ShortArray(chunkFrames * 2)
        val totalFrames = SAMPLE_RATE.toLong() * TONE_SECONDS
        var writtenFrames = 0L
        var angle = 0.0
        var stalled = false
        onChannel("audio channel test channel=${channel.label} hz=${channel.hz.toInt()} focusGranted=$focusGranted playing")
        try {
            track.play()
            val deadline = android.os.SystemClock.elapsedRealtime() + (TONE_SECONDS + 2) * 1000L
            var lastProgress = android.os.SystemClock.elapsedRealtime()
            while (writtenFrames < totalFrames) {
                val wrote = track.write(chunk, 0, chunk.size, android.media.AudioTrack.WRITE_NON_BLOCKING)
                if (wrote > 0) {
                    writtenFrames += wrote
                    lastProgress = android.os.SystemClock.elapsedRealtime()
                    for (frame in 0 until chunkFrames) {
                        val sample = (AMPLITUDE * 32767 * kotlin.math.sin(angle)).toInt()
                        chunk[frame * 2] = sample.toShort()
                        chunk[frame * 2 + 1] = sample.toShort()
                        angle += 2.0 * Math.PI * channel.hz / SAMPLE_RATE
                    }
                } else if (wrote == 0) {
                    if (android.os.SystemClock.elapsedRealtime() - lastProgress > 1500 ||
                        android.os.SystemClock.elapsedRealtime() > deadline
                    ) {
                        stalled = writtenFrames < totalFrames
                        break
                    }
                    Thread.sleep(20)
                } else break
            }
            val headWhileFull = track.playbackHeadPosition.toLong()
            Thread.sleep(400)
            val headAfter = track.playbackHeadPosition.toLong()
            onChannel(
                "audio channel test channel=${channel.label} hz=${channel.hz.toInt()} " +
                    "focusGranted=$focusGranted writtenFrames=$writtenFrames " +
                    "headFrames=$headAfter headDuring=$headWhileFull " +
                    "consumed=${headAfter > 0}${if (stalled) " writeStalled" else ""}",
            )
        } catch (error: Exception) {
            onChannel("audio channel test channel=${channel.label} error: $error")
        }
    }

    private const val TONE_SECONDS = 3
    private const val AMPLITUDE = 0.25
}
