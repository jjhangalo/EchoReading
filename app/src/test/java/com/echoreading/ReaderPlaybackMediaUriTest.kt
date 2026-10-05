package com.echoreading

import android.net.Uri
import androidx.media3.common.MediaItem
import com.echoreading.voice.OfflineVoice
import com.echoreading.voice.VoiceOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderPlaybackMediaUriTest {

    @Test
    fun testFileToUriProducesOpaqueUriCausingExoPlayerCrash() {
        val testFile = File("/data/user/0/com.echoreading/cache/reading-audio/1/0.wav")
        val javaUriString = testFile.toURI().toString()

        // file.toURI().toString() produces "file:/..." (single slash, opaque URI)
        // In Android, Uri.parse("file:/...") returns an opaque URI where getPath() == null.
        // ExoPlayer's FileDataSource.open explicitly asserts checkNotNull(dataSpec.uri.getPath()),
        // crashing with NullPointerException during playback preparation.
        assertTrue("File.toURI().toString() produces single-slash URI", javaUriString.startsWith("file:/"))
        assertFalse("File.toURI().toString() is not hierarchical file:///", javaUriString.startsWith("file:///"))
    }

    @Test
    fun testOfflineVoicePtPtOptionConfiguration() {
        val ptPt = OfflineVoice.option("pt-PT")
        assertEquals("pt-PT", ptPt.id)
        assertEquals("pt_PT-tugao-medium.onnx", ptPt.modelFile)
        assertEquals(22050, ptPt.sampleRate)
        assertNotNull("pt-PT should provide fallback URL for environments lacking bundled assets", ptPt.url)
        assertEquals(63_201_425L, ptPt.fileSize)
        assertEquals("0d922da6f6fd87f981bb05fa8f698a1af6fc5c9366c212cdeb36a0f04c3c056d", ptPt.sha256)
    }

    @Test
    fun testOfflineVoiceFallbackDetectionWhenAssetsAbsent() {
        val voiceWithoutUrl = VoiceOption(
            id = "custom-uninstalled",
            label = "Custom",
            modelFile = "nonexistent.onnx",
            sampleRate = 16000,
            url = null,
        )
        // A voice that lacks bundled asset, lacks on-disk file, and has no download URL cannot be installed
        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getNoBackupFilesDir(): File = File(System.getProperty("java.io.tmpdir"), "fake-no-backup")
        }
        assertFalse(OfflineVoice.isInstalled(mockContext, voiceWithoutUrl))
    }

    @Test
    fun testOfflineVoiceSynthesizeThrowsWhenNoVoiceInstalled() {
        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getNoBackupFilesDir(): File = File(System.getProperty("java.io.tmpdir"), "fake-no-backup-${System.nanoTime()}")
            override fun getApplicationContext(): android.content.Context = this
        }
        try {
            OfflineVoice.synthesize(mockContext, "Teste de síntese", "nonexistent-voice")
            org.junit.Assert.fail("Synthesize should throw IllegalStateException when voice not installed")
        } catch (e: IllegalStateException) {
            assertTrue("Exception message indicates voice not installed", e.message?.contains("Voice not installed") == true)
        }
    }

    @Test
    fun testHierarchicalFileUriPreservesPath() {
        val testUri = android.net.TestUri("file:///data/user/0/com.echoreading/cache/reading-audio/1/0.wav")
        assertEquals("file", testUri.scheme)
        assertEquals("/data/user/0/com.echoreading/cache/reading-audio/1/0.wav", testUri.path)
        assertTrue(testUri.isHierarchical)
    }

    @Test
    fun testReaderPlaybackServiceFormatTitleEdgeCases() {
        val fallback = "Eco Leitura"
        assertEquals(fallback, com.echoreading.reader.ReaderPlaybackService.formatTitle("", fallback))
        assertEquals(fallback, com.echoreading.reader.ReaderPlaybackService.formatTitle("   \n\t  ", fallback))
        assertEquals("Hello World", com.echoreading.reader.ReaderPlaybackService.formatTitle("Hello   World", fallback))
    }

    @Test
    fun testIsInstalledRejectsZeroByteModelEvenWithZeroExpectedSize() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "zero-byte-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val mockContext = object : android.content.ContextWrapper(null) {
                override fun getNoBackupFilesDir(): File = tempDir
            }
            val voice = VoiceOption(
                id = "zero-voice",
                label = "Zero",
                modelFile = "zero.onnx",
                sampleRate = 22050,
                fileSize = 0L,
            )
            val modelDir = File(tempDir, "voices/zero-voice").apply { mkdirs() }
            val zeroFile = File(modelDir, "zero.onnx").apply { createNewFile() }
            assertEquals(0L, zeroFile.length())
            assertFalse("0-byte model must not be considered installed", OfflineVoice.isInstalled(mockContext, voice))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testIsInstalledDetectsModelInCatalogDirectory() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "catalog-dir-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val mockContext = object : android.content.ContextWrapper(null) {
                override fun getNoBackupFilesDir(): File = tempDir
            }
            val ptPt = OfflineVoice.option("pt-PT")
            val catalogDir = File(tempDir, "voices/pt_PT-tugao-medium").apply { mkdirs() }
            val modelFile = File(catalogDir, ptPt.modelFile).apply {
                writeBytes(ByteArray(1024))
            }
            // Temporarily test with a dummy option requiring 500 bytes
            val optionWithSmallerSize = ptPt.copy(fileSize = 500L)
            assertTrue("Model in catalog directory should be recognized", OfflineVoice.isInstalled(mockContext, optionWithSmallerSize))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
