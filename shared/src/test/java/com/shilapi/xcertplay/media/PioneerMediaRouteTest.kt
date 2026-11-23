package com.shilapi.xcertplay.media

import android.content.Intent
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class PioneerMediaRouteTest {
    private class Source(var value: Int = 0, val switchWorks: Boolean = true) : PioneerMediaSource {
        var switches = 0
        var pauses = 0
        override fun current() = value
        override fun next(): Int { switches++; if (switchWorks) value = 300; return 0 }
        override fun pausePlayer() { pauses++ }
    }

    @Test fun reclaimsFocusOnlyAfterTheDelayedPlayerResumeHasBeenPaused() {
        val source = Source()
        var reclaimed = false
        val result = preparePioneerMediaRoute(source, { true }, {
            assertEquals(2, source.pauses)
            assertEquals(300, source.value)
            reclaimed = true
        }, {})
        assertTrue(result.ready)
        assertTrue(reclaimed)
        assertEquals(1, source.switches)
    }

    @Test fun alreadySelectedMediaStillPausesTheOemPlayerWithoutCyclingRadio() {
        val source = Source(300)
        assertTrue(preparePioneerMediaRoute(source, { true }, {}, {}).ready)
        assertEquals(0, source.switches)
        assertEquals(2, source.pauses)
    }

    @Test fun refusedSourceIsBoundedAndDoesNotPauseOrReclaimFocus() {
        val source = Source(switchWorks = false)
        val result = preparePioneerMediaRoute(source, { true }, { fail("no route") }, {})
        assertFalse(result.ready)
        assertEquals(6, source.switches)
        assertEquals(0, source.pauses)
    }

    @Test fun disconnectDuringHandoverStopsPendingActions() {
        val source = Source(300)
        var active = true
        try {
            preparePioneerMediaRoute(source, { active }, { fail("disconnected") }, { active = false })
            fail("must cancel")
        } catch (_: InterruptedException) {
            assertEquals(1, source.pauses)
        }
    }

    @Test fun sourceLostDuringPauseIsNotReportedAsReady() {
        val source = Source(300)
        val result = preparePioneerMediaRoute(source, { true }, { fail("lost source") }, { source.value = 0 })
        assertFalse(result.ready)
    }

    @Test fun ringingAndCallsAreNeverCycledAwayOrPaused() {
        for (value in listOf(206, 207, 208, 302)) {
            val source = Source(value)
            assertFalse(preparePioneerMediaRoute(source, { true }, { fail("call owns audio") }, {}).ready)
            assertEquals(0, source.switches)
            assertEquals(0, source.pauses)
        }
    }

    @Test fun focusHandoverThatDropsTheSourceSelectsItAgainWithoutAnotherReclaim() {
        val source = Source(300)
        var reclaims = 0
        val result = preparePioneerMediaRoute(source, { true }, {
            reclaims++
            source.value = 0
        }, {})
        assertTrue(result.ready)
        assertEquals(300, source.value)
        assertEquals(1, reclaims)
    }

    @Test fun pauseTargetsOnlyTheOemReceiverAndNeverUsesToggle() {
        val app = RuntimeEnvironment.getApplication()
        pausePioneerPlayer(app)
        val intents = shadowOf(app).broadcastIntents.filter { it.action == Intent.ACTION_MEDIA_BUTTON }
        assertEquals(2, intents.size)
        intents.forEachIndexed { index, intent ->
            assertEquals("com.tencent.wecarflow", intent.component?.packageName)
            assertEquals("com.tencent.wecarflow.media.service.MediaButtonIntentReceiver", intent.component?.className)
            @Suppress("DEPRECATION")
            val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)!!
            assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, key.keyCode)
            assertEquals(if (index == 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, key.action)
        }
    }
}
