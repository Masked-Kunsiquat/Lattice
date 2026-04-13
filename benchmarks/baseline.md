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
| `cold_generateEmbedding` | **287 ms** (median), 169 ms (min) | Full `OrtSession` init (23 MB asset load) + inference per iteration. Clean passing run — no thermal throttle. ~91,881 allocs/call. |
| `warm_generateEmbedding` | **1.58 ms** | 50-call batch, session persistent. Extremely stable — no thermal variance across 50 iterations. |
| `embedding_outputIsValid` | — | Functional assertion. Passed. 384-dim output, non-zero vector confirmed. |

> `warm_generateEmbedding` raw: ~78.8 ms / 50 calls. Allocation count: 13,650 / 50 calls (~273 per call).

---

## SearchBenchmark

### Before optimization (Pair allocations + Flow wrapper)

| Test | Per-call median | Allocations/call | Notes |
|---|---|---|---|
| `findSimilarEntries` | **~6 ms** | ~6,140 | 100-call batch. Includes `PiiShield.mask()` + `generateEmbedding()` + O(95) cosine scan. Two thermal throttle pauses; climbs to ~11 ms/call by end of run. |
| `findEvidenceEntries` | **~0.4 ms** | ~343 | 100-call batch. Pure Room valence filter + placeholder match. High variance from GC (floor ~0.35 ms, spikes to ~1.8 ms). |

> `findSimilarEntries` raw: ~600 ms / 100 calls (stable window, iters 1–15). Allocation count: ~613,940 / 100 calls.  
> `findEvidenceEntries` raw: ~35–40 ms / 100 calls (stable floor). Allocation count: ~34,298 / 100 calls.

### After optimization (index-based scoring + suspend fun + zero-vector guard)

| Test | Per-call floor | Allocations/call | Notes |
|---|---|---|---|
| `findSimilarEntries` | **5.65 ms** | **5,899** (−4%) | 100-call batch. Floor stable iters 2–22 at 565–619 ms/100. Thermal pause skews median to ~8.5 ms. |
| `findEvidenceEntries` | **0.35 ms** | **294** (−14%) | 100-call batch. Median ~1.0 ms due to GC variance; floor unchanged vs before. |

> `findSimilarEntries` raw floor: 565,310,521 ns / 100 calls. Allocation count: 589,898–589,908 / 100 calls.  
> `findEvidenceEntries` raw floor: 34,546,563 ns / 100 calls. Allocation count: 29,400 / 100 calls.  
> Allocation savings are real but modest — dominant cost remains `PiiShield.mask()` + `generateEmbedding()`, not the scoring loop.

---

## Observations

- **findEvidenceEntries** is fast enough to be invisible in the CBT pipeline; no optimization needed.
- **warm inference** at 1.5 ms is well within the 16 ms frame budget for any UI-adjacent work.
- All numbers from a `debuggable=true` build — expect 20–40% improvement on a proper release-signed benchmark once the app module hosts the benchmark variant.
