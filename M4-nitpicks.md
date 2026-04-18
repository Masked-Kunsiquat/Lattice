## Running commit message

```text
fix: address nitpick findings from m4-stability (part 2)

```

---

[x] core-logic/src/androidTest/java/com/github/maskedkunisquat/lattice/core/logic/EmbeddingBenchmark.kt-95-97 (1)
95-97: Already fixed — stream closed via bufferedReader().use {}; dimension checked via require().

[x] core-logic/src/androidTest/java/com/github/maskedkunisquat/lattice/core/logic/EmbeddingBenchmark.kt-105-138 (1)
105-138: Already fixed — cosineSimilarity has require(a.size == b.size); seed require(reference.size == EMBEDDING_DIM).

---

[x] core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/DistortionCorpusMapper.kt-58-64 (1)
58-64: Fixed — toLabels now returns BooleanArray? (null for unmapped/blank dominant).

---

[x] core-logic/src/main/java/com/github/maskedkunisquat/lattice/core/logic/JournalRepository.kt-38-49 (1)
38-49: Already fixed — getEntriesForPerson unmasks both content and reframedContent.

---

[x] app/src/main/java/com/github/maskedkunisquat/lattice/ui/PersonDetailScreen.kt-354-379 (1)
354-379: Already fixed — entries come from getEntriesForPerson which unmasks content.

---

[x] app/src/main/java/com/github/maskedkunisquat/lattice/ui/PersonDetailScreen.kt-97-104 (1)
97-104: Already handled — LaunchedEffect(state) calls onBack() when NotFound.

---

[x] app/src/main/java/com/github/maskedkunisquat/lattice/ui/PersonDetailScreen.kt-494-503 (1)
494-503: Already fixed — `if (normalized.isNotBlank())` guards phone creation.

---

[x] app/src/main/java/com/github/maskedkunisquat/lattice/ui/SettingsScreen.kt-257-264 (1)
257-264: Already fixed — calls checkSelfPermission directly, no remember(isDownloading) caching.

---

[x] LlmOrchestratorTest.kt:26-29 — FakeProvider already captures lastSystemInstruction; test at line 170 already asserts it.

---

[x] ReframingLoop.kt:111-125 — require(labels.size >= CognitiveDistortion.entries.size) already present; throws into onFailure → logger.warn → getOrNull() → null fallback.

---

[x] JournalEditorScreen.kt:360-365 — Fixed placeholder + supportingText.

[x] JournalEditorScreen.kt:112-118 — Removed dead if/else.

---

[x] DistortionCorpusMapper.kt:51-64 — toLabels returns BooleanArray? for unmapped dominant; tests updated.

---

[x] MainActivity.kt:49-54 — Permission request moved after unlock, permission check added, asked_once guard added.

---

[x] LlmOrchestrator.kt:74-94 — KDoc documents systemInstruction as developer-authored; PII gate on prompt only is intentional.


