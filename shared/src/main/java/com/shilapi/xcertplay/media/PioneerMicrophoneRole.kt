package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioStreamId
import android.content.Context
import java.io.Closeable

internal interface PioneerMicrophoneController {
    fun current(): String?
    fun claim(token: String): Boolean
    fun release(token: String): Boolean
    fun acquireRecordFocus(reason: String): Boolean
    fun releaseRecordFocus(reason: String): Boolean
}

/**
 * A microphone role belongs to input streams. With [holdAcrossStreams] the first speech input
 * claims it for the whole CarPlay session instead: the iPhone's speech streams open and close
 * every few seconds, and each per-stream restore let the OEM assistant's recorder return in the
 * gaps — the user could wake it mid-Siri, and its concurrent capture degraded our uplink.
 */
internal class PioneerMicrophoneRole(
    private val token: String,
    private val controllerFactory: () -> PioneerMicrophoneController,
    private val report: (String) -> Unit = {},
    private val holdAcrossStreams: Boolean = false,
) : Closeable {
    private val streams = mutableSetOf<AudioStreamId>()
    private var controller: PioneerMicrophoneController? = null
    private var previous: String? = null
    private var claimed = false
    private var recordFocusAcquired = false
    private var closed = false

    @Synchronized fun acquire(id: AudioStreamId) {
        check(!closed) { "microphone owner closed" }
        if (id in streams) return
        if (streams.isEmpty() && !sessionHeld()) {
            // A gap between Siri streams leaves `streams` empty while the session still holds
            // the mic. Claiming again makes this head unit refuse the second recording focus,
            // and the failure path used to hand the mic back to the OEM assistant.
            if (controller != null) restore()
            val backend = controllerFactory()
            previous = backend.current()
            controller = backend
            try {
                check(backend.acquireRecordFocus(token)) { "OEM recording focus refused" }
                recordFocusAcquired = true
                emit("Microphone: OEM recording focus acquired")
                if (previous != token) {
                    check(backend.claim(token)) { "OEM microphone claim refused" }
                    claimed = true
                }
                check(backend.current() == token) { "OEM microphone owner changed" }
            } catch (error: Exception) {
                restore()
                throw error
            }
            emit("Microphone: OEM role acquired previous=$previous owner=$token")
        } else {
            check(controller?.current() == token) { "OEM microphone was taken by another owner" }
            if (sessionHeld()) emit("Microphone: OEM role reused owner=$token")
        }
        streams.add(id)
    }

    /** True while a session-scoped hold still owns both the recording focus and the mic token. */
    private fun sessionHeld(): Boolean =
        holdAcrossStreams && recordFocusAcquired && controller?.current() == token

    @Synchronized fun release(id: AudioStreamId) {
        if (streams.remove(id) && streams.isEmpty() && !holdAcrossStreams) restore()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        streams.clear()
        restore()
    }

    private fun restore() {
        val backend = controller
        val old = previous
        try {
            if (claimed && backend != null && backend.current() == token) {
                check(backend.release(token)) { "OEM microphone release refused" }
                val current = backend.current()
                // Do not overwrite a phone/assistant that has legitimately taken the role.
                if (!old.isNullOrBlank() && old != token &&
                    (current.isNullOrBlank() || current == token || current == "mediamanager.name.invalid")
                ) check(backend.claim(old)) { "OEM microphone restore refused" }
                emit("Microphone: OEM role released previous=$old owner=${backend.current()}")
            }
        } catch (error: Exception) {
            emit("Microphone: OEM role restore failed error=${error.javaClass.simpleName} message=${error.message}")
        } finally {
            if (recordFocusAcquired && backend != null) {
                try {
                    check(backend.releaseRecordFocus(token)) { "OEM recording focus release refused" }
                    emit("Microphone: OEM recording focus released")
                } catch (error: Exception) {
                    emit("Microphone: OEM recording focus restore failed error=${error.javaClass.simpleName} message=${error.message}")
                }
            }
            controller = null
            previous = null
            claimed = false
            recordFocusAcquired = false
        }
    }

    private fun emit(line: String) { runCatching { report(line) } }
}

internal class AndroidPioneerMicrophoneController(context: Context) : PioneerMicrophoneController {
    private val recordFocus = PioneerRecordFocus(context)
    private val type = Class.forName("jp.pioneer.ceam.MediaManager.MediaController")
    private val controller = requireNotNull(type.getMethod("getInstance").invoke(null))
    override fun current() = type.getMethod("getCurMicToken").invoke(controller) as? String
    override fun claim(token: String) = type.getMethod("requestMicOn", String::class.java).invoke(controller, token) as Boolean
    override fun release(token: String) = type.getMethod("requestMicOff", String::class.java).invoke(controller, token) as Boolean
    override fun acquireRecordFocus(reason: String) = recordFocus.acquire(reason)
    override fun releaseRecordFocus(reason: String) = recordFocus.release(reason)
}
