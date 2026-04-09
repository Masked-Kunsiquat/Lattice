package com.github.maskedkunisquat.lattice.core.data.model

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    /**
     * Serialises [FloatArray] to [ByteArray] using IEEE 754 float32 little-endian byte order.
     * Each float occupies exactly 4 bytes; a 384-dim embedding produces a 1536-byte BLOB.
     */
    @TypeConverter
    fun fromFloatArray(array: FloatArray?): ByteArray? {
        array ?: return null
        val buf = ByteBuffer.allocate(array.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(array)
        return buf.array()
    }

    /**
     * Deserialises [ByteArray] (IEEE 754 float32 little-endian) back to [FloatArray].
     */
    @TypeConverter
    fun toFloatArray(bytes: ByteArray?): FloatArray? {
        bytes ?: return null
        val floatBuf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floatBuf.remaining()) { floatBuf.get() }
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
