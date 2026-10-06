package com.denebapps.patrimonio.domain.time

import com.denebapps.patrimonio.domain.model.YearMonth
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.milliseconds

fun millisecondsUntilNextMonthBoundary(now: Instant, zone: TimeZone): Long {
    val local = now.toLocalDateTime(zone)
    val next = if (local.monthNumber == 12) {
        LocalDate(local.year + 1, 1, 1)
    } else {
        LocalDate(local.year, local.monthNumber + 1, 1)
    }.atStartOfDayIn(zone)
    return (next - now).inWholeMilliseconds.coerceAtLeast(1L)
}

fun currentMonthFlow(
    clock: Clock,
    zoneProvider: () -> TimeZone,
    sleeper: suspend (Long) -> Unit = { delay(it.milliseconds) },
): Flow<YearMonth> = flow {
    while (currentCoroutineContext().isActive) {
        val now = clock.now()
        val zone = zoneProvider()
        val local = now.toLocalDateTime(zone)
        emit(YearMonth(local.year, local.monthNumber))
        sleeper(millisecondsUntilNextMonthBoundary(now, zone))
    }
}.distinctUntilChanged()
