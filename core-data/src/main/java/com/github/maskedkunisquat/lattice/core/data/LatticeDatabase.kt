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
import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.ActivityHierarchy
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.LatticeTypeConverters
import com.github.maskedkunisquat.lattice.core.data.model.Mention
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.PhoneNumber
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent

@Database(
    entities = [
        Person::class,
        PhoneNumber::class,
        JournalEntry::class,
        Mention::class,
        TransitEvent::class,
        ActivityHierarchy::class,
    ],
    version = 5,
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
    }
}
