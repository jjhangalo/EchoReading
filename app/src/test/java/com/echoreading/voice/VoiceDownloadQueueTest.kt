package com.echoreading.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceDownloadQueueTest {

    private val first = VoiceDownloadQueueItem(
        key = "pt_PT-tugao-medium",
        label = "Tugão",
        source = VoiceDownloadSource.LOCAL,
    )
    private val second = VoiceDownloadQueueItem(
        key = "pt_PT-edresson-medium",
        label = "Edresson",
        source = VoiceDownloadSource.CATALOG,
    )

    @Test
    fun `enqueue appends voices in request order`() {
        val firstState = VoiceDownloadState().enqueueQueuedVoice(first)
        val secondState = firstState?.enqueueQueuedVoice(second)

        assertEquals(listOf(first, second), secondState?.queued)
    }

    @Test
    fun `enqueue rejects the voice that is already downloading`() {
        val state = VoiceDownloadState(
            key = first.key,
            status = VoiceDownloadStatus.DOWNLOADING,
        )

        assertNull(state.enqueueQueuedVoice(first))
    }

    @Test
    fun `enqueue rejects duplicate queued voices without reordering`() {
        val state = VoiceDownloadState(queued = listOf(first, second))

        assertNull(state.enqueueQueuedVoice(first))
        assertEquals(listOf(first, second), state.queued)
    }

    @Test
    fun `enqueue does not mutate the previous state`() {
        val original = VoiceDownloadState(queued = listOf(first))
        val updated = original.enqueueQueuedVoice(second)

        assertEquals(listOf(first), original.queued)
        assertEquals(listOf(first, second), updated?.queued)
    }

    @Test
    fun `remove keeps the remaining FIFO order`() {
        val state = VoiceDownloadState(queued = listOf(first, second))

        val updated = state.removeQueuedVoice(first.key)

        assertEquals(listOf(second), updated?.queued)
    }

    @Test
    fun `remove reports a missing voice without changing the queue`() {
        val state = VoiceDownloadState(queued = listOf(first))

        assertNull(state.removeQueuedVoice(second.key))
        assertEquals(listOf(first), state.queued)
    }

    @Test
    fun `removing the only queued voice leaves an empty queue`() {
        val state = VoiceDownloadState(queued = listOf(first))

        val updated = state.removeQueuedVoice(first.key)

        assertEquals(emptyList<VoiceDownloadQueueItem>(), updated?.queued)
    }
}
