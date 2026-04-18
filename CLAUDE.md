# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## MCP Toolset (Android Studio integration)

Two MCP tool groups are available in this project. Prefer them over raw Bash for device and IDE operations.

### `mcp__android__*` — device / build / logcat

| Tool | When to use |
|---|---|
| `build_apk` | Compile a debug APK (wraps `assembleDebug`) |
| `install_apk` / `uninstall_apk` | Push/remove APK on connected device |
| `launch_app` / `stop_app` | Start or kill the running app |
| `get_logcat` / `clear_logcat` | Read or flush device log |
| `get_build_errors` | Surface Gradle/compiler errors after a failed build |
| `get_test_results` | Read instrumented test results from device |
| `list_devices` / `get_device_info` | Query connected ADB devices |

### `mcp__Intelli-j__*` — IDE / project operations

| Tool | When to use |
|---|---|
| `get_file_problems` / `getDiagnostics` | Read IntelliJ inspections / lint errors for a file |
| `search_in_files_by_text` / `search_in_files_by_regex` | Cross-project text/regex search |
| `find_files_by_name_keyword` / `find_files_by_glob` | Locate files when path is unknown |
| `get_symbol_info` | Resolve a symbol's type/declaration |
| `reformat_file` | Apply Kotlin code style to a file |
| `rename_refactoring` | Safe rename across all usages |
| `replace_text_in_file` | Targeted in-file replacement via IDE |
| `build_project` | Trigger an IDE build (alternative to Gradle CLI) |
| `execute_terminal_command` | Run a shell command inside the IDE terminal |
| `get_project_modules` / `get_project_dependencies` | Inspect Gradle module graph |
| `list_directory_tree` / `get_all_open_file_paths` | Navigate project structure |
| `open_file_in_editor` / `create_new_file` | Open or create files in the IDE |

---

## Commands

```bash
# Build
./gradlew assembleDebug

# All unit tests (no device)
./gradlew test

# Unit tests per module
./gradlew :core-logic:testDebugUnitTest
./gradlew :core-data:testDebugUnitTest
./gradlew :app:testDebugUnitTest

# Single unit test
./gradlew :core-logic:testDebugUnitTest --tests "com.github.maskedkunisquat.lattice.core.logic.EmbeddingProviderTest"

# Instrumented tests (requires device/emulator)
./gradlew :app:connectedAndroidTest
./gradlew :core-logic:connectedAndroidTest

# Single instrumented test
./gradlew :app:connectedAndroidTest --tests "com.github.maskedkunisquat.lattice.AppNavHostTest"

# Lint
./gradlew lint
```

---

## Module Structure

Three modules, strict layering — only downward imports are allowed:

| Module | Role | Key constraint |
|---|---|---|
| `:app` | Compose UI, ViewModels, Navigation, `LatticeApplication` | depends on both |
| `:core-logic` | Repositories, LLM pipeline, PiiShield, EmbeddingProvider | depends on `:core-data` only |
| `:core-data` | Room schema, DAOs, entities, `SeedManager` | no Android framework in logic |

`:core-logic` has no Android framework dependency (`unitTests.returnDefaultValues = true`), so all business logic is testable on desktop JVM without a device.

---

## Architecture

### Dependency Injection

There is no DI framework. `LatticeApplication` holds all singletons as `by lazy` properties. The wiring order is:

```
KeyProvider → LatticeDatabase (SQLCipher, encrypted) → DAOs
                                                      ↓
SettingsRepository (DataStore) ──────────────────────→ LlmOrchestrator → ReframingLoop
EmbeddingProvider (TFLite) ───────────────────────────↗
LocalFallbackProvider (MediaPipe, background init) ───↗
CloudProvider (Retrofit) ─────────────────────────────↗
JournalRepository / SearchRepository ────────────────→ ReframingLoop
```

On first launch, SQLCipher performs a one-time plaintext-to-encrypted migration (guarded by a SharedPreferences flag). `localFallbackProvider.initialize()` loads the Gemma 3 1B LiteRT model from assets on a background thread.

### Privacy Model

PII masking is enforced at every system boundary:

1. **Save** — `JournalRepository` calls `PiiShield.mask()` before writing `content` to the DB.
2. **Embed** — `EmbeddingProvider` only receives already-masked text.
3. **Prompt** — `ReframingLoop` builds prompts from masked content only.
4. **Retrieval** — `SearchRepository.findSimilarEntries()` masks the query before embedding it.
5. **Export** — `ExportManager` exports masked content with placeholder-resolution metadata.
6. **Cloud gate** — `LlmOrchestrator` rejects any prompt that contains raw PII before cloud dispatch.

`PiiShield.mask()` substitutes person names (full, first, last, nickname) with `[PERSON_<uuid>]` tokens, sorted longest-first to prevent "John" from shadowing "John Smith". `PiiShield.unmask()` does the reverse for UI display only — never before inference.

### LLM Routing (`LlmOrchestrator`)

Three-tier, local-first:

1. **Nano** — Gemini on-device via Google AICore (API 35+, checked at runtime)
2. **LocalFallback** — Gemma 3 1B Instruct via MediaPipe Tasks GenAI (all APIs, GPU + CPU fallback)
3. **Cloud** — Remote API via Retrofit (disabled by default, requires explicit user opt-in)

When cloud is selected, `privacyState` transitions to `PrivacyLevel.CloudTransit` (amber UI border) and a `TransitEvent` is logged — **the prompt is never persisted**. A `SecurityException` blocks cloud dispatch if raw PII is detected in the prompt.

### CBT Pipeline (`ReframingLoop`)

Three stages, each using the active LLM provider:

- **Stage 1 — Affective Mapping**: Extracts `v=<n> a=<n>` from the model's output, clamps to `[-1, 1]`, maps to `MoodLabel` via `CircumplexMapper`.
- **Stage 2 — Diagnosis of Thought (DoT)**: Chain-of-thought over 12 Burns distortions, sentinel line `DISTORTIONS: <csv>` parsed to `List<CognitiveDistortion>`.
- **Stage 3 — Strategic Pivot**: Quadrant-aware intervention —
  - Q2 `v<0, a≥0` → `SOCRATIC_REALITY_TESTING` (Socratic + probability calibration)
  - Q3 `v<0, a<0` → `BEHAVIORAL_ACTIVATION` (BA activity fetch + positive past evidence)
  - Q1/Q4 `v≥0` → `STRENGTHS_AFFIRMATION`

Stage 3 pulls two optional context blocks before prompting: the lowest-difficulty BA activity from `ActivityHierarchyDao` (for BA mode), and positive past entries anchored to the same `[PERSON_uuid]` tokens via `SearchRepository.findEvidenceEntries()`.

### Room Schema

**Version 8** (`LatticeDatabase`), 6 entities, 7 migrations tracked in `LatticeDatabase.kt`:

- When adding a UNIQUE index during migration, dedupe the shadow table first: `SELECT MIN(id), name FROM <table> GROUP BY name` — `INSERT OR IGNORE` alone only skips PK conflicts, not name conflicts.

- `JournalEntry.content` is **nullable** since v8 (mood-only entries have no text).
- `JournalEntry.embedding` is a `BLOB` (384 × float32, IEEE 754 little-endian, 1536 bytes) since v7 — `LatticeTypeConverters` handles FloatArray ↔ ByteArray. Do not store embeddings as CSV strings.
- `TransitEvent` exists for sovereignty auditing. Seeded entries use `providerName = "seed_injection"`.

### Seed Infrastructure

`SeedManager` (`:core-data`) ingests `assets/seeds/<persona>.json` files for three test personas: **Holmes** (Reality Testing, Q2), **Watson** (Behavioral Activation, Q3, includes `activityHierarchy`), **Werther** (Emotional Reasoning/DoT, mixed valence).

Seed JSON must satisfy:
- ≥30 `journalEntries` (Rule of 30 for RAG baseline)
- `content` uses `[PERSON_<uuid>]` placeholders — raw names rejected
- `embeddingBase64` is Base64 of 1536-byte IEEE 754 little-endian float32 array
- All placeholder UUIDs must resolve to an entry in the same file's `people` block

`clearPersona()` reads a `SeedManifest` from SharedPreferences (written at seed time) to safely delete only the inserted IDs, with a fallback to re-parsing the seed file.

### Embeddings

`EmbeddingProvider` loads `snowflake-arctic-embed-xs_float32.tflite` from assets (87 MB, float32, fixed seq_len=128, 384-dim) via `org.tensorflow.lite.Interpreter` (`com.google.ai.edge.litert:litert` AAR). Inputs are INT64; output `last_hidden_state` [1×128×384] is masked mean-pooled over `attention_mask==1` positions. Does **not** copy to `filesDir` — reads from assets on every init. Fallback is a zero-vector `FloatArray(384)`.

Seed JSON files contain real 384-dim embeddings generated from the masked entry text via Arctic Embed XS.

---

## Assets

**Gemma 3 1B model files** in `app/src/main/assets/` are gitignored. Three hardware tiers
are available; `downloadModels` fetches the right one automatically via ADB. Override with
`-PdownloadTier=elite|ultra|universal` if no device is attached.

```bash
./gradlew downloadModels                        # auto-detect connected device
./gradlew downloadModels -PdownloadTier=elite   # force Elite tier
```

Requires accepting Google's Gemma Terms of Use on HuggingFace. Authenticate with
`huggingface-cli login` (or set `HF_TOKEN`) before running.

| File | Tier | Target | Size |
|---|---|---|---|
| `gemma3-1b-it-elite.litertlm` | Elite | SM8750 (S25 Ultra) — Adreno 830 AOT kernels | ~800 MB |
| `gemma3-1b-it-ultra.litertlm` | Ultra | SM8650 (S24 Ultra) — Adreno 750 AOT kernels | ~800 MB |
| `gemma3-1b-it-int4.litertlm` | Universal | Any ARM64 — JIT / OpenCL fallback | ~800 MB |

`LocalFallbackProvider` selects the tier at runtime via `Build.BOARD`: `kailua` → Elite,
`kalama` → Ultra, anything else → Universal.

HuggingFace repos:
- **Gemma model**: `https://huggingface.co/masked-kunsiquat/gemma-3-1b-it-litert`
- **Embedding model**: `https://huggingface.co/masked-kunsiquat/snowflake-arctic-embed-xs`
- **Clinical persona seeds (dataset)**: `https://huggingface.co/datasets/masked-kunsiquat/clinical-personas`

`core-logic/src/main/assets/` contains the embedding models (`snowflake-arctic-embed-xs_float32.tflite` 87 MB, `snowflake-arctic-embed-xs_float16.tflite` 44 MB) and `vocab.txt` — these **are** committed to git, needed by `:core-logic` tests without any download step. The float32 model is the production path; float16 has a MEAN op dtype issue from onnx2tf conversion and is unused.

---

## Commit Style

Conventional commits with feature-area scopes (not module names): `feat(editor):`, `feat(schema):`, `fix(editor):`, `docs:`, `chore:`, `test:`, `refactor:`. Inline review fixes go in a dedicated commit: `fix: address inline review findings from <area>`.

---

## Testing Patterns

Unit tests (`:core-logic:test`, desktop JVM) use `runTest {}` with fake/stub implementations. Instrumented tests (`:core-logic:androidTest`, `:app:androidTest`) use in-memory Room databases with `runBlocking {}` for DAO assertions.

The key instrumented test pattern from memory: avoid `assertDoesNotExist()` — use `assertCountEquals(0)` instead. Use `waitUntil {}` for async state assertions. DataStore-dependent tests must guard against cross-test state leakage.
