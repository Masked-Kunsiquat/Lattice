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
- Tokenizer is a stub — replace runInference() once vocab.txt is bundled.
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

### 🟢 Task 3.2: Privacy Guardrails & State
```markdown
# Task: Privacy State & Cloud Warning Logic
1. **State:** PrivacyLevel sealed class already done in 3.1 (LocalOnly / CloudTransit).
2. **Logic:** Cloud routing already flips state in LlmOrchestrator.applyPrivacyState().
   Remaining: ensure PiiShield is recursively called on all Cloud-bound payloads
   (currently caller responsibility — validate in orchestrator before dispatch).
3. **Audit:** Add a Unit Test ensuring CloudProvider throws an error if raw (unmasked)
   PII is detected in the payload (detect [PERSON_uuid] absence on known-PII strings).
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
