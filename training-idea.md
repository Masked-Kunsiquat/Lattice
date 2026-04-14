# Training Idea: Benchmarking & On-Device Training for the Cognitive Loop

**Branch:** `feature/training/mlp` | **Schema v11** | **ORT 1.20.0**

---

## 1. Instrumented Benchmarking

### What the pipeline currently exposes

The full Cognitive Loop is three sequential phases:

```
generateEmbedding()  →  findSimilarEntries()  →  Stage 1 → Stage 2 → Stage 3
   EmbeddingProvider       SearchRepository              ReframingLoop
```

`EmbeddingProvider` has dispatcher injection, making it isolatable. `SearchRepository` already loads all entries into memory for cosine comparison (O(N·384) dot products). `ReframingLoop` stages are individually invokable through the existing fake-provider pattern in tests.

### Proposed suite structure

Two tiers. Neither requires a new module yet — Phase 1 lives in `:core-logic:androidTest`.

**Phase 1 — MicroBenchmarkRule (`:core-logic:androidTest`)**

Add `androidx.benchmark:benchmark-junit4` to the module. Three benchmark classes:

| Class | Target | What to measure |
|---|---|---|
| `EmbeddingBenchmark` | `generateEmbedding(text)` | Warm session latency, cold session latency (force session teardown between runs), 384-dim output stability |
| `SearchBenchmark` | `findSimilarEntries()` over N=90 seeded entries | Total retrieval time; cosine ops/sec; result list stability across identical queries |
| `CognitiveLoopBenchmark` | Full Stage 1→2→3 with real `LocalFallbackProvider` | TTFT (first token from Stage 1), per-stage wall time, peak memory (via `Debug.getMemoryInfo()`) |

The seed data is the natural corpus. Watson (Q3/BA path) and Holmes (Q2/Socratic path) exercise both intervention branches. Load them via `SeedManager` into an in-memory Room instance before the benchmark suite runs — the `LatticeDatabaseTest` pattern already does exactly this.

**Phase 2 — MacroBenchmark (new `:benchmark` module)**

A separate Gradle module targeting `com.android.test` that drives the full app via `MacrobenchmarkRule`. Deferred until the Llama model is reliably on the device under test. Targets: startup-to-reframe latency, jank frames during token streaming, memory pressure over a 10-entry session.

### Key feasibility notes

- `BenchmarkRule` suppresses JIT and forces `release` build type — add `debuggable false` / `profileable true` to a `benchmark` build variant in `:core-logic`.
- The embedding benchmark can run today. The full loop benchmark requires the Llama shards present in `filesDir` at test time — gate it with `assumeTrue(LocalFallbackProvider.isInitialized())`.
- Cosine similarity over 90 entries is negligible (<1ms). The benchmark establishes a regression baseline for when the corpus grows to 1k+ entries, where an HNSW index becomes worth considering.

---

## 2. Supervision Signal & Hybrid Training Design

### The core problem

The current schema stores **model outputs** as facts. `valence`/`arousal`/`moodLabel`/`cognitiveDistortions` on `JournalEntry` are all LLM-generated. There is no column that distinguishes "the model said `v=-0.7`" from "the user confirmed `v=-0.7`". That distinction is the entire training signal problem.

### Base Layer: GoEmotions → Circumplex mapping

GoEmotions (58k samples, 27 labels + neutral) provides domain-general coverage the user's own 30-entry corpus cannot. The labels have well-established valence/arousal coordinates in the literature:

| GoEmotions cluster | Approx. circumplex quadrant | Notes |
|---|---|---|
| joy, amusement, excitement | Q1 (v≥0, a>0) | Direct |
| contentment, relief, gratitude | Q4 (v≥0, a<0) | Arousal slightly negative |
| fear, nervousness, anxiety | Q2 (v<0, a≥0) | Maps to TENSE/ANGRY zone |
| anger, annoyance, disgust | Q2 (v<0, a≥0) | Same quadrant, higher arousal |
| sadness, grief, remorse | Q3 (v<0, a<0) | Maps to DEPRESSED/FATIGUED |
| surprise, curiosity | Q1/Q2 border | Arousal high, valence ambiguous |

The base layer is **not fine-tuned on the device**. It is used offline to pre-train the classifier head and ship it as an additional ONNX asset. This sidesteps the entire NDK complexity for the first iteration.

### Refinement Layer: User signals from existing schema

Three signals exist today, one requires a small schema addition:

| Signal | Current schema anchor | How to read it | Quality |
|---|---|---|---|
| Accepted reframe unchanged | `reframedContent IS NOT NULL` | Implicit positive: user saw and accepted Stage 3 output | Weak — user may not have read it |
| Accepted reframe after edit | Requires `reframeEditedByUser: Boolean` (schema v11) | Strong positive for user's own text; strong negative for model's text | **High** |
| User mood grid adjustment | Requires `userValence: Float?`, `userArousal: Float?` (schema v11) | Ground truth coordinates replacing model-predicted ones | **Highest** |
| Distortion list edit | `cognitiveDistortions` currently write-once | Would need `cognitiveDistortionsConfirmed: Boolean` flag | Medium |

**Schema v11 additions** (one migration, all nullable — backward safe):

```sql
ALTER TABLE journal_entries ADD COLUMN user_valence REAL;
ALTER TABLE journal_entries ADD COLUMN user_arousal REAL;
ALTER TABLE journal_entries ADD COLUMN reframe_edited_by_user INTEGER NOT NULL DEFAULT 0;
```

The `reframe_edited_by_user` flag is set by `EntryDetailViewModel` when the user modifies the reframe text before saving — a two-line addition to the existing `updateReframedContent` flow.

### Training signal taxonomy (what trains what)

| Signal | Trains | Loss |
|---|---|---|
| GoEmotions base labels | Classifier head (base weights) | MSE on (v, a) |
| `userValence`/`userArousal` corrections | Classifier head (refinement) | MSE on correction delta |
| (future) embedding click-through in RAG | Arctic projection layer | Contrastive (InfoNCE) |
| `reframeEditedByUser` diffs | Stage 3 prompt quality (offline eval only, not trainable on-device) | — |

---

## 3. ODT Implementation: Comparative Analysis

### Option A — Fine-tune Arctic Embed XS projection layer (ONNX RT Training)

**What trains:** The final projection Linear(384→384) — approximately 150K parameters (with bias). All BERT encoder layers frozen.

**Supervision:** Contrastive triplets (anchor entry, positive entry sharing distortions, negative entry from different quadrant). The existing `cognitiveDistortions` column is the label source.

**Artifact preparation (offline, Python):**
```python
# ort.training.api — generate 4 artifacts from PyTorch checkpoint
artifacts = ort.training.artifacts.generate_artifacts(
    onnx_model,
    requires_grad=["projection.weight", "projection.bias"],
    loss=ort.training.artifacts.LossType.CrossEntropyLoss,
    optimizer=ort.training.artifacts.OptimType.AdamW,
    artifact_directory="assets/training/"
)
```

**JNI bridge (new `core-training` module, C++):**
- ~300 lines: `JNI_OnLoad`, `createTrainingSession`, `runTrainStep`, `runOptimizerStep`, `exportEvalModel`, `releaseSession`
- Uses `OrtTrainingSession` (separate from `OrtSession` used by inference)
- Separate Gradle dependency: `com.microsoft.onnxruntime:onnxruntime-training-android` — distinct artifact from `onnxruntime-android`, must be confirmed available at ORT 1.20.0

**Critical constraint:** arm64-v8a only. `minSdk 24` is fine, but the training session must be gated:
```kotlin
if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) return TrainingResult.Skipped
```
This means x86/x86_64 emulators used for unit testing cannot run the training path.

**Impact on `EmbeddingProvider`:** After training, `exportEvalModel()` writes a new `eval_model.onnx` to `filesDir`. `EmbeddingProvider.initialize()` checks `filesDir` for a fine-tuned eval model and prefers it over the bundled asset. The inference API is unchanged — output is still `FloatArray(384)`.

---

### Option B — Lightweight classifier head for affective mapping (Kotlin-first)

**What trains:** A 2-layer MLP on top of **frozen** Arctic embeddings:
```
Linear(384 → 128) → ReLU → Linear(128 → 2) → Tanh → (valence, arousal)
```
~50K parameters. This **replaces** the regex parsing of `v=<n> a=<n>` from Stage 1.

**Why this is the better first target:**

| Dimension | Option A (Arctic projection) | Option B (Classifier head) |
|---|---|---|
| NDK/JNI required | Yes — ORT Training C++ API | No — pure Kotlin viable |
| arm64-v8a only | Yes | No |
| Offline artifact prep | Complex (4 ORT artifacts) | None (Kotlin-defined model) |
| Supervision data needed | ~200+ triplets | ~30 labeled (embedding, v/a) pairs |
| Data available today | Needs user engagement | GoEmotions base layer ships with app |
| Pipeline impact | Better RAG / Stage 3 evidence | More accurate Stage 1 |
| Fragility removed | — | Eliminates regex parsing of LLM output |

**Implementation sketch (no NDK):**

The MLP forward pass is trivially implementable in Kotlin as matrix operations. For inference at runtime, two `FloatArray` multiplications + ReLU + Tanh is ~0.1ms with zero new dependencies.

For on-device training: the MLP is small enough that a Kotlin backprop implementation is feasible (50K parameters, AdamW state = 100K floats = ~400KB). Alternatively, export the MLP as an ONNX model and use the **inference** `OrtSession` for forward pass, keeping backprop in Kotlin — this avoids the training-API NDK dependency entirely.

**Supervision path:** `userValence`/`userArousal` from schema v11. When a user corrects the mood grid, their coordinates become a new training sample. The GoEmotions base layer provides ~1k pre-labeled samples at install time (stored as compressed assets, decoded to (embedding, v/a) pairs at first training run).

---

### Option A vs B verdict

Build Option B first. It eliminates the most brittle part of the current pipeline (regex parsing LLM output for coordinates), has a clear supervision path from the proposed schema addition, requires no NDK/C++, and works on all ABIs. Option A becomes worth investing in once the user has enough journal entries to produce meaningful contrastive triplets (~200+), which implies sustained usage — likely lattice-v3 territory.

---

## 4. Operationalization

### WorkManager training scheduler

Lives in `:core-logic` as `TrainingCoordinator`, wired up in `LatticeApplication`.

**Trigger conditions (all required):**
- Device charging
- Device idle
- Storage not low
- `N ≥ 30 new labeled samples` since last training run (counted via DAO query on `userValence IS NOT NULL AND timestamp > lastTrainingTimestamp`)

**Work definition:**
```kotlin
PeriodicWorkRequestBuilder<EmbeddingTrainingWorker>(24, TimeUnit.HOURS)
    .setConstraints(Constraints.Builder()
        .setRequiresCharging(true)
        .setRequiresDeviceIdle(true)
        .setRequiresStorageNotLow(true)
        .build())
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
    .build()
```

Inside `EmbeddingTrainingWorker.doWork()`:
1. Count new labeled samples — if `< 30`, return `Result.success()` immediately (no training, no retry)
2. Fetch `(embedding, userValence, userArousal)` triples from DAO
3. Run N training steps (configurable, default 10 epochs over the batch)
4. Save checkpoint, write manifest
5. Return `Result.success()`

A `ForegroundInfo` notification informs the user ("Personalizing your experience…") — required for long-running WorkManager tasks on API 31+.

### Checkpoint versioning for lattice-v2

The versioning problem: a fine-tuned projection layer trained on Arctic XS v1 weights is incompatible with Arctic XS v2 weights. The checkpoint must be invalidated when the base model changes.

**Manifest schema** (`SharedPreferences` key `lattice_training_manifest`, JSON):
```json
{
  "schemaVersion": 1,
  "baseModelHash": "sha256:<hash of snowflake-arctic-embed-xs.onnx>",
  "checkpointPath": "embedding_head_v2_c045.bin",
  "trainedOnCount": 45,
  "lastTrainingTimestamp": 1709251200000,
  "baseLayerVersion": "goEmotions-1.0"
}
```

**`EmbeddingProvider.initialize()` resolution order:**
1. Read manifest from SharedPreferences
2. Compute SHA-256 of bundled ONNX asset
3. If `manifest.baseModelHash == assetHash` → load checkpoint from `filesDir`
4. If mismatch (new model shipped in app update) → delete stale checkpoint + manifest, train from scratch on next WorkManager trigger, log event
5. If no manifest → use base model weights (pre-GoEmotions head, or zero head)

The `baseModelHash` check means a model update in a new app release automatically invalidates user checkpoints without any migration code — the SHA-256 of the asset is the version.

**Orphan cleanup:** On manifest write, glob `filesDir` for `embedding_head_*.bin` and delete any path not matching `manifest.checkpointPath`.

---

## Architecture shift summary

| Layer | Before ODT | After ODT |
|---|---|---|
| Stage 1 output | Regex-parsed `v=<n> a=<n>` from LLM | MLP classifier head on Arctic embedding |
| `EmbeddingProvider` | Loads from assets, inference-only | Checks `filesDir` for fine-tuned checkpoint; falls back to assets |
| Schema | v10, all columns model-output | v11 adds `userValence`, `userArousal`, `reframeEditedByUser` |
| New module | — | Optional `:core-training` (JNI bridge, Option A only) |
| New dependency | — | `WorkManager` (likely already present), `benchmark-junit4` for Phase 1 |
| Privacy posture | Unchanged — training inputs are already masked text | |

The privacy model holds throughout. Training samples are `(FloatArray embedding, Float valence, Float arousal)` — the text has already been discarded by the time a training sample is constructed. The checkpoint lives in `filesDir` (protected by the existing SQLCipher key provider pattern). No training data, labels, or checkpoint ever leaves the device.

---

## 5. Implementation Progress

### Milestone 1 — Benchmarks & Schema v11 ✅
- `BenchmarkRule` suite in `:core-logic:androidTest` (`EmbeddingBenchmark`, `SearchBenchmark`)
- Schema v11 migration: `user_valence`, `user_arousal`, `reframe_edited_by_user`
- Mood grid UI for user valence/arousal correction

### Milestone 2 — Affective MLP (Option B)

| # | Milestone | Status | Key artifact |
|---|---|---|---|
| 2.1 | GoEmotions base asset | ✅ | `core-logic/src/main/assets/training/goEmotions_base_v1.bin` (838 rows, ~1.3 MB) |
| 2.2 | `AffectiveMlp` | ✅ | `Linear(384→128)→ReLU→Linear(128→2)→Tanh`, Xavier init, IEEE 754 LE serialisation; 10 unit tests |
| 2.3 | `AffectiveMlpTrainer` | ✅ | AdamW (β1=0.9, β2=0.999), `trainStep` + `trainBatch`(shuffle+epochs); 9 unit tests |
| 2.4 | `AffectiveMlpInitializer` | ✅ | Reads base asset, warm-starts on first launch (`epochs=5`), saves to `filesDir/affective_head_v1.bin`, SharedPrefs guard; 10 unit tests |
| 2.5 | Wire into Stage 1 | 🔲 | Load checkpoint in `LatticeApplication`; `ReframingLoop` calls `mlp.forward(embedding)` instead of regex-parsing LLM output |

### Milestone 3 — User-driven refinement
- `EmbeddingTrainingWorker` (WorkManager): trigger on `N≥30` labeled samples, charging+idle constraints
- `TrainingCoordinator` in `LatticeApplication`
- Checkpoint manifest (SHA-256 base-model hash for invalidation on app update)