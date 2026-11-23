package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class UsbBulkTransferTest {
    @Test fun aTimeoutStaysOnTheNativePathAndABadIoctlDoesNotOnceOneTransferHasWorked() {
        assertFalse(UsbBulkTransfer.abandonNative(64, false))
        assertFalse(UsbBulkTransfer.abandonNative(-110, false))
        assertTrue(UsbBulkTransfer.abandonNative(-22, false))
        assertFalse(UsbBulkTransfer.abandonNative(-22, true))
        assertFalse(UsbBulkTransfer.abandonNative(-25, true))
    }

    @Test fun aHeapBufferNeverAttemptsTheIoctl() {
        val heap = ByteBuffer.allocate(32)
        assertEquals(
            UsbBulkTransfer.UNSUPPORTED,
            UsbBulkTransfer.transfer(3, 0x02, heap, heap.capacity(), 20),
        )
    }
}
