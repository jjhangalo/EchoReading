# Project: EchoReading 2.0 — STT Voice Recording, Audio Sharing, Adaptive Layout & Model Management

## Architecture
EchoReading 2.0 is an offline-first Android reading and speech-to-text assistant built with Jetpack Compose, Sherpa-ONNX, and Media3.
- **Audio & Synthesis (TTS)**: `OfflineVoice.kt` orchestrates Piper VITS ONNX model catalog and synthesis via Sherpa-ONNX `OfflineTts`.
- **Speech-to-Text (STT)**: `OfflineSpeech.kt` manages pinned, checksum-verified multilingual Whisper Tiny, Base, and Small models through the official Sherpa-ONNX v1.13.8 runtime.
- **Audio Capture & Quality**: `SpeechProcessingService` records 16kHz mono PCM to private storage, keeps a checkpoint, and applies `AudioQualityChecker` to each bounded transcription block.
- **Audio Decoding**: `AudioDecoder.kt` converts selected or shared audio to file-backed 16kHz mono PCM, with a 30-minute limit and chunked reads.
- **State Management**: `SpeechToTextState` exposes service-owned recording, download, and transcription state that survives navigation and can resume checkpoints.
- **Adaptive UI Layer**: Jetpack Compose based `MainActivity.kt` renders an adaptive layout: `NavigationBar` (BottomBar) in portrait, `NavigationRail` (SideBar) in landscape, hosting exactly 4 destinations:
  1. Leitura (`home`)
  2. Transcrever (`transcribe`)
  3. Biblioteca (`history`)
  4. Definições (`settings`)
- **Settings & Model Management**: `SettingsScreen.kt` provides unified management for TTS and for installing, selecting, pausing, resuming, or deleting each Whisper STT model.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | In-App Mic Recording Button | Intuitive mic button on Transcribe screen to start/stop recording with live timer/state | M1 | ORIGINAL_REQUEST §R1 |
| 2 | Native AudioRecord Async Capture | Capture 16kHz mono 16-bit PCM into a private checkpointed file through a foreground service | M1 | ORIGINAL_REQUEST §R1 |
| 3 | AudioQualityChecker SNR & Distortion | Evaluate SNR (tolerate moderate noise >= 5dB), reject silence, inaudible audio, clipping (>5% >=0.99f), absence of voice | M1 | ORIGINAL_REQUEST §R1 |
| 4 | One-Tap Copy & Send to Reader | Prominent transcription display with one-tap clipboard copy and send-to-reader (TTS) action | M1 | ORIGINAL_REQUEST §R1 |
| 5 | Manifest RECORD_AUDIO Permission | Declare `android.permission.RECORD_AUDIO` and handle runtime permission gracefully | M1 | ORIGINAL_REQUEST §R1 |
| 6 | Audio Share Intent Extraction | Extract audio Uri from `ACTION_SEND` (`audio/*`) checking `EXTRA_STREAM` and `clipData` in `ShareIntentHandler` | M2 | ORIGINAL_REQUEST §R2 |
| 7 | Immediate Transcribe Navigation | Route `MainActivity` immediately to `"transcribe"` screen on both `onCreate` and `onNewIntent` | M2 | ORIGINAL_REQUEST §R2 |
| 8 | Persistent Pending Audio Uri | Keep incoming audio in `SpeechToTextState` until the foreground service owns its private job copy | M2 | ORIGINAL_REQUEST §R2 |
| 9 | Off-Thread Audio Decoding & Query | Execute `contentResolver.query`, `AudioDecoder`, and Whisper inference strictly on `Dispatchers.IO` | M2 | ORIGINAL_REQUEST §R2 |
| 10 | 4-Tab Navigation Sequence | Sequence: 1. Leitura -> 2. Transcrever -> 3. Biblioteca -> 4. Definições | M3 | ORIGINAL_REQUEST §R3 |
| 11 | Material 3 Consistent Icons | VolumeUp (Leitura), Mic (Transcrever), MenuBook (Biblioteca), Settings (Definições) | M3 | ORIGINAL_REQUEST §R3 |
| 12 | Adaptive Navigation Layout | `NavigationBar` in portrait; `NavigationRail` in landscape with main content unobstructed | M3 | ORIGINAL_REQUEST §R4 |
| 13 | Screen Rotation State Persistence | Preserve typed text, transcription text/progress, recording state, and active route across orientation changes | M3 | ORIGINAL_REQUEST §R4 |
| 14 | TTS Piper Voice Management | In Settings: list installed voices, select active voice, download from catalog, delete models | M4 | ORIGINAL_REQUEST §R5 |
| 15 | STT Whisper Offline Management | In Settings: status indicator, disk footprint, download progress, readiness status, storage tracking | M4 | ORIGINAL_REQUEST §R5 |
| 16 | E2E Testing Suite (Tiers 1-4) | Comprehensive opaque-box test suite for R1-R5 covering feature, boundary, pairwise, and workload scenarios | M5 | ORIGINAL_REQUEST Quality |
| 17 | Tier 5 Adversarial Coverage Hardening | White-box stress testing, edge-case probing, and coverage hardening | M5 | Project Pattern |
| 18 | Quality & Build Verification | `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` pass 100% | M5 | ORIGINAL_REQUEST Quality |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | In-App Voice Recording & Quality Checker | `AudioQualityChecker.kt`, `SpeechProcessingService.kt`, `AndroidManifest.xml`, unit tests | none | IMPLEMENTED |
| M2 | External Audio Share Intent & Async Pipeline | `ShareIntentHandler.kt`, `MainActivity.kt`, `SpeechProcessingService.kt`, unit tests | none | IMPLEMENTED |
| M3 | Adaptive 4-Tab Navigation & UI Integration | `MainActivity.kt` (`EcoApp`), `SpeechToTextScreen.kt`, state persistence | M1, M2 | IMPLEMENTED |
| M4 | Voice Model Management in Settings | `SettingsScreen.kt`, `OfflineSpeech.kt`, `OfflineVoice.kt`, storage state | none | IMPLEMENTED |
| M5 | E2E Integration, Tier 5 Hardening & Verification | JVM suite, APK build, JNI/ONNX packaging check | M1, M2, M3, M4 | IMPLEMENTED |

## Interface Contracts
### `SpeechToTextState` and `SpeechProcessingService`
- `SpeechToTextState.snapshot` exposes recording, decoding, quality checking, transcription, pause, completion, and error states.
- `SpeechToTextState.downloadModel`, `pauseDownload`, and `resume` control resumable model downloads.
- `SpeechProcessingService` owns the foreground notification, wake lock, microphone capture, checkpointing, and chunked transcription.

### `ShareIntentHandler` ↔ `MainActivity`
- `ShareIntentHandler.extractAudioUri(intent: Intent): Uri?`: Extracts Uri from `EXTRA_STREAM` or `clipData` when mimeType is `audio/*`.

### `OfflineSpeech` (STT Model Management)
- `OfflineSpeech.isModelInstalled(context: Context): Boolean`
- `OfflineSpeech.getModelSizeBytes(context: Context): Long`
- `OfflineSpeech.deleteModel(context: Context): Boolean`
- `OfflineSpeech.downloadModel(context: Context, onProgress: (Float) -> Unit, onComplete: (Boolean) -> Unit)`

## Code Layout
- `app/src/main/java/com/echoreading/audio/AudioQualityChecker.kt`: Quality evaluation (SNR, silence, clipping, voice presence).
- `app/src/main/java/com/echoreading/speech/SpeechProcessingService.kt`: STT foreground service, PCM capture, checkpoints, downloads, and transcription orchestration.
- `app/src/main/java/com/echoreading/share/ShareIntentHandler.kt`: Intent extraction for text and audio streams.
- `app/src/main/java/com/echoreading/MainActivity.kt`: Main activity, adaptive layout (`NavigationBar` / `NavigationRail`), intent routing.
- `app/src/main/java/com/echoreading/TranscriberScreen.kt`: STT UI, mic record button, copy and send-to-reader actions.
- `app/src/main/java/com/echoreading/SettingsScreen.kt`: TTS and STT voice model configuration and storage overview.
- `app/src/main/java/com/echoreading/speech/OfflineSpeech.kt`: Whisper model catalog, verified installation, selection, and inference.
- `app/src/main/java/com/echoreading/voice/OfflineVoice.kt`: Piper TTS model catalog, installation, and repair.
- `app/src/main/AndroidManifest.xml`: Permissions (`RECORD_AUDIO`), intent filters (`ACTION_SEND` audio/* & text/plain).
- `app/src/test/java/com/echoreading/`: Comprehensive JVM unit tests.
