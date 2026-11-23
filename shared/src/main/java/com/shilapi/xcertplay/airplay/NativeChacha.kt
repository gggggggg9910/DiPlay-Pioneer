package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.network.UserspaceLwip

/**
 * ChaCha20-Poly1305 through the bundled Conscrypt BoringSSL AEAD, skipping
 * javax.crypto.Cipher. On this head unit the cipher itself is fast and the
 * JCA wrapper is not.
 */
internal object NativeChacha {
    private val used = object : ThreadLocal<Boolean>() {
        override fun initialValue() = false
    }
    // A missing/incompatible installed library cannot appear later in this process. Cache failure
    // too, so fallback crypto does not throw a linker exception on every audio/video packet.
    private val nativeAvailable: Boolean by lazy { UserspaceLwip.ensureLoaded() }

    private val comparison: String by lazy {
        if (nativeAvailable) nativeCompare() else "JniCompare selected=disabled reason=library"
    }

    fun comparisonReport(): String = comparison

    val implementation: String?
        get() = if (used.get() == true) "ConscryptAEAD/" + comparison.substringAfter("selected=").substringBefore(" ") else null

    fun open(key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray): ByteArray? =
        invoke { nativeOpen(key, nonce, input, aad) }

    fun seal(key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray): ByteArray? =
        invoke { nativeSeal(key, nonce, input, aad) }

    /** RFC vector plus a timed open. Safe to call before a phone is connected. */
    private val selfTestResult: String by lazy {
        if (prepare()) nativeSelfTest() + " | " + comparison else "fail native-unavailable | " + comparison
    }

    fun selfTest(): String = selfTestResult

    private inline fun invoke(call: () -> ByteArray?): ByteArray? {
        if (!prepare()) return null
        val output = call() ?: return null
        used.set(true)
        return output
    }

    private fun prepare(): Boolean = nativeAvailable && !comparison.contains("selected=disabled")

    @JvmStatic private external fun nativeOpen(key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray): ByteArray?
    @JvmStatic private external fun nativeSeal(key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray): ByteArray?
    @JvmStatic private external fun nativeSelfTest(): String
    @JvmStatic private external fun nativeCompare(): String
}
