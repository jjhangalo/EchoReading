package com.echoreading.ui.component

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayPauseMorphTest {
    @Test
    fun fullPauseDrawsTwoSeparateBars() {
        val (left, right) = playPauseMorphQuads(1f)

        // Left bar spans x 0.19..0.41, right bar x 0.59..0.81, full height.
        assertEquals(0.19f, left[0], 1e-4f)
        assertEquals(0.41f, left[2], 1e-4f)
        assertEquals(0.59f, right[0], 1e-4f)
        assertEquals(0.81f, right[2], 1e-4f)
        assertEquals(0.20f, left[1], 1e-4f)
        assertEquals(0.80f, left[5], 1e-4f)
    }

    @Test
    fun fullPlayCollapsesRightQuadIntoTheTriangleTip() {
        val (left, right) = playPauseMorphQuads(0f)

        // Left quad is the triangle's left half; the right quad's right edge
        // collapses onto the tip at (0.78, 0.5).
        assertEquals(0.28f, left[0], 1e-4f)
        assertEquals(0.78f, right[2], 1e-4f)
        assertEquals(0.5f, right[3], 1e-4f)
        assertEquals(right[2], right[4], 1e-4f)
        assertEquals(right[3], right[5], 1e-4f)
    }

    @Test
    fun halfwayMorphInterpolatesEveryCorner() {
        val (leftZero, _) = playPauseMorphQuads(0f)
        val (leftHalf, _) = playPauseMorphQuads(0.5f)
        val (leftOne, _) = playPauseMorphQuads(1f)

        for (i in 0 until 8) {
            assertEquals((leftZero[i] + leftOne[i]) / 2f, leftHalf[i], 1e-4f)
        }
    }

    @Test
    fun outOfRangeFractionsClamp() {
        val (_, lowClamp) = playPauseMorphQuads(-1f)
        val (_, highClamp) = playPauseMorphQuads(2f)
        val (_, zero) = playPauseMorphQuads(0f)
        val (_, one) = playPauseMorphQuads(1f)

        assertArrayEquals(zero, lowClamp, 1e-4f)
        assertArrayEquals(one, highClamp, 1e-4f)
    }
}
