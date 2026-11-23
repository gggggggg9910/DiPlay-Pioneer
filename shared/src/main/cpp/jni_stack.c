#include "userspace_stack.h"

#include <jni.h>
#include <stdlib.h>
#include <string.h>

static JavaVM *java_vm;
static jclass lwip_class;
static jmethodID on_pending;
static jfieldID event_type;
static jfieldID event_id;
static jfieldID event_generation;
static jfieldID event_local_port;
static jfieldID event_remote_port;
static jfieldID event_address;
static jfieldID event_data;
static jfieldID event_length;
static jmethodID acquire_buffer;

static void notify_if_queued(JNIEnv *env, int before) {
    if (userspace_stack_event_count() == before) return;
    (*env)->CallStaticVoidMethod(env, lwip_class, on_pending);
}

static int event_count_now(void) {
    return userspace_stack_event_count();
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    java_vm = vm;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local = (*env)->FindClass(env, "com/shilapi/xcertplay/network/UserspaceLwip");
    if (local == NULL) return JNI_ERR;
    lwip_class = (*env)->NewGlobalRef(env, local);
    on_pending = (*env)->GetStaticMethodID(env, lwip_class, "onIoPending", "()V");
    jclass event = (*env)->FindClass(env, "com/shilapi/xcertplay/network/UserspaceLwip$Event");
    if (event == NULL || on_pending == NULL) return JNI_ERR;
    event_type = (*env)->GetFieldID(env, event, "type", "I");
    event_id = (*env)->GetFieldID(env, event, "id", "I");
    event_generation = (*env)->GetFieldID(env, event, "generation", "I");
    event_local_port = (*env)->GetFieldID(env, event, "localPort", "I");
    event_remote_port = (*env)->GetFieldID(env, event, "remotePort", "I");
    event_address = (*env)->GetFieldID(env, event, "address", "[B");
    event_data = (*env)->GetFieldID(env, event, "data", "[B");
    event_length = (*env)->GetFieldID(env, event, "dataLength", "I");
    acquire_buffer = (*env)->GetStaticMethodID(env, lwip_class, "acquireEventBuffer", "(I)[B");
    if (event_type == NULL || event_generation == NULL || event_data == NULL ||
        event_length == NULL || acquire_buffer == NULL) return JNI_ERR;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_shilapi_xcertplay_network_UserspaceLwip_nativeStart(JNIEnv *env, jclass clazz, jbyteArray address) {
    (void) clazz;
    if (address == NULL || (*env)->GetArrayLength(env, address) != 16) return JNI_FALSE;
    jbyte bytes[16];
    (*env)->GetByteArrayRegion(env, address, 0, 16, bytes);
    int before = event_count_now();
    int ok = userspace_stack_start((const uint8_t *) bytes);
    notify_if_queued(env, before);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_network_UserspaceLwip_nativeStop(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    userspace_stack_stop();
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_network_UserspaceLwip_nativeInput(JNIEnv *env, jclass clazz, jbyteArray packet, jint length) {
    (void) clazz;
    if (packet == NULL || length <= 0) return;
    jbyte *bytes = (*env)->GetByteArrayElements(env, packet, NULL);
    if (bytes == NULL) return;
    int before = event_count_now();
    userspace_stack_input((const uint8_t *) bytes, length);
    (*env)->ReleaseByteArrayElements(env, packet, bytes, JNI_ABORT);
    notify_if_queued(env, before);
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_network_UserspaceLwip_nativeInputRange(JNIEnv *env, jclass clazz, jbyteArray packet, jint offset, jint length) {
    (void) clazz;
    if (packet == NULL || offset < 0 || length <= 0) return;
    jsize size = (*env)->GetArrayLength(env, packet);
    if (length > size || offset > size - length) return;
    jbyte *bytes = (*env)->GetByteArrayElements(env, packet, NULL);
    if (bytes == NULL) return;
    int before = event_count_now();
    /* stack_input copies to owned pbuf storage before returning. */
    userspace_stack_input((const uint8_t *) bytes + offset, length);
    (*env)->ReleaseByteArrayElements(env, packet, bytes, JNI_ABORT);
    notify_if_queued(env, before);
}

JNIEXPORT jboolean JNICALL
Java_com_shilapi_xcertplay_network_UserspaceLwip_nativePoll(JNIEnv *env, jclass clazz, jobject event) {
    (void) clazz;
    if (event == NULL) return JNI_FALSE;
    struct userspace_event polled;
    memset(&polled, 0, sizeof(polled));
    if (!userspace_stack_poll(&polled)) return JNI_FALSE;
    (*env)->SetIntField(env, event, event_type, polled.type);
    (*env)->SetIntField(env, event, event_id, polled.id);
    (*env)->SetIntField(env, event, event_generation, polled.generation);
    (*env)->SetIntField(env, event, event_local_port, polled.local_port);
    (*env)->SetIntField(env, event, event_remote_port, polled.remote_port);
    jbyteArray address = (*env)->GetObjectField(env, event, event_address);
    if (address != NULL && (*env)->GetArrayLength(env, address) >= 16) {
        (*env)->SetByteArrayRegion(env, address, 0, 16, (const jbyte *) polled.address);
    }
    /* Size-class buffers from the Java pool. Callers must honour dataLength: a recycled
     * buffer is larger than this event, and the bytes past dataLength are stale. */
    jbyteArray data = NULL;
    if (polled.length > 0) {
        data = (*env)->CallStaticObjectMethod(env, lwip_class, acquire_buffer, polled.length);
        if ((*env)->ExceptionCheck(env) || data == NULL ||
            (*env)->GetArrayLength(env, data) < polled.length) {
            free(polled.data);
            return JNI_FALSE;
        }
        if (polled.data != NULL) {
            (*env)->SetByteArrayRegion(env, data, 0, polled.length, (const jbyte *) polled.data);
        }
    } else {
        data = (*env)->NewByteArray(env, 0);
    }
    free(polled.data);
    if (data == NULL || (*env)->ExceptionCheck(env)) return JNI_FALSE;
    (*env)->SetIntField(env, event, event_length, polled.length > 0 ? polled.length : 0);
    (*env)->SetObjectField(env, event, event_data, data);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_network_UserspaceLwip_nativeWrite(JNIEnv *env, jclass clazz, jint id, jint generation,
                                                             jbyteArray data, jint offset, jint length) {
    (void) clazz;
    if (data == NULL || length <= 0) return 0;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return -1;
    int before = event_count_now();
    int written = userspace_stack_write(id, generation, (const uint8_t *) bytes + offset, length);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    notify_if_queued(env, before);
    return written;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_network_UserspaceLwip_nativeRecved(JNIEnv *env, jclass clazz, jint id, jint generation,
                                                              jint length) {
    (void) env;
    (void) clazz;
    userspace_stack_recved(id, generation, length);
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_network_UserspaceLwip_nativeShutdown(JNIEnv *env, jclass clazz, jint id,
                                                                 jint generation) {
    (void) clazz;
    int before = event_count_now();
    userspace_stack_shutdown(id, generation);
    notify_if_queued(env, before);
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_network_UserspaceLwip_nativeAbort(JNIEnv *env, jclass clazz, jint id, jint generation) {
    (void) clazz;
    int before = event_count_now();
    userspace_stack_abort(id, generation);
    notify_if_queued(env, before);
}

JNIEXPORT jboolean JNICALL
Java_com_shilapi_xcertplay_network_UserspaceLwip_nativeSendUdp(JNIEnv *env, jclass clazz, jint source_port,
                                                               jbyteArray destination, jint destination_port,
                                                               jbyteArray data, jint offset, jint length) {
    (void) clazz;
    if (destination == NULL || (*env)->GetArrayLength(env, destination) != 16 || data == NULL || length < 0) {
        return JNI_FALSE;
    }
    jbyte dest[16];
    (*env)->GetByteArrayRegion(env, destination, 0, 16, dest);
    jbyte *bytes = length == 0 ? NULL : (*env)->GetByteArrayElements(env, data, NULL);
    if (length > 0 && bytes == NULL) return JNI_FALSE;
    int before = event_count_now();
    int ok = userspace_stack_send_udp(source_port, (const uint8_t *) dest, destination_port,
                                      bytes == NULL ? NULL : (const uint8_t *) bytes + offset, length);
    if (bytes != NULL) (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    notify_if_queued(env, before);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_network_UserspaceLwip_nativeCheckTimeouts(JNIEnv *env, jclass clazz) {
    (void) clazz;
    int before = event_count_now();
    userspace_stack_timeouts();
    notify_if_queued(env, before);
}
