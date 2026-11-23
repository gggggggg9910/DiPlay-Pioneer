package com.shilapi.xcertplay.airplay

import android.view.KeyEvent

/**
 * Hardware media keys → CarPlay media HID presses (indices into [AirPlayHid]'s media report).
 *
 * Hardware play and pause keys both map to the toggle: BYD picks PLAY or PAUSE from its own idea of
 * the play state, and a wrong guess would make the button do nothing. Explicit play and pause
 * commands from media controllers use [PLAY] and [PAUSE].
 */
object CarPlayMediaButton {
    const val PLAY = 1
    const val PAUSE = 2
    const val PLAY_PAUSE = 3
    const val NEXT = 4
    const val PREVIOUS = 5

    /** BYD's steering-wheel play/pause key; the firmware normally rewrites it to MEDIA_PLAY/PAUSE. */
    const val KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE = 353

    /** BYD's steering-wheel voice key: a short press, and the code the wheel sends for a long press. */
    const val KEYCODE_BYD_AUTO_MEDIA_VOICE = 304
    const val KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG = 312

    /** DiLink 3's play/pause key (KEYCODE_AUTO_MEDIA_PLAY_PAUSE), when the firmware does not rewrite it. */
    const val KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE_DILINK3 = 331

    /**
     * Pioneer wired seek on the Camry panel (`20b8000.kpp`, scan 1 and 2). The layout names them
     * EXTEND_WIRED_UP / EXTEND_WIRED_DOWN, and the input service numbers them 554 / 555. They are
     * the previous/next buttons; they are not Android's MEDIA_NEXT (87).
     */
    const val KEYCODE_PIONEER_WIRED_SEEK_UP = 554
    const val KEYCODE_PIONEER_WIRED_SEEK_DOWN = 555

    /**
     * Pioneer voice key on the same keyboard, named EXTEND_WIRED_VR and numbered 570.
     * The panel scan is 0x06 and the wired scan is 0x0119. A press wakes Siri while CarPlay is up;
     * otherwise the car's own assistant (on this Camry, WeChat) keeps the key.
     */
    const val KEYCODE_PIONEER_WIRED_VR = 570

    /**
     * Pioneer source key. The face button is scan 0x16, EXTEND_MODE, numbered 307. The wired
     * button is scan 0x05, EXTEND_WIRED_MODE, numbered 567. Either one cycles USB, Bluetooth and FM
     * until a CarPlay session is up; then a press brings DiPlay forward instead.
     */
    const val KEYCODE_PIONEER_MODE = 307
    const val KEYCODE_PIONEER_WIRED_MODE = 567

    /**
     * DiLink 3's CarPlay voice key, short and long. BYD's window manager keeps it for its own CarPlay
     * app, so only the wheel key service sees it (see [opensSiriWhileCarPlay]).
     */
    const val KEYCODE_BYD_CARPLAY_VOICE = 327
    const val KEYCODE_BYD_CARPLAY_VOICE_LONG = 328

    /**
     * Whether [keyCode] is a voice key that opens Siri. The BYD wheel sends each press as an
     * instant down/up pair, so a long press arrives as its own key rather than as a held one.
     */
    fun opensSiri(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
        keyCode == KEYCODE_BYD_AUTO_MEDIA_VOICE || keyCode == KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG

    /** The voice keys BYD sends only for CarPlay; they open Siri while a CarPlay session runs. */
    fun opensSiriWhileCarPlay(keyCode: Int): Boolean =
        keyCode == KEYCODE_BYD_CARPLAY_VOICE || keyCode == KEYCODE_BYD_CARPLAY_VOICE_LONG

    /** The two voice keys the wheel sends for a short press of its voice button. */
    fun isShortVoiceKey(keyCode: Int): Boolean =
        keyCode == KEYCODE_BYD_CARPLAY_VOICE || keyCode == KEYCODE_BYD_AUTO_MEDIA_VOICE

    /**
     * Whether this event is the Pioneer voice key. A press can arrive with only the scan code
     * filled in: panel 0x06, wired 0x0119, both on event device 0.
     */
    fun isPioneerWiredVoice(keyCode: Int, scanCode: Int = -1, deviceId: Int = -1): Boolean =
        keyCode == KEYCODE_PIONEER_WIRED_VR ||
            (deviceId == PIONEER_KEY_DEVICE && scanCode in PIONEER_VOICE_SCANS)

    /** Whether [keyCode] is a Pioneer MODE key that cycles the head unit's audio source. */
    fun isPioneerMode(keyCode: Int): Boolean =
        keyCode == KEYCODE_PIONEER_MODE || keyCode == KEYCODE_PIONEER_WIRED_MODE

    /**
     * Face MODE is scan 0x16 and the wired MODE is scan 0x05 on `20b8000.kpp` (event device 0).
     * A press can arrive with only the scan code filled in.
     */
    fun isPioneerModeKey(keyCode: Int, scanCode: Int, deviceId: Int): Boolean =
        isPioneerMode(keyCode) || (deviceId == PIONEER_KEY_DEVICE && scanCode in PIONEER_MODE_SCANS)

    /** Pioneer panel seek, or null when this event is some other key. */
    fun pioneerWiredSeek(keyCode: Int, scanCode: Int = -1, deviceId: Int = -1): Int? = when {
        keyCode == KEYCODE_PIONEER_WIRED_SEEK_UP -> NEXT
        keyCode == KEYCODE_PIONEER_WIRED_SEEK_DOWN -> PREVIOUS
        deviceId == PIONEER_KEY_DEVICE && scanCode == 0x01 -> NEXT
        deviceId == PIONEER_KEY_DEVICE && scanCode == 0x02 -> PREVIOUS
        else -> null
    }

    private const val PIONEER_KEY_DEVICE = 0
    private val PIONEER_MODE_SCANS = intArrayOf(0x05, 0x16)
    private val PIONEER_VOICE_SCANS = intArrayOf(0x06, 0x0119)

    fun forKeyCode(keyCode: Int, experimentalDiLink3Keys: Boolean = false): Int? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT, KEYCODE_PIONEER_WIRED_SEEK_UP -> NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KEYCODE_PIONEER_WIRED_SEEK_DOWN -> PREVIOUS
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE -> PLAY_PAUSE
        KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE_DILINK3 -> if (experimentalDiLink3Keys) PLAY_PAUSE else null
        else -> null
    }

    /**
     * The CarPlay press the CarPlay screen itself should claim for [keyCode], or null to let the key
     * go on to the car.
     *
     * BYD hands the steering wheel's next/previous keys to whichever window is in front, and DiPlay's
     * CarPlay screen is that window whenever the driver is looking at it. Claiming them here is what
     * makes the wheel change tracks without the accessibility service, which a head unit with no ADB
     * cannot turn on.
     *
     * Next and previous are always claimed. Play/pause is claimed only where [claimPlayPause] says
     * the panel's media keys are ordinary Android keys: BYD acts on the toggle itself and the wheel
     * key service owns it for DiLink 3, while the Pioneer panel hands it to the front window like
     * any media key. Nothing is claimed outside a CarPlay session, or during a call, where the car's
     * own call handling is what the driver expects from these keys.
     */
    fun forCarPlayWindow(
        keyCode: Int,
        sessionActive: Boolean,
        inCall: Boolean,
        claimPlayPause: Boolean = false,
    ): Int? {
        if (!sessionActive || inCall) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT, KEYCODE_PIONEER_WIRED_SEEK_UP -> NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KEYCODE_PIONEER_WIRED_SEEK_DOWN -> PREVIOUS
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> if (claimPlayPause) PLAY_PAUSE else null
            else -> null
        }
    }
}
