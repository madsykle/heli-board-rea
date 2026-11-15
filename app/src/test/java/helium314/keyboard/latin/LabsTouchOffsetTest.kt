// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import kotlin.test.Test
import kotlin.test.assertEquals

class LabsTouchOffsetTest {
    @Test
    fun horizontalThirdSplitsTheKeyboard() {
        assertEquals(0, touchHorizontalThird(0, 300))
        assertEquals(0, touchHorizontalThird(99, 300))
        assertEquals(1, touchHorizontalThird(100, 300))
        assertEquals(1, touchHorizontalThird(199, 300))
        assertEquals(2, touchHorizontalThird(200, 300))
        assertEquals(2, touchHorizontalThird(299, 300))
        assertEquals(1, touchHorizontalThird(50, 0))
    }

    @Test
    fun rowIndexUsesTheLetterRowTops() {
        val tops = intArrayOf(0, 100, 200)
        assertEquals(0, touchRowIndex(tops, 0))
        assertEquals(0, touchRowIndex(tops, 50))
        assertEquals(1, touchRowIndex(tops, 100))
        assertEquals(2, touchRowIndex(tops, 200))
        assertEquals(2, touchRowIndex(tops, 250))
        assertEquals(0, touchRowIndex(tops, -10))
        assertEquals(0, touchRowIndex(IntArray(0), 123))
    }

    @Test
    fun extraLetterRowsClampToTheBottomBucket() {
        val tops = intArrayOf(0, 100, 200, 300)
        assertEquals(2, touchRowIndex(tops, 300))
        assertEquals(2, touchRowIndex(tops, 350))
    }

    @Test
    fun bucketIndexIsRowMajor() {
        val tops = intArrayOf(0, 100, 200)
        assertEquals(0, touchBucketIndex(tops, 0, 0, 300))
        assertEquals(1, touchBucketIndex(tops, 0, 150, 300))
        assertEquals(2, touchBucketIndex(tops, 0, 250, 300))
        assertEquals(3, touchBucketIndex(tops, 100, 0, 300))
        assertEquals(5, touchBucketIndex(tops, 100, 250, 300))
        assertEquals(7, touchBucketIndex(tops, 200, 150, 300))
    }

    @Test
    fun bucketComputesMeanAndStandardDeviation() {
        val bucket = TouchOffsetBucket(
            count = 2,
            sumDx = 4,
            sumDy = -4,
            sumDx2 = 10,
            sumDy2 = 10,
        )
        assertEquals(2.0, bucket.meanDx, 1e-9)
        assertEquals(-2.0, bucket.meanDy, 1e-9)
        assertEquals(1.0, bucket.stdDx, 1e-9)
        assertEquals(1.0, bucket.stdDy, 1e-9)
    }

    @Test
    fun emptyBucketHasZeroStats() {
        val bucket = TouchOffsetBucket()
        assertEquals(0.0, bucket.meanDx, 0.0)
        assertEquals(0.0, bucket.meanDy, 0.0)
        assertEquals(0.0, bucket.stdDx, 0.0)
        assertEquals(0.0, bucket.stdDy, 0.0)
    }
}
