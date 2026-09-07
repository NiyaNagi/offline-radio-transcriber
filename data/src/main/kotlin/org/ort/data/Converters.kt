package org.ort.data

import androidx.room.TypeConverter

/**
 * The handful of composite column types the data model needs (functional spec §8) that Room
 * cannot map natively: string lists (`participantOrder[]`, `enrolmentSessionIds[]`, ...) and
 * the prior-contribution map on [org.ort.data.entity.CallsignCandidateEntity]. Domain values
 * here (ULIDs, callsigns, prior names) never contain the pipe or semicolon delimiters below, so
 * plain delimiters are sufficient — no JSON dependency needed for what is otherwise an opaque
 * blob column.
 */
public object Converters {

    private const val ITEM_SEP = "|"
    private const val ENTRY_SEP = ";;"
    private const val KV_SEP = "="

    @TypeConverter
    @JvmStatic
    public fun fromStringList(list: List<String>?): String? = list?.joinToString(ITEM_SEP)

    @TypeConverter
    @JvmStatic
    public fun toStringList(value: String?): List<String>? = when {
        value == null -> null
        value.isEmpty() -> emptyList()
        else -> value.split(ITEM_SEP)
    }

    @TypeConverter
    @JvmStatic
    public fun fromLongList(list: List<Long>?): String? = list?.joinToString(ITEM_SEP)

    @TypeConverter
    @JvmStatic
    public fun toLongList(value: String?): List<Long>? = when {
        value == null -> null
        value.isEmpty() -> emptyList()
        else -> value.split(ITEM_SEP).map { it.toLong() }
    }

    @TypeConverter
    @JvmStatic
    public fun fromDoubleMap(map: Map<String, Double>?): String? =
        map?.entries?.joinToString(ENTRY_SEP) { "${it.key}$KV_SEP${it.value}" }

    @TypeConverter
    @JvmStatic
    public fun toDoubleMap(value: String?): Map<String, Double>? = when {
        value == null -> null
        value.isEmpty() -> emptyMap()
        else -> value.split(ENTRY_SEP).associate {
            val (k, v) = it.split(KV_SEP)
            k to v.toDouble()
        }
    }
}
