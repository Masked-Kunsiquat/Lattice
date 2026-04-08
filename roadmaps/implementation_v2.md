# 📑 Lattice: Master Implementation Backlog (v2.0)

## Phase 2: The Librarian (Local Semantic Intelligence)

### 🟢 Task 2.1: ONNX Embedding Pipeline
```markdown
# Task: Implement ONNX Embedding Pipeline
In the `:core-data` and `:core-logic` modules, set up local text embeddings.
1. **Dependency:** Add `microsoft.onnxruntime:onnxruntime-android` to `:core-logic`.
2. **Model Asset:** Create a placeholder in `assets/` for `snowflake-arctic-embed-xs.onnx`.
3. **Provider:** Create `EmbeddingProvider.kt` in `:core-logic`:
   - Implement `generateEmbedding(text: String): FloatArray` (384-dim).
   - Ensure it runs on `Dispatchers.Default` to avoid blocking the UI.
4. **Integration:** Update `JournalRepository`:
   - When saving an entry, trigger `EmbeddingProvider` using MASKED text.
   - Save the resulting `FloatArray` to the `embedding` column in the DB.
5. **Test:** Add a test verifying that embedding generation is off-main-thread.
```

### 🟢 Task 2.2: Vector Search Engine
```markdown
# Task: Implement Local Semantic Search
Enable the app to find "Related Memories" using vector distance.
1. **DAO:** Update `JournalDao` with a query for Euclidean distance or Cosine Similarity.
2. **Logic:** Create `SearchRepository.kt` in `:core-data`:
   - Implement `findSimilarEntries(queryText: String, limit: Int): Flow<List<JournalEntry>>`.
   - Logic: Vectorize the query string -> search DB for nearest neighbors.
3. **Privacy:** Ensure search queries are masked via `PiiShield` before vectorization.
```

---

## Phase 3: The Orchestrator (Privacy Switchboard)

### 🟢 Task 3.1: LLM Strategy Manager & Fallbacks
```markdown
# Task: Implement LLM Orchestrator & Local Fallbacks
Manage how the app handles "Reframing" and "Summarization" based on hardware.
1. **Interface:** Create `LlmProvider` interface with a `process(text: String): Flow<Result>` method.
2. **Strategies:** Implement three tiers:
   - `NanoProvider`: Uses Google AICore (Gemini Nano).
   - `LocalFallbackProvider`: Uses MediaPipe/ONNX to run a bundled `Qwen-1.5B`.
   - `CloudProvider`: Secure implementation for Claude/Gemini Pro (DISABLED by default).
3. **Orchestrator:** Create `LlmOrchestrator.kt` to auto-detect hardware and select the best Local tier.
```

### 🟢 Task 3.2: Privacy Guardrails & State
```markdown
# Task: Privacy State & Cloud Warning Logic
1. **State:** Create a `PrivacyLevel` Sealed Class: `LOCAL_ONLY`, `CLOUD_TRANSIT`.
2. **Logic:** Implement a check in `LlmOrchestrator`:
   - If a request is routed to `CloudProvider`, the state must flip to `CLOUD_TRANSIT`.
   - Ensure `PiiShield` is recursively called on all Cloud-bound payloads.
3. **Audit:** Add a Unit Test ensuring `CloudProvider` throws an error if raw (unmasked) PII is detected in the payload.
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
2. **Privacy Highlighting:** Implement a VisualTransformation that highlights masked PII placeholders (e.g., [PERSON_UUID]) in a specific color.
3. **Privacy Border:** Implement a dynamic UI border/background:
   - Blue = Processing Locally.
   - Amber = Cloud Model Selected (Warning).
4. **Integration:** Connect the `MoodGrid` result and Text result to `JournalRepository.saveEntry()`.
```

---

## Phase 5: The Reframing Engine (CBT)

### 🟢 Task 5.1: Distortion Detection & !reframe
```markdown
# Task: Implement the CBT Reframing Flow
1. **Detection:** Create `DistortionAnalyzer.kt` to tag `cognitiveDistortions` in journal text.
2. **Command:** Implement the "!reframe" trigger logic.
3. **RAG Integration:** When reframing, use `SearchRepository` to pull "Evidence for the Contrary" (Positive past entries about the mentioned person) to ground the AI's response.
```
