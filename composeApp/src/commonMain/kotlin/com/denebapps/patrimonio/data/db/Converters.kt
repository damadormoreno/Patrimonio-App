package com.denebapps.patrimonio.data.db

import kotlinx.datetime.LocalDate

/**
 * Plain LocalDate <-> epoch-day Long mapping helpers used by the repository/mapper layer at the
 * entity boundary (`transactions.dateEpochDay`). Not a Room `@TypeConverter` — entity fields
 * already store the raw `Long`, so no Room-level conversion registration is needed.
 */
object Converters {
    fun LocalDate.toEpochDayLong(): Long = toEpochDays().toLong()

    fun epochDayLongToLocalDate(epochDay: Long): LocalDate = LocalDate.fromEpochDays(epochDay.toInt())
}
