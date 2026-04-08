# 📑 Lattice: Master Implementation Backlog (v2.0)

## Phase 2: The Librarian (Local Semantic Intelligence) ✅

### ✅ Task 2.1: ONNX Embedding Pipeline
```markdown
# Task: Implement ONNX Embedding Pipeline
DONE — commit 0e14ce3
- EmbeddingProvider: open class, dispatcher-injectable, initialize(context) lazy-loads
  snowflake-arctic-embed-xs.onnx from assets, falls back to zero-vectors if absent.
- JournalRepository.saveEntry() calls generateEmbedding(maskedContent) — PII-masked
  text only ever enters the embedding pipeline.
- onnxruntime-android 1.20.0 added to core-logic.
- assets/snowflake-arctic-embed-xs.onnx.placeholder documents model placement.
- Unit tests: off-main-thread dispatch contract + 384-dim fallback output.
- WordPieceTokenizer.kt implemented: lowercase → CJK/punct spacing → WordPiece with [CLS]/[SEP]; vocab.txt and int8 ONNX model bundled as assets.
```

### ✅ Task 2.2: Vector Search Engine
```markdown
# Task: Implement Local Semantic Search
DONE — commit 874ee19
- SearchRepository.findSimilarEntries(queryText, limit): Flow<List<JournalEntry>>
  in-memory cosine similarity (SQLite has no native vector ops), flowOn(Dispatchers.Default).
- Query text is PII-masked via PiiShield before vectorization (matches stored embeddings).
- Zero-vector entries excluded from results (score > 0f guard).
- JournalDao.getAllEntries() one-shot suspend query added.
- EmbeddingProvider made open with open generateEmbedding() for test subclassing.
- Unit tests (4): PII masking enforcement, cosine ranking, limit capping, zero-vector exclusion.
```

---

## Phase 3: The Orchestrator (Privacy Switchboard) ✅

### ✅ Task 3.1: LLM Strategy Manager, Fallbacks & Export
```markdown
# Task: Implement LLM Orchestrator, Local Fallbacks, Export & Spec
DONE — commit ff26eee

LLM Provider Tier:
- LlmProvider interface: id, isAvailable(), process(prompt): Flow<LlmResult>
- LlmResult sealed class: Token | Complete | Error
- NanoProvider: Gemini Nano via AICore (API 35+, gracefully unavailable below)
- LocalFallbackProvider: Qwen-1.5B ONNX scaffold (model asset placeholder)
- CloudProvider: remote API stub, DISABLED by default

Privacy Switchboard (LlmOrchestrator):
- Local-first routing: Nano → Local ONNX → Cloud (only if cloudEnabled=true)
- PrivacyLevel sealed class: LocalOnly | CloudTransit(provider, since)
- Sovereignty gate: cloud routing flips StateFlow<PrivacyLevel> to CloudTransit
  AND writes a TransitEvent to Room (timestamp + provider, never the prompt content)

Data layer:
- TransitEvent Room entity + TransitEventDao (audit trail)
- LatticeDatabase migrated v2 → v3 (transit_events table + index)

Export:
- ExportManager.generateManifest(): portable manifest.json with model_spec
  (embedding model, dims, type) — content exported masked, embeddings excluded
- SPEC.md at project root: canonical JSON schema for all entities

Unit tests (8): routing priority, sovereignty state flip, TransitEvent DAO write,
cloud gating when cloudEnabled=false.
```

### ✅ Task 3.2: Privacy Guardrails & State
```markdown
# Task: Privacy State & Cloud Warning Logic
DONE

1. **State:** PrivacyLevel sealed class done in 3.1 (LocalOnly / CloudTransit). ✅
2. **Logic:** LlmOrchestrator.process() now validates cloud-bound prompts via piiDetector:
    - New piiDetector: (String) -> Boolean parameter (default: no-op / always false).
    - If cloud is selected AND piiDetector returns true → emits LlmResult.Error(SecurityException)
      and returns early (cloud provider is never invoked, no TransitEvent logged).
    - privacyState does NOT flip to CloudTransit on a blocked request.
3. **Tests (2 added):**
    - `cloud dispatch is blocked and error emitted when raw PII is detected`
      Verifies: error emitted, cloud processCallCount=0, no DAO write, state stays LocalOnly.
    - `cloud dispatch proceeds when prompt contains only PII placeholders`
      Verifies: masked prompts are not blocked, cloud is called, Complete result returned.
```

---

## Phase 4: The Interface (Visualizing the Logic) ✅

### ✅ Task 4.1: The 2D Mood Grid
```markdown
# Task: Implement the 2D Mood Grid UI
DONE — commit a15198f
- MoodGrid.kt: Compose Canvas with quadrant tint backgrounds (amber/green/blue-grey/red),
  axis lines, and a BlurMaskFilter glow dot for the selector.
- Touch & drag handled via awaitEachGesture / awaitFirstDown (no dual-pointerInput conflicts).
- Raw pixel offset mapped to valence ∈ [-1,1] (X) and arousal ∈ [-1,1] (Y, inverted).
- CircumplexMapper.getLabel() called on every pointer event for real-time MoodLabel feedback.
- Axis labels (High/Low Energy, Pleasant/Unpleasant) as rotated Text composables outside the canvas.
- Current MoodLabel displayed below; placeholder text shown until first touch.
- Added :core-logic dependency to :app module.
```

### ✅ Task 4.2: Privacy-Aware Journal Editor
```markdown
# Task: Build the Privacy-Aware Editor
DONE
- LatticeApplication.kt: manual DI container — Room DB, EmbeddingProvider, JournalRepository,
  LlmOrchestrator all lazily wired; embeddingProvider.initialize() called in onCreate().
  **DI evolution note:** manual lazy wiring is intentional for Phase 4 (one ViewModel, no
  circular deps, no test pain yet). Migrate to Hilt (best for multi-module Android, strong
  AS tooling) or Koin (lighter, KMP-friendly) if any of these signals appear: multiple
  ViewModels needing the same deps, factory boilerplate growing, test setup requiring manual
  wiring, or cyclic dependency errors. Action: open a migration spike ticket at that point.
- PiiHighlightTransformation.kt: VisualTransformation matching [PERSON_UUID] placeholders
  via regex; applies tertiary color + 15% alpha background tint inline in the TextField.
- JournalEditorViewModel.kt: exposes orchestrator.privacyState StateFlow; save() calls
  JournalRepository.saveEntry() from viewModelScope; saved pulse state resets after UI ack.
- JournalEditorScreen.kt: animateColorAsState transitions the OutlinedTextField border
  between LocalBlue (#1976D2) and CloudAmber (#FF8F00) over 600 ms; privacy pill at top
  shows current provider label; Save button gated on non-blank text + mood selection.
- MainActivity updated to cast Application → LatticeApplication and inject ViewModel factory.
- Added :core-data dependency to :app module.
```

---

## Phase 5: The Reframing Engine (CBT)

> **Strategy:** All reframing runs locally via `LocalFallbackProvider` using the `llama-3.2-3b.onnx`
> foundation model. `NanoProvider` and `CloudProvider` are excluded from the reframe routing path.
> No reframe content — masked or otherwise — ever leaves the device.
> **Architecture pivot (2026-04-08):** Replaced modular multi-model approach (Qwen-1.5B) with a
> Unified Agent loop on Llama-3.2-3B. Single model handles affective mapping, distortion
> classification, and intervention generation via sequential prompting.

### ✅ Task 5.1: The Unified Reframing Loop (Sequential Prompting)
```markdown
# Task: 3-Stage Inference Loop in LocalFallbackProvider (Llama-3.2-3B)
DONE — commits 499d285 (assets), ff922a2 (Stage 1), a6eae2c (Stage 2), 876506f (Stage 3),
        370e2ee (!reframe wiring + audit trail)

## Completed

### Asset integration
- model_q4.onnx + model_q4.onnx_data + model_q4.onnx_data_1 → app/src/main/assets/
- LocalFallbackProvider rewritten: copyAssetsToFilesDir() stages all three shards to
  context.filesDir on first init (ORT needs real filesystem paths for external-data models),
  then opens OrtSession with NNAPI (NPU/GPU on Snapdragon 8 Elite) falling back to CPU.
- Dispatcher changed to Dispatchers.IO; id = "llama3_onnx_local".
- LatticeApplication: localFallbackProvider extracted as top-level lazy; eager background
  init in onCreate() avoids first-request stall.
- ✅ INFERENCE LIVE: LlamaTokenizer + KV-cached autoregressive loop implemented
  (2026-04-08). OrtSession runs greedy decode, emits LlmResult.Token per generated
  token, terminates on EOS or MAX_NEW_TOKENS=512.

### ReframingLoop.kt (core-logic)
Stage 1 — Affective Mapping ✅
  - Llama-3.2 chat-template prompt requests ONLY "v=<n> a=<n>" output.
  - collectTokens() drains Flow<LlmResult> stream; regex parser tolerates spaces,
    integers, leading dash, stray preamble; clamps both axes to [-1, 1].
  - Maps to MoodLabel via CircumplexMapper.getLabel(); returns Result<AffectiveMapResult>.

Stage 2 — Diagnosis of Thought (DoT) ✅
  - Three-step chain-of-thought prompt: facts/beliefs separation → contrastive analysis
    → DISTORTIONS: <csv> sentinel on the final line.
  - Parser takes the *last* DISTORTIONS: line (handles model self-correction),
    resolves tokens via CognitiveDistortion.fromLabel() with hyphen/case normalisation,
    silently drops unrecognised tokens; preserves full CoT in DiagnosisResult.reasoning.
  - Returns Result<DiagnosisResult(distortions, reasoning)>.
  - ⚠️  NAMING DEVIATION: enum is CognitiveDistortion (not CbtDistortion as specced).
    12th entry is BLAME (Burns taxonomy) — spec listed Magnification/Minimization.
    Rationale: BLAME is a distinct Burns distortion; Catastrophizing already covers
    magnification. Revisit if clinical review requires the spec list verbatim.

Stage 3 — Strategic Pivot ✅
  - selectStrategy(): v<0 & a≥0 → SOCRATIC_REALITY_TESTING (Q2);
                      v<0 & a<0 → BEHAVIORAL_ACTIVATION (Q3); v≥0 → STRENGTHS_AFFIRMATION.
  - Q2 prompt: Socratic questioning + Reality Testing + probability calibration.
  - Q3 prompt: Evidence for the Contrary + one concrete Behavioral Activation step.
  - Free-form reframe — no sentinel parsing; collected via collectTokens().
  - Returns Result<ReframeResult(strategy, reframe)>.
  - ⚠️  DEFERRED (Task 5.2): ActivityHierarchy BA retrieval + prompt injection not yet wired.
  - ⚠️  DEFERRED (Task 5.3): RAG evidence injection not yet wired.

### !reframe command + audit trail (pulled forward from Task 5.3)
  - JournalRepository.maskText(text): new helper for non-persisting callers.
  - JournalEditorViewModel.onTextChanged(): detects "!reframe" anywhere in live text,
    strips it, fires triggerReframe() on viewModelScope.
  - triggerReframe(): masks → Stage 1 → Stage 2 → Stage 3 (fail-fast getOrThrow()),
    updates mood coords from Stage 1, exposes reframe in EditorUiState.reframeResult.
  - On completion logs TransitEvent(providerName="local_llama_3b", operationType="reframe").
  - Save button disabled while isReframing=true.
  - JournalEditorScreen: CircularProgressIndicator + dismissible SecondaryContainer result
    card. (Full streaming UiState + ReframeBottomSheet remain in Task 5.3 scope.)

### Tests
  33 unit tests (all passing):
  - Stage 1: 11 tests — parser edge cases, prompt structure, end-to-end token stream.
  - Stage 2: 12 tests — DISTORTIONS: sentinel, multi-label, normalisation, last-line wins,
    reasoning preserved, buildDotPrompt keyword assertions.
  - Stage 3: 10 tests — selectStrategy boundaries (Q2/Q3/Q1/Q4/zero-arousal), Q2 & Q3
    prompt keyword assertions, empty-distortion context, end-to-end strategy routing x2,
    model error propagation.

## Finalised (2026-04-08)
1. ✅ LlamaTokenizer.kt — streaming parse of tokenizer.json (android.util.JsonReader),
   Llama-3 tiktoken BPE (pre-tokeniser regex + ByteLevel encoding + merge-rank BPE),
   special-token passthrough for chat-template delimiters, UTF-8 streaming decode.
2. ✅ LocalFallbackProvider.process() — KV-cached autoregressive greedy-decode loop;
   numLayers discovered dynamically from session.inputInfo; each token streamed as
   LlmResult.Token; terminates on EOS (128001/128008/128009) or MAX_NEW_TOKENS=512.
3. ✅ Assets — tokenizer.json + tokenizer_config.json + generation_config.json copied
   to app/src/main/assets/ (read via context.assets; model shards still staged to filesDir).
4. Open: Replace BLAME with Magnification/Minimization in CognitiveDistortion if
   clinical review requires strict spec alignment.
```

### ✅ Task 5.2: Behavioral Activation (BA) Integration
```markdown
# Task: ActivityHierarchy Room Entity + Quadrant III Retrieval
DONE — commit ccd7e10

- ActivityHierarchy Room entity (core-data):
  - Fields: id: UUID, taskName: String, difficulty: Int (0–10), valueCategory: String
  - Table: activity_hierarchy
- ActivityHierarchyDao: getActivitiesByMaxDifficulty(max: Int): List<ActivityHierarchy>
  (suspend, one-shot; ordered ASC by difficulty).
- LatticeDatabase migration v3 → v4: CREATE TABLE activity_hierarchy.
- ReframingLoop: optional activityHierarchyDao param; Q3 path calls pickBaActivity()
  which selects lowest-difficulty activity (≤ BA_MAX_DIFFICULTY=5) whose valueCategory
  appears in entry context; falls back to easiest overall; skips if hierarchy is empty.
- buildInterventionPrompt: injects baActivity.taskName + valueCategory into Q3 prompt.
- LatticeApplication: MIGRATION_3_4 added; reframingLoop wired with activityHierarchyDao.
- Unit tests (3): difficulty gate enforced, empty hierarchy handled gracefully (Stage 3
  proceeds without BA block), activity injected into prompt string.
```

### 🟢 Task 5.3: Memory-Augmented Socratic Dialogue
```markdown
# Task: RAG Evidence Injection + Streaming UiState + ReframeBottomSheet
NOTE: !reframe detection, triggerReframe(), and TransitEvent logging were pulled
forward into Task 5.1 (commit 370e2ee). Remaining 5.3 deliverables:

Deliverables:
- SearchRepository.findEvidenceEntries(
      placeholders: Set<String>,
      minValence: Float = 0.5f,
      limit: Int = 5
  ): Flow<List<JournalEntry>>
  - Reuses Snowflake Arctic XS embeddings (existing cosine logic from findSimilarEntries).
  - Fetches entries where valence > minValence (positive quadrant only).
  - Filters to entries whose maskedContent contains at least one placeholder from the set
    (cross-entry evidence anchoring to the same people/entities).
  - Zero-vector entries excluded.
- JournalDao: add getEntriesWithMinValence(minValence: Float): List<JournalEntry>
  (suspend, one-shot).
- ReframingLoop Stage 3: inject top evidence entries as "Evidence for the Contrary"
  block into both Q2 and Q3 prompts.
- JournalEditorViewModel:
  - ✅ DONE: !reframe detection + stripping (commit 370e2ee).
  - ✅ DONE: triggerReframe() with fail-fast pipeline + TransitEvent logging.
  - Upgrade EditorUiState.isReframing/reframeResult → sealed ReframeState:
    Idle | Loading | Streaming(partial: String) | Done(text: String) | Error(msg: String)
  - Stream LlmResult.Token chunks into ReframeState.Streaming; seal to Done on Complete.
- ReframeBottomSheet.kt (app/ui): modal bottom sheet consuming reframeState.
  - Skeleton shimmer while Loading; token-by-token text append while Streaming.
  - "Apply" action: appends reframed text below original in editor field.
  - "Dismiss" resets reframeState to Idle.
- Unit tests (5): cloud provider never invoked, Streaming → Done state transition,
  valence gate enforced, placeholder match required, evidence block injected into prompt.
```

### ✅ Task 5.4: Room Schema Update & Audit Trail
```markdown
# Task: JournalEntry reframedContent Column + TransitEvent Logging
DONE — commit cb0240d

- JournalEntry entity: reframedContent: String? = null column added.
- LatticeDatabase migration v4 → v5 (note: v3→v4 was consumed by Task 5.2 activity_hierarchy):
  ALTER TABLE journal_entries ADD COLUMN reframedContent TEXT
- JournalDao: updateReframedContent(entryId: String, content: String) @Query suspend fun.
- JournalRepository: updateReframedContent() delegates to DAO (no extra TransitEvent).
- JournalEditorViewModel: tracks savedEntryId after save(); applyReframe() calls
  updateReframedContent() and clears reframeResult; dismissReframe() unchanged (state-only).
- LatticeApplication: MIGRATION_4_5 added to migration chain.
- Unit tests (3): null default on entity, DAO delegation on apply, DAO untouched on dismiss.
```
