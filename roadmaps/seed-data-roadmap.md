# Implementation Roadmap: Seed-Data Engine & Persona-Driven Testing (Schema v7)

> Last updated: 2026-04-09 — §1 complete  
> Branch: `chore/seed-data`  
> Codebase audit performed against current HEAD.

---

## Context

Phase 5 development has pivoted to a **Unified Agent loop** on Llama-3.2-3B. To validate the Strategic Pivot logic (Socratic vs. BA), we need a robust seeding mechanism that allows us to inject clinical benchmarks and literary datasets into the air-gapped environment.

The Room schema is at **version 8** (`LatticeDatabase`, `:core-data`). All seven migrations (v1→v8) are defined and verified. The pivot to `embedding BLOB` (FloatArray, 384-dim, 1536 bytes IEEE-754 LE) landed in the 6→7 migration; `JournalEntry.content` was made nullable in the 7→8 migration to support mood-log entries. The seed infrastructure (§1) is complete.

---

## Module Map

| Module | Role |
|---|---|
| `:app` | Compose UI, ViewModels, navigation, `LatticeApplication`, asset bundling |
| `:core-data` | Room entities, DAOs, `LatticeDatabase`, `KeyProvider`, `CloudCredentialStore` |
| `:core-logic` | Repositories, `EmbeddingProvider`, `PiiShield`, `LlmOrchestrator`, `ReframingLoop` |

There is no `:core-domain` or dedicated `:debug`/`:internal` module. All seed infrastructure will live in `:core-data` (data layer) and `:app` (debug UI).

---

## Scope of Work

---

### 1. Data Ingestion Architecture ✓

**Goal:** A `SeedManager` in `:core-data` that maps JSON seed files to Room entities and inserts them transactionally.

#### 1.1 `RawSeed` Data Class ✓

Seed files drive a `RawSeed` data class that mirrors the "Lattice Mix" distribution (70% journal entries, 20% mood logs, 10% social graph):

```kotlin
data class RawSeed(
    val people: List<RawPerson>,
    val journalEntries: List<RawJournalEntry>,   // 70%
    val moodLogs: List<RawMoodLog>,              // 20% — see §1.2
    val activityHierarchy: List<RawActivity>     // part of 10% social graph
)
```

**`RawJournalEntry` fields:**
- `content: String` — pre-masked text using `[PERSON_<uuid>]` placeholders (matching `PiiShield` contract).
- `embedding: FloatArray` — pre-computed 384-dim vector (snowflake-arctic-embed-xs). Must be serialised as a 1536-byte BLOB via `LatticeTypeConverters`. **Do not store as a CSV string** — the 6→7 migration already dropped that format.
- `valence: Float`, `arousal: Float` — coordinates for `CircumplexMapper`.
- `mentions: List<String>` — list of person UUIDs for `Mention` insertion.

#### 1.2 Mood Logs — Schema Gap ✓

The roadmap calls for mood-only entries (null content, valid valence/arousal). Currently `JournalEntry.content: String` is **non-nullable**. Two options:

Option A implemented: `JournalEntry.content` is now `String?` (schema v8, `MIGRATION_7_8`). All callsites in `JournalRepository`, `SearchRepository`, `ReframingLoop`, and `JournalHistoryScreen` updated with null-safe handling.

#### 1.3 `SeedManager` in `:core-data` ✓

Responsibilities:
1. Parse seed JSON from `assets/seeds/<persona>.json`.
2. Verify all `[PERSON_<uuid>]` placeholders in `content` resolve to a seeded `Person` record.
3. Insert all entities in a single `withTransaction` block via `LatticeDatabase`.
4. Write a `TransitEvent` per journal entry with `model = "seed_injection"` to keep the sovereignty audit log consistent.
5. Expose `suspend fun seedPersona(persona: SeedPersona)` and `suspend fun clearPersona(persona: SeedPersona)` as the public API.

**Guardrail:** `SeedManager` must call `PiiShield.mask()` as a validation pass even on pre-masked seed content, to catch any raw name leakage in the JSON files before insertion.

#### 1.4 Migration Path Verification ✓

`SeedManager.seedPersona()` asserts schema v8 at runtime before any writes:

```kotlin
check(db.openHelper.readableDatabase.version == 8) {
    "SeedManager requires schema v8 — found v${db.openHelper.readableDatabase.version}"
}
```

---

### 2. Persona-Driven Benchmarking

Each persona is a self-contained seed set: a `people` block, a `journal_entries` block, and optionally an `activity_hierarchy` block. Minimum **30 entries per persona** (Rule of 30) to provide a viable RAG baseline for `SearchRepository` cosine similarity.

#### Benchmark A — "Sherlock Holmes" (Q2 Focus)

- **Clinical target:** Reality Testing and Decatastrophizing.
- **Mood profile:** High arousal, negative valence (upper-left circumplex — `TENSE`/`ANGRY` labels).
- **Content pattern:** Catastrophising journal entries ("This is a disaster") paired with reframed versions showing evidence-contrary reasoning.
- **Social graph:** Watson (`[PERSON_<watson_uuid>]`) as the primary mention. `vibeScore` anchored near +0.8.
- **Embedding requirement:** Pre-compute via `EmbeddingProvider` against masked text. Models are present at `core-logic/src/main/assets/`.
- **`ActivityHierarchy`:** Not required for this persona.
- **Success check:** `SearchRepository.findEvidenceEntries()` returns ≥3 relevant entries for a negative-valence query.

#### Benchmark B — "John Watson" (Q3 Focus)

- **Clinical target:** Behavioral Activation (BA) via `activity_hierarchy`.
- **Mood profile:** Low arousal, negative valence (lower-left — `DEPRESSED`/`FATIGUED` labels).
- **Content pattern:** Avoidance-themed entries with low activity density. Paired `ActivityHierarchy` rows: 5–8 tasks spanning difficulty 1–8, `valueCategory` in `["connection", "achievement", "vitality"]`.
- **Social graph:** Holmes as a mention with declining `vibeScore`.
- **Strategic Pivot check:** `LlmOrchestrator` routes to BA mode (not Socratic) for a Watson-seeded low-arousal state.

#### Benchmark C — "Young Werther" (Ruminative)

- **Clinical target:** Diagnosis of Thought (DoT) against Emotional Reasoning.
- **Mood profile:** Mixed valence, moderate-to-high arousal. `cognitiveDistortions` array should include `EMOTIONAL_REASONING` and `OVERGENERALIZATION`.
- **Content pattern:** Highly ruminative entries with Lotte-related mentions (`[PERSON_<lotte_uuid>]`). High `vibeScore` for Lotte despite negative overall valence — tests the tension between relational anchoring and distress.
- **No `ActivityHierarchy`** for this persona.
- **DoT validation:** `ReframingLoop` should flag `EMOTIONAL_REASONING` in ≥60% of Werther entries.

---

### 3. Operational Guardrails

#### 3.1 PII Isolation

`PiiShield` is fully implemented in `:core-logic`: name-variant regex substitution (full name, first, last, nickname), sorted by length descending, word-boundary anchored, case-insensitive. Masked text is stored in `journal_entries.content`; raw names live only in `people`.

**Seed-specific requirements:**
- All `[PERSON_<uuid>]` placeholders in seed JSON must reference UUIDs present in the same file's `people` block.
- `SeedManager` must run `PiiShield.mask()` as a validation pass (not just trust the JSON).
- Phone numbers in `phone_numbers` are not currently masked in journal text — this known gap is out of scope for seed data since seed entries will not include raw phone numbers.

#### 3.2 Sovereignty Check

`transit_events` table exists with `entryId TEXT` (added in 5→6 migration). For seeded entries:
- Write a `TransitEvent` per entry with `providerName = "seed_injection"` and `operationType = "seed"`.
- This keeps the sovereignty audit log self-consistent and prevents seeded entries from inflating `llama3_onnx_local` attribution stats.

#### 3.3 Rule of 30

Each persona seed file must contain ≥30 `journalEntries`. `SeedManager` should enforce this at parse time:

```kotlin
require(seed.journalEntries.size >= 30) { "Persona '${persona.name}' has fewer than 30 entries — RAG baseline insufficient" }
```

---

### 4. Integration & UI

#### 4.1 `DebugSeedScreen`

A Compose screen accessible only in `debug` builds. No product flavor exists yet — gating via `BuildConfig.DEBUG` is sufficient for now (add a flavor dimension if an `internal` release variant is introduced later).

UI requirements:
- Button per persona: "Seed Holmes", "Seed Watson", "Seed Werther".
- "Clear All Seeds" button that calls `SeedManager.clearPersona()` for each.
- Entry count readout per persona (live `Flow` from `JournalDao.countByTag()` or equivalent).
- Seeding progress indicator (coroutine scope tied to the screen's lifecycle).

Navigation: add a hidden debug entry point in `SettingsScreen` (or a dev-only nav route) — do not expose in release builds.

#### 4.2 ONNX Model Sharding Progress Indicator

`LocalFallbackProvider.initialize()` copies three files from assets to `filesDir`:
- `model_q4.onnx`
- `model_q4.onnx_data`
- `model_q4.onnx_data_1`

This copy happens on first launch and blocks the ONNX session from opening. Currently there is no UI feedback during this process.

**Implementation:**
- Expose a `StateFlow<ModelLoadState>` from `LlmOrchestrator` (or `LocalFallbackProvider` directly).
- `ModelLoadState` enum: `IDLE`, `COPYING_SHARDS`, `LOADING_SESSION`, `READY`, `ERROR`.
- Show an indeterminate progress indicator in the home screen (or a splash/loading screen) while state is `COPYING_SHARDS` or `LOADING_SESSION`.
- The `EmbeddingProvider` model (`snowflake-arctic-embed-xs.onnx`) is small and reads directly from assets — no copy needed, no progress indicator required.

---

## Pre-requisites & Blockers

| Blocker | Impact | Resolution |
|---|---|---|
| ~~`snowflake-arctic-embed-xs.onnx` and `vocab.txt` not committed to source~~ | ~~Cannot pre-compute embeddings for seed files~~ | **Resolved** — models live in `core-logic/src/main/assets/` |
| ~~`JournalEntry.content` non-nullable~~ | ~~Cannot represent mood-log entries~~ | **Resolved** — nullable via `MIGRATION_7_8` (schema v8) |
| `schema-v7.md` does not exist | Roadmap references it but `SPEC.md` (`schema_version: "lattice-v2"`) is the actual reference | Either rename/update `SPEC.md` to `schema-v7.md` or update all references to point at `SPEC.md` |

---

## Success Criteria

- [ ] `SeedManager` injects a 50-entry "Holmes" dataset transactionally with zero raw-name leakage (validated by `PiiShield` pass).
- [ ] `SearchRepository.findEvidenceEntries()` returns ≥3 relevant entries for a negative-valence query against seeded Holmes data.
- [ ] Strategic Pivot correctly routes to BA mode for a Watson-seeded low-arousal entry.
- [ ] `ReframingLoop` flags `EMOTIONAL_REASONING` in ≥60% of Werther entries.
- [ ] `DebugSeedScreen` is inaccessible in release builds (`BuildConfig.DEBUG` gate).
- [ ] ONNX shard copy shows a progress state in the UI; home screen does not hang on first launch.
