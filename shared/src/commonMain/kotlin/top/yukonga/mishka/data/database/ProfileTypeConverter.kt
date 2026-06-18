package top.yukonga.mishka.data.database

import androidx.room3.ColumnTypeConverter
import top.yukonga.mishka.data.model.ProfileType

class ProfileTypeConverter {
    @ColumnTypeConverter
    fun fromType(value: ProfileType): String = value.name

    @ColumnTypeConverter
    fun toType(value: String): ProfileType = ProfileType.fromStringOrDefault(value)
}
