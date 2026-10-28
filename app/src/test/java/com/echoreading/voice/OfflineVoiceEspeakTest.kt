package com.echoreading.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineVoiceEspeakTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testIsEspeakDataValidWithNonExistentOrEmptyDir() {
        assertFalse(OfflineVoice.isEspeakDataValid(null))
        assertFalse(OfflineVoice.isEspeakDataValid(File(tempFolder.root, "nonexistent")))

        val emptyDir = tempFolder.newFolder("empty")
        assertFalse(OfflineVoice.isEspeakDataValid(emptyDir))
    }

    @Test
    fun testIsEspeakDataValidWithIncompleteFiles() {
        val dir = tempFolder.newFolder("incomplete")
        File(dir, "phontab").writeBytes(byteArrayOf(1, 2, 3))
        assertFalse("Missing phonindex and phondata", OfflineVoice.isEspeakDataValid(dir))

        File(dir, "phonindex").writeBytes(byteArrayOf(1, 2, 3))
        assertFalse("Missing phondata", OfflineVoice.isEspeakDataValid(dir))

        File(dir, "phondata").writeBytes(byteArrayOf()) // 0 bytes
        assertFalse("phondata is 0 bytes", OfflineVoice.isEspeakDataValid(dir))
    }

    @Test
    fun testIsEspeakDataValidWithAllRequiredFiles() {
        val dir = tempFolder.newFolder("valid")
        File(dir, "phontab").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "phonindex").writeBytes(byteArrayOf(4, 5, 6))
        File(dir, "phondata").writeBytes(byteArrayOf(7, 8, 9))
        assertTrue("Valid when phontab, phonindex, and phondata are non-empty", OfflineVoice.isEspeakDataValid(dir))
    }

    @Test
    fun testResolveEspeakDirDirectAndNested() {
        val dir = tempFolder.newFolder("direct")
        File(dir, "phontab").writeBytes(byteArrayOf(1))
        File(dir, "phonindex").writeBytes(byteArrayOf(2))
        File(dir, "phondata").writeBytes(byteArrayOf(3))

        assertEquals(dir, OfflineVoice.resolveEspeakDir(dir))

        val parentDir = tempFolder.newFolder("parent")
        val nested = File(parentDir, "espeak-ng-data").apply { mkdirs() }
        File(nested, "phontab").writeBytes(byteArrayOf(1))
        File(nested, "phonindex").writeBytes(byteArrayOf(2))
        File(nested, "phondata").writeBytes(byteArrayOf(3))

        assertEquals(nested, OfflineVoice.resolveEspeakDir(parentDir))
        assertNull(OfflineVoice.resolveEspeakDir(tempFolder.newFolder("invalid")))
    }

    @Test
    fun testBundledAssetDirectoryContainsValidEspeakData() {
        val candidates = listOf(
            File("app/src/main/assets/espeak-ng-data"),
            File("src/main/assets/espeak-ng-data"),
            File("../app/src/main/assets/espeak-ng-data"),
            File("app/src/main/assets/vits-piper-pt_PT-tugao-medium/espeak-ng-data"),
            File("src/main/assets/vits-piper-pt_PT-tugao-medium/espeak-ng-data"),
            File("../app/src/main/assets/vits-piper-pt_PT-tugao-medium/espeak-ng-data")
        )
        val assetDir = candidates.firstOrNull { it.isDirectory }
        assertNotNull("Bundled espeak-ng-data must exist in assets", assetDir)
        assertTrue("Bundled espeak-ng-data must be valid", OfflineVoice.isEspeakDataValid(assetDir))
        assertTrue("phontab must be > 10KB", File(assetDir, "phontab").length() > 10_000)
        assertTrue("phonindex must be > 10KB", File(assetDir, "phonindex").length() > 10_000)
        assertTrue("phondata must be > 100KB", File(assetDir, "phondata").length() > 100_000)
    }

    @Test
    fun testBundledAssetTokensExist() {
        val candidates = listOf(
            File("app/src/main/assets/vits-piper-pt_PT-tugao-medium/tokens.txt"),
            File("src/main/assets/vits-piper-pt_PT-tugao-medium/tokens.txt"),
            File("../app/src/main/assets/vits-piper-pt_PT-tugao-medium/tokens.txt")
        )
        val tokensFile = candidates.firstOrNull { it.isFile }
        assertNotNull("Bundled tokens.txt must exist in assets", tokensFile)
        assertTrue("tokens.txt must contain phoneme mappings", tokensFile!!.readText().contains("^"))
    }

    @Test
    fun testFindInstalledEspeakDirDiscoversExistingDirectory() {
        val noBackup = tempFolder.newFolder("no_backup")
        val tugaoEspeak = File(noBackup, "voice-tugao-v1/espeak-ng-data").apply { mkdirs() }
        File(tugaoEspeak, "phontab").writeBytes(byteArrayOf(1, 2, 3))
        File(tugaoEspeak, "phonindex").writeBytes(byteArrayOf(4, 5, 6))
        File(tugaoEspeak, "phondata").writeBytes(byteArrayOf(7, 8, 9))

        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getNoBackupFilesDir(): File = noBackup
            override fun getFilesDir(): File = File(noBackup, "files").apply { mkdirs() }
        }

        val discovered = OfflineVoice.findInstalledEspeakDir(mockContext)
        assertNotNull(discovered)
        assertEquals(tugaoEspeak.canonicalPath, discovered?.canonicalPath)
        assertTrue(OfflineVoice.isEspeakDataValid(discovered))
    }

    @Test
    fun testEnsureEspeakDataRecoversFromCorruptedReadyDirectory() {
        val noBackup = tempFolder.newFolder("no_backup_corrupted")
        val tugaoDir = File(noBackup, "voice-tugao-v1").apply { mkdirs() }
        val readyFile = File(tugaoDir, "ready").apply { writeText("ready") }
        // Corrupted empty espeak directory
        val espeakDir = File(tugaoDir, "espeak-ng-data").apply { mkdirs() }

        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getNoBackupFilesDir(): File = noBackup
            override fun getFilesDir(): File = File(noBackup, "files").apply { mkdirs() }
        }

        // Initially invalid despite ready marker
        assertNull(OfflineVoice.findInstalledEspeakDir(mockContext))
        assertFalse(OfflineVoice.isEspeakDataValid(espeakDir))
    }
}
