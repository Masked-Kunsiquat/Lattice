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

> **Strategy:** All reframing runs locally via `LocalFallbackProvider` (Qwen-1.5B ONNX).
> `NanoProvider` and `CloudProvider` are excluded from the reframe routing path.
> No reframe content — masked or otherwise — ever leaves the device.

### 🟢 Task 5.1: Qwen-Specific Prompt Engineering
```markdown
# Task: CbtPromptBuilder — Qwen-1.5B Prompt Formatting
Deliverables:
- CbtPromptBuilder.kt (core-logic): pure function object, no Android deps.
  - build(maskedText: String, distortions: List<CbtDistortion>, evidence: List<String>): String
  - Formats a structured <|im_start|>/<|im_end|> ChatML prompt (Qwen native format).
  - System turn: instructs Qwen to act as a CBT coach, never reveal PII placeholders,
    output a reframe in ≤ 3 sentences.
  - Evidence turn: injects "Evidence for the Contrary" block when evidence list non-empty.
  - User turn: masked journal text + detected distortion labels.
  - Distortion coverage: Catastrophizing, Black-and-White Thinking, Mind Reading,
    Overgeneralization, Emotional Reasoning (maps to CbtDistortion enum).
- Unit tests (5): empty evidence path, multi-distortion label formatting,
  placeholder passthrough (no UUID stripping), max-length guard, golden-string snapshot.
```

### 🟢 Task 5.2: Semantic Evidence RAG
```markdown
# Task: Positive-Valence Evidence Retrieval in SearchRepository
Deliverables:
- SearchRepository.findEvidenceEntries(
      placeholders: Set<String>,
      minValence: Float = 0.5f,
      limit: Int = 5
  ): Flow<List<JournalEntry>>
  - Fetches entries where valence > minValence (positive quadrant only).
  - Filters to entries whose maskedContent contains at least one placeholder from the set
    (same person/entity as current entry — cross-entry evidence anchoring).
  - Ranks by cosine similarity to the current entry's embedding (reuses existing
    in-memory cosine logic from findSimilarEntries).
  - Zero-vector entries excluded.
- JournalDao: add getEntriesWithMinValence(minValence: Float): List<JournalEntry>
  (suspend, one-shot; drives the positive-only pre-filter before cosine ranking).
- Unit tests (4): valence gate enforced, placeholder match required, cosine ranking order,
  limit capping.
```

### 🟢 Task 5.3: !reframe Command Trigger & Stream
```markdown
# Task: !reframe Interception, LocalFallbackProvider Routing, UI Stream
Deliverables:
- JournalEditorViewModel:
  - Detect trailing "!reframe" token in live text via regex; strip it before save.
  - triggerReframe(): collects findEvidenceEntries(), calls CbtPromptBuilder.build(),
    routes prompt exclusively through LocalFallbackProvider (bypasses LlmOrchestrator
    tier selection — no cloud path possible).
  - New UiState fields: reframeState: ReframeState (Idle | Loading | Streaming(partial) | Done(text) | Error)
  - Streams LlmResult.Token chunks into reframeState.Streaming; seals to Done on Complete.
- ReframeBottomSheet.kt (app/ui): modal bottom sheet consuming reframeState.
  - Skeleton shimmer while Loading; token-by-token text append while Streaming.
  - "Apply" action: appends reframed text below original in editor field.
  - "Dismiss" resets reframeState to Idle.
- LocalFallbackProvider: wire real ONNX inference call when model asset present;
  retain stub/scaffold behaviour (emitting placeholder tokens) when asset absent.
- Unit tests (3): !reframe stripped from saved text, cloud provider never invoked,
  Streaming → Done state transition.
```

### 🟢 Task 5.4: Room Schema Update & Audit Trail
```markdown
# Task: JournalEntry reframedContent Column + TransitEvent Logging
Deliverables:
- JournalEntry entity: add reframedContent: String? = null column.
- LatticeDatabase migration v3 → v4:
  ALTER TABLE journal_entries ADD COLUMN reframed_content TEXT;
- JournalDao: updateReframedContent(entryId: String, content: String) suspend fun.
- JournalEditorViewModel: on "Apply", call updateReframedContent() and log a
  TransitEvent(provider="local_qwen", operationType="reframe", entryId=...) —
  timestamp + provider only, no content written to audit trail.
- TransitEvent entity: add operationType: String column (migration v3→v4 same block).
- Unit tests (3): migration no-op for null reframedContent, TransitEvent logged on apply,
  TransitEvent NOT logged on dismiss.
```
