/*
 * JNI face of the bundled FFmpeg H.264 decoder (libavcodec + libavutil only). The Kotlin side
 * owns one handle per decoder; every call happens on that decoder's worker thread. Plane data is
 * compacted row by row into the caller's direct buffers, so the renderer uploads with tight
 * strides without needing GLES3 unpack-row extensions.
 */
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <android/log.h>
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>

#define LOG_TAG "xcertplay-ffmpeg"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

struct ffmpeg_decoder {
    AVCodecContext *ctx;
    AVFrame *frame;
};

/* info indices shared with FfmpegNative.kt */
#define INFO_WIDTH 0
#define INFO_HEIGHT 1
#define INFO_COLORSPACE 2
#define INFO_RANGE 3

static void copy_plane(uint8_t *dst, int dst_width, int dst_height,
                       const uint8_t *src, int src_stride) {
    for (int row = 0; row < dst_height; row++) {
        memcpy(dst + (size_t)row * dst_width, src + (size_t)row * src_stride, dst_width);
    }
}

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_FfmpegNative_nativeOpen(JNIEnv *env, jclass clazz, jint threads) {
    (void) env; (void) clazz;
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_H264);
    if (codec == NULL) return 0;
    struct ffmpeg_decoder *dec = av_mallocz(sizeof(*dec));
    if (dec == NULL) return 0;
    dec->ctx = avcodec_alloc_context3(codec);
    dec->frame = av_frame_alloc();
    if (dec->ctx == NULL || dec->frame == NULL) goto fail;
    if (threads > 1) {
        dec->ctx->thread_count = threads;
        dec->ctx->thread_type = FF_THREAD_FRAME;
    }
    if (avcodec_open2(dec->ctx, codec, NULL) < 0) goto fail;
    LOGI("h264 decoder open threads=%d", dec->ctx->thread_count);
    return (jlong) (intptr_t) dec;
fail:
    av_frame_free(&dec->frame);
    avcodec_free_context(&dec->ctx);
    av_free(dec);
    return 0;
}

/*
 * Decodes one Annex B access unit. Returns the number of frames produced; when at least one came
 * out, the newest frame's planes are copied into y/u/v and info is filled. Returns a negative
 * AVERROR when the decoder rejected the unit.
 */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_FfmpegNative_nativeDecode(JNIEnv *env, jclass clazz,
        jlong handle, jbyteArray data, jint length, jobject y, jobject u, jobject v,
        jintArray info) {
    (void) clazz;
    struct ffmpeg_decoder *dec = (struct ffmpeg_decoder *) (intptr_t) handle;
    if (dec == NULL || dec->ctx == NULL) return AVERROR(EINVAL);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return AVERROR(ENOMEM);

    AVPacket *packet = av_packet_alloc();
    int produced = 0;
    int result = 0;
    AVFrame *newest = NULL;
    if (packet == NULL || av_new_packet(packet, length) < 0) {
        result = AVERROR(ENOMEM);
        goto done;
    }
    memcpy(packet->data, bytes, length);
    packet->pts = AV_NOPTS_VALUE;

    result = avcodec_send_packet(dec->ctx, packet);
    if (result < 0 && result != AVERROR(EAGAIN)) goto done;
    for (;;) {
        int receive = avcodec_receive_frame(dec->ctx, dec->frame);
        if (receive == AVERROR(EAGAIN) || receive == AVERROR_EOF) break;
        if (receive < 0) { result = receive; goto done; }
        produced++;
        if (newest == NULL) newest = av_frame_alloc();
        av_frame_unref(newest);
        av_frame_move_ref(newest, dec->frame);
    }

done:
    av_packet_free(&packet);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);

    if (produced > 0 && newest != NULL && newest->data[0] != NULL) {
        int width = newest->width;
        int height = newest->height;
        uint8_t *dst_y = y ? (*env)->GetDirectBufferAddress(env, y) : NULL;
        uint8_t *dst_u = u ? (*env)->GetDirectBufferAddress(env, u) : NULL;
        uint8_t *dst_v = v ? (*env)->GetDirectBufferAddress(env, v) : NULL;
        jsize cap_y = y ? (jsize) (*env)->GetDirectBufferCapacity(env, y) : 0;
        jsize cap_u = u ? (jsize) (*env)->GetDirectBufferCapacity(env, u) : 0;
        jsize cap_v = v ? (jsize) (*env)->GetDirectBufferCapacity(env, v) : 0;
        if (dst_y != NULL && cap_y >= (jsize)(width * height) &&
            dst_u != NULL && cap_u >= (jsize)(width * height / 4) &&
            dst_v != NULL && cap_v >= (jsize)(width * height / 4)) {
            copy_plane(dst_y, width, height, newest->data[0], newest->linesize[0]);
            copy_plane(dst_u, width / 2, height / 2, newest->data[1], newest->linesize[1]);
            copy_plane(dst_v, width / 2, height / 2, newest->data[2], newest->linesize[2]);
        }
        if (info != NULL) {
            jint values[4] = {width, height, (jint) newest->colorspace, (jint) newest->color_range};
            (*env)->SetIntArrayRegion(env, info, 0, 4, values);
        }
    }
    if (newest != NULL) av_frame_free(&newest);
    if (result < 0 && result != AVERROR(EAGAIN)) {
        LOGW("decode error %d", result);
        return result;
    }
    return produced;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_FfmpegNative_nativeFlush(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    struct ffmpeg_decoder *dec = (struct ffmpeg_decoder *) (intptr_t) handle;
    if (dec != NULL && dec->ctx != NULL) avcodec_flush_buffers(dec->ctx);
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_FfmpegNative_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    struct ffmpeg_decoder *dec = (struct ffmpeg_decoder *) (intptr_t) handle;
    if (dec == NULL) return;
    if (dec->ctx != NULL) {
        LOGI("h264 decoder close");
        avcodec_free_context(&dec->ctx);
    }
    av_frame_free(&dec->frame);
    av_free(dec);
}
