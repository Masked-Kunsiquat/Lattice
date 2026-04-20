# Lattice — Feature Roadmap

Milestones are ordered by priority. Items within a milestone can usually be parallelized
once the milestone is started.

---

## M4 · Stability & First Debug Build

**Goal:** close the known UX breaks before any real testing session.

- [x] **Fix reframe-before-save silent failure** — `JournalEditorViewModel.applyReframe()` early-returns if `savedEntryId == null`; a user who reframes before saving loses the result silently. Auto-save when Reframe is triggered so `savedEntryId` is always set before Apply is reachable.
- [x] **First-run hint text** — replace the bare `"What's on your mind?"` placeholder with a short hint explaining `@person`, `#tag`, `!place` triggers. Show only when the field is empty and unfocused.
- [x] **Model-loading contextual copy** — add a second line below the progress bar explaining *why* it's slow ("running locally — no network needed") so users don't think the app is frozen.
- [x] **Delete dead code** — `EntryDetailViewModel.applyReframe()` (line 144) is unreachable; the nav host calls `viewModel::acceptReframe`. Remove it.

---

## M5 · Universal Search

**Goal:** surface all entity types through a single `SearchBar` at the top of History,
using the `expanded` overlay for multi-category results.

### SearchBar shape

`SearchBar` has two states:

- **Collapsed** — compact pill input at the top of History; list loads normally beneath it.
- **Expanded** — full-screen overlay; the `content` lambda owns the whole area.

Inside expanded content, a `TabRow` with four tabs:

```text
[  Entries  |  People  |  Places  |  Tags  ]
```

| Tab | Backend | Behavior |
|---|---|---|
| **Entries** | `SearchRepository.findSimilarEntries()` | Debounce 300 ms; mood label + snippet. Tap → `EntryDetailScreen`. |
| **People** | `PersonDao.searchByName()` — substring LIKE | Relationship chip + vibeScore dot. Tap → `PersonDetailScreen` (M6). |
| **Places** | `PlaceDao` name search | Entry count. |
| **Tags** | `TagDao` name search | Entry count. |

Semantic entry search (embedding + O(n) cosine scan) runs on its own cancellable
coroutine. Name / place / tag are cheap LIKE queries and share one debounced launch.

### Tasks

- [x] Add `SearchHistoryViewModel` with `SearchUiState(query, expanded, activeTab, entryResults, peopleResults, placeResults, tagResults, isLoading)`
- [x] Wire `SearchRepository`, `PeopleRepository`, `PlaceRepository`, `TagRepository` into the VM
- [x] Implement debounced query dispatch — semantic search cancels on each keystroke; LIKE queries debounce 150 ms
- [x] Build `SearchBar` composable in `JournalHistoryScreen` — collapsed pill above the `LazyColumn`, expanded overlay with `TabRow`
- [x] Entry results row — mood label chip + 2-line content snippet, tap navigates to `EntryDetailScreen`
- [x] People results row — display name + relationship chip + vibeScore dot, tap navigates to `PersonDetailScreen` (stub until M6)
- [x] Place results row — name + entry count
- [x] Tag results row — name + entry count
- [x] Back-press / focus loss collapses the `SearchBar` and clears results

---

## M6 · People

**Goal:** make people first-class in the UI. The entire backend already exists
(`Person`, `PhoneNumber`, `Mention`, `PersonDao`, `MentionDao`, `PeopleRepository`) —
nothing needs to change in `:core-data` or `:core-logic`.

### Bottom nav

Add a fourth tab between History and Settings:

```text
[ Journal ]  [ History ]  [ People ]  [ Settings ]
```

- [x] Add `BottomNavDest.People` (`route = "people"`, icon `Icons.Filled.Group`) in `AppNavHost`
- [x] Register `composable("people")` and `composable("people/{personId}")` routes

### PeopleListScreen

- [x] `PeopleListScreen` + `PeopleListViewModel` — collect `PeopleRepository.getPeople()` flow
- [x] `PersonCard` — display name (nickname ?? firstName lastName), `RelationshipType` chip, vibeScore indicator (green > 0.3 / gray -0.3–0.3 / amber < -0.3), favorite star
- [x] Sort order: favorites first, then by `|vibeScore|` descending
- [x] Empty state — "No people yet. Mention someone with @name while journaling."
- [x] FAB (`Icons.Filled.PersonAdd`) opens add-person bottom sheet (firstName, lastName, nickname, relationshipType). No phone numbers at creation — those live in the detail screen.

### PersonDetailScreen — route `people/{personId}`

- [x] `PersonDetailViewModel` — combine three flows: `peopleRepository.getPeople()` (filter to this person), `mentionDao.getMentionsForPerson(personId)`, `journalRepository.getEntries()` (filter client-side by mention entryIds)
- [x] Vibe score card — large arc indicator spanning -1 to +1, label ("Based on N entries")
- [x] Relationship type chip + favorite toggle
- [x] Phone numbers section — list of `rawNumber` rows, [+ Add number] action, swipe-to-delete
- [x] Journal entries section — entry rows with mood + snippet; tap → `EntryDetailScreen`
- [x] Edit action in TopAppBar — bottom sheet with all `Person` fields + phone number CRUD; persists via `PeopleRepository.savePerson()`
- [x] Delete action with confirmation dialog — deletes person + cascades mentions via FK

---

## M7 · Bug Fixes

**Goal:** correct functional regressions and data integrity issues found during first debug testing.

- [x] **Mention records never persisted** — `JournalRepository.saveEntry()` computes person/place UUIDs from masked content but never calls `mentionDao.insertMention()`. Insert a `Mention` row for each `[PERSON_<uuid>]` token found in `maskedContent` so `PersonDetailViewModel` can surface linked entries and vibe score accumulation works.
- [x] **Investigate: favorite toggle deletes linked journal entries** — `toggleFavorite` only calls `peopleRepository.savePerson()` with a flipped flag; no cascade delete is triggered by design. Likely a seed-data artifact or a UI handler accidentally wired to a delete action. Audit and fix.
- [x] **@mention re-trigger after pill resolution** — `onMentionSelected` keeps `@Name` in the raw text; subsequent typing (e.g. ` and @Person 2`) causes the end-anchored `MENTION_REGEX` to match `@Name and` as a new query, showing a spurious "Create 'Name and'" suggestion. After a pill is resolved, the `@` prefix must no longer be treated as an active trigger (e.g. strip the `@`, replace with a non-triggering display token, or exclude already-resolved spans from the regex).
- [x] **SearchBar: live results and tab visibility** — results don't appear while typing; the `TabRow` flashes briefly only on exit. Investigate `onExpandedChange` / Compose recomposition timing in `JournalHistoryScreen`; ensure `SearchHistoryViewModel.query` StateFlow drives the composable without prematurely collapsing `expanded`.

---

## M8 · Entry Experience & Settings Polish

**Goal:** fill gaps in the journaling core UX and address Settings/UI issues that surfaced in the first real-use session.

### Entry detail & history

- [x] **Journal entry editing** — `EntryDetailScreen` has only Reframe and Delete; no way to correct typos or update thoughts. Add an Edit action that opens the entry in `JournalEditorScreen` pre-populated with existing content and resolved mentions. Decide whether edits invalidate the existing embedding (re-embed on save) and how they interact with saved `reframedContent`.
- [x] **PII / mention highlighting** — person, place, and tag tokens are unmasked to plain text with no visual distinction. Add colored inline chips or highlights for resolved tokens in `JournalHistoryScreen` entry snippets and the `EntryDetailScreen` content body.
- [x] **Entry detail title** — `TopAppBar` shows the bare string "Entry". Replace with the entry's formatted date/time or mood label for at-a-glance context.
- [x] **Mood data prominence** — valence, arousal, and label are rendered small and secondary in `EntryDetailScreen`. Promote them to a visible card or header area.
- [x] **Tagged entities section** — add a bottom section to `EntryDetailScreen` listing tagged people, places, and tags with tap-through navigation (→ `PersonDetailScreen`; future place/tag detail screens).

### Settings

- [x] **Sub-page navigation** — the flat 8-section `LazyColumn` will grow. Group into top-level categories (Inference, Personalization, Privacy & Data, About) with nested routes, following the pattern already used for Audit Trail and Activities.
- [x] **Model download: feature framing, not error framing** — `IDLE` and `ERROR` states use red styling and error-like copy. Since the model is intentionally not bundled, the not-downloaded state should read "Download to enable local inference" with neutral/informational styling.
- [x] **Model download notification** — progress notifications don't appear during download. Verify `POST_NOTIFICATIONS` runtime permission is requested before `ModelDownloadWorker` is enqueued, and that the notification channel is created before the first notification is posted.

### Visual polish

- [x] **Centralize status colors** — `Color(0xFF2E7D32)`, `Color(0xFFB00020)`, `Color(0xFFF59E0B)` are hardcoded in `SettingsScreen.kt`. Move to named semantic aliases in `Color.kt`.
- [x] **NavBar second-tap → tab root** — `launchSingleTop + popUpTo` is already wired; verify on-device behavior. If the second tap is a no-op rather than a pop-to-root, fix `onNavigateToDestination` in `AppNavHost`.
- [x] **Person Detail arc spacing** — `VibeArcCard` canvas sits immediately below the `TopAppBar` with minimal breathing room. Add top padding so the semicircular arc doesn't crowd the header.

---

## M9 · CBT Pipeline Quality

**Goal:** fix the reframe pipeline's quality failures exposed by real-world testing on Gemma 3 1B. The original three-stage loop was designed around Llama 3.2-3B's instruction-following capacity. With Stages 1 and 2 now handled by on-device MLPs, the only remaining LLM call is Stage 3 text generation — and the prompt structure that worked for a 3B model actively hurts a 1B model: it can't hold six simultaneous constraints in attention, so it pattern-matches to "CBT-sounding text" and ignores the actual entry.

> **Root cause (confirmed):** real-world entry `"meeting up with @P1 later. might go to !The Park and see @P2 at his performance later."` was labelled **ALIVE** (v≥0, a≥0, v≥a) and routed to `STRENGTHS_AFFIRMATION`. The model received opaque UUID placeholders it couldn't parse and a 6-constraint prompt it couldn't fully satisfy, and fabricated a completely unrelated reframe about "being focused" and "seeing my own strengths."

### Prompt-level fixes (near-term)

- [x] **REFLECTION strategy for neutral/low-positive entries** — `selectStrategy()` maps every `v≥0` to `STRENGTHS_AFFIRMATION`. The primary at-risk label is **ALIVE** (`v≥0, a≥0, v≥a`), which covers everything from barely-positive diary notes to genuinely meaningful moments; most casual social/plans entries land here. Add a fourth `ReframeStrategy.REFLECTION` for the low-positive band (`0f ≤ v < AFFIRMATION_THRESHOLD`, default `0.4f`). Its prompt asks the model to notice what the entry reveals about what matters to the writer — no fabricated strength or effort. `STRENGTHS_AFFIRMATION` narrows to `v ≥ AFFIRMATION_THRESHOLD`, covering EXCITED entries and high-valence ALIVE. Threshold is a named companion-object constant so it can be tuned without code changes.

- [x] **Replace UUID tokens with pseudonymous display names in Stage 3 prompts** — `[PERSON_3f2a1b…]` and `[PLACE_uuid]` are semantically opaque; the model abandons all entity references and generates generic text. Add `buildDisplayText(maskedText, personById, placeById)` in `ReframingLoop` that converts placeholders to `@{nickname ?: firstName}` / `!{placeName}` for use in `buildInterventionPrompt` only. Everything upstream (Stage 1, Stage 2, evidence retrieval, storage) continues to receive UUID-masked text. Thread `personById: Map<UUID, Person>` and `placeById: Map<UUID, Place>` through `streamStage3Intervention` / `runStage3Intervention` from the call sites (`JournalEditorViewModel`, `EntryDetailViewModel`).

- ~~**Anchor call before reframe generation**~~ — **reverted**. For sparse positive entries the 1B model hallucinated a false anchor (e.g. `"hanging with @P1 & @P2 later"` → anchor: `"The person is watching friends interact while feeling anxious"`) that then contaminated the reframe. The anchor approach requires a model with reliable summarization capacity; on Gemma 3 1B it makes generation strictly worse for exactly the entries that needed the most help. Removed `runAnchorCall`, `buildAnchorPrompt`, and `anchorText` from `buildInterventionPrompt`.

- [x] **Compact per-strategy prompts** — replace the current single prompt with multiple simultaneous constraints with one short, focused instruction per strategy. Hard constraints (first-person, entry-grounded, no cheerleading) move permanently to `INTERVENTION_SYSTEM` as character; per-call instructions reduce to the single most important directive for each strategy. A 1B model reliably follows one instruction per call; it does not reliably follow six.

### Retrieval-first path for undistorted entries (near-term)

The deeper problem: for positive/neutral entries with no cognitive distortions, text generation is the wrong tool entirely. Asking a 1B model to generate wisdom from `"hanging with @P1 & @P2 later"` gives it license to fabricate negative narrative. These entries don't need reframing — they need reflection surfaced from what's already in the DB.

- [x] **Revert anchor call** — remove `runAnchorCall`, `buildAnchorPrompt`, and `anchorText` parameter from `buildInterventionPrompt`. Mark the item above as reverted in code history.

- [x] **Distortion-gated LLM dispatch** — only invoke text generation when `DistortionMlp` fires at least one distortion. `REFLECTION` entries with an empty distortion set have nothing to reframe; routing them to a 1B text-gen call produces fabricated negative narrative. Add an early-exit in `runStage3Intervention` / `streamStage3Intervention`: when `strategy == REFLECTION && diagnosis.distortions.isEmpty()`, skip the LLM entirely and delegate to the retrieval card path.

- [x] **Retrieval card for undistorted REFLECTION entries** — when LLM dispatch is skipped, assemble a `ReframeResult` without calling the model. Extend `fetchEvidenceEntries` beyond the current `valence < 0` gate so it also runs for `REFLECTION` entries with `[PERSON_uuid]` / `[PLACE_uuid]` tokens. Build the reframe string from display names + retrieved snippets (e.g. `"Last time you mentioned @P1: \"…\""`) — no generated text, no hallucination risk. If no evidence exists, return a single templated line: `"Spending time with {names} is something that matters to you."` The strategy in `ReframeResult` carries a new companion value `REFLECTION_CARD` so the UI can render it differently from a generated reframe.

### Model-level fix (long-term)

- [ ] **CBT LoRA fine-tune** — fine-tune a LoRA adapter on top of Gemma 3 1B specifically for CBT reframing, then merge weights into a new standalone model file. This is the structural fix that eliminates prompt-engineering fragility entirely.

  > **Architecture note:** LiteRT-LM 0.10.x has no adapter/LoRA loading API — `EngineConfig` accepts a single model path only. The practical path is: train LoRA offline → `peft.merge_and_unload()` → export merged model to LiteRT INT4 → distribute as `gemma3-1b-it-cbt-int4.litertlm`. No Android-side adapter hook is needed; the CBT model is a drop-in replacement for the base model file.

  **Python pipeline** (`scripts/`):
  - [x] `curate_cbt_training_data.py` — Gemini 2.5 Flash Lite labeler; 550 pairs across 4 strategies (150/150/150/100); outputs `cbt_training_data.jsonl` + `gemma_ft_data.jsonl`
  - [x] `finetune_cbt_lora.py` — QLoRA (4-bit base, LoRA r=16 on Q/V projections); cosine LR schedule, paged AdamW; 95/5 train/eval split; saves adapter + merged model; HF_TOKEN auth via Colab secret
  - [x] `export_cbt_model.py` — merges LoRA weights via `peft.merge_and_unload()`, exports to LiteRT INT4 via `ai-edge-torch`; outputs `gemma3-1b-it-cbt-int4.litertlm`

  **Android integration**:
  - [x] `ModelDownloadWorker` — `UNIQUE_WORK_NAME_CBT` added; CBT download runs independently of base model download
  - [x] `LocalFallbackProvider` — `MODEL_FILE_CBT` constant + `downloadCbtModel()`; `selectModelAndBackends()` checks `filesDir` for CBT file first, falls back to board-tier selection
  - [x] Run training to completion; review loss curve and sample outputs before uploading
  - [x] Upload merged model to HuggingFace (`masked-kunsiquat/gemma-3-1b-it-litert`) and populate `MODEL_SHA256[MODEL_FILE_CBT]`

---

## M10 · History Enhancements

Depends on M5 (SearchBar already in place). Can run in parallel with M9.

- [x] **Date grouping** — group `LazyColumn` items by calendar day with `stickyHeader {}` date labels; Today/Yesterday labels, falling back to "EEE, MMM d" (same year) or "EEE, MMM d, yyyy" (prior years)
- [x] **Empty-state copy** — replaced bare "No entries yet" with a two-line hint explaining @people, #topics, !places syntax
- ~~**Mood filter chips**~~ — deferred; a permanent chip row above the history list adds visual clutter without earning it. Mood filtering will live inside the search overlay's Entries section as inline filter chips (scoped to search results, not the full list). See M9 search overlay enhancement.
- ~~**Person filter chip**~~ — deferred; `PersonDetailScreen` already shows all entries mentioning a person, and the search overlay People section navigates there directly. Redundant at the history list level.

---

## M11 · Export

`ExportManager` in `:core-logic` is complete. This milestone is purely UI wiring.

- [ ] **Export row in Settings** — "Export data" `ListItem` that calls `ExportManager` and fires `Intent.ACTION_SEND` with the resulting file
- [ ] **Export progress state** — `LinearProgressIndicator` + "Preparing export…" copy while the async write runs; dismiss on completion

---

## M12 · On-Device Inference Upgrade (Gemma 3 1B + LiteRT)

**Goal:** replace the ORT-based local inference stack with a hardware-accelerated
one that runs at GPU speed on the S25 Ultra (and equivalent devices) — cutting
reframe latency from ~60 s to ~10–15 s and eliminating ORT as a dependency.

Two independent sub-tracks that can land separately but share a dependency review
at the end: if both land, ORT is removed entirely from `:core-logic`.

---

### Track A — LocalFallbackProvider → Gemma 3 1B via MediaPipe

**Why:** Llama 3.2-3B Q4 on ORT runs on CPU at ~8 tok/s. Gemma 3 1B via
MediaPipe's GPU delegate runs at 35–50 tok/s on Adreno 700-series GPUs — 1B
parameters fit in GPU SRAM, so DRAM bandwidth stops being the bottleneck.
Instruction-following quality also improves, which directly helps DoT's
`DISTORTIONS: <csv>` sentinel parsing and reduces prompt-mirroring artifacts.

**DoT impact specifically:** Stage 2 (Diagnosis of Thought) generates the most
tokens of the three stages — chain-of-thought over 12 Burns distortions before
the sentinel line. It therefore gets the largest absolute latency saving, and
the improved instruction adherence means the `DISTORTIONS:` line is more
reliably formatted on the first pass (fewer malformed outputs to handle).

**Tasks:**

- [x] Add MediaPipe Tasks dependency (`com.google.mediapipe:tasks-genai`) to `:core-logic`
- [x] Add Gemma 3 1B hardware tiers to `downloadModels` Gradle task — ADB-detected board selects elite/ultra/universal; `-PdownloadTier` override (source: HuggingFace `masked-kunsiquat/gemma-3-1b-it-litert`)
- [x] Rewrite `LocalFallbackProvider` against `LlmInference` API — streaming via `LlmInference.generateAsync()`, `ModelLoadState` flow preserved
- [x] **Add in-app model downloader** — Hugging Face streaming download (689MB) with progress tracking in Settings screen; resolves `FileNotFoundException` for non-bundled builds.
- [x] Remove Llama shard copy logic (`copyAssetsToFilesDir`, `ASSET_FILES`) — MediaPipe handles model loading from a single file path
- [x] Remove `LlamaTokenizer`, `LlamaTokenizerTest` — MediaPipe tokenizes internally
- [x] Remove ORT KV-cache forward-pass loop (`runForwardPass`, `nativeBytesAt`, `nativeFloatsAt`, `greedySample`) — all replaced by the MediaPipe session
- [x] Update `downloadModels` Gradle task to fetch Gemma 1B instead of (or alongside) Llama shards; update `CLAUDE.md` assets table
- [x] Update `LatticeApplication` wiring — `localFallbackProvider.initialize()` call stays, internals change
- [ ] Smoke-test all three CBT stages through the new provider on device

---

### Track B — EmbeddingProvider → LiteRT

**Why:** Primarily to eliminate ORT as a dependency once Track A lands — not for
latency. Baseline benchmarks (`benchmarks/baseline.md`) show warm embedding
inference at **1.58 ms** on the S25 Ultra; LiteRT/XNNPACK would halve that at
best, which is imperceptible. The real value is a single runtime (LiteRT,
already bundled with MediaPipe) instead of two.

**Note on ODT:** No migration concern. App is debug-only with a single user;
fresh install on migration. Seed embeddings can be regenerated from the
HuggingFace dataset (`masked-kunsiquat/clinical-personas`) at any time.

**Tasks:**

- [x] **Re-export with fixed input shapes** — re-exported from PyTorch with fixed [1, 128] shapes (no `dynamic_axes`) and converted via onnx2tf; eliminates the SPLIT op failure. Float32 model works; float16 has a MEAN op dtype issue at node 15 (non-blocking — float32 is production path).
- [x] Rewrite `EmbeddingProvider.initialize()` to load the `.tflite` model via `Interpreter` (`org.tensorflow.lite.Interpreter` from `com.google.ai.edge.litert:litert` AAR)
- [x] Rewrite `EmbeddingProvider.runInference()` — `Interpreter.runSignature()` with `[1 × 128]` INT64 input tensors; masked mean-pool `last_hidden_state` [1 × 128 × 384] over attention_mask==1 positions
- [x] Verify `WordPieceTokenizer` vocab is compatible with the TFLite model (cosine similarity ≥ 0.99 confirmed)
- [x] Run `EmbeddingBenchmark.tflite_cosineSimilarity_vs_onnxSeedEmbeddings` — passed ≥ 0.99 vs ONNX seed embeddings

---

### Milestone close

- [x] If both tracks land: remove `onnxruntime-android` from `:core-logic/build.gradle.kts` and verify build
- [x] Update `CLAUDE.md` Architecture section to reflect new inference stack
- [x] Update `CLAUDE.md` Assets table (Llama shards → Gemma 1B)

---

## M13 · Distortion MLP Head

**Goal:** replace the Stage 2 LLM call (Diagnosis of Thought) with a deterministic
multi-label MLP classifier. Eliminates hallucination risk, drops Stage 2 latency to
<5 ms, and makes distortion detection reliable regardless of which LLM backend is
active. The MLP reuses the 384-dim Arctic Embed XS embedding already computed in
Stage 1 — no second embedding pass needed when `AffectiveMlp` is active.

### Architecture

`384 → 128 → 12` with independent sigmoid outputs (one logit per `CognitiveDistortion`).
Multi-label: each class is thresholded independently. Threshold tuned per-class on a
held-out validation split — default 0.5 is likely too aggressive for rare classes.

### Training data

Two-source strategy to avoid the self-referential loop of using Gemma to label
its own replacement's training data:

| Source | Classes covered | Notes |
|---|---|---|
| Shreevastava et al. corpus | 10 (all except `DISQUALIFYING_POSITIVE`, `BLAME`) | Human-annotated therapy forum posts; dominant + optional secondary label per row |
| Claude-labeled synthetic | `DISQUALIFYING_POSITIVE`, `BLAME` | ~200 examples per class — labeled by Claude, **not** Gemma |

**Corpus label mapping:**

| Corpus label | `CognitiveDistortion` enum |
|---|---|
| All-or-nothing thinking | `ALL_OR_NOTHING` |
| Overgeneralization | `OVERGENERALIZATION` |
| Mental filter | `MENTAL_FILTER` |
| Should statements | `SHOULD_STATEMENTS` |
| Labeling | `LABELING` |
| Personalization | `PERSONALIZATION` |
| Magnification | `CATASTROPHIZING` |
| Emotional Reasoning | `EMOTIONAL_REASONING` |
| Mind Reading | `MIND_READING` |
| Fortune-telling | `FORTUNE_TELLING` |

`No Distortion` rows map to the all-zeros label vector — keep them, they train the
model to output an empty set rather than always firing something.

### Tasks

**Data pipeline**
- [x] Download Shreevastava et al. corpus; extract `(text, dominant, secondary)` rows into a local CSV → `distortion_corpus.jsonl` (2530 rows, 10 classes, 0 dropped)
- [x] Write `DistortionCorpusMapper` — maps corpus label strings to `CognitiveDistortion`; logs and drops unmapped labels
- [x] Generate ~200 synthetic examples for `DISQUALIFYING_POSITIVE` via Claude; save as `distortion_synth_dqp.jsonl` (307 examples)
- [x] Generate ~200 synthetic examples for `BLAME` via Claude; save as `distortion_synth_blame.jsonl` (339 examples)
- [x] Embed all rows via `EmbeddingProvider` (Arctic Embed XS); serialize dataset as `(FloatArray, BooleanArray(12))` pairs → `DistortionSample` + `DistortionDatasetLoader` (JSONL assets + binary cache in filesDir)

**Model**
- [x] Add `DistortionMlp.kt` — `forward(embedding: FloatArray): BooleanArray`; per-class sigmoid thresholds; weights loaded from `distortion_mlp.bin` in `filesDir`
- [x] Add `DistortionManifest.kt` — version, per-class threshold array, training sample counts; serialized alongside weights
- [x] Add `DistortionMlpTrainer.kt` — AdamW, binary cross-entropy loss per class, `trainStep(embedding, labels)` / `trainBatch(batch)` / `save(context)`

**Integration**
- [x] Add `distortionMlp: DistortionMlp? = null` to `ReframingLoop` constructor
- [x] In `runStage2DiagnosisOfThought`: use MLP when non-null; fall back to LLM path when absent (preserves existing behaviour for devices without a trained head)
- [x] Wire `DistortionMlp` through `LatticeApplication` alongside `AffectiveMlp`

**Evaluation**
- [ ] 80/20 held-out split per class; log per-class precision, recall, F1 at training completion
- [ ] Per-class threshold sweep on validation set; write chosen thresholds into `DistortionManifest`

---

## Deferred / Not Yet Scoped

- **Option A embedding fine-tuning** — Arctic projection fine-tuning deferred until ~200 labeled entries exist.
- **Tags & Places detail screens** — "entries tagged #foo" / "entries at !bar" filtered views. Natural follow-on after M7 filter chips.
- **Daily journaling prompt** — notification + streak. Requires notification permission flow.
- **Biometric setup prompt** — one-time `AlertDialog` for users without a device lock screen, pointing to system settings.
- **Cloud API key pre-flight validation** — currently any key string is accepted; no auth check before Reframe dispatches to cloud.
