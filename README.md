# Lattice

A private, on-device CBT journaling app for Android. You write. The model thinks. Nothing leaves the phone unless you say so.

---

## What it does

Lattice lets you log journal entries with a mood coordinate (valence × arousal on the circumplex model), automatically masks the names of people you mention with stable per-person UUIDs, and runs a three-stage cognitive reframing pipeline locally using a quantized Llama-3.2-3B model. The reframe is streamed token-by-token to the UI. You can accept it (it persists to the entry) or dismiss it.

**Type `!reframe` anywhere in the editor to trigger the pipeline.**

---

## Privacy model

All inference is local by default. The orchestrator routes requests through a strict priority chain:

```text
Gemini Nano (AICore, API 35+)
  → Llama-3.2-3B via ONNX Runtime (all API levels)
    → Cloud API (disabled by default, requires explicit opt-in)
```

When a request is routed to the cloud provider, the UI border transitions from blue to amber and a `TransitEvent` is written to the local audit log (timestamp + provider name — the prompt is never logged). This is called the **sovereignty gate**.

PII masking is enforced at every boundary:
- Names in journal text are replaced with `[PERSON_<uuid>]` before the entry is saved to Room.
- The embedding pipeline and all LLM prompts receive only masked text.
- `getEntries()` unmasks for UI display using the local person registry.

---

## Architecture

```text
app/                    Compose UI, ViewModel, DI wiring (LatticeApplication)
core-logic/             Business logic — no Android framework dependencies
  EmbeddingProvider     Snowflake Arctic Embed XS (384-dim, ONNX)
  JournalRepository     PII masking, embedding generation, vibe score updates
  LlmOrchestrator       Provider routing + sovereignty gate
  ReframingLoop         3-stage CBT inference pipeline
  SearchRepository      Cosine similarity search + RAG evidence retrieval
  PiiShield             Name → [PERSON_uuid] masking / unmasking
core-data/              Room entities, DAOs, database + migrations
```

### Room schema (v5)

| Entity | Purpose |
|---|---|
| `JournalEntry` | Masked text, mood coords, embedding, reframed content |
| `Person` | Name registry with per-person vibe score |
| `ActivityHierarchy` | Behavioral Activation activity list (difficulty 0–10) |
| `TransitEvent` | Cloud routing audit trail |
| `Mention` | Person mention tracking |
| `PhoneNumber` | Contact linking |

---

## Reframing pipeline

Triggered by `!reframe` in the editor. Runs entirely on-device via `LocalFallbackProvider`.

**Stage 1 — Affective Mapping**
Prompts the model for `v=<n> a=<n>` coordinates. Maps to a `MoodLabel` via the Russell circumplex model. Updates the mood grid in the editor.

**Stage 2 — Diagnosis of Thought (DoT)**
Chain-of-thought facts-vs-beliefs separation followed by identification of active cognitive distortions (Burns taxonomy, 12 types). The final line must contain `DISTORTIONS: <csv>`.

**Stage 3 — Strategic Pivot**
Quadrant-aware intervention generation:
- **Q2** (negative valence, high arousal — tense/angry): Socratic questioning + Reality Testing
- **Q3** (negative valence, low arousal — depressed/fatigued): Behavioral Activation + Evidence for the Contrary
- **Q1/Q4** (positive valence): Strengths affirmation

Stage 3 is streamed token-by-token into a `ReframeBottomSheet`. If past positive entries mention the same people, they are injected as RAG evidence via `SearchRepository.findEvidenceEntries()`.

**ReframeState lifecycle:**
```text
Idle → Loading → Streaming(partial) → Done(text)
                                    → Error(msg)
```

---

## Local models

| Model | Role | Format |
|---|---|---|
| Snowflake Arctic Embed XS | Semantic embeddings | ONNX, int8, 384-dim |
| Llama-3.2-3B-Instruct Q4 | All three reframing stages | ONNX, Q4, 3 shards |

The Llama model shards (`model_q4.onnx`, `model_q4.onnx_data`, `model_q4.onnx_data_1`) are staged to `context.filesDir` on first run — ONNX Runtime requires real filesystem paths for external-data models. NNAPI (NPU/GPU on Snapdragon 8 Elite) is requested first with CPU fallback. Inference uses a KV-cached greedy-decode loop capped at 512 new tokens.

> **Model files are not committed to this repository.** Place them in `app/src/main/assets/` before building. See `assets/snowflake-arctic-embed-xs.onnx.placeholder` for the expected filenames.

---

## Build

```bash
# Clone and open in Android Studio Ladybug or later
./gradlew assembleDebug

# Unit tests (no device needed)
./gradlew :app:testDebugUnitTest :core-logic:testDebugUnitTest :core-data:testDebugUnitTest
```

**Requirements:**
- Android Studio Ladybug+
- JDK 11+
- Min SDK 24 / Target SDK 36
- Model assets in `app/src/main/assets/` (see above)

---

## Export format

`ExportManager.generateManifest()` produces a `manifest.json` following the Lattice v2 schema. Exported content is always masked; embeddings are excluded. See [SPEC.md](SPEC.md) for the full schema.

---

## Tech stack

Kotlin · Jetpack Compose · Room · ONNX Runtime · Coroutines/Flow · Retrofit + Moshi · Coil · Accompanist · KSP
