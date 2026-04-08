package com.github.maskedkunisquat.lattice.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao
import com.github.maskedkunisquat.lattice.core.data.dao.MentionDao
import com.github.maskedkunisquat.lattice.core.data.dao.PersonDao
import com.github.maskedkunisquat.lattice.core.data.dao.PhoneNumberDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.LatticeTypeConverters
import com.github.maskedkunisquat.lattice.core.data.model.Mention
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.PhoneNumber

@Database(
    entities = [
        Person::class,
        PhoneNumber::class,
        JournalEntry::class,
        Mention::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(LatticeTypeConverters::class)
abstract class LatticeDatabase : RoomDatabase() {
    abstract fun personDao(): PersonDao
    abstract fun journalDao(): JournalDao
    abstract fun phoneNumberDao(): PhoneNumberDao
    abstract fun mentionDao(): MentionDao
}
