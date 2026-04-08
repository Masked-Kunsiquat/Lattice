# Lattice Export Format Specification

**Schema Version:** `lattice-v2`  
**Manifest Version:** `1.0`

This document defines the canonical JSON structure produced by `ExportManager.generateManifest()`. Any app that speaks the Lattice Standard can import this file and reconstruct the full semantic graph.

---

## Top-Level Envelope

```json
{
  "export_version": "1.0",
  "exported_at": "2026-04-07T12:00:00Z",
  "schema_version": "lattice-v2",
  "model_spec": { ... },
  "data": {
    "people": [ ... ],
    "journal_entries": [ ... ],
    "transit_events": [ ... ]
  }
}
```

| Field | Type | Description |
|---|---|---|
| `export_version` | string | Manifest format version — bump when the envelope structure changes |
| `exported_at` | ISO 8601 UTC | Timestamp of export |
| `schema_version` | string | Lattice database schema version |
| `model_spec` | object | Embedding model metadata (see below) |
| `data` | object | All entity arrays |

---

## `model_spec`

Included in every export so the data remains interpretable by future apps — even after model upgrades.

```json
{
  "embedding_model": "snowflake-arctic-embed-xs",
  "embedding_dimensions": 384,
  "embedding_type": "float32",
  "embedding_pooling": "mean",
  "model_source": "https://huggingface.co/Snowflake/snowflake-arctic-embed-xs",
  "note": "Embeddings are stored as comma-separated float32 values in Room. Re-embed content_masked with this model to restore semantic search."
}
```

| Field | Value |
|---|---|
| `embedding_model` | `snowflake-arctic-embed-xs` |
| `embedding_dimensions` | `384` |
| `embedding_type` | `float32` |
| `embedding_pooling` | `mean` (mean-pool over token dimension) |

---

## `data.people` — Person Entity

Represents a person in the user's social graph.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "first_name": "Jordan",
  "last_name": null,
  "nickname": "J",
  "relationship_type": "friend",
  "vibe_score": 0.35,
  "is_favorite": false
}
```

| Field | Type | Description |
|---|---|---|
| `id` | UUID string | Stable identifier; also appears in `[PERSON_uuid]` placeholders |
| `first_name` | string | Required |
| `last_name` | string \| null | Optional |
| `nickname` | string \| null | Display name shown in UI (preferred over first_name if present) |
| `relationship_type` | enum string | One of: `family`, `friend`, `professional`, `acquaintance` |
| `vibe_score` | float [-1.0, 1.0] | Narrative sentiment score; evolves as person is mentioned with +/- valence entries |
| `is_favorite` | boolean | UI pin indicator |

---

## `data.journal_entries` — Journal Entry

A single moment of self-reflection — the atomic unit of the Lattice.

```json
{
  "id": "7f3a9c2e-1234-5678-abcd-ef0123456789",
  "timestamp_ms": 1744027200000,
  "timestamp_human": "2026-04-07T12:00:00Z",
  "content_masked": "I had a difficult conversation with [PERSON_550e8400-e29b-41d4-a716-446655440000] today.",
  "valence": -0.4,
  "arousal": 0.6,
  "mood_label": "tense",
  "cognitive_distortions": ["All-or-Nothing"]
}
```

| Field | Type | Description |
|---|---|---|
| `id` | UUID string | Entry identifier |
| `timestamp_ms` | long | Unix epoch in milliseconds |
| `timestamp_human` | ISO 8601 UTC | Human-readable timestamp |
| `content_masked` | string | Journal text with PII replaced by `[PERSON_uuid]` placeholders. Cross-reference `data.people` to resolve names. Raw embeddings are intentionally excluded. |
| `valence` | float [-1.0, 1.0] | Emotional positivity: -1 = very negative, +1 = very positive |
| `arousal` | float [-1.0, 1.0] | Emotional intensity: -1 = low energy, +1 = high energy |
| `mood_label` | enum string | Derived from the Circumplex Model of Affect. One of: `excited`, `alive`, `serene`, `calm`, `depressed`, `fatigued`, `angry`, `tense` |
| `cognitive_distortions` | string[] | CBT distortions detected. Current detector: `"All-or-Nothing"` |

### Mood Coordinate System

The 2D valence/arousal space maps to mood labels as follows:

```
                   HIGH AROUSAL (+1)
                         |
          EXCITED        |        TENSE
      (high +v, high +a) | (high -v, high +a)
                         |
PLEASANT (-1) ───────────┼─────────────── UNPLEASANT (+1)
    VALENCE              |                   VALENCE
                         |
          SERENE         |        FATIGUED
       (high +v, low -a) | (high -v, low -a)
                         |
                   LOW AROUSAL (-1)
```

Quadrant boundaries are determined by whichever dimension has greater absolute magnitude.

---

## `data.transit_events` — Sovereignty Audit Trail

Written whenever user data was routed to a cloud LLM provider. The **prompt content is intentionally not stored** — only metadata confirming when and where data travelled.

```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "timestamp_ms": 1744027500000,
  "timestamp_human": "2026-04-07T12:05:00Z",
  "provider": "cloud_claude",
  "operation": "reframe"
}
```

| Field | Type | Description |
|---|---|---|
| `id` | UUID string | Event identifier |
| `timestamp_ms` | long | Unix epoch in milliseconds |
| `timestamp_human` | ISO 8601 UTC | Human-readable timestamp |
| `provider` | string | Which cloud endpoint received the data. Known values: `cloud_claude`, `cloud_gemini_pro` |
| `operation` | string | Type of inference request. Known values: `reframe`, `summarize` |

---

## PII Placeholder Resolution

Journal entries use `[PERSON_<uuid>]` placeholders in `content_masked`. To reconstruct human-readable text:

1. Build a map of `{ id → nickname ?? first_name }` from `data.people`
2. Replace all `[PERSON_<uuid>]` occurrences in `content_masked` using the map

This separation ensures journal text is safe to store, index, and embed without exposing real names.
