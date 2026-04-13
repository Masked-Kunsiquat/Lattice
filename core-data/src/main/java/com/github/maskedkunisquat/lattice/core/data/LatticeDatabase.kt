package com.github.maskedkunisquat.lattice.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.github.maskedkunisquat.lattice.core.data.dao.ActivityHierarchyDao
import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao
import com.github.maskedkunisquat.lattice.core.data.dao.MentionDao
import com.github.maskedkunisquat.lattice.core.data.dao.PersonDao
import com.github.maskedkunisquat.lattice.core.data.dao.PhoneNumberDao
import com.github.maskedkunisquat.lattice.core.data.dao.PlaceDao
import com.github.maskedkunisquat.lattice.core.data.dao.TagDao
import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.ActivityHierarchy
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.LatticeTypeConverters
import com.github.maskedkunisquat.lattice.core.data.model.Mention
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.PhoneNumber
import com.github.maskedkunisquat.lattice.core.data.model.Place
import com.github.maskedkunisquat.lattice.core.data.model.Tag
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent

@Database(
    entities = [
        Person::class,
        PhoneNumber::class,
        JournalEntry::class,
        Mention::class,
        TransitEvent::class,
        ActivityHierarchy::class,
        Tag::class,
        Place::class,
    ],
    version = 11,
    exportSchema = false
)
@TypeConverters(LatticeTypeConverters::class)
abstract class LatticeDatabase : RoomDatabase() {
    abstract fun personDao(): PersonDao
    abstract fun journalDao(): JournalDao
    abstract fun phoneNumberDao(): PhoneNumberDao
    abstract fun mentionDao(): MentionDao
    abstract fun transitEventDao(): TransitEventDao
    abstract fun activityHierarchyDao(): ActivityHierarchyDao
    abstract fun tagDao(): TagDao
    abstract fun placeDao(): PlaceDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN cognitiveDistortions TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_entries_timestamp ON journal_entries (timestamp)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS transit_events (
                        id TEXT NOT NULL PRIMARY KEY,
                        timestamp INTEGER NOT NULL,
                        providerName TEXT NOT NULL,
                        operationType TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transit_events_timestamp ON transit_events (timestamp)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS activity_hierarchy (
                        id TEXT NOT NULL PRIMARY KEY,
                        taskName TEXT NOT NULL,
                        difficulty INTEGER NOT NULL CHECK(difficulty BETWEEN 0 AND 10),
                        valueCategory TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_activity_hierarchy_difficulty ON activity_hierarchy(difficulty)"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE journal_entries ADD COLUMN reframedContent TEXT"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transit_events ADD COLUMN entryId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transit_events_entryId ON transit_events(entryId)")
            }
        }

        /**
         * Migrates the `embedding` column in `journal_entries` from TEXT (CSV floats) to
         * BLOB (IEEE 754 float32 little-endian), matching the updated [LatticeTypeConverters].
         * SQLite cannot change a column type in-place, so we recreate the table.
         * Existing embeddings are replaced with zeroblob(1536) — 384 × 4 bytes of zeros.
         * Zero-vector entries are already excluded from semantic search results by
         * [SearchRepository], so this is a safe lossy migration during development.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS journal_entries_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        timestamp INTEGER NOT NULL,
                        content TEXT NOT NULL,
                        valence REAL NOT NULL,
                        arousal REAL NOT NULL,
                        moodLabel TEXT NOT NULL,
                        embedding BLOB NOT NULL,
                        cognitiveDistortions TEXT NOT NULL DEFAULT '[]',
                        reframedContent TEXT
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO journal_entries_new
                    SELECT id, timestamp, content, valence, arousal, moodLabel,
                           zeroblob(1536), cognitiveDistortions, reframedContent
                    FROM journal_entries
                """.trimIndent())
                db.execSQL("DROP TABLE journal_entries")
                db.execSQL("ALTER TABLE journal_entries_new RENAME TO journal_entries")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_entries_timestamp ON journal_entries (timestamp)")
            }
        }

        /**
         * Makes `content` nullable in `journal_entries` to support mood-log entries
         * (valid valence/arousal coordinates with no text). SQLite cannot alter column
         * nullability in-place, so the table is recreated. Existing rows are preserved;
         * all previously stored content values remain unchanged (non-null strings stay
         * non-null in the new schema — NULL is now permitted but not forced).
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS journal_entries_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        timestamp INTEGER NOT NULL,
                        content TEXT,
                        valence REAL NOT NULL,
                        arousal REAL NOT NULL,
                        moodLabel TEXT NOT NULL,
                        embedding BLOB NOT NULL,
                        cognitiveDistortions TEXT NOT NULL DEFAULT '[]',
                        reframedContent TEXT
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO journal_entries_new
                    SELECT id, timestamp, content, valence, arousal, moodLabel,
                           embedding, cognitiveDistortions, reframedContent
                    FROM journal_entries
                """.trimIndent())
                db.execSQL("DROP TABLE journal_entries")
                db.execSQL("ALTER TABLE journal_entries_new RENAME TO journal_entries")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_entries_timestamp ON journal_entries (timestamp)")
            }
        }

        /**
         * Adds `tags` and `places` tables. Adds `tagIds` and `placeIds` JSON-array columns
         * to `journal_entries` (default empty array — existing rows carry no tags or places).
         */
        val MIGRATION_8_9  = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tags (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tags_name ON tags (name)"
                )
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS places (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_places_name ON places (name)"
                )
                db.execSQL(
                    "ALTER TABLE journal_entries ADD COLUMN tagIds TEXT NOT NULL DEFAULT '[]'"
                )
                db.execSQL(
                    "ALTER TABLE journal_entries ADD COLUMN placeIds TEXT NOT NULL DEFAULT '[]'"
                )
            }
        }

        /**
         * Adds UNIQUE constraints to `tags.name` and `places.name` to prevent duplicate
         * entries from concurrent inserts. Existing duplicates (if any) are deduplicated
         * by keeping the first inserted row (lowest rowid) via INSERT OR IGNORE.
         */
        /**
         * Adds user feedback columns to `journal_entries` for on-device MLP training signal:
         * - `user_valence` / `user_arousal`: coordinates from the mood grid ("How does this land?")
         * - `reframe_edited_by_user`: true if the user modified the model's reframe before accepting
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN user_valence REAL")
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN user_arousal REAL")
                db.execSQL("ALTER TABLE journal_entries ADD COLUMN reframe_edited_by_user INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Tags — recreate with unique name index
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tags_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("INSERT OR IGNORE INTO tags_new SELECT MIN(id), name FROM tags GROUP BY name")
                db.execSQL("DROP TABLE tags")
                db.execSQL("ALTER TABLE tags_new RENAME TO tags")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tags_name ON tags (name)")

                // Places — recreate with unique name index
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS places_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("INSERT OR IGNORE INTO places_new SELECT MIN(id), name FROM places GROUP BY name")
                db.execSQL("DROP TABLE places")
                db.execSQL("ALTER TABLE places_new RENAME TO places")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_places_name ON places (name)")
            }
        }
    }
}
