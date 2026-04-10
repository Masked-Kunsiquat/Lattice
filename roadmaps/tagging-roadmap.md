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

**Goal:** Typing `@` opens a docked suggestion strip of existing `Person` records filtered by
query. The strip is pinned above the soft keyboard using `Modifier.imePadding()` — no popup
or overlay. Selecting a chip inserts the display name inline; on save the token resolves to
`[PERSON_uuid]` via pre-save substitution in `save()`.

#### Completed — ViewModel & Data Layer

- [x] `MentionState` sealed class in `JournalEditorViewModel`:
  `Idle | SuggestingPerson(query, results: List<Person>) | SuggestingTag(query, results: List<Tag>) | SuggestingPlace(query, results: List<Place>)`
- [x] `onTextChanged()`: detects `@((?:\w+ )*\w+|\w*)$` (spaces allowed); cancels previous `currentMentionJob` before launching new lookup; emits `SuggestingPerson`
- [x] `PersonDao`: `searchByName(query): Flow<List<Person>>` — `LIKE '%query%'` on `firstName`, `lastName`, `nickname`; limit 20
- [x] `onMentionSelected(person)`: replaces `@<query>` with `@displayName` (nickname ?? "First Last" ?? firstName); stores `displayName → UUID` in `resolvedPersons`; sets `MentionState.Idle`
- [x] `onMentionCreateNew(name)`: splits query on first space into `firstName` + `lastName`; constructs `Person(ACQUAINTANCE)`; calls `insertPerson`; calls `onMentionSelected`
- [x] `PeopleRepository`: `suspend fun insertPerson(person)`, `suspend fun searchByName(query): List<Person>`
- [x] `save()`: substitutes `@displayName` → `[PERSON_uuid]` (longest-first) before handing content to `JournalRepository`

#### Completed — Original Dropdown UI (superseded)

- [x] `MentionDropdown` composable: `DropdownMenu` with `PopupProperties(focusable = false)`; `SuggestingPerson` branch
- [x] Wired into `JournalEditorScreen` inside `Box(Alignment.BottomStart)` wrapping `OutlinedTextField`

#### Completed — Docked Suggestion Strip

- [x] **`SuggestionStrip` composable** — `LazyRow` of `SuggestionChip`s (results) + trailing `AssistChip` (create-new); handles all three `Suggesting*` states in a single `when` block; keyed by entity id; renders nothing when `Idle`
  - Person chips: leading `Icons.Filled.Person`; label = `@displayName`; trailing `AssistChip` with `Icons.Filled.PersonAdd`
  - Tag chips: label = `#name`; trailing `AssistChip` with `Icons.Filled.Add`
  - Place chips: leading `Icons.Filled.LocationOn`; label = `!name`; trailing `AssistChip` with `Icons.Filled.AddLocation`
- [x] **IME anchor** — `Modifier.imePadding()` on the editor `Column`; strip placed between text field and save/reframe row so it floats above the soft keyboard naturally
- [x] Strip animates in/out with `fadeIn + expandVertically` / `fadeOut + shrinkVertically`; takes no layout space when `Idle`
- [x] `TextField.onFocusChanged` calls `onMentionDismiss()` when focus leaves the field
- [x] Removed `MentionDropdown`, `DropdownMenu`, `PopupProperties` — no popup overhead
- [x] `PiiHighlightTransformation` already highlights `@\S+` fallback; multi-word resolved names covered by `resolvedPersonNames` set

**Acceptance criteria:**
- [x] Typing `@Wat` surfaces Watson (if seeded) as an `AssistChip` above the keyboard; tapping replaces `@Wat` with `@Watson` (or full name)
- [x] Typing `@John Smith` (multi-word) surfaces results matching first + last; chip inserts `@John Smith` as a highlighted token
- [x] "Add @{query}" chip creates a new Person and immediately resolves the mention
- [x] Strip is invisible when `mentionState == Idle`; reappears on next `@` trigger
- [x] Keyboard remains visible and focussed throughout interaction (no IME steal)

---

### 8.4 — `#` Tag Autocomplete

**Goal:** Typing `#` shows existing `Tag` records in the docked strip. Tags are unmasked
organizational labels — no PII implications. Selecting or creating a tag appends it to
`JournalEntry.tagIds` on save.

#### Completed — ViewModel & Data Layer

- [x] `MentionState.SuggestingTag(query, results: List<Tag>)`
- [x] `onTextChanged()`: detects `#(\w*)$`; queries `TagRepository.searchTags(query)`; emits `SuggestingTag`
- [x] `onTagSelected(tag)`: replaces `#<query>` with `#${tag.name}`; stores `tag.name → tag.id` in `resolvedTags`
- [x] `onTagCreateNew(name)`: get-or-create via `TagRepository.insertTag(name)`; calls `onTagSelected`
- [x] `TagRepository`: `suspend fun insertTag(name): Tag`, `suspend fun searchTags(query): List<Tag>`
- [x] `TagDao`: `getByName(name): Tag?` for get-or-create semantics
- [x] `save()`: scans text for `#(\w+)` tokens, resolves against `resolvedTags`, includes `tagIds` in `JournalEntry`
- [x] `PiiHighlightTransformation`: `tagHighlightColor` param; highlights `#\w+` tokens in `colorScheme.secondary`

#### Completed — Docked Strip Integration

- [x] `SuggestionStrip` handles `MentionState.SuggestingTag`: chips labelled `#name`; trailing "Add #" `AssistChip`
- [x] Tags single-word only (`\w*` regex); no multi-word handling needed
- [x] `PiiHighlightTransformation` `TAG_REGEX` covers resolved tags; no changes needed

**Acceptance criteria:**
- [x] Typing `#work` surfaces existing "work" tag as a chip; creates it if absent
- [x] Strip chip reads `#work`; tapping inserts `#work` highlighted in `secondary` color
- [ ] Saved entry has correct `tagIds` list in DB — *not yet device-verified*

---

### 8.5 — `!` Place Autocomplete + PiiShield Extension

**Goal:** Typing `!` shows existing `Place` records in the docked strip. Places are masked like
persons — `[PLACE_uuid]` tokens stored in DB, display names shown in UI.

#### Completed — ViewModel & Data Layer

- [x] `MentionState.SuggestingPlace(query, results: List<Place>)` — `!((?:\w+ )*\w+|\w*)$` trigger
- [x] `PlaceRepository`: `suspend fun searchPlaces(query): List<Place>`, `suspend fun insertPlace(name): Place` (get-or-create)
- [x] `onPlaceSelected(place)`: replaces `!<query>` with `!${place.name}`; stores `name → UUID` in `resolvedPlaces`; sets `MentionState.Idle`
- [x] `onPlaceCreateNew(name)`: get-or-create via `PlaceRepository.insertPlace(name)`; calls `onPlaceSelected`
- [x] `PiiShield.mask()`: place masking pass — `[PLACE_uuid]` tokens, longest-first; backward-compatible `places` param
- [x] `PiiShield.unmask()`: place unmask pass — `[PLACE_uuid]` → `place.name`
- [x] `JournalRepository`: injects `PlaceDao`; `saveEntry()` + `maskText()` pass places to `PiiShield.mask()`; `getEntries()` + `getEntryById()` pass places to `PiiShield.unmask()`
- [x] `save()`: substitutes `!name` → `[PLACE_uuid]` (longest-first) before handoff; collects `placeIds` from `resolvedPlaces` map
- [x] `PiiHighlightTransformation`: `placeHighlightColor` param + `resolvedPlaceNames` set; highlights multi-word `!Place Name` tokens in `PlaceGreen`

#### Completed — Docked Strip Integration

- [x] `SuggestionStrip` handles `MentionState.SuggestingPlace`: chips with `Icons.Filled.LocationOn` + `!name`; trailing "Add !" `AssistChip` with `Icons.Filled.AddLocation`
- [x] Multi-word place names handled by `PLACE_REGEX` and `resolvedPlaceNames` highlight set; no regex changes needed

**Acceptance criteria:**
- [x] `!library` → strip shows "!library" chip → tap → stored as `[PLACE_<uuid>]`; displayed as "!library" in editor (highlighted green), "library" unmasked in history
- [x] `!Central Park` (multi-word) → chip inserts `!Central Park` as a single green-highlighted token
- [ ] `PiiShield.mask()` masks both persons and places before any entry reaches the LLM — *requires reframe smoke-test to verify*
- [ ] Strip dismiss (tap outside text field) sets `mentionState = Idle`; strip disappears

---

### 8.6 — End-to-End UX Pass

**Goal:** Walk the app as a first-time user with no seed data. Identify and fix critical
friction points before any wider testing.

- [ ] Fresh install: confirm lock screen → biometric → editor flow works
- [ ] Write a journal entry with `@`, `#`, `!` tags — verify all three strips fire above the keyboard
- [ ] **IME-Padding and Keyboard-Anchor Validation** — confirm `SuggestionStrip` sits flush above the soft keyboard on API 29+ in both gesture-nav and 3-button-nav modes; test with `WindowCompat.setDecorFitsSystemWindows(window, false)` enabled; confirm strip does not overlap the bottom nav bar when keyboard is dismissed
- [ ] Trigger a reframe via the new button — verify `Loading → Streaming → Done` states
- [ ] Accept the reframe — verify it persists to the entry
- [ ] Navigate to History — confirm the entry appears with unmasked content + tags; mood dot positioned correctly in circumplex on open
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
