# Training Roadmap

Derived from `training-idea.md`. Three sequential milestones. Each milestone is independently shippable — later milestones depend on the schema and signal infrastructure from earlier ones, but not on Option A (NDK path).

---

## Milestone 1 — Benchmarks & Schema Foundation
**Goal:** Establish performance baselines and capture the first user feedback signals before any training code exists.
**Blocking on:** Nothing. Can start on current branch.

### 1.1 Benchmark build variant
- [x] Add `benchmark` build variant to `:core-logic/build.gradle.kts`: `debuggable false`, `profileable true`, `minifyEnabled false`, `signingConfig` pointing to debug keystore
- [x] Add `androidx.benchmark:benchmark-junit4` to `androidTestImplementation` in `:core-logic`

> **Android Studio integration note:** These two changes are what unlock AS's benchmarking as a first-class feature. Once in place, benchmark classes run from the gutter like unit tests and results appear in the Run/Test panel with median + percentile breakdowns. Setting `profileable true` (not `debuggable true`) also enables **Profiling mode** from the run configuration dropdown — this captures a Perfetto system trace alongside the timing data, which opens directly in the **Profiler tab** (CPU flame chart). Use this when a benchmark flags a regression and you need to see exactly which ONNX internal calls are responsible, without manually attaching the Profiler to a live session.
>
> `minifyEnabled false` is required on this variant. If R8 runs, Perfetto traces will show obfuscated names (`a.b()`, `c.d()`) instead of `EmbeddingProvider.generateEmbedding()` — the flame chart becomes unreadable. The benchmark variant never ships to users, so there's no reason to minify it.

### 1.2 `EmbeddingBenchmark`
- [x] Create `core-logic/src/androidTest/.../EmbeddingBenchmark.kt`
- [x] Measure cold session latency: tear down `OrtSession` between `@Test` iterations, re-initialize, time `generateEmbedding()`
- [x] Measure warm session latency: session stays open, time `generateEmbedding()` across 50 repeated calls
- [x] Assert output dimensionality is 384 and no zero-vector is returned for non-empty input

### 1.3 `SearchBenchmark`
- [x] Create `core-logic/src/androidTest/.../SearchBenchmark.kt`
- [x] Seed in-memory Room with all 3 personas (90+ entries) using `SeedManager` in `@Before`
- [x] Benchmark `findSimilarEntries(query, limit=5)` — record median and p99 across 100 calls
- [x] Benchmark `findEvidenceEntries(placeholders)` separately

### 1.4 `CognitiveLoopBenchmark`
- [x] Create `core-logic/src/androidTest/.../CognitiveLoopBenchmark.kt`
- [x] Gate entire class with `assumeTrue(LocalFallbackProvider.isInitialized())` — skips gracefully on emulators without the Llama shards
- [x] Measure TTFT: time from `reframingLoop.run(entry)` call to receipt of first `LlmResult.Token`
- [x] Measure total wall time per stage (Stage 1, 2, 3 individually)
- [x] Record peak memory via `Debug.MemoryInfo` before and after full loop
- [x] Run Watson entry (Q3/BA) and Holmes entry (Q2/Socratic) as separate benchmark cases

### 1.5 Schema v11 migration
- [x] Add 3 columns to `JournalEntry`:
  ```kotlin
  @ColumnInfo(name = "user_valence") val userValence: Float? = null,
  @ColumnInfo(name = "user_arousal") val userArousal: Float? = null,
  @ColumnInfo(name = "reframe_edited_by_user") val reframeEditedByUser: Boolean = false,
  ```
- [x] Write migration `MIGRATION_10_11` in `LatticeDatabase.kt`:
  ```kotlin
  ALTER TABLE journal_entries ADD COLUMN user_valence REAL;
  ALTER TABLE journal_entries ADD COLUMN user_arousal REAL;
  ALTER TABLE journal_entries ADD COLUMN reframe_edited_by_user INTEGER NOT NULL DEFAULT 0;
  ```
- [x] Bump `LatticeDatabase` version to 11
- [x] Add DAO query: `getLabeledEntriesSince(timestamp: Long): List<JournalEntry>` — filters `user_valence IS NOT NULL AND timestamp > :timestamp`
- [x] Add DAO query: `countLabeledEntriesSince(timestamp: Long): Int` — used by WorkManager gate

### 1.6 Capture `reframeEditedByUser` signal
- [x] In `EntryDetailViewModel.acceptReframe(editedText: String)`: compare `editedText` to the model's original reframe string; if they differ, set `reframeEditedByUser = true` before calling `journalRepository.updateEntry()`
- [x] Unit test: `acceptReframe` with identical text → `reframeEditedByUser = false`; with modified text → `true`

### 1.7 Mood grid UI (user valence/arousal input)
- [x] Add a 2D touch target (draggable point on a circumplex grid) to `EntryDetailScreen` — shown after the reframe is displayed, labelled "How does this land?"
- [x] Wire drag-end coordinates → `EntryDetailViewModel.confirmMoodCoordinates(v: Float, a: Float)`
- [x] `confirmMoodCoordinates` writes `userValence`/`userArousal` to the entry via `journalRepository.updateEntry()`
- [x] The grid is optional — skip button dismisses without writing coordinates (coordinates remain `null`)

**Milestone 1 exit criteria:**
- All 3 benchmark classes run on a physical device without crashing
- Schema v11 migration passes `LatticeDatabaseTest` round-trip
- At least one mood grid correction writes `userValence`/`userArousal` to Room, verified by instrumented test

---

## Milestone 2 — MLP Classifier Head (Option B, Kotlin-first)
**Goal:** Replace the regex-parsed `v=<n> a=<n>` from Stage 1 with a trained MLP that takes a 384-dim embedding as input.
**Blocking on:** Milestone 1 (schema v11 + GoEmotions base layer asset).

### 2.1 GoEmotions base layer asset (offline, Python)
- [x] Write `scripts/prepare_goEmotions_base.py`:
  - Load GoEmotions via HuggingFace: `load_dataset("google-research-datasets/go_emotions", "simplified")` — use the `train` split (43,410 examples, ≥2/3 rater agreement required for inclusion)
  - The dataset has **28 classes** (emotions 0–26 + neutral=27); each comment carries 1–5 labels (multi-label). Strategy: keep only single-label examples to avoid ambiguous v/a targets; this leaves ~30–40% of train (~13–17k examples)
  - Map all 28 class IDs → (valence, arousal) using the Russell & Barrett 1999 circumplex coordinates lookup table — document the full 28-entry table in a comment block at the top of the script; neutral maps to (0.0, 0.0)
  - Run each text through Arctic Embed XS → 384-dim embedding (use the same ONNX model as production)
  - Output `assets/training/goEmotions_base_v1.bin`: header (count, dim) + packed `float32` rows of (384 embedding + 2 labels)
  - Subsample to ~1k balanced examples (equal quadrant representation) to keep asset <6MB
- [x] Commit the generated asset (run `python scripts/prepare_goEmotions_base.py` with model present, then `git add core-logic/src/main/assets/training/goEmotions_base_v1.bin`)
- [x] Add path to `.gitattributes` as `binary`
- [x] Document the label mapping in a comment block at the top of the script

### 2.2 `AffectiveMlp` class (`:core-logic`)
- [x] Create `core-logic/src/main/java/.../AffectiveMlp.kt`
- [x] Architecture: `Linear(384→128) → ReLU → Linear(128→2) → Tanh`
- [x] `forward(embedding: FloatArray): Pair<Float, Float>` — returns (valence, arousal) in [-1, 1]
- [x] Weight storage: two `FloatArray` weight matrices + bias vectors, total ~50K floats (~200KB)
- [x] Serialization: `saveWeights(path: File)` / `loadWeights(path: File)` — raw IEEE 754 LE binary, same convention as existing embedding BLOB

### 2.3 `AffectiveMlpTrainer` class (`:core-logic`)
- [x] Create `core-logic/src/main/java/.../AffectiveMlpTrainer.kt`
- [x] Implements AdamW in Kotlin: maintain `m`/`v` moment buffers alongside weights
- [x] `trainStep(embedding: FloatArray, targetValence: Float, targetArousal: Float)`: forward pass → MSE loss → backward pass → AdamW update
- [x] `trainBatch(samples: List<TrainingSample>)`: shuffle, iterate, call `trainStep` per sample
- [x] Hyperparameters: `lr = 1e-3`, `weightDecay = 1e-4`, `epochs = 10` (all configurable, not hardcoded)
- [x] Unit test: loss decreases over 100 steps on a trivial synthetic dataset

### 2.4 Base layer warm-start
- [x] Create `AffectiveMlpInitializer.kt`: reads `goEmotions_base_v1.bin` from assets, deserializes (embedding, v, a) pairs, runs `AffectiveMlpTrainer.trainBatch()` for `epochs=5`, saves weights to `filesDir/affective_head_v1.bin`
- [x] This runs once on first launch (guarded by `SharedPreferences` flag `affective_head_initialized`)
- [x] Runs on `Dispatchers.Default`, non-blocking — MLP uses random weights until initialization completes

### 2.5 Wire MLP into Stage 1
- [x] In `ReframingLoop.runStage1()`: after `EmbeddingProvider.generateEmbedding(maskedText)`, pass the resulting `FloatArray` to `AffectiveMlp.forward()` → get `(mlpValence, mlpArousal)`
- [x] Keep the existing LLM `v=<n> a=<n>` parse as a **fallback**: if MLP head is not yet initialized (weights file absent), fall through to the existing regex path
- [x] Log which path was taken: `Log.d("Stage1", "source=mlp|regex")`
- [x] Update `AffectiveMapResult` to include `source: AffectiveSource` enum (`MLP`, `REGEX`) for debugging

### 2.6 Checkpoint manifest (MLP)
- [x] `SharedPreferences` key `lattice_affective_manifest`, JSON:
  ```json
  {
    "schemaVersion": 1,
    "baseModelHash": "sha256:<hash of snowflake-arctic-embed-xs.onnx>",
    "headPath": "affective_head_v1_c030.bin",
    "trainedOnCount": 30,
    "lastTrainingTimestamp": 0,
    "baseLayerVersion": "goEmotions-1.0"
  }
  ```
- [x] `AffectiveMlp.load()` checks `baseModelHash` against current asset SHA-256 — mismatch deletes stale weights, re-runs base warm-start on next WorkManager trigger

### 2.7 Audit follow-ups (post-implementation findings)

#### Correctness
- [x] **2.7-a** `AffectiveMlpInitializer.kt` — set `PREF_KEY` guard flag **before** writing weights + manifest, not after; if the process dies between `AffectiveManifestStore.write()` and the `putBoolean()` call, the guard never gets set and warm-start retries forever
- [x] **2.7-b** `AffectiveMlpInitializer.kt` — the `samples.isEmpty()` early-return path (`"No samples in $ASSET_PATH — skipping"`) exits without setting the guard flag; add `prefs.edit().putBoolean(PREF_KEY, true).apply()` before `return@launch`
- [x] **2.7-c** `AffectiveMlpInitializer.kt:loadSamples` — add a pre-read bounds check: `require(bytes.size >= 8 + count * (dim + 2) * 4)` before the deserialization loop; a truncated asset currently throws `BufferUnderflowException` with no diagnostic
- [x] **2.7-d** `AffectiveMlpTrainer.kt` — `data class TrainingSample(val embedding: FloatArray, ...)` — Kotlin generates reference-based `equals`/`hashCode` for `FloatArray`; override both using `embedding.contentEquals()` / `embedding.contentHashCode()`

#### Security
- [x] **2.7-e** `ReframingLoop.kt:runStage1AffectiveMap` — add a `require()` assertion that `maskedText` contains at least one `[PERSON_<uuid>]` placeholder or is blank; CLAUDE.md mandates enforcement at every system boundary, not caller trust

#### Robustness
- [x] **2.7-f** `AffectiveMlp.kt:loadWeights` — the file-size `require()` check races with a possible mid-read modification; add `require(buf.hasRemaining())` inside the `next(n)` lambda with a position-aware error message so `BufferUnderflowException` is never the user-visible failure
- [x] **2.7-g** `AffectiveMlp.kt:saveWeights` — `file.parentFile?.mkdirs()` silently discards a `false` return; replace with `require(parentDir.mkdirs() || parentDir.exists()) { "Failed to create $parentDir" }`
- [x] **2.7-h** `AffectiveMlp.kt:load` — reaching into `AffectiveMlpInitializer.PREF_KEY` to perform eviction is tight coupling; introduce `AffectiveManifestStore.resetAll(prefs)` that owns both key removals so a rename doesn't silently break eviction

#### Test coverage
- [x] **2.7-i** Add end-to-end warm-start integration test in `AffectiveMlpInitializerTest`: load asset → `trainBatch` → save weights → write manifest → assert guard flag set → second call is a no-op
- [x] **2.7-j** Add convergence assertion to `AffectiveMlpTest`: after `AffectiveMlpTrainer.trainBatch()` on a small synthetic dataset, `forward(sample.embedding)` output must be closer to the target than before training
- [x] **2.7-k** Add edge-case tests to `AffectiveMlpTrainerTest`: (1) `lr=1.0` → loss stays finite; (2) zero-gradient step with `weightDecay > 0` → weight magnitudes still decrease
- [x] **2.7-l** Add fallback scenario to `ReframingLoopTest`: embedder present but `generateEmbedding()` throws → source must be `REGEX`, not a crash
- [x] **2.7-m** Add `scripts/test_prepare_goEmotions_base.py` covering: quadrant boundary conditions, binary output byte order (read back as `<f4` and verify first embedding matches input), and single-label filter count

**Milestone 2 exit criteria:**
- `AffectiveMlp.forward()` produces valid (v, a) coordinates for all 3 seed persona entries
- Stage 1 uses MLP path when head is initialized, regex fallback when not — covered by unit tests
- Loss curve from `AffectiveMlpTrainer` decreases monotonically on GoEmotions base layer in an instrumented test

---

## Milestone 3 — WorkManager Scheduler & Refinement Loop
**Goal:** Automate on-device fine-tuning from accumulated user corrections and gate it safely.
**Blocking on:** Milestone 2 (`AffectiveMlpTrainer` complete) + Milestone 1 (schema v11 populated with real user labels).

### 3.1 `EmbeddingTrainingWorker`
- [x] Create `core-logic/src/main/java/.../EmbeddingTrainingWorker.kt` (extends `CoroutineWorker`)
- [x] `doWork()` steps:
  1. Read `lastTrainingTimestamp` from manifest
  2. Call `journalDao.countLabeledEntriesSince(lastTrainingTimestamp)` — if `< 30`, return `Result.success()` immediately
  3. Fetch full sample batch: `journalDao.getLabeledEntriesSince(lastTrainingTimestamp)` — returns `List<JournalEntry>` with non-null `userValence`/`userArousal`
  4. Construct `List<TrainingSample>` from `(entry.embedding, entry.userValence!!, entry.userArousal!!)`
  5. Load current MLP weights from `filesDir` (or base warm-start if missing)
  6. Call `AffectiveMlpTrainer.trainBatch(samples, epochs=10)`
  7. Save updated weights to `filesDir/affective_head_v1_c{count}.bin`
  8. Write updated manifest (bump `trainedOnCount`, update `lastTrainingTimestamp`, update `headPath`)
  9. Delete orphaned `affective_head_*.bin` files not matching new `headPath`
  10. Return `Result.success()`
- [x] Set `ForegroundInfo` with a silent (no sound) notification: "Personalizing Lattice…" — required API 31+
- [x] Handle `CancellationException` from `isStopped` check: save partial weights before exiting

### 3.2 `TrainingCoordinator` (`:core-logic`)
- [x] Create `TrainingCoordinator.kt` — thin wrapper that registers/cancels the WorkManager request
- [x] `scheduleIfNeeded(context: Context)`: enqueues `PeriodicWorkRequest` with `ExistingPeriodicWorkPolicy.KEEP` (no-op if already enqueued)
- [x] `cancelAll(context: Context)`: for settings "disable personalization" toggle
- [x] Constraints: `requiresCharging + requiresDeviceIdle + requiresStorageNotLow`
- [x] Period: 24 hours, backoff: `EXPONENTIAL` starting at 1 hour

### 3.3 Wire into `LatticeApplication`
- [x] Call `trainingCoordinator.scheduleIfNeeded(this)` in `LatticeApplication.onCreate()` — after DB and DAOs are initialized
- [x] Add `SettingsRepository` key `personalizationEnabled: Boolean` (default `true`)
- [x] In `SettingsRepository` observer: when toggled off → `trainingCoordinator.cancelAll()`; when toggled on → `trainingCoordinator.scheduleIfNeeded()`

### 3.4 Settings UI
- [x] Add "Personalization" toggle to the existing settings screen
- [x] Subtitle: "Improves mood detection over time using your corrections. All learning happens on this device."
- [x] Show `trainedOnCount` from manifest as a read-only stat: "Trained on N corrections"
- [x] Show `lastTrainingTimestamp` formatted as "Last updated: <relative date>"
- [x] "Reset personalization" destructive action: deletes all `affective_head_*.bin` from `filesDir`, clears manifest, re-runs base warm-start on next launch

### 3.5 Instrumented integration test
- [x] Create `EmbeddingTrainingWorkerTest` using `WorkManagerTestInitHelper`
- [x] Seed Room with 35 entries all having `userValence`/`userArousal` set
- [x] Enqueue worker, call `testDriver.setAllConstraintsMet(workRequest)`, await completion
- [x] Assert: checkpoint file exists in `filesDir`, manifest `trainedOnCount == 35`, no orphan weight files

**Milestone 3 exit criteria:**
- Worker runs end-to-end in instrumented test without error
- Worker short-circuits cleanly when `count < 30`
- "Reset personalization" deletes all artifacts and clears manifest, verified by test
- Settings toggle cancels/re-enqueues work, verified by `WorkManager.getWorkInfosForUniqueWork()`

---

## Option A deferral — Arctic projection fine-tuning (lattice-v3)

Deferred until the user base has accumulated ~200+ labeled entries (sufficient for contrastive triplet mining). Prerequisites before starting:

- [ ] Confirm `com.microsoft.onnxruntime:onnxruntime-training-android` artifact availability at ORT 1.20.0
- [ ] Export Arctic XS PyTorch weights → 4 ORT training artifacts (offline Python script, separate from Milestone 2 script)
- [ ] New `:core-training` Gradle module with `externalNativeBuild` pointing to a `CMakeLists.txt` for the JNI bridge
- [ ] ABI gate: `if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) return TrainingResult.Skipped`
- [ ] `EmbeddingProvider.initialize()` resolution order update: check for fine-tuned `eval_model.onnx` in `filesDir` before falling back to bundled asset
- [ ] Contrastive triplet miner: uses `cognitiveDistortions` as label — same distortion set = positive pair, different quadrant = hard negative

---

## Dependency map

```
Milestone 1  ──────────────────────────────────┐
  Schema v11                                    │
  Benchmarks                                    │
  reframeEditedByUser signal                    │
  Mood grid UI                                  │
        │                                       │
        ▼                                       │
Milestone 2  ──────────────────────────────────┤
  GoEmotions base asset (offline)               │
  AffectiveMlp + Trainer (Kotlin)               │
  Stage 1 MLP path + regex fallback             │
  Checkpoint manifest                           │
        │                                       │
        ▼                                       ▼
Milestone 3                            Option A (lattice-v3)
  WorkManager scheduler                Arctic projection fine-tune
  TrainingCoordinator                  :core-training JNI module
  Settings UI + reset                  Contrastive triplet miner
```
