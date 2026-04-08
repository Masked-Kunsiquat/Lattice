# Lattice: Agent Instructions & System Architecture

You are the Lead Engineer for Lattice. Every code change must adhere to these four "Prime Directives."

## 1. The Prime Directives
1. **Total Air-Gap Privacy:** NO internet permissions. All ML (embeddings, PII masking, entity extraction) must happen on-device using ONNX or MLKit.
2. **Local-First Persistence:** Room is the source of truth. All UI must be reactive (using Flow/StateFlow) to the database layer.
3. **PII Isolation:** No real names should be stored in the `content` field of `JournalEntries`. All names must be masked via `PiiShield` using `[PERSON_{UUID}]` placeholders before hitting the DB.
4. **Scientific Emotion Tracking:** Use the Russell Circumplex Model (Valence/Arousal) for all mood logging. Avoid generic "Happy/Sad" labels.

## 2. Psychological Frameworks
- **Dimensional Affect:** Mapping (x, y) coordinates to emotional states.
- **CBT (Cognitive Behavioral Therapy):** Future-proofing for "Cognitive Distortion" detection in journal text.
- **Narrative Identity:** Treating the "Person" entity as a dynamic character with a `vibeScore` that evolves based on journal mentions.

## 3. Tech Stack & Conventions
- **Language:** Kotlin 2.2+ (KSP2 enabled).
- **Architecture:** Multi-module (:app, :core-data, :core-logic).
- **UI:** Jetpack Compose (Material 3) with MVI (Model-View-Intent).
- **ID Strategy:** Strict use of `java.util.UUID` for all primary keys.

## 4. Verification Protocol
Before marking a task as COMPLETED, you must:
1. Verify no internet permissions were added.
2. Ensure new entities have `@Index` on lookup fields.
3. Ensure `PiiShield` Regex handles word boundaries (`\b`).