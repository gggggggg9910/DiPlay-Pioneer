package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.content.Context

/**
 * The audio channel CarPlay audio plays on. NORMAL is the platform default. The other two add
 * FLAG_AUDIBILITY_ENFORCED, copying DingDang's TTS recipe for Pioneer units whose mixer never
 * drains an ordinary media track (the playback head stays at zero while the track plays).
 */
enum class EnforcedAudioChannel {
    NORMAL,
    ENFORCED_MEDIA,
    ENFORCED_SONIFICATION,
    BORROWED_TOKEN;

    /** The tested Pioneer firmware has an independent, watchdog-owned USB media source. */
    fun usesPioneerUsbSource(context: Context): Boolean =
        this == BORROWED_TOKEN && PioneerUsbRoute.supported(context)

    fun attributes(): AudioAttributes? = when (this) {
        NORMAL -> null
        ENFORCED_MEDIA -> AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()
        ENFORCED_SONIFICATION -> AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()
    BORROWED_TOKEN -> null
    }

    companion object {
        /**
         * The identity whose constructor branch selects the OEM private media stream 401
         * (TX_MEDIA, token com.tencent.wecarflow.media). Borrow this one for music; 406
         * (wecarspeech) is the voice-assistant role and its stream is not the media bus.
         */
        const val BORROWED_MEDIA_IDENTITY = "com.tencent.wecarflow"

        /** The OEM private focus/stream type that pairs with [BORROWED_MEDIA_IDENTITY]. */
        const val BORROWED_MEDIA_FOCUS_STREAM = 401

        /**
         * STREAM_TAI_VR (406, token com.tencent.wecarspeech). Verified on the Pioneer unit:
         * its matching focus request activates mix 301 and produces audible output without
         * taking the microphone role. 405 is STREAM_TAI_VR_TTS, a different token/mix whose
         * activation times out on this firmware. Track and focus must use the same stream.
         */
        const val BORROWED_VR_FOCUS_STREAM = 406

        /**
         * Best-effort switch of the head unit's audio source to the App slot through the OEM
         * MediaManager (a boot-classpath API this unit exposes to every app). The OEM AV service
         * never activates the main audio zone for CarPlay music coming from an unknown app, and
         * its mix-zone tokens stay "mediamanager.name.invalid"; only a source claim opens the
         * speaker path. Returns an outcome string for the diagnostic log; constants are read at
         * runtime because they are compile-time inlined and unknown here.
         */
        fun switchSourceToApp(): String = try {
            val sc = Class.forName("jp.pioneer.ceam.MediaManager.SourceController")
            val inst = sc.getMethod("getInstance").invoke(null) ?: return "no-instance"
            val current = { display: Int ->
                runCatching { sc.getMethod("getCurrentSource", Int::class.javaPrimitiveType).invoke(inst, display) as? String }.getOrNull()
            }
            val list = { display: Int ->
                runCatching { sc.getMethod("getSourceList", Int::class.javaPrimitiveType).invoke(inst, display) as? Array<String> }.getOrNull()
            }
            val mcCls = Class.forName("jp.pioneer.ceam.MediaManager.MediaController")
            val display = runCatching { mcCls.getField("DISPLAY_ID_FRONT").getInt(null) }.getOrDefault(1)
            val probe = (0..1).joinToString(" ") { d ->
                "d$d=" + (current(d)?.let { "'$it'" } ?: "null") + "/" + (list(d)?.contentToString() ?: "[]")
            }
            val target = (list(display) ?: emptyArray())
                .firstOrNull { it.contains("AAP", true) || it.contains("APP", true) } ?: "AAP"
            val cur = current(display) ?: ""
            val change = sc.getMethod(
                "change2Source", Int::class.javaPrimitiveType, String::class.java,
            )
            val transition = if (cur.equals(target, true)) {
                // The zone binds on a real transition; a previous session may already sit on the
                // target, so step to another listed source and back.
                val away = (list(display) ?: emptyArray()).firstOrNull { !it.equals(target, true) }
                if (away == null) "no-away-source"
                else {
                    val out = change.invoke(inst, display, away)
                    Thread.sleep(800)
                    val back = change.invoke(inst, display, target)
                    "toggled(away='$away' out=$out back=$back)"
                }
            } else {
                "direct=" + change.invoke(inst, display, target)
            }
            Thread.sleep(1500)
            "display=$display target='$target' before='$cur' after=${current(display)?.let { "'$it'" }} $transition probe[$probe]"
        } catch (e: Throwable) {
            "threw:" + e.javaClass.simpleName + ":" + (e.message ?: "no message")
        } catch (e: Throwable) {
            "threw:" + e.javaClass.simpleName + ":" + (e.message ?: "no message")
        }

        /**
         * Builds attributes carrying the OEM private stream type through this firmware's
         * AudioAttributes.Builder.setInternalLegacyStreamType, whose sparse switch accepts
         * 400-406 and stores the value as the contentType (the public setContentType rejects
         * anything above the standard range). The AudioTrack constructor then maps that
         * contentType back to the private stream and assigns the matching TX_* token itself.
         * Returns null where the internal setter does not exist (ordinary Android builds).
         */
        fun oemAttributesFor(oemStream: Int): AudioAttributes? = try {
            val builder = AudioAttributes.Builder()
            AudioAttributes.Builder::class.java
                .getMethod("setInternalLegacyStreamType", Int::class.javaPrimitiveType)
                .invoke(builder, oemStream)
            builder.build()
        } catch (e: Throwable) {
            null
        }
    }
}
