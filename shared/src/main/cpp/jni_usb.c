#include <errno.h>
#include <jni.h>
#include <string.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

/*
 * Synchronous bulk OUT on a direct buffer. Android's UsbDeviceConnection.bulkTransfer pins the
 * Java byte[] for the whole kernel wait, and that pin blocks ART's GC thread flip. The read path
 * already avoids that with UsbRequest. OUT must not use UsbRequest: one connection has a single
 * requestWait consumer, and sharing it with the NCM read reaper (tried previously) stalled video
 * and then failed later read queues.
 *
 * USBDEVFS_BULK is the ioctl bulkTransfer already uses, aimed at a direct buffer instead of a
 * pinned array. IN and OUT stay on different endpoints, as they do today.
 */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_UsbBulkTransfer_nativeTransfer(
        JNIEnv *env, jclass clazz, jint fd, jint endpoint, jobject buffer, jint length, jint timeout) {
    (void) clazz;
    if (fd < 0 || buffer == NULL || length <= 0 || timeout < 0) return -EINVAL;
    jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    void *address = (*env)->GetDirectBufferAddress(env, buffer);
    if (address == NULL || length > capacity) return -EINVAL;
    struct usbdevfs_bulktransfer bulk;
    memset(&bulk, 0, sizeof(bulk));
    bulk.ep = (unsigned int) endpoint;
    bulk.len = (unsigned int) length;
    bulk.timeout = (unsigned int) timeout;
    bulk.data = address;
    int transferred;
    do {
        transferred = ioctl(fd, USBDEVFS_BULK, &bulk);
    } while (transferred < 0 && errno == EINTR);
    if (transferred < 0) return -errno;
    return transferred;
}
