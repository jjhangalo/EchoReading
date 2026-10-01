# Test Infrastructure Specification — EchoReading 2.0 (R1–R5)

## 1. Test Philosophy

EchoReading 2.0 adopts a **requirements-driven, opaque-box testing philosophy** directly derived from the authoritative follow-up user specifications in `ORIGINAL_REQUEST.md` (timestamp `2026-09-30T08:53:34Z`) and architectural commitments in `PROJECT.md`.

### Core Principles
1. **Opaque-Box Specification Conformance**: Tests validate observable behaviors, public API contracts, audio mathematical properties, intent contracts, layout modes, state preservation invariants, and file management contracts rather than fragile implementation internals.
2. **Authoritative Ground Truth Derivation**:
   - **R1 (Direct In-App Voice Recording & Acoustic Quality)**: Expected sample rates (16kHz), mono channel format, 16-bit PCM normalization `[-1.0f, 1.0f]`, SNR thresholds (>= 5.0 dB tolerated for speech, < 5.0 dB noisy), clipping limits (> 5% of samples at amplitude >= 0.99f), silence thresholds (RMS < 0.005f), and minimum speech presence (>= 5% of frames).
   - **R2 (External Audio Share Intent & Async Pipeline)**: Expected `ACTION_SEND` MIME type matching (`audio/*`), extraction of `EXTRA_STREAM` / `clipData` URIs, immediate routing to `"transcribe"`, and off-thread execution on `Dispatchers.IO`.
   - **R3 (Navigation Structure & Material 3 Icons)**: Expected 4-tab sequence: 1. Leitura (`home`), 2. Transcrever (`transcribe`), 3. Biblioteca (`history`), 4. Definições (`settings`), and consistent Material 3 icons.
   - **R4 (Adaptive Layout & State Persistence in Rotation)**: Expected BottomBar (`NavigationBar`) in portrait, SideBar (`NavigationRail`) in landscape, and non-destructive state retention of typed text, transcription progress, recording status, and active navigation route across orientation flips.
   - **R5 (Voice Model Settings Management)**: Expected TTS Piper voice listing/download/deletion status, and STT Whisper offline model readiness, disk footprint, and download progress reporting.
3. **Zero Facade Policy**: No mock tests that trivially assert true without exercising real logic. Every test exercises real mathematical DSP calculations, XML parsing, Intent validation, state machine transitions, or file system assertions.
4. **Self-Contained & Deterministic**: Tests initialize their own state, run deterministically on the JVM without emulator dependencies, avoid cross-test interference, and clean up temporary files in `@After` lifecycle methods.

---

## 2. Feature Inventory Across R1–R5

| Feature # | Feature Name | Requirement | Description |
|---|---|---|---|
| **F1** | In-App Mic Recording Button | R1 | Intuitive mic button to start/stop recording with live timer and state |
| **F2** | Native AudioRecord Async Capture | R1 | 16kHz mono 16-bit PCM captured into memory asynchronously |
| **F3** | AudioQualityChecker SNR & Distortion | R1 | Evaluates SNR (>= 5dB tolerated), rejects silence, low volume, clipping (> 5%), lack of speech |
| **F4** | One-Tap Copy & Send to Reader | R1 | Transcription text display with one-tap clipboard copy and send-to-reader (TTS) action |
| **F5** | RECORD_AUDIO Permission | R1 | AndroidManifest declaration and runtime permission contracts |
| **F6** | Audio Share Intent Extraction | R2 | Extracts audio Uri from `ACTION_SEND` (`audio/*`) via `EXTRA_STREAM` or `clipData` |
| **F7** | Immediate Transcribe Navigation | R2 | Routes `MainActivity` immediately to `"transcribe"` on both cold start and warm start |
| **F8** | Persistent Pending Audio Uri | R2 | Retains pending audio state in `TranscriberState` to eliminate event drop |
| **F9** | Off-Thread Audio Decoding & Query | R2 | Decodes audio and runs inference strictly on `Dispatchers.IO` without UI freezing |
| **F10** | 4-Tab Navigation Sequence | R3 | Exact sequence: Leitura -> Transcrever -> Biblioteca -> Definições |
| **F11** | Material 3 Consistent Icons | R3 | VolumeUp (Leitura), Mic (Transcrever), MenuBook/History (Biblioteca), Settings (Definições) |
| **F12** | Adaptive Navigation Layout | R4 | `NavigationBar` in portrait; `NavigationRail` in landscape with main area unobstructed |
| **F13** | Screen Rotation State Persistence | R4 | Preserves typed text, transcription progress, and route across configuration changes |
| **F14** | TTS Piper Voice Management | R5 | Settings voice catalog, selection, download, and deletion |
| **F15** | STT Whisper Offline Management | R5 | Settings Whisper status, disk footprint, download progress, readiness check |

---

## 3. Four-Tier Test Methodology

The test suite employs a 4-tier methodology:

### Tier 1: Feature Coverage (Category-Partition)
- **Minimum Threshold**: >= 5 distinct test cases per feature (F1 through F15).
- **Technique**: Category-partition testing on valid input equivalence classes, ensuring every feature contract functions according to specification under standard operating conditions.

### Tier 2: Boundary Value Analysis & Corner Cases
- **Minimum Threshold**: >= 5 edge/boundary test cases per feature area.
- **Technique**: Extreme values, nullability, missing extras, 0-byte inputs, boundary thresholds:
  - *Audio*: Empty arrays, 1-sample buffers, maximum duration audio (60s+), zero SNR, negative SNR, extreme clipping (100%), boundary clipping (4.9% vs 5.1%), amplitude threshold boundaries (0.009f vs 0.011f).
  - *Intents*: Null intent, missing extras, non-audio MIME types (`text/plain`, `image/*`), mixed-case MIME (`AUDIO/MPEG`), invalid URI schemes.
  - *Navigation & Layout*: Extreme screen aspect ratios, orientation flips between portrait and landscape, empty text vs massive text retention.
  - *Models*: Missing model directories, 0-byte `.onnx` files, incomplete download `.part` files, missing companion tokens.

### Tier 3: Pairwise & Cross-Feature Combinations
- **Focus**: Multi-component interactions and state transitions:
  - Record audio in-app -> verify quality -> transcribe -> send text to reader -> trigger TTS.
  - Share audio via `ACTION_SEND` -> rotate screen -> verify pending URI and transcription state survive.
  - Reject poor audio (silence/distortion) -> verify clear error reported -> re-record clean audio -> successful recovery.
  - Switch between TTS voice settings and Transcribe screen -> verify engine readiness and selection stability.

### Tier 4: Real-World Application Workloads
- **Focus**: Complete end-to-end user journeys simulating real-world production usage:
  - *Workflow 1 (Dictation to Speech)*: User opens Transcribe, records memo, reviews quality, copies to clipboard, sends to Leitor, starts playback.
  - *Workflow 2 (Audio Message Ingestion)*: User shares WhatsApp voice note (`audio/ogg`) into EchoReading, app opens directly on Transcribe, decodes off-thread, displays transcription.
  - *Workflow 3 (Acoustic Quality Self-Healing)*: User records in a severely clipped/loud environment, receives instant feedback, re-records in normal environment, transcription completes.
  - *Workflow 4 (Adaptive Multi-Tasking & Rotation)*: User begins transcription, rotates phone to landscape to read, navigates tabs, verifies no state loss.
  - *Workflow 5 (Offline Model Setup & Execution)*: User verifies Whisper model status in Settings, checks disk footprint, transcribes audio, verifies Piper voice.
  - *Workflow 6 (Adversarial Multi-Step Resilience)*: Rapid tab switching, invalid shares followed by valid recordings, zero crash guarantee.

---

## 4. Test Suite Architecture

```
app/src/test/java/com/echoreading/e2e/
├── AudioCaptureQualityRequirementTest.kt   # R1: Features F1–F5 (Tiers 1–3)
├── AudioShareIntentRequirementTest.kt       # R2: Features F6–F9 (Tiers 1–3)
├── AdaptiveNavigationRequirementTest.kt     # R3 & R4: Features F10–F13 (Tiers 1–3)
├── VoiceModelSettingsRequirementTest.kt    # R5: Features F14–F15 (Tiers 1–3)
├── EndToEndWorkflowsR2Test.kt              # R1–R5: Workflows 1–6 (Tier 4)
└── testutil/
    ├── AudioTestFixtures.kt                # Waveform synthesis, DSP helpers, contract models
    ├── IntentTestContracts.kt              # Intent extraction and share simulation
    ├── ManifestTestParser.kt               # XML parser for AndroidManifest, strings, themes
    └── ProtobufTestOracle.kt               # Reference Protobuf wire oracle
```

---

## 5. Test Execution Commands & Quality Thresholds

### Execution Commands
- **Run Full E2E Test Suite**:
  ```powershell
  ./gradlew testDebugUnitTest --tests "com.echoreading.e2e.*"
  ```
- **Run Entire Project Unit Test Suite**:
  ```powershell
  ./gradlew testDebugUnitTest
  ```
- **Verify Clean APK Build**:
  ```powershell
  ./gradlew assembleDebug
  ```

### Quality Gate Thresholds
| Metric | Threshold | Target |
|---|---|---|
| **Tier 1 Feature Coverage** | >= 5 tests per feature (F1–F15) | 100% pass |
| **Tier 2 Boundary Tests** | >= 5 tests per feature domain | 100% pass |
| **Tier 3 Combinatorial Tests** | Cross-feature interaction tests | 100% pass |
| **Tier 4 Workloads** | >= 6 complete multi-step journeys | 100% pass |
| **Test Pass Rate** | 100% | 0 failures, 0 errors, 0 skipped |
| **Execution Duration** | Sub-second to few seconds | JVM-native, fast CI feedback |
