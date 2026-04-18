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

- [ ] **Journal entry editing** — `EntryDetailScreen` has only Reframe and Delete; no way to correct typos or update thoughts. Add an Edit action that opens the entry in `JournalEditorScreen` pre-populated with existing content and resolved mentions. Decide whether edits invalidate the existing embedding (re-embed on save) and how they interact with saved `reframedContent`.
- [ ] **PII / mention highlighting** — person, place, and tag tokens are unmasked to plain text with no visual distinction. Add colored inline chips or highlights for resolved tokens in `JournalHistoryScreen` entry snippets and the `EntryDetailScreen` content body.
- [ ] **Entry detail title** — `TopAppBar` shows the bare string "Entry". Replace with the entry's formatted date/time or mood label for at-a-glance context.
- [ ] **Mood data prominence** — valence, arousal, and label are rendered small and secondary in `EntryDetailScreen`. Promote them to a visible card or header area.
- [ ] **Tagged entities section** — add a bottom section to `EntryDetailScreen` listing tagged people, places, and tags with tap-through navigation (→ `PersonDetailScreen`; future place/tag detail screens).

### Settings

- [ ] **Sub-page navigation** — the flat 8-section `LazyColumn` will grow. Group into top-level categories (Inference, Personalization, Privacy & Data, About) with nested routes, following the pattern already used for Audit Trail and Activities.
- [ ] **Model download: feature framing, not error framing** — `IDLE` and `ERROR` states use red styling and error-like copy. Since the model is intentionally not bundled, the not-downloaded state should read "Download to enable local inference" with neutral/informational styling.
- [ ] **Model download notification** — progress notifications don't appear during download. Verify `POST_NOTIFICATIONS` runtime permission is requested before `ModelDownloadWorker` is enqueued, and that the notification channel is created before the first notification is posted.

### Visual polish

- [ ] **Centralize status colors** — `Color(0xFF2E7D32)`, `Color(0xFFB00020)`, `Color(0xFFF59E0B)` are hardcoded in `SettingsScreen.kt`. Move to named semantic aliases in `Color.kt`.
- [ ] **NavBar second-tap → tab root** — `launchSingleTop + popUpTo` is already wired; verify on-device behavior. If the second tap is a no-op rather than a pop-to-root, fix `onNavigateToDestination` in `AppNavHost`.
- [ ] **Person Detail arc spacing** — `VibeArcCard` canvas sits immediately below the `TopAppBar` with minimal breathing room. Add top padding so the semicircular arc doesn't crowd the header.

---

## M9 · History Enhancements

Depends on M5 (SearchBar already in place).

- [ ] **Date grouping** — group `LazyColumn` items by calendar day with `stickyHeader {}` date labels
- [ ] **Mood filter chips** — horizontal chip row below SearchBar: `All · Positive · Negative · Neutral`; filter `entries` flow client-side by valence range
- [ ] **Person filter chip** — opens a person picker bottom sheet; filters to entries containing `[PERSON_<id>]` in stored masked content
- [ ] **Empty-state copy** — replace plain "No entries yet" text with more descriptive instructional copy

---

## M10 · Export

`ExportManager` in `:core-logic` is complete. This milestone is purely UI wiring.

- [ ] **Export row in Settings** — "Export data" `ListItem` that calls `ExportManager` and fires `Intent.ACTION_SEND` with the resulting file
- [ ] **Export progress state** — `LinearProgressIndicator` + "Preparing export…" copy while the async write runs; dismiss on completion

---

## M11 · On-Device Inference Upgrade (Gemma 3 1B + LiteRT)

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

## M12 · Distortion MLP Head

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
