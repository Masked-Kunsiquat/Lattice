# Project Pulse — Lattice @ 2026-04-08

**Branch:** `feature/the-interface` | **For:** Lattice Project Lead sync

---

## 1. PII Shield Verification — ENFORCED

**Status: PASS**

`PiiShield.kt` (object, `core-logic`) implements:
- `mask(text, people)` — replaces names/nicknames with `[PERSON_<UUID>]` tokens, longest-match first, word-boundary safe.
- `unmask(text, people)` — reverses for UI display only; stored content is never unmasked.

`JournalRepository.saveEntry()` enforces the contract at line 52–54:
```
maskedContent = PiiShield.mask(entry.content, people)   // ← masking gate
...
val embedding = embeddingProvider.generateEmbedding(maskedContent)  // masked input only
journalDao.insertEntry(entryToSave.copy(content = maskedContent))   // masked persisted
```
Raw text never reaches Room or the embedding pipeline. Read path (`getEntries`, `getEntryById`) unmasks in-memory only.

---

## 2. Librarian (Embedding Pipeline) — ACTIVE, ZERO-VECTOR FALLBACK IN EFFECT

**Status: CONDITIONAL PASS**

- Model: `snowflake-arctic-embed-xs.onnx` | Vocab: `vocab.txt` (assets)
- Output: `EMBEDDING_DIM = 384` `FloatArray` — mean-pooled from `[1 × seq_len × 384]` ONNX output.
- `JournalEntry.embedding: FloatArray` is persisted in `journal_entries` table.
- **Pipeline input constraint** is documented at `EmbeddingProvider.kt:56`: *"text must already be PII-masked before calling this function."* This is enforced by call site in `JournalRepository`.

**Gap:** If `snowflake-arctic-embed-xs.onnx` is absent from assets at runtime, `generateEmbedding` silently returns a zero-vector (`FloatArray(384)`). The asset presence has not been verified in this audit — confirm the `.onnx` file is bundled before release.

---

## 3. Orchestrator Check — LOCAL-FIRST, CLOUD DISABLED BY DEFAULT

**Status: PASS**

`LlmOrchestrator` routing priority (`LlmOrchestrator.kt:18–21`):
1. `nanoProvider` (Gemini Nano / AICore, API 35+)
2. `localFallbackProvider` (Qwen-1.5B / ONNX) ← effective fallback
3. `cloudProvider` — **only reachable when `cloudEnabled = true`**

Constructor default: `cloudEnabled = false` (line 49). Cloud is structurally unreachable unless explicitly opted in. The `init` block enforces that `cloudEnabled=true` requires both a non-null `cloudProvider` and a `piiDetector` — fail-safe by design.

Privacy state machine: `LocalOnly` ↔ `CloudTransit(providerName, sinceTimestamp)` — drives UI amber border and writes `TransitEvent` audit log (timestamp + provider only, never the prompt).

---

## 4. Phase 4 UI Audit — MoodGrid Circumplex Mapping CORRECT

**Status: PASS**

`MoodGrid.kt:219–221` (`handlePosition`):
```kotlin
val valence = (clamped.x / width) * 2f - 1f   // X-axis → Valence  [-1, +1]
val arousal = 1f - (clamped.y / height) * 2f   // Y-axis → Arousal  [-1, +1] (inverted: top=high)
```

Quadrant layout matches Russell Circumplex:

| Screen region | Valence | Arousal | Label |
|---|---|---|---|
| Top-right | + | + | EXCITED / ALIVE |
| Top-left | − | + | TENSE / ANGRY |
| Bottom-right | + | − | SERENE / CALM |
| Bottom-left | − | − | DEPRESSED / FATIGUED |

`CircumplexMapper.getLabel()` uses sub-quadrant magnitude comparison (e.g., `a > v → EXCITED else ALIVE`) to resolve 8 labels from the 2D space. Axis labels in the UI ("High Energy" top, "Low Energy" bottom, "Unpleasant" left, "Pleasant" right) are correct per the model.

---

## 5. Phase 5 Readiness — CBT Reframing Logic

**Status: FOUNDATION PRESENT, NO TRIGGER DETECTION YET**

**What exists:**
- `CbtLogic.detectDistortions(maskedText): List<String>` — currently detects **"All-or-Nothing"** via keyword match (`always`, `never`, `everyone`, etc.)
- `JournalEntry.cognitiveDistortions: List<String>` — persisted to Room per save.
- `JournalRepository.saveEntry()` calls `CbtLogic.detectDistortions(maskedContent)` automatically on every save.

**What does NOT yet exist:**
- No `!reframe` command detection anywhere in `JournalEditorViewModel`, `JournalEditorScreen`, or `JournalRepository`.
- No routing of a distortion-aware prompt to `LlmOrchestrator.process()`.
- No reframe response surface in `EditorUiState` or the UI layer.
- `CbtLogic` has only 1 of ~15 standard distortion categories implemented (stub-level).

**Phase 5 insertion points are clear:**
- `JournalEditorViewModel.onTextChanged()` — intercept `!reframe` prefix.
- `EditorUiState` — add `reframeResult: String?` field.
- `LlmOrchestrator.process(prompt, operationType = "reframe")` — already accepts an `operationType` parameter ready for this use case.

---

## Summary

| Subsystem | State |
|---|---|
| PII Shield | Enforced end-to-end |
| Embedding Pipeline | Architecture correct; asset presence unverified |
| LLM Orchestrator | Cloud off by default; local routing confirmed |
| MoodGrid / Circumplex | Correctly implemented |
| Phase 5 CBT Reframing | Distortion detection stub exists; `!reframe` trigger not yet wired |