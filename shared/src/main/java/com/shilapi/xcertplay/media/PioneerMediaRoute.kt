package com.shilapi.xcertplay.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

internal interface PioneerMediaSource {
    fun current(): Int
    fun next(): Int
    fun pausePlayer()
}

internal data class PioneerMediaRouteResult(val ready: Boolean, val detail: String)

/** Bounded handover to the shared OEM media bus, without taking the VR microphone role. */
internal fun preparePioneerMediaRoute(
    source: PioneerMediaSource,
    isActive: () -> Boolean,
    reassertFocus: () -> Unit,
    sleep: (Long) -> Unit = { Thread.sleep(it) },
): PioneerMediaRouteResult {
    fun checkActive() {
        if (!isActive() || Thread.currentThread().isInterrupted) throw InterruptedException()
    }
    checkActive()
    val before = source.current()
    var current = before
    val steps = mutableListOf<String>()
    for (step in 0 until 6) {
        // Native Bluetooth ringing/calls and the Tencent phone source have priority over music.
        if (current in setOf(206, 207, 208, 302)) {
            return PioneerMediaRouteResult(false, "deferred-phone=$current steps=$steps")
        }
        if (current == 300) break
        checkActive()
        val result = source.next()
        sleep(700)
        checkActive()
        current = source.current()
        steps.add("$result->$current")
    }
    if (current != 300) return PioneerMediaRouteResult(false, "before=$before reached=false steps=$steps")
    // Activation can resume a paused OEM song asynchronously. Address only its exported receiver;
    // a global media key may instead pause our own CarPlay MediaSession.
    checkActive()
    source.pausePlayer()
    sleep(350)
    checkActive()
    source.pausePlayer()
    sleep(700)
    checkActive()
    current = source.current()
    if (current != 300) return PioneerMediaRouteResult(false, "before=$before lost-after-pause=$current steps=$steps")
    // The OEM player may have requested focus during source activation, after our renderer did.
    // On this head unit that reclaim can move the source off 300. Put it back once and do not
    // reclaim again: a second abandon is what cut Siri off after the first words.
    reassertFocus()
    sleep(200)
    checkActive()
    current = source.current()
    if (current != 300) {
        for (step in 0 until 6) {
            if (current in setOf(206, 207, 208, 302)) {
                return PioneerMediaRouteResult(false, "before=$before deferred-phone=$current steps=$steps")
            }
            if (current == 300) break
            checkActive()
            val result = source.next()
            sleep(700)
            checkActive()
            current = source.current()
            steps.add("refocus:$result->$current")
        }
        if (current != 300) return PioneerMediaRouteResult(false, "before=$before lost-after-focus=$current steps=$steps")
    }
    return PioneerMediaRouteResult(true, "before=$before reached=true targetedPause=true steps=$steps")
}

internal class AndroidPioneerMediaSource(private val context: Context) : PioneerMediaSource {
    private val controllerClass = Class.forName("jp.pioneer.ceam.MediaManager.MediaController")
    private val controller = requireNotNull(controllerClass.getMethod("getInstance").invoke(null))
    private val currentMethod = controllerClass.getMethod("getCurSource", Int::class.javaPrimitiveType)
    private val nextMethod = controllerClass.getMethod("change2NextSource", Int::class.javaPrimitiveType)

    override fun current() = (currentMethod.invoke(controller, 1) as Number).toInt()
    override fun next() = (nextMethod.invoke(controller, 1) as Number).toInt()

    override fun pausePlayer() {
        pausePioneerPlayer(context)
    }
}

internal fun pausePioneerPlayer(context: Context) {
    val receiver = ComponentName("com.tencent.wecarflow", "com.tencent.wecarflow.media.service.MediaButtonIntentReceiver")
    for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PAUSE)))
    }
}
