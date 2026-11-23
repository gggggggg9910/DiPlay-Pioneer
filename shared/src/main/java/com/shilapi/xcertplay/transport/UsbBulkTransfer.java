package com.shilapi.xcertplay.transport;

import java.nio.ByteBuffer;

/**
 * Bulk OUT that does not pin a Java array for the kernel wait. See jni_usb.c.
 * Unit tests run without the native library and keep the historical bulkTransfer path.
 */
public final class UsbBulkTransfer {
    /** Native ioctl is not usable; the caller must use {@code bulkTransfer}. */
    public static final int UNSUPPORTED = Integer.MIN_VALUE;

    private static final int EPERM = 1;
    private static final int EBADF = 9;
    private static final int EACCES = 13;
    private static final int EFAULT = 14;
    private static final int EINVAL = 22;
    private static final int ENOTTY = 25;
    private static final int ENOSYS = 38;

    private static final boolean available;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("xcertplay_lwip");
            loaded = true;
        } catch (Throwable ignored) {
            loaded = false;
        }
        available = loaded;
    }

    private UsbBulkTransfer() {}

    public static boolean isAvailable() {
        return available;
    }

    /**
     * Bytes transferred, a negative errno, or {@link #UNSUPPORTED}. A negative errno means the
     * ioctl was attempted and did not complete; the caller must not send the same bytes again.
     */
    public static int transfer(int fd, int endpoint, ByteBuffer buffer, int length, int timeout) {
        if (!available || buffer == null || !buffer.isDirect() || length <= 0 || timeout < 0) {
            return UNSUPPORTED;
        }
        return nativeTransfer(fd, endpoint, buffer, length, timeout);
    }

    /**
     * True when this result means the ioctl interface itself is missing or was called wrong, and
     * only before a transfer has succeeded. A timeout or a device stall is a real result.
     */
    public static boolean abandonNative(int result, boolean proven) {
        if (result >= 0 || proven) return false;
        int errno = -result;
        return errno == EINVAL || errno == ENOTTY || errno == ENOSYS
                || errno == EPERM || errno == EACCES || errno == EFAULT || errno == EBADF;
    }

    private static native int nativeTransfer(int fd, int endpoint, ByteBuffer buffer, int length, int timeout);
}
