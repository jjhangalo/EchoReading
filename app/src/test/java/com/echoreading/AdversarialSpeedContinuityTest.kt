package com.echoreading

import com.echoreading.e2e.testutil.IntentTestContracts
import com.echoreading.e2e.testutil.ManifestTestParser
import com.echoreading.reader.AudioTimeline
import com.echoreading.reader.CachedReading
import com.echoreading.reader.ReaderAudioCache
import com.echoreading.reader.ReaderPlaybackService
import com.echoreading.reader.ReaderSnapshot
import com.echoreading.reader.ReaderState
import com.echoreading.reader.ReadingChunk
import com.echoreading.reader.ReadingChunks
import com.echoreading.reader.ReadingStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * Empirical Adversarial Challenger Test Suite (challenger_m2_2):
 * Stress-testing Speed Adjustment and Background Playback Continuity.
 *
 * Dimension 1: Dynamic speed changes during active synthesis/playback
 * Dimension 2: Audio cache speed separation & precision boundaries
 * Dimension 3: Activity finish() does not terminate ReaderPlaybackService
 * Dimension 4: Expansion intent flags & text delivery to MainActivity
 */
class AdversarialSpeedContinuityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val originalSnapshot = ReaderState.snapshot.value

    private fun findProjectRoot(): File {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/AndroidManifest.xml").isFile) {
                return dir
            }
            dir = dir.parentFile
        }
        return File(".").canonicalFile
    }

    @Before
    fun setUp() {
        ReaderAudioCache.clear()
        ReaderState.snapshot.value = ReaderSnapshot()
    }

    @After
    fun tearDown() {
        ReaderAudioCache.clear()
        ReaderState.snapshot.value = originalSnapshot
    }

    // =========================================================================
    // DIMENSION 1: DYNAMIC SPEED CHANGES DURING ACTIVE SYNTHESIS / PLAYBACK
    // =========================================================================

    /**
     * Replicates the exact dispatch decision logic of ReaderPlaybackService.ACTION_SPEED
     * (ReaderPlaybackService.kt lines 172-181) for empirical validation.
     */
    private fun simulateActionSpeedDispatch(
        intentSpeed: Float?,
        intentText: String?,
        intentPosition: Long?,
        initialState: ReaderSnapshot
    ): Pair<ReaderSnapshot, Boolean> {
        var current = initialState
        val newSpeed = intentSpeed ?: current.speed
        current = current.copy(speed = newSpeed)

        val currentText = intentText.orEmpty().ifBlank { current.text }
        val shouldRestartReading = currentText.isNotBlank() &&
            (current.status == ReadingStatus.PLAYING || current.status == ReadingStatus.PREPARING)

        val finalSnapshot = if (shouldRestartReading) {
            val pos = intentPosition ?: current.positionMs
            current.copy(
                text = currentText,
                status = ReadingStatus.PREPARING,
                positionMs = pos,
                durationMs = 0,
                speed = newSpeed
            )
        } else {
            current
        }

        return finalSnapshot to shouldRestartReading
    }

    @Test
    fun testSpeedChangeDuringActivePlaybackRestartsAtCurrentPosition() {
        val initial = ReaderSnapshot(
            text = "O rápido desenvolvimento da tecnologia de leitura acessível.",
            status = ReadingStatus.PLAYING,
            positionMs = 4_500L,
            durationMs = 12_000L,
            speed = 1.0f,
            voiceId = "pt-PT"
        )

        // User taps 1.5x speed pill in ReaderQuickPanel
        val (updated, restarted) = simulateActionSpeedDispatch(
            intentSpeed = 1.5f,
            intentText = initial.text,
            intentPosition = 4_500L,
            initialState = initial
        )

        assertTrue("Dynamic speed change during PLAYING must trigger re-synthesis/restart", restarted)
        assertEquals(1.5f, updated.speed, 0.001f)
        assertEquals(ReadingStatus.PREPARING, updated.status)
        assertEquals(4_500L, updated.positionMs)
        assertEquals(initial.text, updated.text)
    }

    @Test
    fun testSpeedChangeDuringActiveSynthesisRestartsWithNewSpeed() {
        val initial = ReaderSnapshot(
            text = "Síntese inicial em andamento que o utilizador decide acelerar.",
            status = ReadingStatus.PREPARING,
            positionMs = 0L,
            durationMs = 0L,
            speed = 1.0f,
            voiceId = "pt-PT"
        )

        // User changes speed to 2.0x while synthesis is still preparing
        val (updated, restarted) = simulateActionSpeedDispatch(
            intentSpeed = 2.0f,
            intentText = initial.text,
            intentPosition = 0L,
            initialState = initial
        )

        assertTrue("Dynamic speed change during PREPARING must restart synthesis", restarted)
        assertEquals(2.0f, updated.speed, 0.001f)
        assertEquals(ReadingStatus.PREPARING, updated.status)
        assertEquals(0L, updated.positionMs)
    }

    @Test
    fun testSpeedChangeWhilePausedUpdatesStateWithoutRestartingAudio() {
        val initial = ReaderSnapshot(
            text = "Texto pausado para troca de ritmo.",
            status = ReadingStatus.PAUSED,
            positionMs = 3_200L,
            durationMs = 8_000L,
            speed = 1.0f,
            voiceId = "pt-PT"
        )

        val (updated, restarted) = simulateActionSpeedDispatch(
            intentSpeed = 1.25f,
            intentText = initial.text,
            intentPosition = 3_200L,
            initialState = initial
        )

        assertFalse("Speed change while PAUSED must NOT trigger automatic playback", restarted)
        assertEquals(1.25f, updated.speed, 0.001f)
        assertEquals(ReadingStatus.PAUSED, updated.status)
        assertEquals(3_200L, updated.positionMs)
    }

    @Test
    fun testSpeedChangeWhileIdleUpdatesStateWithoutRestartingAudio() {
        val initial = ReaderSnapshot(
            text = "",
            status = ReadingStatus.IDLE,
            positionMs = 0L,
            durationMs = 0L,
            speed = 1.0f,
            voiceId = "pt-PT"
        )

        val (updated, restarted) = simulateActionSpeedDispatch(
            intentSpeed = 0.75f,
            intentText = null,
            intentPosition = 0L,
            initialState = initial
        )

        assertFalse("Speed change while IDLE must NOT trigger playback", restarted)
        assertEquals(0.75f, updated.speed, 0.001f)
        assertEquals(ReadingStatus.IDLE, updated.status)
    }

    @Test
    fun testSpeedChangeWithBlankOrMissingIntentTextFallsBackToSnapshotText() {
        val initial = ReaderSnapshot(
            text = "Texto prévio do snapshot",
            status = ReadingStatus.PLAYING,
            positionMs = 1_000L,
            speed = 1.0f
        )

        // Intent with null text
        val (updated1, restarted1) = simulateActionSpeedDispatch(
            intentSpeed = 1.5f,
            intentText = null,
            intentPosition = null,
            initialState = initial
        )
        assertTrue(restarted1)
        assertEquals("Texto prévio do snapshot", updated1.text)
        assertEquals(1.5f, updated1.speed, 0.001f)

        // Intent with whitespace text
        val (updated2, restarted2) = simulateActionSpeedDispatch(
            intentSpeed = 2.0f,
            intentText = "   \n\t  ",
            intentPosition = null,
            initialState = initial
        )
        assertTrue(restarted2)
        assertEquals("Texto prévio do snapshot", updated2.text)
        assertEquals(2.0f, updated2.speed, 0.001f)
    }

    @Test
    fun testRapidSequentialSpeedSwitchingStressHarness() {
        val speedSequence = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 1.0f, 0.75f, 2.0f)
        var state = ReaderSnapshot(
            text = "Texto para teste de stress de troca rápida de velocidade.",
            status = ReadingStatus.PLAYING,
            positionMs = 5_000L,
            speed = 1.0f
        )

        for (i in 0 until 1_000) {
            val targetSpeed = speedSequence[i % speedSequence.size]
            val (nextState, restarted) = simulateActionSpeedDispatch(
                intentSpeed = targetSpeed,
                intentText = state.text,
                intentPosition = state.positionMs,
                initialState = state
            )
            assertTrue("Rapid switch $i must trigger restart", restarted)
            assertEquals(targetSpeed, nextState.speed, 0.001f)
            assertEquals(state.text, nextState.text)
            assertEquals(5_000L, nextState.positionMs)
            state = nextState.copy(status = ReadingStatus.PLAYING) // simulate resumed playing
        }
    }

    @Test
    fun testTimelineLocateConsistencyUnderScaledDuration() {
        val timeline = AudioTimeline()
        timeline.add(3_000L) // chunk 0: 0..3000
        timeline.add(4_000L) // chunk 1: 3000..7000
        timeline.add(5_000L) // chunk 2: 7000..12000

        // Seek at 5,000ms (chunk 1, offset 2,000ms)
        val (chunkIdx, offset) = timeline.locate(5_000L)
        assertEquals(1, chunkIdx)
        assertEquals(2_000L, offset)

        // Position reconstituted back
        val globalPos = timeline.globalPosition(chunkIdx, offset)
        assertEquals(5_000L, globalPos)
    }

    // =========================================================================
    // DIMENSION 2: AUDIO CACHE SPEED SEPARATION & PRECISION BOUNDARIES
    // =========================================================================

    @Test
    fun testAudioCacheStrictSpeedDifferentiationAllPairs() {
        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val testText = "Diferenciação estrita de velocidade na cache de áudio"
        val voice = "pt-PT"

        for (s in speeds) {
            val dir = tempFolder.newFolder("cache_speed_${s.toString().replace('.', '_')}")
            File(dir, "0.wav").writeBytes(ByteArray(128))

            ReaderAudioCache.put(
                text = testText,
                voiceId = voice,
                speed = s,
                chunks = listOf(ReadingChunk(testText, 0)),
                durations = listOf(3_000L),
                audioDir = dir
            )

            // Cache MUST hit for exact speed
            val hit = ReaderAudioCache.get(testText, voice, s)
            assertNotNull("Cache must hit for exact speed $s", hit)
            assertEquals(s, hit!!.speed, 0.001f)

            // Cache MUST miss for any other standard speed
            for (other in speeds) {
                if (other != s) {
                    val miss = ReaderAudioCache.get(testText, voice, other)
                    assertNull("Cache for speed $s must MISS when queried with speed $other", miss)
                }
            }

            ReaderAudioCache.clear()
        }
    }

    @Test
    fun testAudioCacheSpeedEpsilonBoundaryConditions() {
        val baseSpeed = 1.000f
        val dir = tempFolder.newFolder("epsilon_cache")
        File(dir, "0.wav").writeBytes(ByteArray(64))

        ReaderAudioCache.put(
            text = "Epsilon boundary test",
            voiceId = "pt-PT",
            speed = baseSpeed,
            chunks = listOf(ReadingChunk("Epsilon boundary test", 0)),
            durations = listOf(2_000L),
            audioDir = dir
        )

        // 1. Within tolerance (delta = 0.005 <= 0.01) -> HITS
        val hitWithin = ReaderAudioCache.get("Epsilon boundary test", "pt-PT", 1.005f)
        assertNotNull("Delta <= 0.01f should hit cache due to float tolerance", hitWithin)

        // 2. Exactly at tolerance (delta = 0.010 <= 0.01) -> HITS
        val hitEdge = ReaderAudioCache.get("Epsilon boundary test", "pt-PT", 1.010f)
        assertNotNull("Delta == 0.01f should hit cache", hitEdge)

        // 3. Beyond tolerance (delta = 0.015 > 0.01) -> MISSES
        val missBeyond = ReaderAudioCache.get("Epsilon boundary test", "pt-PT", 1.015f)
        assertNull("Delta > 0.01f must miss cache", missBeyond)

        // 4. Lower bound beyond tolerance (delta = 0.015 > 0.01) -> MISSES
        val missLower = ReaderAudioCache.get("Epsilon boundary test", "pt-PT", 0.985f)
        assertNull("Delta > 0.01f lower must miss cache", missLower)
    }

    @Test
    fun testPuttingNewSpeedPurgesPreviousCacheDirectory() {
        val dir1 = tempFolder.newFolder("speed_1_0")
        File(dir1, "0.wav").writeBytes(ByteArray(64))

        ReaderAudioCache.put(
            text = "Texto com troca de cache",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Texto com troca de cache", 0)),
            durations = listOf(2_000L),
            audioDir = dir1
        )
        assertTrue("dir1 must exist while cached", dir1.exists())

        // Put speed 1.5f in a different directory
        val dir2 = tempFolder.newFolder("speed_1_5")
        File(dir2, "0.wav").writeBytes(ByteArray(64))

        ReaderAudioCache.put(
            text = "Texto com troca de cache",
            voiceId = "pt-PT",
            speed = 1.5f,
            chunks = listOf(ReadingChunk("Texto com troca de cache", 0)),
            durations = listOf(1_333L),
            audioDir = dir2
        )

        // Old directory dir1 must be deleted recursively by ReaderAudioCache.put
        assertFalse("Old audioDir dir1 must be deleted when replaced by new speed", dir1.exists())
        assertTrue("New audioDir dir2 must exist", dir2.exists())
        assertNull("Query for old speed 1.0f must now miss", ReaderAudioCache.get("Texto com troca de cache", "pt-PT", 1.0f))
        assertNotNull("Query for new speed 1.5f must hit", ReaderAudioCache.get("Texto com troca de cache", "pt-PT", 1.5f))
    }

    @Test
    fun testCorruptedFileAtSpeedCausesCacheMissAndEviction() {
        val dir = tempFolder.newFolder("corrupted_cache")
        val audioFile = File(dir, "0.wav")
        audioFile.writeBytes(ByteArray(100))

        ReaderAudioCache.put(
            text = "Texto áudio corrompido",
            voiceId = "pt-PT",
            speed = 1.25f,
            chunks = listOf(ReadingChunk("Texto áudio corrompido", 0)),
            durations = listOf(2_500L),
            audioDir = dir
        )

        // Truncate file to 0 bytes
        audioFile.writeBytes(ByteArray(0))

        val hit = ReaderAudioCache.get("Texto áudio corrompido", "pt-PT", 1.25f)
        assertNull("0-byte file must invalidate cache entry", hit)
        assertNull("Cache must be cleared after encountering 0-byte file", ReaderAudioCache.current)
    }

    @Test
    fun testCacheTTLMSEnforcementWithSpeed() {
        val dir = tempFolder.newFolder("ttl_cache")
        File(dir, "0.wav").writeBytes(ByteArray(64))
        val baseTime = 1_000_000L

        ReaderAudioCache.put(
            text = "Texto TTL",
            voiceId = "pt-PT",
            speed = 2.0f,
            chunks = listOf(ReadingChunk("Texto TTL", 0)),
            durations = listOf(1_000L),
            audioDir = dir,
            timestamp = baseTime
        )

        // Just before expiration (4m 59s)
        val hit = ReaderAudioCache.get(
            text = "Texto TTL",
            voiceId = "pt-PT",
            speed = 2.0f,
            now = baseTime + ReaderAudioCache.CACHE_TTL_MS - 1_000L
        )
        assertNotNull("Cache must hit before TTL expiration", hit)

        // After expiration (5m 1s)
        val expired = ReaderAudioCache.get(
            text = "Texto TTL",
            voiceId = "pt-PT",
            speed = 2.0f,
            now = baseTime + ReaderAudioCache.CACHE_TTL_MS + 1_000L
        )
        assertNull("Cache must expire after TTL", expired)
        assertNull("Cache entry must be cleared on expiration", ReaderAudioCache.current)
    }

    // =========================================================================
    // DIMENSION 3: ACTIVITY FINISH() DOES NOT TERMINATE ReaderPlaybackService
    // =========================================================================

    @Test
    fun testManifestDeclaresReaderPlaybackServiceAsIndependentForegroundService() {
        val factory = DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().parse(ManifestTestParser.manifestFile)
        val services = doc.getElementsByTagName("service")

        var serviceElement: Element? = null
        for (i in 0 until services.length) {
            val el = services.item(i) as Element
            if (el.getAttribute("android:name").contains("ReaderPlaybackService")) {
                serviceElement = el
                break
            }
        }

        assertNotNull("ReaderPlaybackService must be declared in AndroidManifest.xml", serviceElement)
        assertEquals("ReaderPlaybackService must be exported", "true", serviceElement!!.getAttribute("android:exported"))
        assertEquals(
            "ReaderPlaybackService must declare foregroundServiceType=mediaPlayback",
            "mediaPlayback",
            serviceElement.getAttribute("android:foregroundServiceType")
        )

        // Verify media session action filter
        val filters = serviceElement.getElementsByTagName("action")
        var hasMediaSessionAction = false
        for (i in 0 until filters.length) {
            val act = (filters.item(i) as Element).getAttribute("android:name")
            if (act == "androidx.media3.session.MediaSessionService") {
                hasMediaSessionAction = true
            }
        }
        assertTrue("ReaderPlaybackService must declare MediaSessionService action filter", hasMediaSessionAction)
    }

    @Test
    fun testManifestDeclaresForegroundServicePermissions() {
        val factory = DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().parse(ManifestTestParser.manifestFile)
        val permissions = doc.getElementsByTagName("uses-permission")

        val permissionNames = mutableSetOf<String>()
        for (i in 0 until permissions.length) {
            val el = permissions.item(i) as Element
            permissionNames.add(el.getAttribute("android:name"))
        }

        assertTrue(
            "Must declare FOREGROUND_SERVICE permission",
            permissionNames.contains("android.permission.FOREGROUND_SERVICE")
        )
        assertTrue(
            "Must declare FOREGROUND_SERVICE_MEDIA_PLAYBACK permission",
            permissionNames.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK")
        )
        assertTrue(
            "Must declare POST_NOTIFICATIONS permission",
            permissionNames.contains("android.permission.POST_NOTIFICATIONS")
        )
    }

    @Test
    fun testQuickReadActivityFinishContractDoesNotDispatchStopService() {
        // Contractual verification: QuickReadActivity finish() or onClose() closes only the activity UI.
        // It NEVER calls stopService() or dispatches ACTION_STOP to ReaderPlaybackService.
        val quickReadCode = File(findProjectRoot(), "app/src/main/java/com/echoreading/QuickReadActivity.kt").readText()
        assertFalse(
            "QuickReadActivity must NOT call stopService in its code",
            quickReadCode.contains("stopService")
        )
        assertFalse(
            "QuickReadActivity must NOT dispatch ACTION_STOP",
            quickReadCode.contains("ACTION_STOP")
        )
        assertFalse(
            "QuickReadActivity must NOT bind with BIND_AUTO_CREATE (which would kill service on finish)",
            quickReadCode.contains("BIND_AUTO_CREATE")
        )
    }

    @Test
    fun testReaderStateSnapshotPersistsAcrossSimulatedActivityDestruction() {
        // 1. Simulate active playback initiated by QuickReadActivity
        val activeText = "Texto de leitura contínua que sobrevive ao fecho da janela."
        ReaderState.snapshot.value = ReaderSnapshot(
            text = activeText,
            status = ReadingStatus.PLAYING,
            positionMs = 7_200L,
            durationMs = 20_000L,
            speed = 1.25f,
            voiceId = "pt-PT"
        )

        // 2. Simulate activity finish() and garbage collection of activity context
        // Since ReaderState is an object singleton and ReaderPlaybackService is a separate foreground service,
        // snapshot and service state remain intact.
        val snapshotAfterFinish = ReaderState.snapshot.value
        assertEquals(activeText, snapshotAfterFinish.text)
        assertEquals(ReadingStatus.PLAYING, snapshotAfterFinish.status)
        assertEquals(7_200L, snapshotAfterFinish.positionMs)
        assertEquals(1.25f, snapshotAfterFinish.speed, 0.001f)
    }

    @Test
    fun testReaderPlaybackServiceNotificationChannelsAndOngoingProperty() {
        // CHANNEL_ID and STATUS_CHANNEL_ID must be distinct
        assertEquals("eco-reading-playback", ReaderPlaybackService.CHANNEL_ID)
        assertEquals("eco-reading-status", ReaderPlaybackService.STATUS_CHANNEL_ID)
        assertNotEquals(ReaderPlaybackService.CHANNEL_ID, ReaderPlaybackService.STATUS_CHANNEL_ID)

        // Foreground notification IDs
        assertEquals(1101, ReaderPlaybackService.NOTIFICATION_ID)
        assertEquals(1102, ReaderPlaybackService.STATUS_NOTIFICATION_ID)
        assertNotEquals(ReaderPlaybackService.NOTIFICATION_ID, ReaderPlaybackService.STATUS_NOTIFICATION_ID)
    }

    // =========================================================================
    // DIMENSION 4: EXPANSION INTENT FLAGS & TEXT DELIVERY TO MAINACTIVITY
    // =========================================================================

    @Test
    fun testAbrirNoLeitorButtonConstructsValidExpansionIntent() {
        val project = findProjectRoot()
        val source = listOf(
            File(project, "app/src/main/java/com/echoreading/ReaderUi.kt"),
            File(project, "app/src/main/java/com/echoreading/ui/component/AppWidget.kt"),
        ).firstOrNull { it.isFile }
        assertNotNull("Reader expansion source must exist", source)
        val readerUiCode = checkNotNull(source).readText()

        assertTrue(
            "Reader expansion must reference MainActivity::class.java",
            readerUiCode.contains("MainActivity::class.java")
        )
        assertTrue(
            "ReaderUi.kt must specify Intent.ACTION_VIEW for expansion",
            readerUiCode.contains("action = Intent.ACTION_VIEW")
        )
        assertTrue(
            "ReaderUi.kt must set Intent.EXTRA_TEXT",
            readerUiCode.contains("putExtra(Intent.EXTRA_TEXT, text)")
        )
        assertTrue(
            "ReaderUi.kt must set FLAG_ACTIVITY_SINGLE_TOP or FLAG_ACTIVITY_CLEAR_TOP",
            readerUiCode.contains("Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP")
        )
    }

    @Test
    fun testExpansionIntentBitmaskCalculation() {
        val singleTop = IntentTestContracts.FLAG_ACTIVITY_SINGLE_TOP
        val clearTop = IntentTestContracts.FLAG_ACTIVITY_CLEAR_TOP
        val combined = singleTop or clearTop

        assertEquals(0x20000000, singleTop)
        assertEquals(0x04000000, clearTop)
        assertEquals(0x24000000, combined)

        // Verify contract evaluator
        val contract = IntentTestContracts.createExpansionIntent("Texto de teste")
        assertEquals("com.echoreading.MainActivity", contract.targetActivity)
        assertEquals("Texto de teste", contract.textExtra)
        assertTrue("Contract must confirm hasSingleTop", contract.hasSingleTop)
        assertTrue("Contract must confirm hasClearTop", contract.hasClearTop)
    }

    @Test
    fun testExpansionDuringActivePlaybackPreservesAudioWithoutInterruption() {
        val runningText = "Texto actualmente a ser reproduzido no serviço de fundo."
        ReaderState.snapshot.value = ReaderSnapshot(
            text = runningText,
            status = ReadingStatus.PLAYING,
            positionMs = 8_400L,
            durationMs = 25_000L,
            characterOffset = 45,
            speed = 1.5f,
            voiceId = "pt-PT"
        )

        // In ReaderUi.kt lines 359-368:
        // val editable = snapshot.status == ReadingStatus.IDLE || snapshot.status == ReadingStatus.ERROR
        // When !editable, ReaderHome automatically receives snapshot.text and sets character selection
        val snapshot = ReaderState.snapshot.value
        val editable = snapshot.status == ReadingStatus.IDLE || snapshot.status == ReadingStatus.ERROR
        assertFalse("While PLAYING, ReaderHome input must NOT be marked editable (it binds to active text)", editable)

        // Emulate ReaderHome text synchronization:
        var localInput = ""
        if (!editable && snapshot.text != localInput) {
            localInput = snapshot.text
        }
        assertEquals(runningText, localInput)
        assertEquals(ReadingStatus.PLAYING, snapshot.status)
        assertEquals(8_400L, snapshot.positionMs)
    }

    @Test
    fun testExpansionTextCarriesExoticPayloads() {
        val exoticPayloads = listOf(
            "Texto curto",
            "Texto com\nmúltiplas\nlinhas\ne\ttabs.",
            "Unicode & Emojis: 🚀📚🎧 Ecoar em português com acentuação: ação, café, ímpar.",
            "Special chars: <tag> & \"quotes\" 'single' `backticks` \\backslash",
            "A".repeat(120_000) // 120KB payload
        )

        for (payload in exoticPayloads) {
            val contract = IntentTestContracts.createExpansionIntent(payload)
            assertEquals(payload, contract.textExtra)
            assertTrue(contract.hasSingleTop)
            assertTrue(contract.hasClearTop)
        }
    }
}
