package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shilapi.xcertplay.telecom.PhoneAudioRoute

/** Boot hook for call-audio routing and the optional debug bridge. DiPlay itself is not launched. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Starts the watcher for other apps' calls when that setting is on, even without opening DiPlay.
        PhoneAudioRoute.attach(context)
        // Optional out-of-band adb relay; a no-op unless its marker file exists.
        com.shilapi.xcertplay.network.DebugBridge.ensureStarted()
        // DiPlay is not launched at boot. The head unit starts it when the driver opens it.
        StartupDiagnosticSnapshot.received(context, false)
    }
}
