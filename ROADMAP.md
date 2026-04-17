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

## M7 · History Enhancements

Depends on M5 (SearchBar already in place).

- [ ] **Date grouping** — group `LazyColumn` items by calendar day with `stickyHeader {}` date labels
- [ ] **Mood filter chips** — horizontal chip row below SearchBar: `All · Positive · Negative · Neutral`; filter `entries` flow client-side by valence range
- [ ] **Person filter chip** — opens a person picker bottom sheet; filters to entries containing `[PERSON_<id>]` in stored masked content
- [ ] **Empty-state copy** — replace plain "No entries yet" text with more descriptive instructional copy

---

## M8 · Export

`ExportManager` in `:core-logic` is complete. This milestone is purely UI wiring.

- [ ] **Export row in Settings** — "Export data" `ListItem` that calls `ExportManager` and fires `Intent.ACTION_SEND` with the resulting file
- [ ] **Export progress state** — `LinearProgressIndicator` + "Preparing export…" copy while the async write runs; dismiss on completion

---

## M9 · On-Device Inference Upgrade (Gemma 3 1B + LiteRT)

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

- [ ] Add MediaPipe Tasks dependency (`com.google.mediapipe:tasks-genai`) to `:core-logic`
- [ ] Add Gemma 3 1B `.task` model to `downloadModels` Gradle task (source: Kaggle / HuggingFace `google/gemma-3-1b-it-litert-preview`)
- [ ] Rewrite `LocalFallbackProvider` against `LlmInference` API — streaming via `LlmInference.generateAsync()`, `ModelLoadState` flow preserved
- [ ] Remove Llama shard copy logic (`copyAssetsToFilesDir`, `ASSET_FILES`) — MediaPipe handles model loading from a single file path
- [ ] Remove `LlamaTokenizer`, `LlamaTokenizerTest` — MediaPipe tokenizes internally
- [ ] Remove ORT KV-cache forward-pass loop (`runForwardPass`, `nativeBytesAt`, `nativeFloatsAt`, `greedySample`) — all replaced by the MediaPipe session
- [ ] Update `downloadModels` Gradle task to fetch Gemma 1B instead of (or alongside) Llama shards; update `CLAUDE.md` assets table
- [ ] Update `LatticeApplication` wiring — `localFallbackProvider.initialize()` call stays, internals change
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

- [ ] Identify the canonical TFLite/LiteRT conversion of Arctic Embed XS on HuggingFace; run a correctness check against seed entries (cosine similarity ≥ 0.999 vs. ORT output confirms identical embedding space)
- [ ] Rewrite `EmbeddingProvider.initialize()` to load the `.tflite` model via `Interpreter`; enable XNNPACK delegate — use the LiteRT runtime already bundled with MediaPipe, no separate dependency needed
- [ ] Rewrite `EmbeddingProvider.runInference()` — `Interpreter.run()` with `[1 × seq_len]` int64 input tensors; mean-pool output `[1 × seq_len × 384]` (pooling logic unchanged)
- [ ] Verify `WordPieceTokenizer` vocab is compatible with the TFLite model (should be identical — same model lineage)
- [ ] Run `EmbeddingBenchmark` before and after to confirm no regression

---

### Milestone close

- [ ] If both tracks land: remove `onnxruntime-android` from `:core-logic/build.gradle.kts` and verify build
- [ ] Update `CLAUDE.md` Architecture section to reflect new inference stack
- [ ] Update `CLAUDE.md` Assets table (Llama shards → Gemma 1B)

---

## Deferred / Not Yet Scoped

- **Option A embedding fine-tuning** — Arctic projection fine-tuning deferred until ~200 labeled entries exist.
- **Tags & Places detail screens** — "entries tagged #foo" / "entries at !bar" filtered views. Natural follow-on after M7 filter chips.
- **Daily journaling prompt** — notification + streak. Requires notification permission flow.
- **Biometric setup prompt** — one-time `AlertDialog` for users without a device lock screen, pointing to system settings.
- **Cloud API key pre-flight validation** — currently any key string is accepted; no auth check before Reframe dispatches to cloud.
