# Lattice

A private, on-device CBT journaling app for Android. You write. The model thinks. Nothing leaves the phone unless you say so.

---

## What it does

Lattice lets you log journal entries with a mood coordinate (valence × arousal on the circumplex model), automatically masks the names of people you mention with stable per-person UUIDs, and runs a three-stage cognitive reframing pipeline locally using a Gemma 3 1B model via LiteRT-LM. The reframe is streamed token-by-token to the UI. You can accept it (it persists to the entry) or dismiss it.

The pipeline is triggered by a **Reframe** button — available in the journal editor and on any saved entry's detail screen.

---

## Privacy model

All inference is local by default. The orchestrator routes requests through a strict priority chain:

```text
Gemini Nano (AICore, API 35+)
  → Gemma 3 1B via LiteRT-LM (all API levels)
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
  EmbeddingProvider     Snowflake Arctic Embed XS (384-dim, LiteRT TFLite)
  JournalRepository     PII masking, embedding generation, vibe score updates
  LlmOrchestrator       Provider routing + sovereignty gate
  ReframingLoop         3-stage CBT inference pipeline
  SearchRepository      Cosine similarity search + RAG evidence retrieval
  PiiShield             Name → [PERSON_uuid] masking / unmasking
core-data/              Room entities, DAOs, database + migrations
```

### Room schema (v8)

| Entity | Purpose |
|---|---|
| `JournalEntry` | Masked text (nullable since v8), mood coords, 384-dim embedding blob, reframed content |
| `Person` | Name registry with per-person vibe score |
| `Place` | Location registry |
| `Tag` | Topic tags |
| `ActivityHierarchy` | Behavioral Activation activity list (difficulty 0–10) |
| `TransitEvent` | Cloud routing audit trail |
| `Mention` | Person mention tracking per entry |
| `PhoneNumber` | Contact linking |

---

## Reframing pipeline

Triggered by the **Reframe** button in the journal editor or entry detail screen. Runs entirely on-device via `LocalFallbackProvider`.

**Stage 1 — Affective Mapping**
Prompts the model for `v=<n> a=<n>` coordinates. Maps to a `MoodLabel` via the Russell circumplex model. Updates the mood grid in the editor.

**Stage 2 — Diagnosis of Thought (DoT)**
Identifies active cognitive distortions (Burns taxonomy, 12 types). When a trained `DistortionMlp` head is present it runs as a deterministic 384→128→12 MLP (<5 ms, no LLM call). Without a trained head it falls back to chain-of-thought prompting with a `DISTORTIONS: <csv>` sentinel line.

**Stage 3 — Strategic Pivot**
Quadrant-aware intervention — strategy selected from valence/arousal coordinates:
- **Q2** (`v<0, a≥0` — tense/angry): `SOCRATIC_REALITY_TESTING` — Socratic questioning + probability calibration
- **Q3** (`v<0, a<0` — depressed/fatigued): `BEHAVIORAL_ACTIVATION` — lowest-difficulty BA activity + positive past evidence
- **Q1/Q4 high** (`v≥0.4`): `STRENGTHS_AFFIRMATION` — genuine strength surfaced from the entry
- **Q1/Q4 low** (`0≤v<0.4`): `REFLECTION` — what the entry reveals about what matters to the writer

For `REFLECTION` entries with no detected distortions, the LLM is skipped entirely. A `REFLECTION_CARD` is assembled from past entries mentioning the same people/places — no generation, no hallucination risk.

Stage 3 is streamed token-by-token into a `ReframeBottomSheet`. Positive past entries mentioning the same `[PERSON_uuid]` tokens are injected as RAG evidence via `SearchRepository.findEvidenceEntries()`.

**ReframeState lifecycle:**
```text
Idle → Loading → Streaming(partial) → Done(text)
                                    → Error(msg)
```

---

## Local models

| Model | Role | Format |
|---|---|---|
| Snowflake Arctic Embed XS | Semantic embeddings | LiteRT TFLite float32, 384-dim |
| Gemma 3 1B Instruct | Stage 1 + Stage 3 reframing | LiteRT INT4 — three hardware tiers |

Three model variants are available. `LocalFallbackProvider` selects at runtime via
`Build.BOARD`; `./gradlew downloadModels` downloads the right one via ADB:

| Tier | File | Target | Latency |
|---|---|---|---|
| Elite | `gemma3-1b-it-elite.litertlm` | SM8750 S25 Ultra | ~10 s reframe |
| Ultra | `gemma3-1b-it-ultra.litertlm` | SM8650 S24 Ultra | ~15 s reframe |
| Universal | `gemma3-1b-it-int4.litertlm` | Any ARM64 | fallback |

The selected file is read from `context.filesDir` on init (LiteRT-LM requires a
filesystem path). Context window is 1,280 tokens (`ekv1280`).

> **Model files are not committed to this repository.** Run `./gradlew downloadModels` before building.

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

Kotlin · Jetpack Compose · Room (SQLCipher encrypted) · LiteRT (embeddings + LiteRT-LM for Gemma) · Coroutines/Flow · Retrofit + Moshi · WorkManager · KSP
