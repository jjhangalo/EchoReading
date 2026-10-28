# Project: EchoReading 2.3.0

EchoReading is an offline-first Android reader and speech-to-text assistant built
with Jetpack Compose, Media3, Sherpa-ONNX, and Piper/Whisper models. It requires
Android 13 (API 33) or newer.

## Architecture

- **Reader UI and navigation**: `ui/EcoApp.kt` hosts four routes (`home`,
  `transcribe`, `history`, `settings`) in a Compose `NavHost`. Portrait and
  landscape viewports use a bottom bar and rail respectively. Tab selection keeps
  Home as the single root, preserves tab state, and avoids duplicate destinations.
- **Reader state**: `reader/ReaderState.kt` is the canonical `StateFlow` for text,
  status, position, duration, voice, speed, and errors. `loadText()` resets the
  session and uses an epoch guard so a late callback from an old playback session
  cannot overwrite newly loaded text.
- **History**: `reader/ReadingHistory.kt` persists entries in SQLite. Because
  history is non-critical, it retries database access, recreates the helper, and
  deletes an unusable database as a last resort instead of allowing a history
  write to crash playback. A failed history store degrades to empty/no-op
  behavior and is logged under the `ReadingHistory` tag.
- **Playback**: `reader/ReaderPlaybackService.kt` owns a Media3 `ExoPlayer` and
  `MediaSession`. It synthesizes text in chunks, supports seek, ±10 seconds,
  restart, pause/resume, media-notification controls, and active speed/voice
  changes. `util/ComandUtils.kt` is the typed command-entry helper.
- **Playback progress**: `ui/component/PlaybackProgressSection.kt` owns the seek
  bar and time row. The service publishes an authoritative position every
  500 ms with `ReaderSnapshot.positionUpdatedAtNanos`; the component re-anchors
  to that monotonic timestamp and advances with `withFrameNanos`, so it follows
  the display clock (60/90/120 Hz) without stepping. Generated audio already
  contains the selected synthesis speed and Media3 plays it at 1x, so
  interpolation must not apply `snapshot.speed` again. Pause, seek, restart, and
  end snap to the supplied position instead of extrapolating.
- **Reader text card**: `AppTextCard` grows its collapsed field with the text
  from 160 dp to 200 dp. When the content exceeds that limit, a down-arrow in
  the header opens a full-screen editor/dialog; closing it returns to the
  auto-grown height. During playback the card renders the styled text through
  its own selectable `ScrollState` instead of a read-only `TextField`. Home
  passes the current chunk as a visual character offset; the card follows it
  only when the spoken line reaches the lower viewport margin, placing it about
  one third from the top so following text stays visible. If the user scrolls
  ahead, the card does not pull the view back.
- **Text-to-speech**: `voice/OfflineVoice.kt` manages bundled and downloaded Piper
  VITS models through Sherpa-ONNX. The Portuguese (Portugal) Tugão voice is the
  bundled/default model; Portuguese (Brazil) and English (US) models are optional
  downloads. `voice/VoiceDownloadWorker.kt` runs transfers through WorkManager,
  and `voice/VoiceDownloadManager.kt` maintains a persistent FIFO queue: one
  transfer is active at a time and the next requested voice starts automatically
  after success, failure, or cancellation. Active progress, queue position, and
  terminal completions are exposed through the same `StateFlow` contract; the
  top app bar shows a global transfer chip/sheet on every route, and Settings,
  Voice Discovery, and the voice picker allow queued voices to be removed.
  Downloads survive navigation and app-process death; `.part` files are validated
  and renamed only after a complete transfer.
- **Optional online voices**: `voice/ElevenLabs*.kt` and the settings section add
  optional ElevenLabs synthesis. Enabling them sends the text to ElevenLabs over
  the Internet; offline reading never does.
- **Speech-to-text**: `speech/SpeechProcessingService.kt`,
  `speech/SpeechToTextState.kt`, `speech/AudioDecoder.kt`, and
  `speech/OfflineSpeech.kt` implement recording, file/shared-audio ingestion,
  quality checks, chunked transcription, and resumable Whisper model downloads.
  The STT screen selects models with the same bottom-sheet pattern as the TTS
  voice picker, including installed size, download, pause, resume, and progress.
  A playable copy of imported audio (or a WAV of a recording) is kept outside the
  temporary job directory; `TranscriptionSnapshot.audioPath` survives app restart
  and drives separate play/pause controls on the STT screen.
- **History and storage**: `reader/ReadingHistory.kt`, `HistoryScreen.kt`, and
  `StorageStats.kt` persist and display local history and disk usage. Recursive
  storage walks run on `Dispatchers.IO`.
- **Privacy**: Android cloud backup and device-to-device transfer are disabled via
  the manifest and `res/xml/data_extraction_rules.xml`. Local text, history, and
  preferences stay on the device unless the user explicitly enables ElevenLabs.

## Main flows

1. Type or share text, optionally adjust voice/speed, then play it through the
   foreground Media3 service; playback continues with the notification.
2. Select text in another app and choose the reader action, or share text/audio
   into EchoReading; the app routes to the correct screen from any current route.
3. Open History and load an entry into the reader; the old session is stopped and
   the reader starts cleanly at position 0.
4. Record or import audio, choose a Whisper model, check quality, transcribe, and
   send the result to the reader; the imported/recorded audio can be played and
   paused on the STT screen.
5. Manage bundled/downloaded TTS voices and Whisper models in Settings, including
   storage usage, queue position, removal, and deletion.

## State and interface contracts

- `ReaderState.snapshot` exposes reader status and progress to Compose.
  `positionUpdatedAtNanos` is the monotonic sample time for `positionMs` and is
  set on every service position write, including pause/resume and seek.
- `ReaderSnapshot.characterOffset` is a source-text offset at the start of the
  current synthesis chunk. Home remaps it with
  `MarkdownDocument.originalToVisual()` before passing it to `AppTextCard`;
  during playback Home must not drive the text field selection from it.
- `ReaderState.loadText(context, text)` stops playback, resets the session, writes
  the draft, and emits `loadTextEvent`.
- `ReaderState.setSpeed/setVoice/refreshVoice` update state and notify an active
  service; paused sessions discard stale prepared audio.
- `ReaderPlaybackService` accepts `ACTION_READ`, `ACTION_PLAY`, `ACTION_PAUSE`,
  `ACTION_STOP`, `ACTION_RESET`, `ACTION_BACK`, `ACTION_FORWARD`, `ACTION_SPEED`,
  `ACTION_VOICE`, and `ACTION_SEEK` with `EXTRA_TEXT`, `EXTRA_POSITION`,
  `EXTRA_VOICE`, and `EXTRA_SPEED`.
- `SpeechToTextState` exposes recording, decoding, quality, transcription, model
  download, pause, resume, and error state. `TranscriptionSnapshot.audioPath` is
  the retained playable copy used by the STT play/pause controls.
- `VoiceDownloadManager.state` exposes the active transfer, progress, bytes, the
  FIFO queue, and durable terminal completions. Screens acknowledge each
  completion by request id; `start()` returns `STARTED`, `QUEUED`, or
  `DUPLICATE`.

## Code layout

- `app/src/main/java/com/echoreading/MainActivity.kt`: activity entry, intent
  routing, startup IO prewarm, and Compose host.
- `app/src/main/java/com/echoreading/ui/EcoApp.kt`: navigation, share routing, and
  adaptive layout.
- `app/src/main/java/com/echoreading/ui/screen/`: reader Home and speech-to-text
  screens.
- `app/src/main/java/com/echoreading/ui/component/`: app bar/voice picker,
  shared reader/transcription text card (`AppTextCard`), audio card,
  frame-clock playback progress row (`PlaybackProgressSection`), Quick Read
  popup, and shared UI.
- `app/src/main/java/com/echoreading/reader/`: reader state, playback service,
  history, chunks, audio cache, and WAV writing.
- `app/src/main/java/com/echoreading/voice/`: TTS models, catalog, downloads,
  ElevenLabs integration, and system TTS service.
- `app/src/main/java/com/echoreading/speech/`: STT service, state, decoding,
  quality checks, and Whisper model management.
- `app/src/main/java/com/echoreading/share/`: share/`ACTION_PROCESS_TEXT` intent
  extraction.
- `app/src/test/java/com/echoreading/`: JVM tests only (see `TEST_READY.md`).

## Build and provisioning

- JDK 21 and Android SDK 37. Current configuration: `minSdk 33`,
  `targetSdk 36`, `compileSdk 37`, Java/Kotlin target 17, version `2.3.0`
  (`versionCode 6`).
- Release builds enable R8 minification and resource shrinking. Sherpa/ONNX keep
  rules live in `app/proguard-rules.pro`.
- The 63 MB Tugão `.onnx` model is intentionally not stored in normal Git history.
  On a clean checkout run:

  ```text
  .\gradlew.bat fetchBundledVoice
  ```

- `verifyBundledVoice` fails with an actionable message if the model is missing or
  corrupt; `assembleRelease` depends on it.
- `verifySherpaPackaging` checks that the debug APK contains the Sherpa-ONNX and
  ONNX Runtime JNI libraries for all four packaged ABIs.
- Release signing is optional and driven by `RELEASE_STORE_FILE`,
  `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`
  (Gradle properties or environment variables). Without them the APK/AAB are
  unsigned. The AAB is produced by `:app:bundleRelease`.

## Known gaps

- Android coverage is a four-test smoke suite
  (`app/src/androidTest/.../ReaderAndroidSmokeTest.kt`): launch/Home, share from
  Settings, `loadText` reset, and real playback/seek/speed/history. It runs on
  the Pixel_6 API 37 emulator with `ActivityScenario`/UIAutomator. Rotation,
  permission denial, a second voice during active playback, and audible output
  remain uncovered.
- Voice-download durability was verified manually in debug and signed release
  builds (force-stop/relaunch), but there is no automated process-death test.
- History-database corruption recovery was verified manually with a 0-byte
  read-only database, but there is no automated failure-injection test.
- Production signing and Play submission are external release steps. The
  signed-release verification used a temporary local test key, not a production
  keystore, and the current Play target-API policy must be checked at submission
  time.
- The Sherpa artifact is pinned to the third-party JitPack coordinate
  `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8`. Vendor the AAR or add Gradle
  dependency verification before a public production release.
- `docs/REMEDIATION.md` is the authoritative task ledger for these gaps.
