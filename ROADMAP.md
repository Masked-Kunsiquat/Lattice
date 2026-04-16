# Lattice — Feature Roadmap

Milestones are ordered by priority. Items within a milestone can usually be parallelized
once the milestone is started.

---

## M4 · Stability & First Debug Build

**Goal:** close the known UX breaks before any real testing session.

- [x] **Fix reframe-before-save silent failure** — `JournalEditorViewModel.applyReframe()` early-returns if `savedEntryId == null`; a user who reframes before saving loses the result silently. Auto-save when Reframe is triggered so `savedEntryId` is always set before Apply is reachable.
- [x] **First-run hint text** — replace the bare `"What's on your mind?"` placeholder with a short hint explaining `@person`, `#tag`, `!place` triggers. Show only when the field is empty and unfocused.
- [x] **Model-loading contextual copy** — add a second line below the progress bar explaining *why* it's slow ("running locally — no network needed") so users don't think the app is frozen.
- [x] **Delete dead code** — `EntryDetailViewModel.applyReframe()` (line 144) is unreachable; the nav host calls `viewModel::acceptReframe`. Remove it.

---

## M5 · Universal Search

**Goal:** surface all entity types through a single `SearchBar` at the top of History,
using the `expanded` overlay for multi-category results.

### SearchBar shape

`SearchBar` has two states:

- **Collapsed** — compact pill input at the top of History; list loads normally beneath it.
- **Expanded** — full-screen overlay; the `content` lambda owns the whole area.

Inside expanded content, a `TabRow` with four tabs:

```
[  Entries  |  People  |  Places  |  Tags  ]
```

| Tab | Backend | Behavior |
|---|---|---|
| **Entries** | `SearchRepository.findSimilarEntries()` | Debounce 300 ms; mood label + snippet. Tap → `EntryDetailScreen`. |
| **People** | `PersonDao.searchByName()` — substring LIKE | Relationship chip + vibeScore dot. Tap → `PersonDetailScreen` (M6). |
| **Places** | `PlaceDao` name search | Entry count. |
| **Tags** | `TagDao` name search | Entry count. |

Semantic entry search (embedding + O(n) cosine scan) runs on its own cancellable
coroutine. Name / place / tag are cheap LIKE queries and share one debounced launch.

### Tasks

- [ ] Add `SearchHistoryViewModel` with `SearchUiState(query, expanded, activeTab, entryResults, peopleResults, placeResults, tagResults, isLoading)`
- [ ] Wire `SearchRepository`, `PeopleRepository`, `PlaceRepository`, `TagRepository` into the VM
- [ ] Implement debounced query dispatch — semantic search cancels on each keystroke; LIKE queries debounce 150 ms
- [ ] Build `SearchBar` composable in `JournalHistoryScreen` — collapsed pill above the `LazyColumn`, expanded overlay with `TabRow`
- [ ] Entry results row — mood label chip + 2-line content snippet, tap navigates to `EntryDetailScreen`
- [ ] People results row — display name + relationship chip + vibeScore dot, tap navigates to `PersonDetailScreen` (stub until M6)
- [ ] Place results row — name + entry count
- [ ] Tag results row — name + entry count
- [ ] Back-press / focus loss collapses the `SearchBar` and clears results

---

## M6 · People

**Goal:** make people first-class in the UI. The entire backend already exists
(`Person`, `PhoneNumber`, `Mention`, `PersonDao`, `MentionDao`, `PeopleRepository`) —
nothing needs to change in `:core-data` or `:core-logic`.

### Bottom nav

Add a fourth tab between History and Settings:

```
[ Journal ]  [ History ]  [ People ]  [ Settings ]
```

- [ ] Add `BottomNavDest.People` (`route = "people"`, icon `Icons.Filled.Group`) in `AppNavHost`
- [ ] Register `composable("people")` and `composable("people/{personId}")` routes

### PeopleListScreen

- [ ] `PeopleListScreen` + `PeopleListViewModel` — collect `PeopleRepository.getPeople()` flow
- [ ] `PersonCard` — display name (nickname ?? firstName lastName), `RelationshipType` chip, vibeScore indicator (green > 0.3 / gray -0.3–0.3 / amber < -0.3), favorite star
- [ ] Sort order: favorites first, then by `|vibeScore|` descending
- [ ] Empty state — "No people yet. Mention someone with @name while journaling."
- [ ] FAB (`Icons.Filled.PersonAdd`) opens add-person bottom sheet (firstName, lastName, nickname, relationshipType). No phone numbers at creation — those live in the detail screen.

### PersonDetailScreen — route `people/{personId}`

- [ ] `PersonDetailViewModel` — combine three flows: `peopleRepository.getPeople()` (filter to this person), `mentionDao.getMentionsForPerson(personId)`, `journalDao.getEntries()` (filter client-side to entries whose masked content contains `[PERSON_<personId>]`)
- [ ] Vibe score card — large arc indicator spanning -1 to +1, label ("Based on N entries")
- [ ] Relationship type chip + favorite toggle
- [ ] Phone numbers section — list of `rawNumber` rows, [+ Add number] action, swipe-to-delete
- [ ] Journal entries section — reuse `EntryCard` from `JournalHistoryScreen`; tap → `EntryDetailScreen`
- [ ] Edit action in TopAppBar — bottom sheet with all `Person` fields + phone number CRUD; persists via `PeopleRepository.savePerson()`
- [ ] Delete action with confirmation dialog — deletes person + cascades mentions via FK

---

## M7 · History Enhancements

Depends on M5 (SearchBar already in place).

- [ ] **Date grouping** — group `LazyColumn` items by calendar day with `stickyHeader {}` date labels
- [ ] **Mood filter chips** — horizontal chip row below SearchBar: `All · Positive · Negative · Neutral`; filter `entries` flow client-side by valence range
- [ ] **Person filter chip** — opens a person picker bottom sheet; filters to entries containing `[PERSON_<id>]` in stored masked content
- [ ] **Empty-state copy** — replace plain "No entries yet" text with more descriptive instructional copy

---

## M8 · Export

`ExportManager` in `:core-logic` is complete. This milestone is purely UI wiring.

- [ ] **Export row in Settings** — "Export data" `ListItem` that calls `ExportManager` and fires `Intent.ACTION_SEND` with the resulting file
- [ ] **Export progress state** — `LinearProgressIndicator` + "Preparing export…" copy while the async write runs; dismiss on completion

---

## Deferred / Not Yet Scoped

- **Option A embedding fine-tuning** — Arctic projection fine-tuning deferred until ~200 labeled entries exist.
- **Tags & Places detail screens** — "entries tagged #foo" / "entries at !bar" filtered views. Natural follow-on after M7 filter chips.
- **Daily journaling prompt** — notification + streak. Requires notification permission flow.
- **Biometric setup prompt** — one-time `AlertDialog` for users without a device lock screen, pointing to system settings.
- **Cloud API key pre-flight validation** — currently any key string is accepted; no auth check before Reframe dispatches to cloud.
