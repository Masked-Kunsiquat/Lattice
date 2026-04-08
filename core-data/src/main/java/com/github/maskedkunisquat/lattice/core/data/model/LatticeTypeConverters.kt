package com.github.maskedkunisquat.lattice.core.data.model

import androidx.room.TypeConverter
import java.util.UUID

class LatticeTypeConverters {
    @TypeConverter
    fun fromUUID(uuid: UUID?): String? = uuid?.toString()

    @TypeConverter
    fun toUUID(uuidString: String?): UUID? = uuidString?.let { UUID.fromString(it) }

    @TypeConverter
    fun fromRelationshipType(value: RelationshipType?): String? = value?.name

    @TypeConverter
    fun toRelationshipType(value: String?): RelationshipType? = value?.let { enumValueOf<RelationshipType>(it) }

    @TypeConverter
    fun fromMentionSource(value: MentionSource?): String? = value?.name

    @TypeConverter
    fun toMentionSource(value: String?): MentionSource? = value?.let { enumValueOf<MentionSource>(it) }

    @TypeConverter
    fun fromMentionStatus(value: MentionStatus?): String? = value?.name

    @TypeConverter
    fun toMentionStatus(value: String?): MentionStatus? = value?.let { enumValueOf<MentionStatus>(it) }

    @TypeConverter
    fun fromFloatArray(array: FloatArray?): String? = array?.joinToString(",")

    @TypeConverter
    fun toFloatArray(value: String?): FloatArray? = value?.let {
        if (it.isEmpty()) floatArrayOf()
        else it.split(",").map { s -> s.toFloat() }.toFloatArray()
    }

    @TypeConverter
    fun fromStringList(value: List<String>?): String? = value?.let {
        org.json.JSONArray(it).toString()
    }

    @TypeConverter
    fun toStringList(value: String?): List<String>? = value?.let {
        val array = org.json.JSONArray(it)
        List(array.length()) { i -> array.getString(i) }
    }
}
