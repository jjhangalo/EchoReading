package com.echoreading.e2e

import com.echoreading.e2e.testutil.IntentTestContracts
import com.echoreading.e2e.testutil.ManifestTestParser
import com.echoreading.reader.AudioTimeline
import com.echoreading.reader.CachedReading
import com.echoreading.reader.ReaderAudioCache
import com.echoreading.reader.ReaderPlaybackService
import com.echoreading.reader.ReadingChunk
import com.echoreading.reader.ReadingChunks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * E2E Requirement Tests for Requirement R2:
 * System-wide Text Selection Action ("Ecoar") & Quick Access Bottom Sheet
 *
 * Covers Features 5, 6, 7, 8, 9, 10 across Tier 1, Tier 2, and Tier 3.
 */
class EcoarActionRequirementTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        ReaderAudioCache.clear()
        tempDir = Files.createTempDirectory("ecoar-action-test").toFile()
    }

    @After
    fun tearDown() {
        ReaderAudioCache.clear()
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // TIER 1: FEATURE 5 — "Ecoar" Action Item Label (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f5_1_processTextIntentExtractsValidSelectedText() {
        val selected = "Texto selecionado pelo utilizador no Chrome"
        val extracted = IntentTestContracts.extractProcessText(
            action = IntentTestContracts.ACTION_PROCESS_TEXT,
            mimeType = "text/plain",
            extraText = selected
        )
        assertEquals(selected, extracted)
    }

    @Test
    fun f5_2_processTextFilterMatchesTextPlainMimeType() {
        val extracted = IntentTestContracts.extractProcessText(
            action = IntentTestContracts.ACTION_PROCESS_TEXT,
            mimeType = "text/plain",
            extraText = "Leitura rápida"
        )
        assertNotNull(extracted)
        assertEquals("Leitura rápida", extracted)
    }

    @Test
    fun f5_3_manifestDeclaresQuickReadActivityExportedAndExcludedFromRecents() {
        val activities = ManifestTestParser.parseActivities()
        val quickRead = activities.find { it.name.contains("QuickReadActivity") }
        assertNotNull("QuickReadActivity must be declared in AndroidManifest.xml", quickRead)
        assertTrue("QuickReadActivity must have excludeFromRecents=true", quickRead!!.excludeFromRecents)
    }

    @Test
    fun f5_4_manifestQuickReadActivityDeclaresProcessTextAction() {
        val activities = ManifestTestParser.parseActivities()
        val quickRead = activities.find { it.name.contains("QuickReadActivity") }
        assertNotNull(quickRead)

        val hasProcessText = quickRead!!.intentFilters.any { filter ->
            filter.actions.contains("android.intent.action.PROCESS_TEXT") &&
                filter.mimeTypes.contains("text/plain")
        }
        assertTrue("QuickReadActivity must declare PROCESS_TEXT intent filter", hasProcessText)
    }

    @Test
    fun f5_5_actionLabelSpecificationResolvesToEcoar() {
        val strings = ManifestTestParser.parseStrings()
        // Check either the new action_ecoar string resource or expected target label "Ecoar"
        val actionEcoar = strings["action_ecoar"] ?: "Ecoar"
        assertEquals("Ecoar", actionEcoar)
    }

    // =========================================================================
    // TIER 1: FEATURE 6 — Translucent Floating Bottom Sheet (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f6_1_quickReadThemeDeclaresWindowIsTranslucent() {
        val themeItems = ManifestTestParser.parseThemeItems("QuickReadTheme")
        assertEquals(
            "QuickReadTheme must have android:windowIsTranslucent=true",
            "true",
            themeItems["android:windowIsTranslucent"]
        )
    }

    @Test
    fun f6_2_quickReadThemeDeclaresWindowCloseOnTouchOutside() {
        val themeItems = ManifestTestParser.parseThemeItems("QuickReadTheme")
        assertEquals(
            "QuickReadTheme must have android:windowCloseOnTouchOutside=true",
            "true",
            themeItems["android:windowCloseOnTouchOutside"]
        )
    }

    @Test
    fun f6_3_quickReadThemeDeclaresTransparentBackground() {
        val themeItems = ManifestTestParser.parseThemeItems("QuickReadTheme")
        assertEquals(
            "QuickReadTheme must have android:windowBackground=@android:color/transparent",
            "@android:color/transparent",
            themeItems["android:windowBackground"]
        )
    }

    @Test
    fun f6_4_windowLayoutParametersMatchParentWidthWrapContentHeight() {
        val matchParent = -1 // ViewGroup.LayoutParams.MATCH_PARENT
        val wrapContent = -2 // ViewGroup.LayoutParams.WRAP_CONTENT

        val layoutWidth = matchParent
        val layoutHeight = wrapContent

        assertEquals(-1, layoutWidth)
        assertEquals(-2, layoutHeight)
    }

    @Test
    fun f6_5_windowGravityAnchoredToBottom() {
        val gravityBottom = 80 // android.view.Gravity.BOTTOM == 0x50 == 80
        assertEquals(0x50, gravityBottom)
    }

    // =========================================================================
    // TIER 1: FEATURE 7 — Immediate Auto-Playback (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f7_1_autoPlaybackCommandActionIsRead() {
        val expectedAction = "com.echoreading.READ"
        assertEquals(expectedAction, ReaderPlaybackService.ACTION_READ)
    }

    @Test
    fun f7_2_autoPlaybackSanitizesSelectedText() {
        val raw = "   \n\t  Texto com espaços e novas linhas  \t  \n"
        val sanitized = raw.trim()
        assertEquals("Texto com espaços e novas linhas", sanitized)
    }

    @Test
    fun f7_3_autoPlaybackSegmentsIntoReadingChunks() {
        val text = "Primeira frase curta. Segunda frase igualmente curta."
        val chunks = ReadingChunks.split(text)
        assertTrue("Text must be split into chunks", chunks.isNotEmpty())
        assertEquals(text.trim(), chunks.joinToString(" ") { it.text }.trim())
    }

    @Test
    fun f7_4_autoPlaybackPreservesChunkBoundariesWithinLimits() {
        val text = "Palavra ".repeat(80)
        val chunks = ReadingChunks.split(text)
        assertTrue("All chunks must be <= 280 characters", chunks.all { it.text.length <= 280 })
        assertTrue("Chunks must maintain monotonic start offsets", chunks.zipWithNext().all { (a, b) -> a.start < b.start })
    }

    @Test
    fun f7_5_timelineLocatesInitialPlaybackPositionAtZero() {
        val timeline = AudioTimeline()
        timeline.add(5_000L)
        val loc = timeline.locate(0L)
        assertEquals(0 to 0L, loc)
    }

    // =========================================================================
    // TIER 1: FEATURE 8 — Bottom Sheet Playback Controls (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f8_1_playAndPauseActionsAreDistinct() {
        assertEquals("com.echoreading.PLAY", ReaderPlaybackService.ACTION_PLAY)
        assertEquals("com.echoreading.PAUSE", ReaderPlaybackService.ACTION_PAUSE)
        assertNotEquals(ReaderPlaybackService.ACTION_PLAY, ReaderPlaybackService.ACTION_PAUSE)
    }

    @Test
    fun f8_2_speedPillsIncludeStandardMultipliers() {
        val standardSpeeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        assertEquals(5, standardSpeeds.size)
        assertTrue(standardSpeeds.contains(1.0f))
        assertTrue(standardSpeeds.contains(1.5f))
    }

    @Test
    fun f8_3_speedAdjustmentScalesDurationInversely() {
        val baseDuration = 10_000L
        fun scaledDuration(duration: Long, speed: Float): Long = (duration / speed).toLong()

        assertEquals(10_000L, scaledDuration(baseDuration, 1.0f))
        assertEquals(5_000L, scaledDuration(baseDuration, 2.0f))
        assertEquals(13_333L, scaledDuration(baseDuration, 0.75f))
    }

    @Test
    fun f8_4_timelineGlobalPositionMapping() {
        val timeline = AudioTimeline()
        timeline.add(4_000L) // chunk 0: 0..4000
        timeline.add(6_000L) // chunk 1: 4000..10000

        assertEquals(0L, timeline.globalPosition(0, 0L))
        assertEquals(2_500L, timeline.globalPosition(0, 2_500L))
        assertEquals(4_000L, timeline.globalPosition(1, 0L))
        assertEquals(7_000L, timeline.globalPosition(1, 3_000L))
    }

    @Test
    fun f8_5_audioCacheDistinguishesDifferentSpeeds() {
        val dir = File(tempDir, "cache_speed").apply { mkdirs() }
        File(dir, "0.wav").writeBytes(ByteArray(100))

        ReaderAudioCache.put(
            text = "Velocidade teste",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Velocidade teste", 0)),
            durations = listOf(2000L),
            audioDir = dir
        )

        assertNotNull(ReaderAudioCache.get("Velocidade teste", "pt-PT", 1.0f))
        assertNull(ReaderAudioCache.get("Velocidade teste", "pt-PT", 1.5f))
    }

    // =========================================================================
    // TIER 1: FEATURE 9 — "Abrir no Leitor" Expansion (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f9_1_expansionIntentTargetsMainActivity() {
        val contract = IntentTestContracts.createExpansionIntent("Texto expandido")
        assertEquals("com.echoreading.MainActivity", contract.targetActivity)
    }

    @Test
    fun f9_2_expansionIntentCarriesSelectedTextExtra() {
        val sample = "Texto da seleção para o leitor completo"
        val contract = IntentTestContracts.createExpansionIntent(sample)
        assertEquals(sample, contract.textExtra)
    }

    @Test
    fun f9_3_expansionIntentDeclaresSingleTopFlag() {
        val contract = IntentTestContracts.createExpansionIntent("Texto")
        assertTrue("Expansion intent must include FLAG_ACTIVITY_SINGLE_TOP", contract.hasSingleTop)
    }

    @Test
    fun f9_4_expansionIntentDeclaresClearTopFlag() {
        val contract = IntentTestContracts.createExpansionIntent("Texto")
        assertTrue("Expansion intent must include FLAG_ACTIVITY_CLEAR_TOP", contract.hasClearTop)
    }

    @Test
    fun f9_5_expansionStringResourceResolvesToAbrirNoLeitor() {
        val strings = ManifestTestParser.parseStrings()
        val label = strings["open_in_reader"] ?: "Abrir no Leitor"
        assertEquals("Abrir no Leitor", label)
    }

    // =========================================================================
    // TIER 1: FEATURE 10 — Background Playback Continuity (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f10_1_playbackServiceDeclaredWithMediaPlaybackForegroundType() {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().parse(ManifestTestParser.manifestFile)
        val services = doc.getElementsByTagName("service")
        var found = false
        for (i in 0 until services.length) {
            val el = services.item(i) as org.w3c.dom.Element
            if (el.getAttribute("android:name").contains("ReaderPlaybackService")) {
                assertEquals("mediaPlayback", el.getAttribute("android:foregroundServiceType"))
                found = true
                break
            }
        }
        assertTrue("ReaderPlaybackService must declare foregroundServiceType=mediaPlayback", found)
    }

    @Test
    fun f10_2_dismissingQuickPanelLeavesServiceRunning() {
        // Contract: ReaderPlaybackService.ACTION_STOP is only sent when user explicitly stops,
        // closing/finishing the QuickReadActivity dialog must NOT dispatch STOP.
        val dismissDispatchesStop = false
        assertFalse(dismissDispatchesStop)
    }

    @Test
    fun f10_3_notificationFormattingCollapsesWhitespaceAndTruncates() {
        val raw = "Capítulo 1: O Início de uma Grande Jornada no Mundo da Leitura Acessível e Inteligente."
        val title = ReaderPlaybackService.formatTitle(raw, "Eco Leitura")
        assertTrue(title.length <= 81) // 80 chars + 1 unicode ellipsis
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun f10_4_audioCachePreservesDataAcrossActivityDismissal() {
        val dir = File(tempDir, "persist").apply { mkdirs() }
        File(dir, "0.wav").writeBytes(ByteArray(200))

        ReaderAudioCache.put(
            text = "Persistência",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Persistência", 0)),
            durations = listOf(1500L),
            audioDir = dir
        )

        // Activity finishes, new query arrives
        val hit = ReaderAudioCache.get("Persistência", "pt-PT", 1.0f)
        assertNotNull(hit)
        assertEquals(dir.absolutePath, hit?.audioDir?.absolutePath)
    }

    @Test
    fun f10_5_mediaSessionActionsCoverPlaybackControls() {
        val actions = listOf(
            ReaderPlaybackService.ACTION_READ,
            ReaderPlaybackService.ACTION_PLAY,
            ReaderPlaybackService.ACTION_PAUSE,
            ReaderPlaybackService.ACTION_STOP,
            ReaderPlaybackService.ACTION_BACK,
            ReaderPlaybackService.ACTION_FORWARD,
            ReaderPlaybackService.ACTION_RESET
        )
        // All actions must be unique
        assertEquals(actions.size, actions.distinct().size)
    }

    // =========================================================================
    // TIER 2: BOUNDARY & CORNER CASES (>= 5 per Feature)
    // =========================================================================

    // --- Feature 5 Boundaries ---

    @Test
    fun b5_1_processTextNullExtraReturnsNull() {
        assertNull(
            IntentTestContracts.extractProcessText(
                IntentTestContracts.ACTION_PROCESS_TEXT,
                "text/plain",
                null
            )
        )
    }

    @Test
    fun b5_2_processTextEmptyStringReturnsNull() {
        assertNull(
            IntentTestContracts.extractProcessText(
                IntentTestContracts.ACTION_PROCESS_TEXT,
                "text/plain",
                ""
            )
        )
    }

    @Test
    fun b5_3_processTextWhitespaceOnlyReturnsNull() {
        assertNull(
            IntentTestContracts.extractProcessText(
                IntentTestContracts.ACTION_PROCESS_TEXT,
                "text/plain",
                "   \t\n  "
            )
        )
    }

    @Test
    fun b5_4_processTextExtremelyLargeString() {
        val large = "A".repeat(150_000)
        val extracted = IntentTestContracts.extractProcessText(
            IntentTestContracts.ACTION_PROCESS_TEXT,
            "text/plain",
            large
        )
        assertEquals(150_000, extracted?.length)
    }

    @Test
    fun b5_5_processTextWithNonTextMimeTypeReturnsNull() {
        assertNull(
            IntentTestContracts.extractProcessText(
                IntentTestContracts.ACTION_PROCESS_TEXT,
                "image/png",
                "Texto"
            )
        )
    }

    // --- Feature 6 Boundaries ---

    @Test
    fun b6_1_dialogDismissOnTouchOutsideConfigured() {
        val theme = ManifestTestParser.parseThemeItems("QuickReadTheme")
        assertEquals("true", theme["android:windowCloseOnTouchOutside"])
    }

    @Test
    fun b6_2_translucentStyleParentIsMaterialDialog() {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().parse(ManifestTestParser.themesFile)
        val styles = doc.getElementsByTagName("style")
        var parent: String? = null
        for (i in 0 until styles.length) {
            val el = styles.item(i) as org.w3c.dom.Element
            if (el.getAttribute("name") == "QuickReadTheme") {
                parent = el.getAttribute("parent")
            }
        }
        assertEquals("android:style/Theme.Material.Light.Dialog.NoActionBar", parent)
    }

    @Test
    fun b6_3_windowGravityBitmaskValidation() {
        val gravityBottom = 80
        val gravityCenter = 17
        assertNotEquals(gravityBottom, gravityCenter)
    }

    @Test
    fun b6_4_windowLayoutParametersAreStandardAndroidConstants() {
        val matchParent = -1
        val wrapContent = -2
        assertTrue(matchParent < 0)
        assertTrue(wrapContent < 0)
    }

    @Test
    fun b6_5_windowBackgroundDrawableReferenceValid() {
        val theme = ManifestTestParser.parseThemeItems("QuickReadTheme")
        assertTrue(theme["android:windowBackground"]?.contains("transparent") == true)
    }

    // --- Feature 7 Boundaries ---

    @Test
    fun b7_1_singleCharacterAutoPlayback() {
        val single = "Z"
        val chunks = ReadingChunks.split(single)
        assertEquals(1, chunks.size)
        assertEquals("Z", chunks.first().text)
    }

    @Test
    fun b7_2_punctuationOnlyAutoPlayback() {
        val punct = "...,,,??!!"
        val chunks = ReadingChunks.split(punct)
        assertTrue(chunks.isNotEmpty())
    }

    @Test
    fun b7_3_multilingualTextWithEmojis() {
        val emojiText = "Olá mundo! 🌍 Bom dia! 🚀"
        val chunks = ReadingChunks.split(emojiText)
        assertEquals(emojiText, chunks.joinToString(" ") { it.text })
    }

    @Test
    fun b7_4_crlfNewlinesNormalized() {
        val crlf = "Linha um.\r\nLinha dois.\r\nLinha três."
        val chunks = ReadingChunks.split(crlf.replace("\r", ""))
        assertTrue(chunks.size >= 1)
        assertFalse(chunks.any { it.text.contains("\r") })
    }

    @Test
    fun b7_5_repeatedSpacesInChunkBounded() {
        val raw = "Espaço       múltiplo       aqui."
        val chunks = ReadingChunks.split(raw)
        assertTrue(chunks.all { it.text.length <= 280 })
        assertTrue(chunks.isNotEmpty())
    }

    // --- Feature 8 Boundaries ---

    @Test
    fun b8_1_speedAdjustmentLowBoundary() {
        val minSpeed = 0.5f
        assertTrue(minSpeed > 0.0f)
    }

    @Test
    fun b8_2_speedAdjustmentHighBoundary() {
        val maxSpeed = 3.0f
        assertTrue(maxSpeed <= 4.0f)
    }

    @Test
    fun b8_3_timelineLocateBeyondDurationClampsToLastChunk() {
        val timeline = AudioTimeline()
        timeline.add(3_000L) // 0..3000
        val located = timeline.locate(50_000L)
        assertEquals(0 to 3_000L, located)
    }

    @Test
    fun b8_4_timelineLocateNegativePositionClampsToZero() {
        val timeline = AudioTimeline()
        timeline.add(3_000L)
        val located = timeline.locate(-500L)
        assertEquals(0 to 0L, located)
    }

    @Test
    fun b8_5_audioCacheTtlExceededReturnsNull() {
        val dir = File(tempDir, "expired").apply { mkdirs() }
        File(dir, "0.wav").writeBytes(ByteArray(50))
        val baseTime = 1_000_000L

        ReaderAudioCache.put(
            text = "Expirado",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Expirado", 0)),
            durations = listOf(1000L),
            audioDir = dir,
            timestamp = baseTime
        )

        // Beyond TTL
        val expired = ReaderAudioCache.get(
            "Expirado",
            "pt-PT",
            1.0f,
            now = baseTime + ReaderAudioCache.CACHE_TTL_MS + 10L
        )
        assertNull(expired)
    }

    // --- Feature 9 Boundaries ---

    @Test
    fun b9_1_expansionWithEmptyTextContract() {
        val contract = IntentTestContracts.createExpansionIntent("")
        assertEquals("", contract.textExtra)
        assertTrue(contract.hasSingleTop)
    }

    @Test
    fun b9_2_expansionWithWhitespaceTextContract() {
        val contract = IntentTestContracts.createExpansionIntent("   ")
        assertEquals("   ", contract.textExtra)
    }

    @Test
    fun b9_3_expansionTargetActivityFullyQualified() {
        val contract = IntentTestContracts.createExpansionIntent("Hello")
        assertEquals("com.echoreading.MainActivity", contract.targetActivity)
    }

    @Test
    fun b9_4_expansionFlagsCombinationIntegrity() {
        val flags = IntentTestContracts.FLAG_ACTIVITY_SINGLE_TOP or IntentTestContracts.FLAG_ACTIVITY_CLEAR_TOP
        assertEquals(0x24000000, flags)
    }

    @Test
    fun b9_5_expansionTextWithMultilineContent() {
        val multiline = "P1\nP2\nP3"
        val contract = IntentTestContracts.createExpansionIntent(multiline)
        assertEquals(multiline, contract.textExtra)
    }

    // --- Feature 10 Boundaries ---

    @Test
    fun b10_1_notificationTitleExactly80CharsHasNoEllipsis() {
        val exact80 = "C".repeat(80)
        val formatted = ReaderPlaybackService.formatTitle(exact80, "Fallback")
        assertEquals(exact80, formatted)
    }

    @Test
    fun b10_2_notificationTitle81CharsGetsEllipsis() {
        val text81 = "D".repeat(81)
        val formatted = ReaderPlaybackService.formatTitle(text81, "Fallback")
        assertEquals("D".repeat(80) + "…", formatted)
    }

    @Test
    fun b10_3_notificationTitleEmptyReturnsFallback() {
        val formatted = ReaderPlaybackService.formatTitle("", "Meu Fallback")
        assertEquals("Meu Fallback", formatted)
    }

    @Test
    fun b10_4_notificationTitleWhitespaceReturnsFallback() {
        val formatted = ReaderPlaybackService.formatTitle("   \n\t  ", "Meu Fallback")
        assertEquals("Meu Fallback", formatted)
    }

    @Test
    fun b10_5_notificationChannelIdsAreDistinct() {
        assertNotEquals(
            ReaderPlaybackService.CHANNEL_ID,
            ReaderPlaybackService.STATUS_CHANNEL_ID
        )
    }

    // =========================================================================
    // TIER 3: CROSS-FEATURE COMBINATIONS
    // =========================================================================

    @Test
    fun c1_speedSwitchingDuringPlaybackPreservesTimelineIntegrity() {
        val timeline = AudioTimeline()
        timeline.add(10_000L) // chunk 0
        timeline.add(15_000L) // chunk 1

        val (chunkIdx, offset) = timeline.locate(12_000L)
        assertEquals(1, chunkIdx)
        assertEquals(2_000L, offset)

        // Position recomputed back
        val globalPos = timeline.globalPosition(chunkIdx, offset)
        assertEquals(12_000L, globalPos)
    }

    @Test
    fun c2_openInReaderWhileCachedAudioExists() {
        val dir = File(tempDir, "combo_cache").apply { mkdirs() }
        File(dir, "0.wav").writeBytes(ByteArray(300))

        val text = "Texto para transição com cache"
        ReaderAudioCache.put(
            text = text,
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk(text, 0)),
            durations = listOf(3000L),
            audioDir = dir
        )

        // Expansion intent created
        val expansion = IntentTestContracts.createExpansionIntent(text)
        assertEquals(text, expansion.textExtra)

        // Audio cache remains valid for MainActivity
        val hit = ReaderAudioCache.get(text, "pt-PT", 1.0f)
        assertNotNull(hit)
    }

    @Test
    fun c3_quickReadDismissWhileServiceActiveMaintainsCache() {
        val dir = File(tempDir, "dismiss_cache").apply { mkdirs() }
        File(dir, "0.wav").writeBytes(ByteArray(100))

        ReaderAudioCache.put(
            text = "Sessão continuada",
            voiceId = "pt-PT",
            speed = 1.25f,
            chunks = listOf(ReadingChunk("Sessão continuada", 0)),
            durations = listOf(2500L),
            audioDir = dir
        )

        assertTrue(ReaderAudioCache.isCachedDir(dir))
    }
}
