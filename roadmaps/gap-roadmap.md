# Lattice: Chassis Gap Roadmap

> Audit date: 2026-04-08  
> Scope: Infrastructure, CRUD integrity, Security Standards  
> Status: Phases 2–5 complete. Phase 6 closes the gap between the engine and the chassis.

---

## Phase 6: The Chassis (Navigation, Settings, Encryption)

### Task 6.1: NavHost + Bottom Navigation

**Goal:** Wire all existing and planned screens into a single-activity NavHost. Replace the direct `JournalEditorScreen` call in `MainActivity.kt`.

**Files to create/modify:**
- `app/.../AppNavHost.kt` — new
- `app/.../MainActivity.kt` — replace `setContent` body

**Routes:**
```
editor                   → JournalEditorScreen (start destination)
history                  → JournalHistoryScreen
history/{entryId}        → JournalEditorScreen (edit mode)
settings                 → SettingsScreen
settings/audit           → AuditTrailScreen
settings/activities      → ActivityHierarchyScreen
```

**Bottom nav destinations:** Editor · History · Settings (Material 3 `NavigationBar`)

**Acceptance criteria:**
- [ ] `NavHost` renders with correct start destination
- [ ] Back press from any secondary screen returns to correct parent
- [ ] `androidx.navigation.compose` (already in `app/build.gradle.kts:58`) is the only nav dependency required — no new dep needed

---

### Task 6.2: deleteEntry Cleanup (CRUD Integrity)

**Goal:** `JournalRepository.deleteEntry()` currently calls `dao.deleteEntry()` with no side effects. Deleting an entry must: (1) reverse vibe score increments on mentioned persons, (2) delete associated `TransitEvent` rows (no FK exists), (3) cascade `Mention` cleanup is already handled by FK.

**Files to modify:**
- `core-data/.../dao/TransitEventDao.kt` — add `deleteEventsForEntry`
- `core-data/.../LatticeDatabase.kt` — migration v5 → v6
- `core-logic/.../JournalRepository.kt` — rewrite `deleteEntry`

**Migration v5 → v6:**
```sql
ALTER TABLE transit_events ADD COLUMN entryId TEXT;
CREATE INDEX IF NOT EXISTS index_transit_events_entryId ON transit_events(entryId);
```

**Updated `deleteEntry` (JournalRepository):**
```kotlin
suspend fun deleteEntry(entry: JournalEntry) = withContext(Dispatchers.IO) {
    // 1. Reverse vibe score increments for all mentioned persons
    val mentions = mentionDao.getMentionsByEntry(entry.id)
    mentions.forEach { personDao.decrementVibeScore(it.personId, it.sentimentContribution) }
    // 2. Delete entry — CASCADE removes Mentions via FK
    journalDao.deleteEntry(entry)
    // 3. Prune orphaned TransitEvents
    transitEventDao.deleteEventsForEntry(entry.id)
}
```

**Acceptance criteria:**
- [x] Deleting an entry with 2 mentions decrements both persons' vibe scores
- [x] `transit_events` rows for that `entryId` are removed
- [x] `Mention` rows are removed via existing CASCADE FK (no extra code)
- [ ] Unit test: delete entry → verify vibe rollback + transit pruning

---

### Task 6.3: ActivityHierarchyDao — Full CRUD

**Goal:** `ActivityHierarchyDao` is insert+read only. The Settings screen (Task 6.5) needs update/delete to be user-manageable.

**Files to modify:**
- `core-data/.../dao/ActivityHierarchyDao.kt`

**Add:**
```kotlin
@Update suspend fun updateActivity(activity: ActivityHierarchy)
@Delete suspend fun deleteActivity(activity: ActivityHierarchy)
@Query("SELECT * FROM activity_hierarchy ORDER BY difficulty ASC")
fun getAllActivities(): Flow<List<ActivityHierarchy>>
```

**Acceptance criteria:**
- [x] All four CRUD operations available on the DAO
- [x] `getAllActivities()` returns a reactive `Flow` for Settings screen binding

---

### Task 6.4: SettingsRepository + DataStore

**Goal:** Wire `androidx.datastore.preferences` (already in `app/build.gradle.kts:78`) into a `SettingsRepository`. This is the single source of truth for cloud toggle, provider selection, and transit retention policy.

**Files to create:**
- `core-logic/.../SettingsRepository.kt`

**Schema:**
```kotlin
data class LatticeSettings(
    val cloudEnabled: Boolean = false,
    val cloudProvider: String = "none",          // matches CloudProvider.id
    val transitRetentionDays: Int = 90,
    val exportIncludeTransitLog: Boolean = true,
)
```

**Key operations:**
```kotlin
val settings: Flow<LatticeSettings>
suspend fun setCloudEnabled(enabled: Boolean)
suspend fun setCloudProvider(providerId: String)
suspend fun setTransitRetentionDays(days: Int)
```

**Wire into `LatticeApplication.kt`:** provide `SettingsRepository` as a lazy singleton, inject into `LlmOrchestrator` so `cloudEnabled` is driven by DataStore rather than the hardcoded `false` at line 56.

**Acceptance criteria:**
- [x] `cloudEnabled` persists across process restarts
- [x] `LlmOrchestrator` reads `cloudEnabled` from `SettingsRepository`, not a constructor param
- [ ] Unit test: toggle cloud → orchestrator routes accordingly

---

### Task 6.5: Settings Screen

**Goal:** Create `SettingsScreen` + `SettingsViewModel`. Exposes all user-controllable sovereignty and data-portability controls.

**Files to create:**
- `app/.../ui/SettingsScreen.kt`
- `app/.../ui/SettingsViewModel.kt`

**Sections:**

| Section | Controls |
|---------|----------|
| **Sovereignty** | Cloud toggle (amber `AlertDialog` warning on enable), provider picker |
| **Audit Trail** | "View Audit Log" → navigates to `settings/audit` |
| **Behavioral Activation** | List of `ActivityHierarchy` rows; add / edit / delete |
| **Data Portability** | "Export Journal" button → triggers `ExportManager` + file write |
| **About** | App version, schema version, embedding model name |

**Acceptance criteria:**
- [ ] Cloud toggle shows amber warning before enabling; disabling requires confirmation
- [ ] Activity list reflects DB state reactively via `getAllActivities()` Flow
- [ ] Export button produces a valid `lattice_export_<timestamp>.json` file

---

### Task 6.6: Audit Trail Screen

**Goal:** Surface `TransitEvent` rows to the user as a read-only chronological log.

**Files to create:**
- `app/.../ui/AuditTrailScreen.kt`

**UI:**
- `LazyColumn` of `TransitEvent` rows: timestamp (human-readable), provider name, operation type
- Empty state: "No data has left this device" with lock icon
- Reached via `settings/audit` route

**Acceptance criteria:**
- [ ] Displays all `TransitEvent` rows from `TransitEventDao.getEventsFlow()`
- [ ] Local-only sessions show empty state message

---

### Task 6.7: Journal History Screen

**Goal:** List all saved journal entries; allow navigation to edit or delete.

**Files to create:**
- `app/.../ui/JournalHistoryScreen.kt`
- `app/.../ui/JournalHistoryViewModel.kt`

**UI:**
- `LazyColumn` of entry cards: timestamp, mood label, first ~80 chars of unmasked content
- Swipe-to-delete with undo `Snackbar` (calls `JournalRepository.deleteEntry`)
- Tap → navigates to `history/{entryId}` (editor in edit mode)

**Acceptance criteria:**
- [ ] List updates reactively via `JournalRepository.getEntries()` Flow
- [ ] Swipe-delete triggers full `deleteEntry` cleanup (Task 6.2)
- [ ] Undo within Snackbar window re-inserts the entry

---

### Task 6.8: Export File I/O + Share Intent

**Goal:** `ExportManager.generateManifest()` is complete. Wire it to disk and the Android share sheet.

**Files to modify:**
- `core-logic/.../ExportManager.kt` — add `exportToFile(context)`
- `app/src/main/AndroidManifest.xml` — add `FileProvider` entry
- `app/src/main/res/xml/file_paths.xml` — new

**`exportToFile`:**
```kotlin
suspend fun exportToFile(context: Context): Uri {
    val manifest = generateManifest()
    val json = Json { prettyPrint = true }.encodeToString(manifest)
    val file = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
        "lattice_export_${System.currentTimeMillis()}.json"
    )
    file.writeText(json)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
```

**Share intent** (called from Settings screen):
```kotlin
val uri = exportManager.exportToFile(context)
context.startActivity(Intent.createChooser(
    Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Export Journal"
))
```

**Acceptance criteria:**
- [ ] Exported JSON validates against `SPEC.md` schema
- [ ] File is written to `Documents/` (accessible without MANAGE_EXTERNAL_STORAGE)
- [ ] Share sheet launches and allows saving/sending the file

---

### Task 6.9: SQLCipher + Android Keystore Encryption

**Goal:** Encrypt the Room database at rest using SQLCipher. The encryption key is generated once, stored in `EncryptedSharedPreferences` (backed by a hardware Android Keystore AES-256-GCM key).

**Dependencies to add** (`core-data/build.gradle.kts`):
```kotlin
implementation("net.zetetic:android-database-sqlcipher:4.5.4")
implementation("androidx.sqlite:sqlite-ktx:2.4.0")
implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

**Files to create/modify:**
- `core-data/.../KeyProvider.kt` — new
- `core-data/.../LatticeDatabase.kt` — add `SupportFactory`

**`KeyProvider.kt`:**
```kotlin
object KeyProvider {
    private const val PREF_FILE = "lattice_db_prefs"
    private const val KEY_ALIAS = "lattice_db_key"

    fun getOrCreateKey(context: Context): ByteArray {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context, PREF_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        return prefs.getString(KEY_ALIAS, null)
            ?.let { Base64.decode(it, Base64.DEFAULT) }
            ?: ByteArray(32).also { key ->
                SecureRandom().nextBytes(key)
                prefs.edit()
                    .putString(KEY_ALIAS, Base64.encodeToString(key, Base64.DEFAULT))
                    .apply()
            }
    }
}
```

**Wire into `LatticeDatabase.kt`:**
```kotlin
Room.databaseBuilder(context, LatticeDatabase::class.java, "lattice.db")
    .openHelperFactory(SupportFactory(KeyProvider.getOrCreateKey(context)))
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
    .build()
```

**Threat model addressed:**
- Stolen/rooted device: DB unreadable without the Keystore-protected key
- Key is hardware-backed on devices with StrongBox/TEE
- Key material never appears in plaintext outside the Keystore

**Acceptance criteria:**
- [ ] App cold-starts and reads/writes entries normally with SQLCipher enabled
- [ ] DB file opened with SQLite CLI (unencrypted) returns an error
- [x] Key survives app restart (generated once, stored in EncryptedSharedPreferences)
- [ ] Existing test suite passes (unit tests mock the DAO layer, unaffected by cipher)

> **Note on migration:** Existing installs (unencrypted DB) need a one-time migration on upgrade.
> Approach: on first launch after update, open DB unencrypted, `ATTACH DATABASE ... KEY '...'`,
> `SELECT sqlcipher_export(...)`, detach, replace original file. Wire this into
> `LatticeApplication.onCreate()` behind a `firstEncryptionMigrationDone` DataStore flag.

---

## Phase 7: API Key Sovereign Storage (Cloud Provider Hardening)

> Prerequisite: Task 6.9 (Keystore in place)

### Task 7.1: EncryptedSharedPreferences for API Keys

**Goal:** `CloudProvider.kt:23` has a TODO for `EncryptedSharedPreferences`. Fulfill it. API keys must never appear in plaintext in SharedPreferences, logs, or exported data.

**Files to modify:**
- `core-logic/.../CloudProvider.kt`
- `app/.../ui/SettingsScreen.kt` — add API key entry field (masked `TextField`)

**Pattern:**
```kotlin
class CloudCredentialStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "cloud_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun setApiKey(provider: String, key: String) = prefs.edit().putString(provider, key).apply()
    fun getApiKey(provider: String): String? = prefs.getString(provider, null)
    fun clearApiKey(provider: String) = prefs.edit().remove(provider).apply()
}
```

**Acceptance criteria:**
- [ ] API key never written to logcat or unencrypted preferences
- [ ] Key survives app restart
- [ ] Clearing the key in Settings prevents cloud routing until re-entered

---

## Dependency Map

```
6.1 (Nav)  ──────────────────────────► 6.5 (Settings) ──► 6.6 (Audit)
                                              │
6.2 (deleteEntry) ◄── 6.7 (History)          ▼
                                        6.8 (Export)
6.3 (Activity CRUD) ──────────────────► 6.5 (Settings)

6.4 (SettingsRepo) ──────────────────► 6.5 (Settings)
                   └─────────────────► wires LlmOrchestrator

6.9 (SQLCipher) ─────────────────────► 7.1 (API Keys)
```

**Recommended order:** 6.2 → 6.3 → 6.4 → 6.9 → 6.1 → 6.5 → 6.6 → 6.7 → 6.8 → 7.1

Data-layer tasks (6.2–6.4, 6.9) are independent of UI tasks (6.1, 6.5–6.8) and can be parallelized.
