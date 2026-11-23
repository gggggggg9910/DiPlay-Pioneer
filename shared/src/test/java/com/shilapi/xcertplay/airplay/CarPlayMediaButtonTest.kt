package com.shilapi.xcertplay.airplay

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayMediaButtonTest {
    @Test
    fun steeringWheelKeysMapToCarPlayMediaPresses() {
        assertEquals(CarPlayMediaButton.NEXT, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(CarPlayMediaButton.PREVIOUS, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(CarPlayMediaButton.NEXT, CarPlayMediaButton.pioneerWiredSeek(554))
        assertEquals(CarPlayMediaButton.PREVIOUS, CarPlayMediaButton.pioneerWiredSeek(555))
        assertEquals(CarPlayMediaButton.NEXT, CarPlayMediaButton.pioneerWiredSeek(0, scanCode = 1, deviceId = 0))
        assertEquals(CarPlayMediaButton.PREVIOUS, CarPlayMediaButton.pioneerWiredSeek(0, scanCode = 2, deviceId = 0))
        assertNull(CarPlayMediaButton.pioneerWiredSeek(0, scanCode = 1, deviceId = 3))
        assertNull(CarPlayMediaButton.pioneerWiredSeek(KeyEvent.KEYCODE_MEDIA_NEXT))
        // BYD rewrites its play/pause key into PLAY or PAUSE from the session state; both toggle.
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_HEADSETHOOK))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(353))
        // DiLink 3 (Han, GCC) sends KEYCODE_AUTO_MEDIA_PLAY_PAUSE unchanged.
        assertNull(CarPlayMediaButton.forKeyCode(331))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE,
            CarPlayMediaButton.forKeyCode(331, experimentalDiLink3Keys = true))
    }

    @Test
    fun dilink3CarPlayVoiceKeysOpenSiriOnlyThroughTheWheelKeyService() {
        assertTrue(CarPlayMediaButton.opensSiriWhileCarPlay(327))
        assertTrue(CarPlayMediaButton.opensSiriWhileCarPlay(328))
        assertFalse(CarPlayMediaButton.opensSiriWhileCarPlay(304))
        assertFalse(CarPlayMediaButton.opensSiri(327))
    }

    @Test
    fun theWheelsShortVoiceKeysAreTheCarPlayAndGenericShortCodes() {
        assertTrue(CarPlayMediaButton.isShortVoiceKey(327))
        assertTrue(CarPlayMediaButton.isShortVoiceKey(304))
        assertFalse(CarPlayMediaButton.isShortVoiceKey(328))
        assertFalse(CarPlayMediaButton.isShortVoiceKey(312))
        assertFalse(CarPlayMediaButton.isShortVoiceKey(KeyEvent.KEYCODE_VOICE_ASSIST))
    }

    @Test
    fun theVoiceKeyOpensSiri() {
        // Recorded on DiLink 5.0: short press 304 (scan 290), long press 312 (scan 312).
        assertTrue(CarPlayMediaButton.opensSiri(304))
        assertTrue(CarPlayMediaButton.opensSiri(312))
        assertTrue(CarPlayMediaButton.opensSiri(KeyEvent.KEYCODE_VOICE_ASSIST))
        assertFalse(CarPlayMediaButton.opensSiri(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(CarPlayMediaButton.isPioneerWiredVoice(570))
        assertTrue(CarPlayMediaButton.isPioneerWiredVoice(0, 0x06, 0))
        assertTrue(CarPlayMediaButton.isPioneerWiredVoice(0, 0x0119, 0))
        assertFalse(CarPlayMediaButton.isPioneerWiredVoice(0, 0x06, 3))
        assertTrue(CarPlayMediaButton.isPioneerMode(307))
        assertTrue(CarPlayMediaButton.isPioneerMode(567))
        assertTrue(CarPlayMediaButton.isPioneerModeKey(0, 0x16, 0))
        assertTrue(CarPlayMediaButton.isPioneerModeKey(0, 0x05, 0))
        assertFalse(CarPlayMediaButton.isPioneerModeKey(0, 0x16, 3))
        assertFalse(CarPlayMediaButton.isPioneerMode(570))
        assertFalse(CarPlayMediaButton.isPioneerWiredVoice(KeyEvent.KEYCODE_VOICE_ASSIST))
        assertFalse(CarPlayMediaButton.opensSiri(570))
        assertNull(CarPlayMediaButton.forKeyCode(304))
    }

    @Test
    fun otherKeysAreLeftToTheSystem() {
        assertNull(CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_STOP))
    }

    @Test
    fun indicesMatchTheAdvertisedMediaHidReport() {
        // Media report usages: 0 none, 1 play, 2 pause, 3 play/pause, 4 next, 5 previous.
        assertEquals(3, CarPlayMediaButton.PLAY_PAUSE)
        assertEquals(4, CarPlayMediaButton.NEXT)
        assertEquals(5, CarPlayMediaButton.PREVIOUS)
    }

    @Test
    fun theWindowClaimsTrackKeysOnlyWhileCarPlayIsRunning() {
        fun claim(keyCode: Int, sessionActive: Boolean = true, inCall: Boolean = false) =
            CarPlayMediaButton.forCarPlayWindow(keyCode, sessionActive, inCall)

        assertEquals(CarPlayMediaButton.NEXT, claim(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(CarPlayMediaButton.PREVIOUS, claim(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(CarPlayMediaButton.NEXT, claim(CarPlayMediaButton.KEYCODE_PIONEER_WIRED_SEEK_UP))
        assertEquals(CarPlayMediaButton.PREVIOUS, claim(CarPlayMediaButton.KEYCODE_PIONEER_WIRED_SEEK_DOWN))
        // Without a session, or during a call, the wheel keeps the car's own action.
        assertNull(claim(KeyEvent.KEYCODE_MEDIA_NEXT, sessionActive = false))
        assertNull(claim(CarPlayMediaButton.KEYCODE_PIONEER_WIRED_SEEK_UP, sessionActive = false))
        assertNull(claim(CarPlayMediaButton.KEYCODE_PIONEER_WIRED_SEEK_DOWN, inCall = true))
        assertNull(claim(KeyEvent.KEYCODE_MEDIA_PREVIOUS, sessionActive = false))
        assertNull(claim(KeyEvent.KEYCODE_MEDIA_NEXT, inCall = true))
        assertNull(claim(KeyEvent.KEYCODE_MEDIA_PREVIOUS, inCall = true))
    }

    @Test
    fun theWindowLeavesPlayPauseAndUnrelatedKeysAlone() {
        // BYD acts on play/pause itself, and the wheel key service owns it for DiLink 3. Claiming it
        // in the window too would take it away from the car for every driver.
        for (keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, 353, 331,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_VOLUME_UP)) {
            assertNull("keyCode=$keyCode", CarPlayMediaButton.forCarPlayWindow(keyCode, true, false))
        }
    }

    @Test
    fun pioneerWindowsAlsoClaimPlayPause() {
        fun claim(keyCode: Int, claimPlayPause: Boolean = true, sessionActive: Boolean = true,
            inCall: Boolean = false) =
            CarPlayMediaButton.forCarPlayWindow(keyCode, sessionActive, inCall, claimPlayPause)

        assertEquals(CarPlayMediaButton.PLAY_PAUSE, claim(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, claim(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, claim(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, claim(KeyEvent.KEYCODE_HEADSETHOOK))
        // Track keys keep working, and the same guards apply to the toggle.
        assertEquals(CarPlayMediaButton.NEXT, claim(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(CarPlayMediaButton.PREVIOUS, claim(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertNull(claim(KeyEvent.KEYCODE_MEDIA_PLAY, sessionActive = false))
        assertNull(claim(KeyEvent.KEYCODE_MEDIA_PLAY, inCall = true))
        // Stop, volume and BYD's own codes stay with the car even where the toggle is claimed.
        assertNull(claim(KeyEvent.KEYCODE_MEDIA_STOP))
        assertNull(claim(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(claim(353))
        assertNull(claim(331))
    }
}
