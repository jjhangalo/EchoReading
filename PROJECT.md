# Project: EchoReading 2.0 — Piper Metadata & OS Interoperability

## Architecture
EchoReading 2.0 is an Android text-to-speech reading application built with Jetpack Compose, Sherpa-ONNX, and Media3.
- **Audio & Synthesis**: `OfflineVoice.kt` orchestrates Piper VITS ONNX model management and calls Sherpa-ONNX `OfflineTts`. Synthesized PCM audio streams are cached by `ReaderAudioCache` and queued in ExoPlayer via `ReaderPlaybackService` (`MediaSessionService`).
- **State Management**: `ReaderState` holds global application state via reactive StateFlow `ReaderSnapshot`.
- **UI Layer**: Jetpack Compose based `ReaderUi.kt` (screens: `ReaderHome`, `Library`, `VoiceDiscoveryScreen`, `ReaderQuickPanel`).
- **OS Interoperability Layer**:
  - `QuickReadActivity`: Translucent floating Bottom Sheet triggered via `android.intent.action.PROCESS_TEXT` ("Ecoar") for quick in-place listening.
  - `MainActivity`: Standard reader application handling `android.intent.action.MAIN` and `android.intent.action.SEND` (`text/plain`) for system share sheet routing.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Protobuf Metadata Injection | Stream and inject `sample_rate`, `model_type=vits`, `comment=piper`, `has_espeak=1` into Piper `.onnx` files upon download | M1 | ORIGINAL_REQUEST §R1 |
| 2 | Retroactive Model Repair | Detect and repair existing downloaded Piper models on disk lacking `sample_rate` metadata before synthesis | M1 | ORIGINAL_REQUEST §R1 |
| 3 | Model File Size Check Fix | Update file size validation from strict equality (`==`) to `>=` to accommodate injected metadata | M1 | Survey Finding |
| 4 | Metadata Unit Testing | Pure Kotlin unit test verifying metadata injection and integrity | M1 | ORIGINAL_REQUEST §R1 |
| 5 | "Ecoar" Action Item Label | Register `android.intent.action.PROCESS_TEXT` with label "Ecoar" in `AndroidManifest.xml` | M2 | ORIGINAL_REQUEST §R2 |
| 6 | Translucent Floating Bottom Sheet | Configure `QuickReadActivity` window with `Gravity.BOTTOM` and slide animations over host applications | M2 | ORIGINAL_REQUEST §R2 |
| 7 | Immediate Auto-Playback | Trigger speech synthesis and playback immediately upon opening `QuickReadActivity` | M2 | ORIGINAL_REQUEST §R2 |
| 8 | Bottom Sheet Playback Controls | Provide interactive Play/Pause, speed selector pills, and voice indicator in `ReaderQuickPanel` | M2 | ORIGINAL_REQUEST §R2 |
| 9 | "Abrir no Leitor" Expansion | Action in Bottom Sheet to transition text into `MainActivity` while maintaining continuous playback | M2 | ORIGINAL_REQUEST §R2 |
| 10 | Background Playback Continuity | Ensure `ReaderPlaybackService` continues playback if sheet is dismissed or screen is locked | M2 | ORIGINAL_REQUEST §R2 |
| 11 | Share Sheet Direct Routing | Register `MainActivity` with `android.intent.action.SEND` (`text/plain`) in `AndroidManifest.xml` | M3 | ORIGINAL_REQUEST §R3 |
| 12 | Share Intent Extraction & Routing | Safely extract shared text in `MainActivity` (`onCreate` and `onNewIntent`), populate reader text, and route to `"home"` | M3 | ORIGINAL_REQUEST §R3 |
| 13 | Reader Input State Synchronization | Ensure `ReaderHome` input updates immediately upon receiving shared text in both idle and active states | M3 | Survey Finding |
| 14 | E2E Testing Suite (Tiers 1-4) | Comprehensive opaque-box test suite for R1, R2, R3 across feature, boundary, combinatorial, and workload tiers | E2E Track | Project Pattern |
| 15 | Build & Test Quality Verification | Ensure `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` pass with zero errors | M4 | ORIGINAL_REQUEST Quality |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Piper Voice Model Metadata Injection | `OnnxMetadata.kt`, `OfflineVoice.kt`, `VoiceDiscoveryScreen.kt`, `OnnxMetadataTest.kt` | none | DONE |
| M2 | "Ecoar" Selection & Quick Bottom Sheet | `QuickReadActivity.kt`, `ReaderUi.kt` (`ReaderQuickPanel`), `strings.xml`, `themes.xml`, `AndroidManifest.xml` | none | IN_PROGRESS |
| M3 | System Share Sheet Direct Routing | `MainActivity.kt`, `ShareIntentHandler.kt`, `ReaderState.kt`, `ReaderUi.kt`, `AndroidManifest.xml`, unit tests | none | PLANNED |
| E2E | E2E Testing Track | Requirement-driven test suite (Tiers 1-4) and `TEST_READY.md` generation | none | DONE |
| M4 | Final Integration, Verification & Hardening | Run 100% E2E tests, Tier 5 adversarial testing, `./gradlew testDebugUnitTest`, `./gradlew assembleDebug` | M1, M2, M3, E2E | PLANNED |

## Interface Contracts
### `OnnxMetadata` ↔ `OfflineVoice`
- `OnnxMetadata.injectMetadata(onnxFile: File, sampleRate: Int, numSpeakers: Int = 1)`: Appends repeated field 14 Protobuf metadata entries to `.onnx`.
- `OnnxMetadata.hasSampleRate(onnxFile: File): Boolean`: Scans top-level fields for `metadata_props` with key `"sample_rate"`.
- `OnnxMetadata.repairModelFile(onnxFile: File, jsonConfigFile: File? = null): Boolean`: Verifies and patches missing metadata in-place.

### `QuickReadActivity` ↔ `MainActivity`
- Intent Action: `android.intent.action.VIEW` or explicit `Intent(context, MainActivity::class.java)`
- Extra: `Intent.EXTRA_TEXT` (CharSequence/String)
- Flags: `Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP`
- Behavior: Expands current reading session into full reader without restarting audio playback.

### `ShareIntentHandler` ↔ `MainActivity`
- `ShareIntentHandler.extractText(intent: Intent): String?`: Extracts text from `ACTION_SEND` with `text/plain` extra `EXTRA_TEXT`.
- `ReaderState.loadText(context: Context, text: String)`: Emits text to `loadTextEvent: SharedFlow<String>`, resets status to IDLE, and stores draft.

## Code Layout
- `app/src/main/java/com/echoreading/voice/OnnxMetadata.kt`: Protobuf metadata parser, injector, and repair utilities.
- `app/src/main/java/com/echoreading/voice/OfflineVoice.kt`: Model installation, loading, and retroactive repair hooks.
- `app/src/main/java/com/echoreading/QuickReadActivity.kt`: Translucent Bottom Sheet activity for PROCESS_TEXT.
- `app/src/main/java/com/echoreading/MainActivity.kt`: Main activity handling ACTION_MAIN and ACTION_SEND.
- `app/src/main/java/com/echoreading/share/ShareIntentHandler.kt`: Helper for intent extraction and validation.
- `app/src/main/java/com/echoreading/ReaderState.kt`: Application state and text loading flow.
- `app/src/main/java/com/echoreading/ReaderUi.kt`: Reader UI components (`ReaderHome`, `ReaderQuickPanel`).
- `app/src/main/res/values/strings.xml`: Localized string resources.
- `app/src/main/res/values/themes.xml`: Window styles and dialog themes.
- `app/src/main/AndroidManifest.xml`: Manifest component declarations and intent filters.
- `app/src/test/java/com/echoreading/`: JVM unit tests.
