# Test Infrastructure Specification — EchoReading 2.0

## 1. Test Philosophy

EchoReading 2.0 adopts an **opaque-box, requirement-driven testing philosophy** directly derived from the authoritative user specifications in `ORIGINAL_REQUEST.md` and architectural commitments in `PROJECT.md`.

### Core Principles
1. **Opaque-Box Specification Conformance**: Tests validate observable behaviors, public contracts, byte-level invariants, and system integration points rather than fragile implementation details.
2. **Authoritative Ground Truth**:
   - For **R1 (Piper Voice Model Metadata)**: Expected outputs and Protobuf schemas are derived directly from the working bundled asset `pt_PT-tugao-medium.onnx`, the official ONNX Protocol Buffer specification (`ModelProto.metadata_props` tag `0x72`), and official Piper companion JSON schemas.
   - For **R2 (Text Selection "Ecoar" & Quick Read)**: Expected outputs are derived from Android's `ACTION_PROCESS_TEXT` contract, window manager translucent bottom sheet specs, reactive state transitions in `ReaderState`, and background continuity contracts of `ReaderPlaybackService`.
   - For **R3 (System Share Sheet Direct Routing)**: Expected outputs are derived from Android's `ACTION_SEND` MIME type matching, `MainActivity` singleTop task routing, text sanitization rules, and UI synchronization flows.
3. **Zero Facade Policy**: No mock tests that trivially assert true. Every test exercises real data transforms, binary serialization/deserialization, state machine updates, file system operations, or contract parsers.
4. **Deterministic and Self-Contained**: Each test initializes its own state, isolates temporary file operations, produces repeatable results, and cleans up artifacts upon completion.

---

## 2. Feature Inventory Mapping to Test Tiers

The test suite is structured into four progressive tiers across all features defined in `PROJECT.md § Feature Inventory`:

| Feature # | Feature Description | Milestone | Tier 1: Happy Path (>=5) | Tier 2: Boundary & Corner Cases (>=5) | Tier 3: Cross-Feature Interactions | Tier 4: Real-World Scenarios |
|---|---|---|---|---|---|---|
| **1** | Protobuf Metadata Injection | M1 | `VoiceModelRequirementTest` (F1.1 - F1.5) | `VoiceModelRequirementTest` (B1.1 - B1.5) | Combinations with corrupt headers & model loading | End-to-end download & playback pipeline |
| **2** | Retroactive Model Repair | M1 | `VoiceModelRequirementTest` (F2.1 - F2.5) | `VoiceModelRequirementTest` (B2.1 - B2.5) | In-place repair on legacy disks | Legacy voice activation workflow |
| **3** | Model File Size Check Fix | M1 | `VoiceModelRequirementTest` (F3.1 - F3.5) | `VoiceModelRequirementTest` (B3.1 - B3.5) | Re-download prevention with injected bytes | Catalog discovery & installation check |
| **4** | Metadata Unit Testing | M1 | `VoiceModelRequirementTest` (F4.1 - F4.5) | `VoiceModelRequirementTest` (B4.1 - B4.5) | Byte-level wire validation | End-to-end model verification |
| **5** | "Ecoar" Action Item Label | M2 | `EcoarActionRequirementTest` (F5.1 - F5.5) | `EcoarActionRequirementTest` (B5.1 - B5.5) | Process text vs Share sheet disambiguation | Browser selection to reading |
| **6** | Translucent Floating Bottom Sheet | M2 | `EcoarActionRequirementTest` (F6.1 - F6.5) | `EcoarActionRequirementTest` (B6.1 - B6.5) | Activity theme & gravity validation | In-place overlay without switching apps |
| **7** | Immediate Auto-Playback | M2 | `EcoarActionRequirementTest` (F7.1 - F7.5) | `EcoarActionRequirementTest` (B7.1 - B7.5) | Playback trigger on empty vs populated text | Quick listening workflow |
| **8** | Bottom Sheet Playback Controls | M2 | `EcoarActionRequirementTest` (F8.1 - F8.5) | `EcoarActionRequirementTest` (B8.1 - B8.5) | Speed pills switching during active playback | Interactive listening session |
| **9** | "Abrir no Leitor" Expansion | M2 | `EcoarActionRequirementTest` (F9.1 - F9.5) | `EcoarActionRequirementTest` (B9.1 - B9.5) | Expansion intent extras & flags | Transition from widget to full app |
| **10** | Background Playback Continuity | M2 | `EcoarActionRequirementTest` (F10.1 - F10.5) | `EcoarActionRequirementTest` (B10.1 - B10.5) | Activity finish while service playing | Dismiss sheet, background playback |
| **11** | Share Sheet Direct Routing | M3 | `ShareSheetRequirementTest` (F11.1 - F11.5) | `ShareSheetRequirementTest` (B11.1 - B11.5) | Filter isolation between MainActivity & QuickRead | Share sheet targeting |
| **12** | Share Intent Extraction & Routing | M3 | `ShareSheetRequirementTest` (F12.1 - F12.5) | `ShareSheetRequirementTest` (B12.1 - B12.5) | Cold start vs warm start (singleTop) | Notes app to Reader workflow |
| **13** | Reader Input State Synchronization | M3 | `ShareSheetRequirementTest` (F13.1 - F13.5) | `ShareSheetRequirementTest` (B13.1 - B13.5) | Sharing text while idle vs active reading | Shared text editing & playback |

---

## 3. Test Architecture & Structure

```
app/src/test/java/com/echoreading/e2e/
├── VoiceModelRequirementTest.kt       # Tier 1, 2, 3 tests for R1 (Features 1-4)
├── EcoarActionRequirementTest.kt      # Tier 1, 2, 3 tests for R2 (Features 5-10)
├── ShareSheetRequirementTest.kt       # Tier 1, 2, 3 tests for R3 (Features 11-13)
├── EndToEndReadingWorkflowsTest.kt    # Tier 4 real-world user workflows & multi-feature journeys
└── testutil/
    ├── ProtobufTestOracle.kt          # Reference Protobuf wire encoder/decoder
    ├── IntentTestContracts.kt         # Contract evaluators for Intent actions & extras
    └── ManifestTestParser.kt          # XML DOM parser for AndroidManifest and resource validation
```

### Execution Strategy
- All tests execute directly on the JVM via `./gradlew testDebugUnitTest`.
- Tests do not require physical hardware or emulators, allowing sub-second execution in CI/CD pipelines.
- Temporary files use unique system temp directories with guaranteed cleanup in `@After` blocks.

---

## 4. Coverage Thresholds & Quality Gates

| Metric | Target | Verification Tool |
|---|---|---|
| **Tier 1 Happy Path Coverage** | >=5 test cases per feature | JUnit 4 Suite |
| **Tier 2 Edge/Boundary Coverage** | >=5 test cases per feature | JUnit 4 Suite |
| **Tier 3 Combinatorial Coverage** | Cross-feature pairwise suites | JUnit 4 Suite |
| **Tier 4 Real-World Workflows** | >=5 complete user scenarios | JUnit 4 Suite |
| **Test Compilation & Execution** | Zero failures, zero warnings | `./gradlew testDebugUnitTest` |
| **Application Compilation** | Clean build | `./gradlew assembleDebug` |
