package com.shilapi.xcertplay.airplay

import java.io.File

/** Deletion claims the opt-in once, including when two streams arrive together. */
internal fun consumeMicrophoneCapture(marker: File?): File? = runCatching {
    if (marker != null && marker.isFile && marker.delete()) {
        File(marker.parentFile, "microphone-captures")
    } else null
}.getOrNull()
