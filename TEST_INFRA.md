# Test Infrastructure Specification — EchoReading 2.3.0

## 1. Scope

The tracked automated suite has two layers:

1. A **JVM test suite** under `app/src/test/java/`, used as a fast regression gate
   for pure Kotlin logic, parsers, state helpers, file contracts, and static
   Android configuration checks.
2. A small **Android instrumentation suite** under `app/src/androidTest/java/`
   that runs on a booted emulator/device and exercises real services, TTS
   playback, Activity lifecycle, and UIAutomator navigation.

The instrumentation layer is intentionally small (four smoke tests). Compose
rendering, hyper-specific audio output, and permission-denial variants remain
manual. Durable voice-download behavior (R10-R12) was verified manually in debug
and signed release builds, and history-database recovery (R13) was manually
simulated with a read-only database; neither has an automated failure test.

## 2. Design principles

1. **JVM-first and deterministic**: no network dependency for the normal test
   task; tests use local fixtures and temporary files.
2. **Real logic where practical**: tests exercise parsing, math, state transitions,
   checksums, and file handling rather than trivial assertions.
3. **Isolation**: tests initialize their own state and clean up temporary files.
4. **Static contracts are allowed**: manifest/resource XML is parsed to catch
   regressions in exported components, permissions, backup settings, and intent
   filters.
5. **No false end-to-end claim**: a test suite running on the JVM is not evidence
   that a service, notification, or physical playback path works; use the
   instrumentation suite for those paths and report only checks that actually ran.

## 3. Source layout

```text
app/src/test/java/
├── android/net/TestUri.kt                  # JVM URI stub
├── com/echoreading/
│   ├── *StressTest.kt, *HardeningTest.kt   # adversarial groups
│   ├── *Test.kt                            # Markdown, cache, playback, catalog
│   ├── e2e/
│   │   ├── *RequirementTest.kt             # requirement-oriented scenarios
│   │   ├── *WorkflowsTest.kt               # multi-step JVM scenarios
│   │   └── testutil/                       # audio, intent, manifest, protobuf oracles
│   ├── share/                              # ShareIntentHandler tests
│   ├── speech/                             # audio quality and Whisper catalog tests
│   └── voice/                              # eSpeak data tests
app/src/androidTest/java/com/echoreading/
└── ReaderAndroidSmokeTest.kt               # ActivityScenario + UIAutomator smoke tests
```

## 4. Current inventory

| Group | Classes | Tests | What it covers |
|---|---:|---:|---|
| `com.echoreading.e2e` | 9 | 260 | Reader/navigation/share/model requirements and workflows |
| `com.echoreading` adversarial | 8 | 174 | Stress, boundaries, wire formats, speed continuity, model repair |
| `com.echoreading` core | 9 | 82 | Markdown, metadata, cache, playback media/notifications, chunks, catalog, ElevenLabs |
| `share`, `speech`, `voice` | 4 | 36 | Intent extraction, audio quality, Whisper catalog, eSpeak data |
| **Total** | **30** | **552** | 1 skipped when the optional bundled TTS config is absent |
| Android instrumentation | 1 | 4 | launch/Home, share from Settings, `loadText` reset, real playback/seek/speed/history |

The JVM test XML records 0 failures and 0 errors. The instrumentation XML records
4 tests, 0 failures, 0 errors, 0 skipped on Pixel_6 API 37 x86_64.

## 5. Test tools

### JVM stubs
- `TestUri.kt` supplies the small URI surface needed by intent/source tests.

### `e2e/testutil`
- `AudioTestFixtures.kt`: deterministic waveform synthesis and DSP helpers.
- `IntentTestContracts.kt`: intent extraction and share simulation.
- `ManifestTestParser.kt`: XML DOM helpers for manifest/strings/themes.
- `ProtobufTestOracle.kt`: independent parser/injector for the metadata wire format.

## 6. Commands and quality gates

```powershell
# Full JVM regression gate
.\gradlew.bat :app:testDebugUnitTest --console=plain

# Android instrumentation gate (booted emulator/device required)
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain

# Optional voice asset verification
.\gradlew.bat :app:verifyBundledVoice --console=plain

# APK packaging + Sherpa/ONNX ABI check
.\gradlew.bat :app:verifySherpaPackaging --console=plain

# Release configuration smoke build
.\gradlew.bat :app:assembleRelease :app:bundleRelease --console=plain
```

Release signing is optional. Set `RELEASE_STORE_FILE`,
`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` in the
environment or as Gradle properties to sign the APK/AAB; without them the
artifacts are unsigned.

Recommended gate before handing off:

- 0 test failures and 0 errors.
- The only allowed skip is the optional bundled-config test, unless a new skip is
  documented in `docs/REMEDIATION.md`.
- `verifyBundledVoice` passes before a release build.
- `verifySherpaPackaging` passes for the four packaged ABIs.
- `connectedDebugAndroidTest` passes before shipping a change to reader
  playback, navigation, or share handling.
- No claim of device coverage without an actual instrumentation/manual run.

## 7. What is not covered

The four instrumentation tests do not cover every Android path. Still open:

- Notification-permission denial and foreground-service start restrictions.
- Compose rendering, gestures, adaptive layout, and rotation.
- Changing to a second installed voice during active playback.
- Real codec/audio-device behavior and audible output.
- Automated voice-download continuation across navigation and process death
  (manually verified in debug and signed release).
- Automated history-database corruption/read-only recovery (manually simulated
  in debug).
- Signed production release installation and Play policy checks.

These gaps are tracked in `docs/REMEDIATION.md` R9-R13.
