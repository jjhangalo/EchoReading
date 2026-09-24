package com.echoreading

import com.echoreading.reader.AudioTimeline
import com.echoreading.reader.ReadingChunks
import com.echoreading.reader.WavFiles
import com.k2fsa.sherpa.onnx.GeneratedAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReadingChunksTest {
    @Test fun longTextRemainsOrderedAndBounded() {
        val text = "Primeira frase. " + "Uma palavra comprida ".repeat(50)
        val chunks = ReadingChunks.split(text)
        assertEquals(text.trim(), chunks.joinToString(" ") { it.text }.trim())
        assertTrue(chunks.all { it.text.length <= 280 })
        assertTrue(chunks.zipWithNext().all { (a, b) -> a.start < b.start })
    }

    @Test fun seeksCrossPreparedChunkBoundaries() {
        val timeline = AudioTimeline()
        timeline.add(6_000)
        timeline.add(9_000)
        assertEquals(1 to 4_000L, timeline.locate(10_000))
        assertEquals(0 to 0L, timeline.locate(-10))
        assertEquals(1 to 9_000L, timeline.locate(50_000))
        assertEquals(10_000L, timeline.globalPosition(1, 4_000))
    }

    @Test fun generatedSamplesBecomeSeekablePcm() {
        val file = File.createTempFile("eco-reading", ".wav")
        try {
            assertEquals(1_000L, WavFiles.write(file, GeneratedAudio(FloatArray(22_050), 22_050)))
            val bytes = file.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertEquals(22_050, ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int)
            assertEquals(44 + 44_100, bytes.size)
        } finally {
            file.delete()
        }
    }
}
