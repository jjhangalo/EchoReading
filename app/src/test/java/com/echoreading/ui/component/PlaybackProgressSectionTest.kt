package com.echoreading.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressSectionTest {
    @Test
    fun interpolationAdvancesInRealTime() {
        // The generated audio already contains the requested synthesis speed, so
        // one second of wall time is one second of media time.
        val position = interpolatedPlaybackPositionMs(
            anchorPositionMs = 10_000L,
            anchorTimeNanos = 1_000_000_000L,
            nowNanos = 1_500_000_000L,
            durationMs = 60_000L,
        )
        assertEquals(10_500L, position)
    }

    @Test
    fun interpolationIsBoundedWhenNoFreshSampleArrives() {
        val position = interpolatedPlaybackPositionMs(
            anchorPositionMs = 10_000L,
            anchorTimeNanos = 1_000_000_000L,
            nowNanos = 20_000_000_000L,
            durationMs = 60_000L,
        )
        assertEquals(11_000L, position)
    }

    @Test
    fun interpolationClampsToDurationAndHandlesMissingDuration() {
        assertEquals(
            5_000L,
            interpolatedPlaybackPositionMs(
                anchorPositionMs = 5_000L,
                anchorTimeNanos = 1_000_000_000L,
                nowNanos = 2_000_000_000L,
                durationMs = 5_000L,
            ),
        )
        assertEquals(
            3_000L,
            interpolatedPlaybackPositionMs(
                anchorPositionMs = 3_000L,
                anchorTimeNanos = 1_000_000_000L,
                nowNanos = 2_000_000_000L,
                durationMs = 0L,
            ),
        )
    }
}
