# Benchmark Baseline

**Device:** Samsung Galaxy S25 Ultra (SM-S938U), arm64-v8a  
**OS:** Android 16  
**Date:** 2026-04-13  
**Variant:** `benchmarkDebug` — `debuggable=true` suppressed (library module limitation in AGP 9.x; real numbers will be faster on a non-debuggable build)  
**Seed data:** 95 entries total — Holmes (35) + Watson (30) + Werther (30), real 384-dim Arctic Embed XS embeddings

---

## EmbeddingBenchmark

| Test | Per-call median | Notes |
|---|---|---|
| `cold_generateEmbedding` | ~400 ms | Full `OrtSession` init (23 MB asset load) + inference per iteration. Thermal throttle at iter 0. Clean passing run pending. |
| `warm_generateEmbedding` | **1.5 ms** | 50-call batch, session persistent. Extremely stable — no thermal variance across 50 iterations. |
| `embedding_outputIsValid` | — | Functional assertion. Passed. 384-dim output, non-zero vector confirmed. |

> `warm_generateEmbedding` raw: ~75 ms / 50 calls. Allocation count: ~13,650 / 50 calls (~273 per call).

---

## SearchBenchmark

| Test | Per-call median | Notes |
|---|---|---|
| `findSimilarEntries` | **~6 ms** | 100-call batch. Includes `PiiShield.mask()` + `generateEmbedding()` + O(95) cosine scan. Two thermal throttle pauses; climbs to ~11 ms/call by end of run. |
| `findEvidenceEntries` | **~0.4 ms** | 100-call batch. Pure Room valence filter + placeholder match. High variance from GC (floor ~0.35 ms, spikes to ~1.8 ms). |

> `findSimilarEntries` raw: ~600 ms / 100 calls (stable window, iters 1–15). Allocation count: ~613,940 / 100 calls (~6,140 per call — dominated by embedding pipeline).  
> `findEvidenceEntries` raw: ~35–40 ms / 100 calls (stable floor). Allocation count: ~34,298 / 100 calls (~343 per call).

---

## Observations

- **findSimilarEntries allocation count** (~6,140/call) is high and worth revisiting — the embedding pipeline generates significant short-lived object pressure. Candidate for pooling in a future pass.
- **findEvidenceEntries** is fast enough to be invisible in the CBT pipeline; no optimization needed.
- **warm inference** at 1.5 ms is well within the 16 ms frame budget for any UI-adjacent work.
- All numbers from a `debuggable=true` build — expect 20–40% improvement on a proper release-signed benchmark once the app module hosts the benchmark variant.
