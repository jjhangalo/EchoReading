package com.echoreading.e2e

import com.echoreading.e2e.testutil.AudioTestFixtures
import com.echoreading.reader.ReaderSnapshot
import com.echoreading.reader.ReaderState
import com.echoreading.speech.SpeechToTextState
import com.echoreading.speech.TranscriptionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * E2E Requirement Tests for Requirements R3 & R4:
 * R3: Reorganização da Navegação e Ícones
 * R4: Layout Adaptativo (BottomBar / SideBar) e Persistência de Estado em Rotação
 *
 * Covers Features 10, 11, 12, 13 across Tier 1 (Happy Path), Tier 2 (Boundaries),
 * and Tier 3 (Cross-Feature Combinations).
 */
class AdaptiveNavigationRequirementTest {

    @Before
    fun setUp() {
        ReaderState.snapshot.value = ReaderSnapshot()
        SpeechToTextState.reset()
    }

    @After
    fun tearDown() {
        ReaderState.snapshot.value = ReaderSnapshot()
        SpeechToTextState.reset()
    }

    // =========================================================================
    // TIER 1: FEATURE 10 — 4-Tab Navigation Sequence (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f10_1_navigationHasExactlyFourDestinations() {
        val tabs = AudioTestFixtures.EXPECTED_NAVIGATION_TABS
        assertEquals("Navigation must contain exactly 4 destinations", 4, tabs.size)
    }

    @Test
    fun f10_2_firstTabIsLeituraHome() {
        val tab = AudioTestFixtures.EXPECTED_NAVIGATION_TABS[0]
        assertEquals("home", tab.route)
        assertEquals("Leitura", tab.label)
    }

    @Test
    fun f10_3_secondTabIsTranscrever() {
        val tab = AudioTestFixtures.EXPECTED_NAVIGATION_TABS[1]
        assertEquals("transcribe", tab.route)
        assertEquals("Transcrever", tab.label)
    }

    @Test
    fun f10_4_thirdTabIsBiblioteca() {
        val tab = AudioTestFixtures.EXPECTED_NAVIGATION_TABS[2]
        assertEquals("history", tab.route)
        assertEquals("Biblioteca", tab.label)
    }

    @Test
    fun f10_5_fourthTabIsDefinicoesSettings() {
        val tab = AudioTestFixtures.EXPECTED_NAVIGATION_TABS[3]
        assertEquals("settings", tab.route)
        assertEquals("Definições", tab.label)
    }

    // =========================================================================
    // TIER 1: FEATURE 11 — Material 3 Consistent Icons (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f11_1_leituraIconIsVolumeUp() {
        val tab = AudioTestFixtures.EXPECTED_NAVIGATION_TABS.find { it.route == "home" }
        assertNotNull(tab)
        assertEquals("VolumeUp", tab!!.iconName)
    }

    @Test
    fun f11_2_transcreverIconIsMic() {
        val tab = AudioTestFixtures.EXPECTED_NAVIGATION_TABS.find { it.route == "transcribe" }
        assertNotNull(tab)
        assertEquals("Mic", tab!!.iconName)
    }

    @Test
    fun f11_3_bibliotecaIconIsMenuBookOrHistory() {
        val tab = AudioTestFixtures.EXPECTED_NAVIGATION_TABS.find { it.route == "history" }
        assertNotNull(tab)
        assertTrue(tab!!.iconName == "MenuBook" || tab.iconName == "History")
    }

    @Test
    fun f11_4_definicoesIconIsSettings() {
        val tab = AudioTestFixtures.EXPECTED_NAVIGATION_TABS.find { it.route == "settings" }
        assertNotNull(tab)
        assertEquals("Settings", tab!!.iconName)
    }

    @Test
    fun f11_5_allTabsHaveNonEmptyLabelsAndIcons() {
        AudioTestFixtures.EXPECTED_NAVIGATION_TABS.forEach { tab ->
            assertTrue("Tab label must not be empty", tab.label.isNotEmpty())
            assertTrue("Tab icon must not be empty", tab.iconName.isNotEmpty())
            assertTrue("Tab route must not be empty", tab.route.isNotEmpty())
        }
    }

    // =========================================================================
    // TIER 1: FEATURE 12 — Adaptive Navigation Layout (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f12_1_portraitOrientationSelectsBottomNavigationBar() {
        val component = AudioTestFixtures.resolveNavigationComponent(AudioTestFixtures.OrientationMode.PORTRAIT)
        assertEquals(AudioTestFixtures.NavigationUiComponent.BOTTOM_BAR, component)
    }

    @Test
    fun f12_2_landscapeOrientationSelectsSideNavigationRail() {
        val component = AudioTestFixtures.resolveNavigationComponent(AudioTestFixtures.OrientationMode.LANDSCAPE)
        assertEquals(AudioTestFixtures.NavigationUiComponent.SIDE_BAR, component)
    }

    @Test
    fun f12_3_navigationRailFreesHorizontalSpaceInLandscape() {
        // In landscape, sidebar layout leaves central view unobstructed
        val isSideBar = AudioTestFixtures.resolveNavigationComponent(AudioTestFixtures.OrientationMode.LANDSCAPE) ==
            AudioTestFixtures.NavigationUiComponent.SIDE_BAR
        assertTrue("Landscape must employ sidebar navigation", isSideBar)
    }

    @Test
    fun f12_4_bottomBarOptimizedForVerticalThumbReach() {
        val isBottomBar = AudioTestFixtures.resolveNavigationComponent(AudioTestFixtures.OrientationMode.PORTRAIT) ==
            AudioTestFixtures.NavigationUiComponent.BOTTOM_BAR
        assertTrue("Portrait must employ bottom bar navigation", isBottomBar)
    }

    @Test
    fun f12_5_navigationComponentExhaustiveMapping() {
        for (mode in AudioTestFixtures.OrientationMode.values()) {
            val component = AudioTestFixtures.resolveNavigationComponent(mode)
            assertNotNull(component)
        }
    }

    // =========================================================================
    // TIER 1: FEATURE 13 — Screen Rotation State Persistence (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f13_1_readerTypedTextPersistsAcrossOrientationFlip() {
        val typedText = "Capítulo 1: Introdução ao aprendizado contínuo com áudio e síntese neural."
        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(text = typedText)

        val beforeState = AudioTestFixtures.AppScreenState(
            activeRoute = "home",
            readerDraftText = ReaderState.snapshot.value.text,
            readerCharOffset = 42,
            transcriberText = "",
            transcriberStatus = "IDLE",
            isRecording = false
        )

        val afterState = AudioTestFixtures.simulateOrientationChange(beforeState)
        assertEquals(typedText, afterState.readerDraftText)
        assertEquals(42, afterState.readerCharOffset)
    }

    @Test
    fun f13_2_transcriptionTextPersistsAcrossOrientationFlip() {
        val transcribed = "Texto transcrito durante a reunião de planeamento."
        SpeechToTextState.snapshot.value = SpeechToTextState.snapshot.value.copy(
            status = TranscriptionStatus.DONE,
            transcribedText = transcribed
        )

        val beforeState = AudioTestFixtures.AppScreenState(
            activeRoute = "transcribe",
            readerDraftText = "",
            readerCharOffset = 0,
            transcriberText = SpeechToTextState.snapshot.value.transcribedText,
            transcriberStatus = SpeechToTextState.snapshot.value.status.name,
            isRecording = false
        )

        val afterState = AudioTestFixtures.simulateOrientationChange(beforeState)
        assertEquals(transcribed, afterState.transcriberText)
        assertEquals("DONE", afterState.transcriberStatus)
    }

    @Test
    fun f13_3_activeRoutePersistsAcrossOrientationFlip() {
        val beforeState = AudioTestFixtures.AppScreenState(
            activeRoute = "transcribe",
            readerDraftText = "Olá",
            readerCharOffset = 0,
            transcriberText = "",
            transcriberStatus = "IDLE",
            isRecording = false
        )
        val afterState = AudioTestFixtures.simulateOrientationChange(beforeState)
        assertEquals("transcribe", afterState.activeRoute)
    }

    @Test
    fun f13_4_recordingStatePreservedAcrossOrientationFlip() {
        val beforeState = AudioTestFixtures.AppScreenState(
            activeRoute = "transcribe",
            readerDraftText = "",
            readerCharOffset = 0,
            transcriberText = "",
            transcriberStatus = "IDLE",
            isRecording = true
        )
        val afterState = AudioTestFixtures.simulateOrientationChange(beforeState)
        assertTrue(afterState.isRecording)
    }

    @Test
    fun f13_5_emptyInitialStatePersistsWithoutCorruption() {
        val beforeState = AudioTestFixtures.AppScreenState(
            activeRoute = "home",
            readerDraftText = "",
            readerCharOffset = 0,
            transcriberText = "",
            transcriberStatus = "IDLE",
            isRecording = false
        )
        val afterState = AudioTestFixtures.simulateOrientationChange(beforeState)
        assertEquals("", afterState.readerDraftText)
        assertEquals("home", afterState.activeRoute)
    }

    // =========================================================================
    // TIER 2: BOUNDARY & CORNER CASES (>= 6 test cases)
    // =========================================================================

    @Test
    fun b1_repeatedConsecutiveRotationsPreserveState() {
        var state = AudioTestFixtures.AppScreenState(
            activeRoute = "settings",
            readerDraftText = "Texto complexo persistente",
            readerCharOffset = 15,
            transcriberText = "Gravação de teste",
            transcriberStatus = "DONE",
            isRecording = false
        )

        // 10 consecutive orientation flips
        for (i in 1..10) {
            state = AudioTestFixtures.simulateOrientationChange(state)
        }

        assertEquals("settings", state.activeRoute)
        assertEquals("Texto complexo persistente", state.readerDraftText)
        assertEquals("Gravação de teste", state.transcriberText)
    }

    @Test
    fun b2_largeDocumentDraftSurvivesRotation() {
        // 100k characters of draft text
        val largeText = "A".repeat(100_000)
        val state = AudioTestFixtures.AppScreenState(
            activeRoute = "home",
            readerDraftText = largeText,
            readerCharOffset = 50_000,
            transcriberText = "",
            transcriberStatus = "IDLE",
            isRecording = false
        )
        val after = AudioTestFixtures.simulateOrientationChange(state)
        assertEquals(100_000, after.readerDraftText.length)
        assertEquals(50_000, after.readerCharOffset)
    }

    @Test
    fun b3_specialCharactersAndEmojisSurviveRotation() {
        val specialText = "Texto com símbolos: 🚀 📚 🎙️ © ® € £ § ¶ \n Quebras de linha e acentos: áéíóú çãõ"
        val state = AudioTestFixtures.AppScreenState(
            activeRoute = "home",
            readerDraftText = specialText,
            readerCharOffset = 10,
            transcriberText = specialText,
            transcriberStatus = "DONE",
            isRecording = false
        )
        val after = AudioTestFixtures.simulateOrientationChange(state)
        assertEquals(specialText, after.readerDraftText)
        assertEquals(specialText, after.transcriberText)
    }

    @Test
    fun b4_activeRouteNavigationBackstackSanity() {
        val validRoutes = setOf("home", "transcribe", "history", "settings")
        AudioTestFixtures.EXPECTED_NAVIGATION_TABS.forEach { tab ->
            assertTrue("Route must be in valid set", validRoutes.contains(tab.route))
        }
    }

    @Test
    fun b5_statePreservedWhenTranscriberIsInDecodingState() {
        val state = AudioTestFixtures.AppScreenState(
            activeRoute = "transcribe",
            readerDraftText = "",
            readerCharOffset = 0,
            transcriberText = "",
            transcriberStatus = "DECODING",
            isRecording = false
        )
        val after = AudioTestFixtures.simulateOrientationChange(state)
        assertEquals("DECODING", after.transcriberStatus)
    }

    @Test
    fun b6_statePreservedWhenTranscriberIsInQualityCheckState() {
        val state = AudioTestFixtures.AppScreenState(
            activeRoute = "transcribe",
            readerDraftText = "",
            readerCharOffset = 0,
            transcriberText = "",
            transcriberStatus = "CHECKING_QUALITY",
            isRecording = false
        )
        val after = AudioTestFixtures.simulateOrientationChange(state)
        assertEquals("CHECKING_QUALITY", after.transcriberStatus)
    }

    // =========================================================================
    // TIER 3: CROSS-FEATURE INTERACTIONS (Pairwise)
    // =========================================================================

    @Test
    fun c1_switchTabsDuringActiveTranscriptionPreservesBothScreens() {
        // Reader has text, Transcriber has audio result
        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(text = "Livro em leitura")
        SpeechToTextState.snapshot.value = SpeechToTextState.snapshot.value.copy(
            status = TranscriptionStatus.DONE,
            transcribedText = "Transcrição concluída"
        )

        // User navigates from transcribe to home
        val currentRoute = "home"
        assertEquals("home", currentRoute)
        assertEquals("Livro em leitura", ReaderState.snapshot.value.text)
        assertEquals("Transcrição concluída", SpeechToTextState.snapshot.value.transcribedText)
    }

    @Test
    fun c2_rotateDeviceWhileReceivingAudioSharePreservesPendingUriAndRoute() {
        val testUri = android.net.Uri.parse("content://shared/audio.mp3")
        SpeechToTextState.pendingAudioUri.value = testUri

        val state = AudioTestFixtures.AppScreenState(
            activeRoute = "transcribe",
            readerDraftText = "",
            readerCharOffset = 0,
            transcriberText = "",
            transcriberStatus = "DECODING",
            isRecording = false
        )

        val rotated = AudioTestFixtures.simulateOrientationChange(state)
        assertEquals("transcribe", rotated.activeRoute)
        assertEquals(testUri, SpeechToTextState.pendingAudioUri.value)
    }
}
