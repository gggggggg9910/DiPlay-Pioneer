package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.*
import org.junit.Test

class PioneerMicrophoneRoleTest {
    private val first = AudioStreamId(100, "telephony")
    private val second = AudioStreamId(101, "speechrecognition")
    private val own = "jp.pioneer.ceam.test"
    private val original = "com.tencent.wecarspeech"
    private class Backend(var owner: String?, val trackFocus: Boolean = false) : PioneerMicrophoneController {
        var accept = true
        var acceptFocus = true
        var focusOwned = false
        val actions = mutableListOf<String>()
        override fun current() = owner
        override fun claim(token: String): Boolean {
            actions.add("claim:$token")
            if (accept) owner = token
            return accept
        }
        override fun release(token: String): Boolean {
            actions.add("release:$token")
            if (owner == token) owner = null
            return true
        }
        override fun acquireRecordFocus(reason: String): Boolean {
            if (trackFocus) actions.add("focus:on")
            focusOwned = acceptFocus
            return acceptFocus
        }
        override fun releaseRecordFocus(reason: String): Boolean {
            if (trackFocus) actions.add("focus:off")
            focusOwned = false
            return true
        }
    }

    @Test fun inputClaimsTheRoleAndStopRestoresThePreviousAssistant() {
        val backend = Backend(original)
        val role = PioneerMicrophoneRole(own, { backend })
        assertTrue(backend.actions.isEmpty())
        role.acquire(first)
        assertEquals(own, backend.owner)
        role.release(first)
        assertEquals(original, backend.owner)
        assertEquals(listOf("claim:$own", "release:$own", "claim:$original"), backend.actions)
    }

    @Test fun overlappingInputsAndDuplicateStartsDoNotReleaseEarly() {
        val backend = Backend(original)
        val role = PioneerMicrophoneRole(own, { backend })
        role.acquire(first); role.acquire(first); role.acquire(second)
        role.release(first); role.release(first)
        assertEquals(own, backend.owner)
        role.release(second)
        assertEquals(original, backend.owner)
        assertEquals(3, backend.actions.size)
    }

    @Test fun disconnectRestoresOnceAndLaterStopsAreHarmless() {
        val backend = Backend(original)
        val role = PioneerMicrophoneRole(own, { backend })
        role.acquire(first); role.acquire(second)
        role.close(); role.close(); role.release(first); role.release(second)
        assertEquals(original, backend.owner)
        assertEquals(3, backend.actions.size)
        try { role.acquire(first); fail("closed") } catch (_: IllegalStateException) { }
    }

    @Test fun aDifferentPhoneOwnerIsNotOverwrittenOnStop() {
        val backend = Backend(original)
        val role = PioneerMicrophoneRole(own, { backend })
        role.acquire(first)
        backend.owner = "com.tencent.mm"
        role.release(first)
        assertEquals("com.tencent.mm", backend.owner)
        assertEquals(listOf("claim:$own"), backend.actions)
    }

    @Test fun anAlreadyOwnedRoleIsNotReleasedByThisLease() {
        val backend = Backend(own)
        val role = PioneerMicrophoneRole(own, { backend })
        role.acquire(first); role.release(first); role.close()
        assertEquals(own, backend.owner)
        assertTrue(backend.actions.isEmpty())
    }

    @Test fun failedClaimDoesNotConsumeAStreamAndCanBeRetried() {
        val backend = Backend(original).apply { accept = false }
        val role = PioneerMicrophoneRole(own, { backend })
        try { role.acquire(first); fail("refused") } catch (_: IllegalStateException) { }
        assertEquals(original, backend.owner)
        backend.accept = true
        role.acquire(first); role.release(first)
        assertEquals(original, backend.owner)
    }

    @Test fun failedReportingCannotPreventReturnOfTheMicrophone() {
        val backend = Backend(original)
        val role = PioneerMicrophoneRole(own, { backend }, report = { _ -> throw IllegalStateException("report") })
        role.acquire(first); role.release(first)
        assertEquals(original, backend.owner)
    }

    @Test fun recorderIsSuspendedBeforeClaimAndResumedAfterOwnerRestoration() {
        val backend = Backend(original, trackFocus = true)
        val role = PioneerMicrophoneRole(own, { backend })
        role.acquire(first)
        assertTrue(backend.focusOwned)
        role.release(first)
        assertFalse(backend.focusOwned)
        assertEquals(listOf("focus:on", "claim:$own", "release:$own", "claim:$original", "focus:off"), backend.actions)
    }

    @Test fun refusedRecordingFocusDoesNotTakeTheMicrophone() {
        val backend = Backend(original, trackFocus = true).apply { acceptFocus = false }
        val role = PioneerMicrophoneRole(own, { backend })
        try { role.acquire(first); fail("focus refused") } catch (_: IllegalStateException) { }
        assertEquals(original, backend.owner)
        assertEquals(listOf("focus:on"), backend.actions)
    }

    @Test fun holdAcrossStreamsReusesTheClaimWhenTheNextStreamStarts() {
        val backend = Backend(original, trackFocus = true)
        var builds = 0
        val role = PioneerMicrophoneRole(own, { builds++; backend }, holdAcrossStreams = true)
        role.acquire(first)
        role.release(first)
        assertEquals(own, backend.owner)
        assertTrue(backend.focusOwned)
        // The head unit refuses a second recording-focus request while the first is still held.
        backend.acceptFocus = false
        role.acquire(second)
        assertEquals(own, backend.owner)
        assertTrue(backend.focusOwned)
        assertEquals(1, builds)
        assertEquals(listOf("focus:on", "claim:$own"), backend.actions)
        role.close()
        assertEquals(original, backend.owner)
        assertFalse(backend.focusOwned)
    }

    @Test fun holdAcrossStreamsReclaimsWhenTheOwnerChangedDuringTheGap() {
        val backend = Backend(original, trackFocus = true)
        val role = PioneerMicrophoneRole(own, { backend }, holdAcrossStreams = true)
        role.acquire(first)
        role.release(first)
        backend.owner = original
        role.acquire(second)
        assertEquals(own, backend.owner)
        assertTrue(backend.focusOwned)
        role.close()
        assertEquals(original, backend.owner)
    }

    @Test fun microphoneClaimFailureAlsoRestoresTheOemRecorder() {
        val backend = Backend(original, trackFocus = true).apply { accept = false }
        val role = PioneerMicrophoneRole(own, { backend })
        try { role.acquire(first); fail("mic refused") } catch (_: IllegalStateException) { }
        assertFalse(backend.focusOwned)
        assertEquals(listOf("focus:on", "claim:$own", "focus:off"), backend.actions)
    }
}
