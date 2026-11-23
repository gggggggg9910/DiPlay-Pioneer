package com.shilapi.xcertplay.network;

import java.util.ArrayDeque;

/**
 * JNI entry points for the vendored lwIP stack. The native library is loaded on first use so
 * unit tests that never open the wired transport do not need the shared object.
 */
public final class UserspaceLwip {
    public static final int EVENT_ACCEPT = 1;
    public static final int EVENT_DATA = 2;
    public static final int EVENT_CLOSE = 3;
    public static final int EVENT_UDP = 4;
    public static final int EVENT_PACKET = 5;

    private static final Object signal = new Object();
    private static volatile boolean pending;

    /** Wakes the poll thread after native code queued an event. */
    public static void wake() {
        onIoPending();
    }

    /** Waits until [wake] or the timeout. Clears the pending flag. */
    public static void await(long timeoutMillis) throws InterruptedException {
        synchronized (signal) {
            if (!pending) signal.wait(timeoutMillis);
            pending = false;
        }
    }

    private static boolean loaded;

    private UserspaceLwip() {}

    public static final class Event {
        public int type;
        public int id;
        public int generation;
        public int localPort;
        public int remotePort;
        public byte[] address = new byte[16];
        public byte[] data = new byte[0];
        /** Valid prefix of {@link #data}. Pooled buffers are larger than the event. */
        public int dataLength;
    }

    private static final int[] BUFFER_BUCKETS = {64, 128, 256, 512, 1024, 2048, 4096, 16384};
    private static final int MAX_POOLED_PER_BUCKET = 32;
    private static final ArrayDeque<byte[]>[] bufferPool = newPool();

    @SuppressWarnings("unchecked")
    private static ArrayDeque<byte[]>[] newPool() {
        ArrayDeque<byte[]>[] pool = new ArrayDeque[BUFFER_BUCKETS.length];
        for (int index = 0; index < pool.length; index++) pool[index] = new ArrayDeque<>();
        return pool;
    }

    /**
     * A buffer of at least {@code length} bytes for one lwIP event. The caller reads
     * {@code dataLength} bytes and returns it with {@link #releaseEventBuffer}.
     */
    public static byte[] acquireEventBuffer(int length) {
        if (length <= 0) return new byte[0];
        int index = bucketIndex(length);
        if (index < 0) return new byte[length];
        synchronized (bufferPool[index]) {
            byte[] pooled = bufferPool[index].pollFirst();
            if (pooled != null) return pooled;
        }
        return new byte[BUFFER_BUCKETS[index]];
    }

    /** Returns a buffer from {@link #acquireEventBuffer}. Other arrays are ignored. */
    public static void releaseEventBuffer(byte[] buffer) {
        if (buffer == null) return;
        int index = bucketIndexExact(buffer.length);
        if (index < 0) return;
        synchronized (bufferPool[index]) {
            ArrayDeque<byte[]> bucket = bufferPool[index];
            for (byte[] existing : bucket) {
                if (existing == buffer) return;
            }
            if (bucket.size() < MAX_POOLED_PER_BUCKET) bucket.addFirst(buffer);
        }
    }

    private static int bucketIndex(int length) {
        for (int index = 0; index < BUFFER_BUCKETS.length; index++) {
            if (BUFFER_BUCKETS[index] >= length) return index;
        }
        return -1;
    }

    private static int bucketIndexExact(int size) {
        for (int index = 0; index < BUFFER_BUCKETS.length; index++) {
            if (BUFFER_BUCKETS[index] == size) return index;
        }
        return -1;
    }

    public static synchronized boolean ensureLoaded() {
        if (loaded) return true;
        try {
            System.loadLibrary("xcertplay_lwip");
            loaded = true;
            return true;
        } catch (UnsatisfiedLinkError error) {
            return false;
        }
    }

    /** Called from native code after an event was queued and the lwIP lock was released. */
    static void onIoPending() {
        synchronized (signal) {
            pending = true;
            signal.notifyAll();
        }
    }

    public static native boolean nativeStart(byte[] address);

    public static native void nativeStop();

    public static native void nativeInput(byte[] packet, int length);

    /** Consumes the range synchronously; never retains the Java buffer. */
    public static native void nativeInputRange(byte[] packet, int offset, int length);

    public static native boolean nativePoll(Event event);

    public static native int nativeWrite(int id, int generation, byte[] data, int offset, int length);

    public static native void nativeRecved(int id, int generation, int length);

    public static native void nativeShutdown(int id, int generation);

    public static native void nativeAbort(int id, int generation);

    public static native boolean nativeSendUdp(
            int sourcePort,
            byte[] destination,
            int destinationPort,
            byte[] data,
            int offset,
            int length);

    public static native void nativeCheckTimeouts();
}
