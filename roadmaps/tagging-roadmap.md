# Lattice: Inline Tagging System (Schema v9)

> Created: 2026-04-09
> Branch: `feature/live-embeddings-mlkit-ner`
> Context: Replaces the MLKit NER plan. Explicit user-driven tagging (`@`, `#`, `!`) gives
> the same PII coverage with zero false positives and a better UX. `!reframe` text trigger
> is replaced by a toolbar button to free `!` for place tagging.

---

## Scope

| Trigger | Entity | DB Table | Stored form | PII-masked |
|---------|--------|----------|-------------|------------|
| `@name` | Person | `people` (existing) | `[PERSON_uuid]` | yes |
| `#tag`  | Tag    | `tags` (new, v9)    | display only    | no  |
| `!place`| Place  | `places` (new, v9)  | `[PLACE_uuid]`  | yes |

Reframe trigger: `!reframe` text command → replaced by a **Reframe button** in the editor toolbar.

---

## Phase 8: Inline Tagging + Schema v9

### 8.1 — Schema v9 Migration

**Goal:** Add `tags` and `places` tables. Add `tagIds` and `placeIds` JSON-array columns to
`journal_entries` linking entries to their resolved tag/place records.
Places are masked like persons; tags are unmasked display labels.

- [x] Add `Tag` entity: `id: UUID`, `name: String`
- [x] Add `Place` entity: `id: UUID`, `name: String`
- [x] Add `TagDao`: `getAll(): Flow<List<Tag>>`, `insert`, `deleteById`, `searchByName(query): Flow<List<Tag>>`
- [x] Add `PlaceDao`: `getAll(): Flow<List<Place>>`, `insert`, `deleteById`, `searchByName(query): Flow<List<Place>>`, `getByName(name): Place?`
- [x] `MIGRATION_8_9`:
  ```sql
  CREATE TABLE tags   (id TEXT PRIMARY KEY, name TEXT NOT NULL);
  CREATE TABLE places (id TEXT PRIMARY KEY, name TEXT NOT NULL);
  ALTER TABLE journal_entries ADD COLUMN tagIds TEXT NOT NULL DEFAULT '[]';
  ALTER TABLE journal_entries ADD COLUMN placeIds TEXT NOT NULL DEFAULT '[]';
  ```
- [x] `LatticeTypeConverters`: add `fromUuidList` / `toUuidList` (JSON array of UUID strings)
- [x] `LatticeDatabase`: register `Tag`, `Place` entities; add `MIGRATION_8_9`; expose `tagDao()`, `placeDao()`; bump to v9
- [x] `LatticeApplication`: register `MIGRATION_8_9` in migration chain

**Acceptance criteria:**
- [x] Compiles clean against schema v9
- [ ] Fresh install opens at schema v9 with no migration errors (verify on device in 8.6)
- [ ] Existing installs upgrade cleanly from v8 → v9 (verify on device in 8.6)

---

### 8.2 — Replace `!reframe` with Toolbar Button

**Goal:** Remove the magic text command. Add an explicit Reframe action button to the editor.
Frees `!` as the place trigger.

- [x] Remove `REFRAME_COMMAND = "!reframe"` constant and the intercept in `onTextChanged()`
- [x] Add `fun requestReframe()` to `JournalEditorViewModel` — calls `triggerReframe(uiState.value.text)`
- [x] Add `FilledTonalButton("Reframe")` + `Button("Save")` side-by-side row in `JournalEditorScreen`
  - Disabled when: text is blank, `reframeState` is `Loading`/`Streaming`, or `modelLoadState != READY`
  - Label reflects state: "Reframing…" in-flight, "Model loading…" when not ready
- [x] `modelLoadState` passed into `JournalEditorContent` for button state

**Acceptance criteria:**
- [x] Typing `!` in the editor no longer triggers the reframe pipeline
- [x] Tapping the button fires `requestReframe()` → `triggerReframe()` with current text
- [x] Button is disabled while a reframe is in-flight

---

### 8.3 — `@` Person Mention Autocomplete

**Goal:** Typing `@` opens a dropdown of existing `Person` records filtered by query.
Selecting a result inserts the person's display name inline; on save the token resolves to
`[PERSON_uuid]` via `PiiShield.mask()` (no changes needed to masking logic — name is in DB).
"Create new" adds a minimal `Person` record and selects it.

- [x] `MentionState` sealed class in `JournalEditorViewModel`:
  `Idle | Suggesting(query, results: List<Person>)`
- [x] `onTextChanged()`: detect `@(\w*)$` pattern; query `PeopleRepository.searchByName(query)`; emit `MentionState.Suggesting`
- [x] `PersonDao`: add `searchByName(query: String): List<Person>` — `LIKE '%query%'` on `firstName`, `lastName`, `nickname`; limit 20
- [x] `onMentionSelected(person: Person)`: replace `@<query>` token in text with `person.nickname ?: person.firstName`; set `MentionState.Idle`
- [x] `onMentionCreateNew(name: String)`: insert minimal `Person(firstName = name, relationshipType = ACQUAINTANCE)` via `PeopleRepository`; call `onMentionSelected` with result
- [x] `MentionDropdown` composable: `DropdownMenu` anchored to text field `Box`; shows `Person` rows + "Create '@name'" footer item
- [x] Wire `MentionDropdown` into `JournalEditorScreen` — inside `Box` wrapping `OutlinedTextField`
- [x] `PeopleRepository`: add `suspend fun insertPerson(person: Person): UUID` and `suspend fun searchByName(query: String): List<Person>`

**Acceptance criteria:**
- [ ] Typing `@Wat` surfaces Watson if seeded; selecting replaces token with display name
- [ ] On save, display name is masked to `[PERSON_uuid]` by existing `PiiShield.mask()`
- [ ] "Create new" inserts a `Person` row and immediately resolves the mention
- [ ] Dropdown closes on selection, Escape, or tap-outside

---

### 8.4 — `#` Tag Autocomplete

**Goal:** Typing `#` opens a dropdown of existing `Tag` records. Tags are unmasked
organizational labels — no PII implications. Selecting or creating a tag appends it to
`JournalEntry.tagIds` on save.

- [ ] Extend `MentionState` to handle `#` trigger alongside `@` (shared dropdown logic)
- [ ] `onTextChanged()`: detect `#<query>` pattern; query `TagDao.searchByName(query)`
- [ ] `onTagSelected(tag: Tag)`: replace `#<query>` with `#${tag.name}` in display text; track resolved `tagId` for save
- [ ] `onTagCreateNew(name: String)`: insert `Tag(name)` via new `TagRepository`; call `onTagSelected`
- [ ] `TagRepository`: `suspend fun insertTag(name: String): Tag`, `fun searchTags(query): Flow<List<Tag>>`
- [ ] `JournalEditorViewModel.save()`: collect resolved `tagIds`; include in `JournalEntry` on save
- [ ] `JournalEditorScreen`: display resolved `#tag` tokens with a tinted chip style (reuse `PiiHighlightTransformation` pattern or inline span)

**Acceptance criteria:**
- [ ] Typing `#work` surfaces existing "work" tag; creates it if absent
- [ ] Saved entry has correct `tagIds` list in DB
- [ ] Tags display unmasked in history screen

---

### 8.5 — `!` Place Autocomplete + PiiShield Extension

**Goal:** Typing `!` opens a dropdown of existing `Place` records. Places are masked like
persons — `[PLACE_uuid]` tokens stored in DB, display names shown in UI.

- [ ] Extend `MentionState` to handle `!` trigger
- [ ] `PlaceRepository`: `suspend fun insertPlace(name: String): Place`, `fun searchPlaces(query): Flow<List<Place>>`
- [ ] `onPlaceSelected` / `onPlaceCreateNew` — mirror the `@` person flow
- [ ] `PiiShield.mask()`: add place masking pass — replace place `name` occurrences with `[PLACE_uuid]` tokens (same word-boundary pattern as person masking)
- [ ] `PiiShield.unmask()`: add place unmask pass — replace `[PLACE_uuid]` with `place.name`
- [ ] `JournalRepository.saveEntry()`: pass `places: List<Place>` into `PiiShield.mask()` (alongside `people`) — requires fetching from `PlaceDao`
- [ ] `JournalRepository.maskText()`: same — include places in mask call
- [ ] Update `PiiHighlightTransformation` to also highlight `[PLACE_uuid]` tokens (distinct color from `[PERSON_uuid]`)
- [ ] `JournalEntry`: add `placeIds: List<UUID>` — populated from resolved place tokens on save

**Acceptance criteria:**
- [ ] `!library` → stored as `[PLACE_<uuid>]`; displayed as "library" in history
- [ ] `PiiShield.mask()` masks both persons and places before any entry reaches the LLM
- [ ] `[PLACE_uuid]` tokens highlighted in a distinct color in the editor

---

### 8.6 — End-to-End UX Pass

**Goal:** Walk the app as a first-time user with no seed data. Identify and fix critical
friction points before any wider testing.

- [ ] Fresh install: confirm lock screen → biometric → editor flow works
- [ ] Write a journal entry with `@`, `#`, `!` tags — verify all three dropdowns fire
- [ ] Trigger a reframe via the new button — verify `Loading → Streaming → Done` states
- [ ] Accept the reframe — verify it persists to the entry
- [ ] Navigate to History — confirm the entry appears with unmasked content + tags
- [ ] Navigate to Audit Trail — confirm `TransitEvent` for the reframe is logged
- [ ] Navigate to Settings — confirm cloud toggle, export, and Debug screen (debug build only) all open
- [ ] Delete the entry — confirm swipe-delete removes it from history
- [ ] Identify and log any crashes, missing states, or confusing UX as follow-up issues

---

## Dependency Order

```text
8.1 (Schema v9)
  └── 8.2 (Remove !reframe → button)    — no schema dep, can run parallel with 8.1
  └── 8.3 (@ person mention)            — needs PersonDao.searchByName from 8.1
  └── 8.4 (# tag autocomplete)          — needs TagDao + TagRepository from 8.1
  └── 8.5 (! place autocomplete)        — needs PlaceDao + PiiShield update from 8.1
        └── 8.6 (UX pass)               — needs all of 8.2–8.5 complete
```

**Recommended order:** 8.1 → 8.2 (parallel) → 8.3 → 8.4 → 8.5 → 8.6

8.2 (button swap) has no schema dependency and can land first as a standalone commit.
