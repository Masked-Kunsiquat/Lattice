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

## Phase 3: The Orchestrator (Privacy Switchboard) 🔄

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

## Phase 4: The Interface (Visualizing the Logic)

### 🟢 Task 4.1: The 2D Mood Grid
```markdown
# Task: Implement the 2D Mood Grid UI
Build the interactive emotional input tool in the `:app` module.
1. **Canvas:** Create `MoodGrid.kt` using Jetpack Compose `Canvas`.
2. **Math:** Map 2D touch coordinates to (-1.0, 1.0) for Valence and Arousal.
3. **Mapping:** Link touch updates to `CircumplexMapper.getLabel()` for real-time feedback.
4. **Visuals:** Add axis labels ("Pleasant", "High Energy", etc.) and a glowing selector dot.
```

### 🟢 Task 4.2: Privacy-Aware Journal Editor
```markdown
# Task: Build the Privacy-Aware Editor
1. **UI:** Create a `JournalEditorScreen` with a Material 3 `TextField`.
2. **Privacy Highlighting:** Implement a VisualTransformation that highlights masked PII
   placeholders (e.g., [PERSON_UUID]) in a specific color.
3. **Privacy Border:** Implement a dynamic UI border/background:
   - Blue  = Processing Locally  (PrivacyLevel.LocalOnly)
   - Amber = Cloud Model Selected (PrivacyLevel.CloudTransit)
   Observe LlmOrchestrator.privacyState StateFlow.
4. **Integration:** Connect the MoodGrid result and Text result to JournalRepository.saveEntry().
```

---

## Phase 5: The Reframing Engine (CBT)

### 🟢 Task 5.1: Distortion Detection & !reframe
```markdown
# Task: Implement the CBT Reframing Flow
1. **Detection:** DistortionAnalyzer — CbtLogic.detectDistortions() is already wired into
   saveEntry(). Extend with more distortion rules as needed.
2. **Command:** Implement the "!reframe" trigger logic — route masked journal content
   through LlmOrchestrator.process(prompt, operationType="reframe").
3. **RAG Integration:** When reframing, use SearchRepository.findSimilarEntries() to pull
   "Evidence for the Contrary" (positive past entries about the mentioned person)
   and inject as context into the LLM prompt.
```
