# Test Readiness — EchoReading 2.3.0

**Status**: JVM suite passing; four-test Android instrumentation suite passing
on the Pixel_6 API 37 x86_64 emulator

**Source sets**: `app/src/test/java/` (JVM), `app/src/androidTest/java/`
(instrumentation)

**Environment**: local JVM through Gradle; booted emulator/device for
instrumentation (Windows: `.\gradlew.bat`)

**Current result**: 552 JVM tests, 0 failures, 0 errors, 1 skipped (30 suites);
4 Android instrumentation tests, 0 failures, 0 errors, 0 skipped

**Last verified**: 2026-10-21

## What this suite is

This is a fast JVM suite. It exercises pure Kotlin logic, parsing, file/state
helpers, and static manifest/resource contracts. It does **not** launch Android
services, an emulator, Media3 playback, or Compose UI. `testOptions.unitTests.returnDefaultValues = true`
is enabled, so Android framework calls that reach the JVM stubs can return default
values; the suite is not a substitute for device testing.

The one skipped test is `OnnxMetadataTest.extractMetadataFromConfig`: it requires
an optional bundled TTS config file that is provisioned outside Git. With the
large Tugão model absent from a fresh checkout the suite historically reported
three skips; after `fetchBundledVoice` the current run reports one skip.

## Run commands

```powershell
# JVM tests
.\gradlew.bat :app:testDebugUnitTest --console=plain

# Android instrumentation (requires a booted emulator/device)
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain

# Bundled voice (must pass before release packaging)
.\gradlew.bat :app:verifyBundledVoice --console=plain

# Debug APK + JNI/ABI packaging check
.\gradlew.bat :app:verifySherpaPackaging --console=plain
```

HTML results: `app/build/reports/tests/testDebugUnitTest/index.html` and
`app/build/reports/androidTests/connected/debug/index.html`

Machine-readable results: `app/build/test-results/testDebugUnitTest/` and
`app/build/outputs/androidTest-results/connected/debug/`

## Current suite layout

| Group | Classes | Tests | Focus |
|---|---:|---:|---|
| `com.echoreading.e2e` | 9 | 260 | Requirement-oriented reader, navigation, share, model, and workflow tests |
| `com.echoreading` adversarial | 8 | 174 | Stress, boundary, wire-format, speed-continuity, and model-repair probes |
| `com.echoreading` core | 9 | 82 | Markdown, metadata, audio cache, playback notifications/media URIs, chunks, catalog, ElevenLabs |
| `share`, `speech`, `voice` subpackages | 4 | 36 | Intent extraction, audio quality, Whisper catalog, eSpeak data |
| **Total** | **30** | **552** | |

The test names use historical “Tier 1–4” and “E2E” labels. Treat them as
JVM-level scenarios, not end-to-end Android tests.

## Android instrumentation suite

`app/src/androidTest/java/com/echoreading/ReaderAndroidSmokeTest.kt` runs on a
real emulator/device. Compose/Espresso's injection path fails on API 37
(`InputManager.getInstance()` reflection), so the tests use
`ActivityScenario` plus UIAutomator.

| Test | Covers |
|---|---|
| `appLaunchesOnHome` | Process launch, Home content, and the four bottom tabs |
| `shareFromSettingsUpdatesDraftAndReturnsHome` | `ACTION_SEND` received while Settings is visible; draft/state/Home routing |
| `loadTextResetsPositionAndEpoch` | Canonical `ReaderState.loadText` reset and epoch bump |
| `playbackSeekSpeedAndHistoryLoad` | Real TTS playback, position growth, speed restart, seek, and history load while playing |

Current device result (Pixel_6, API 37, x86_64): **4 tests, 0 failures, 0
errors, 0 skipped** in 26.145 s in the final post-R13 run (58.067 s in the first
run).

## Test utilities

- `android/net/TestUri.kt`: minimal URI implementation for JVM tests.
- `e2e/testutil/AudioTestFixtures.kt`: waveform/DSP fixtures.
- `e2e/testutil/IntentTestContracts.kt`: intent extraction and share simulation.
- `e2e/testutil/ManifestTestParser.kt`: manifest/string/theme XML parsing.
- `e2e/testutil/ProtobufTestOracle.kt`: independent protobuf wire-format oracle.

## Verification evidence

```text
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL
30 suites, 552 tests, 0 failures, 0 errors, 1 skipped

> Task :app:connectedDebugAndroidTest
BUILD SUCCESSFUL
4 tests, 0 failures, 0 errors, 0 skipped (Pixel_6 API 37 x86_64)
```

Additional packaging evidence:

- `:app:verifyBundledVoice` passed with the provisioned 63,201,425-byte model.
- `:app:verifySherpaPackaging` passed; the debug APK contains the Tugão `.onnx`
  and JNI libraries for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.
- `:app:assembleRelease` and `:app:bundleRelease` passed on 2026-10-21 with R8
  minification/resource shrinking. The final release APK is 200,385,009 bytes
  unsigned (200,439,120 bytes after test signing); the final AAB is 124,650,909
  bytes. A temporary test key signed a release build for installation and smoke
  testing; it is not a production signing key.
- Manual device checks also covered the notification permission prompt/channel,
  pause/resume through on-screen controls and media keys, slider seek, restart,
  speed change, share from Settings, history load while playing, and opening the
  voice picker during playback.
- Durable voice download was checked manually in debug and signed release builds:
  start a 60.2 MB catalog download, `am force-stop` mid-transfer, relaunch, and
  observe WorkManager reschedule and complete; Settings/Voice Discovery then
  reported the voice as installed. This path has no automated test yet.
- History-database failure recovery (R13) was checked manually on debug with a
  0-byte read-only `history.db`: playback still completed, the unusable database
  was recreated, and one history entry was written. No `FATAL EXCEPTION` occurred.

## Android coverage and remaining gaps

Covered automatically on the emulator: launch/Home, share from Settings,
`loadText` reset, real playback start, position growth, speed restart, seek, and
history load while playing.

Still not covered automatically:

- A change to a second installed voice model (only the bundled voice is
  installed on the test device).
- Rotation/adaptive layout and other real Compose rendering paths.
- Notification permission denial and foreground-service restrictions.
- Voice-download continuation across navigation and process death in an
  automated test (manually verified in debug and signed release).
- History-database corruption/read-only recovery in an automated test (manually
  simulated in debug).
- Audible-output quality and real codec/device behavior.
- Signed production installation and Play policy checks.

See `docs/REMEDIATION.md` R9-R13 for the tracked follow-ups.
