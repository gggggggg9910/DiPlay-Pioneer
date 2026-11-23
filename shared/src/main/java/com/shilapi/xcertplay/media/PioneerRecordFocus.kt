package com.shilapi.xcertplay.media

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel

/** OEM SDK recording arbitration; unlike MediaController's mic token, this stops its recorder. */
internal class PioneerRecordFocus(private val context: Context) {
    private val binder: IBinder by lazy { findRecordingService() }

    fun acquire(reason: String): Boolean = transact(0x3a, reason)
    fun release(reason: String): Boolean = transact(0x3b, reason)

    private fun transact(code: Int, reason: String): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(RECORDING_INTERFACE)
            data.writeString(reason)
            check(binder.transact(code, data, reply, 0)) { "OEM recording transaction unsupported" }
            reply.readException()
            return reply.readInt() != 0
        } finally { reply.recycle(); data.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun findRecordingService(): IBinder {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = Unit
        }
        val intent = Intent("com.tencent.vrlogic.server.VrLogicService").setComponent(
            ComponentName("com.tencent.wecarspeech", "com.tencent.vrlogic.server.VrLogicService"),
        )
        val root = requireNotNull(receiver.peekService(context, intent)) { "OEM speech service unavailable" }
        val recording = when (root.interfaceDescriptor) {
            RECORDING_INTERFACE -> root
            ROUTER_INTERFACE -> {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(ROUTER_INTERFACE)
                    // Verified against IBindSubService.b(): transaction 2 returns a binder map.
                    check(root.transact(2, data, reply, 0)) { "OEM speech binder map unavailable" }
                    reply.readException()
                    reply.readHashMap(javaClass.classLoader)?.get("$RECORDING_INTERFACE\$Stub") as? IBinder
                } finally { reply.recycle(); data.recycle() }
            }
            else -> null
        }
        requireNotNull(recording) { "OEM recording binder unavailable" }
        check(recording.interfaceDescriptor == RECORDING_INTERFACE) { "Unexpected OEM recording interface" }
        return recording
    }

    private companion object {
        const val RECORDING_INTERFACE = "com.tencent.vrinterface.IVrLogicService"
        const val ROUTER_INTERFACE = "com.tencent.taes.framework.remote.IBindSubService"
    }
}
