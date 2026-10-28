package com.echoreading.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppTextCardFollowTest {
    @Test
    fun spokenLineNearBottomIsPlacedAboveFollowingContext() {
        val target = followScrollTarget(
            lineTop = 980f,
            lineBottom = 1004f,
            currentScroll = 0,
            viewportHeight = 400,
            maxScroll = 2000,
        )

        assertEquals(847, target)
    }

    @Test
    fun spokenLineAlreadyVisibleDoesNotMoveTheView() {
        val target = followScrollTarget(
            lineTop = 120f,
            lineBottom = 144f,
            currentScroll = 0,
            viewportHeight = 400,
            maxScroll = 2000,
        )

        assertNull(target)
    }

    @Test
    fun userLookingAheadIsNotPulledBackToTheSpokenLine() {
        val target = followScrollTarget(
            lineTop = 120f,
            lineBottom = 144f,
            currentScroll = 500,
            viewportHeight = 400,
            maxScroll = 2000,
        )

        assertNull(target)
    }

    @Test
    fun targetIsClampedToTheEndOfTheText() {
        val target = followScrollTarget(
            lineTop = 200f,
            lineBottom = 224f,
            currentScroll = 0,
            viewportHeight = 100,
            maxScroll = 100,
        )

        assertEquals(100, target)
    }

    @Test
    fun emptyViewportDoesNotScroll() {
        val target = followScrollTarget(
            lineTop = 200f,
            lineBottom = 224f,
            currentScroll = 0,
            viewportHeight = 0,
            maxScroll = 100,
        )

        assertNull(target)
    }
}
