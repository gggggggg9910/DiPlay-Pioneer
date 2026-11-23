package com.shilapi.xcertplay.media

/**
 * Which engine decodes the main screen's H.264. PLATFORM asks Android for the default decoder (the
 * VPU on head units that have one); PLATFORM_SOFTWARE puts Android's own software decoder first
 * (OMX.google on old releases, c2.android on new ones); FFMPEG decodes in the bundled FFmpeg
 * library, whose frame threading uses several cores where the platform's software decoder uses one.
 */
enum class VideoDecoderEngine {
    PLATFORM,
    PLATFORM_SOFTWARE,
    FFMPEG,
}
