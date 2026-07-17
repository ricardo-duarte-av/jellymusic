package pt.aguiarvieira.jellymusic.data.db

import androidx.room.TypeConverter

/**
 * Room type converters. Jellyfin item IDs are hex GUIDs (no commas), so a comma-joined string is a
 * safe, dependency-free encoding for the list of track IDs stored on a playlist download.
 */
class Converters {
    @TypeConverter
    fun fromStringList(list: List<String>): String = list.joinToString(",")

    @TypeConverter
    fun toStringList(data: String): List<String> =
        if (data.isEmpty()) emptyList() else data.split(",")
}
