package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.os.Process

/** Uses the codec's surface-render time, not Java callback delivery time. Never runs on the UI thread. */
internal object VideoRenderObserver {
    private val handler: Handler by lazy {
        Handler(HandlerThread("video-render-probe", Process.THREAD_PRIORITY_BACKGROUND).apply {
            isDaemon = true
            start()
        }.looper)
    }

    fun attach(codec: MediaCodec, isCurrent: () -> Boolean, onPresented: (Long) -> Unit): Boolean =
        runCatching {
            codec.setOnFrameRenderedListener({ _, _, nanoTime ->
                if (isCurrent()) onPresented(nanoTime)
            }, handler)
            true
        }.getOrDefault(false)
}
