package com.shilapi.xcertplay.media

import android.content.Context

/**
 * Whether this is the Pioneer head unit the OEM media routes were built for: the native USB-route
 * helper is installed and the factory libSourceSts.so matches the measured fingerprint. Settings
 * and key handling use this as the unit identity, independent of any audio-source choice.
 */
object PioneerHeadUnit {
    fun present(context: Context): Boolean = PioneerUsbRoute.supported(context)
}
