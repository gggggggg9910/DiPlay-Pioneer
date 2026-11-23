package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LwipBufferPoolTest {
    @Test fun aReleasedBucketIsReusedAndASecondReleaseDoesNotDuplicateIt() {
        val buffer = UserspaceLwip.acquireEventBuffer(100)
        assertTrue(buffer.size >= 100)
        UserspaceLwip.releaseEventBuffer(buffer)
        UserspaceLwip.releaseEventBuffer(buffer)
        val first = UserspaceLwip.acquireEventBuffer(100)
        val second = UserspaceLwip.acquireEventBuffer(100)
        assertSame(buffer, first)
        assertNotSame(first, second)
        UserspaceLwip.releaseEventBuffer(first)
        UserspaceLwip.releaseEventBuffer(second)
    }

    @Test fun buffersOutsideTheSizeClassesAreNotPooled() {
        val huge = UserspaceLwip.acquireEventBuffer(20_000)
        assertEquals(20_000, huge.size)
        UserspaceLwip.releaseEventBuffer(huge)
        assertNotSame(huge, UserspaceLwip.acquireEventBuffer(20_000))
    }
}
