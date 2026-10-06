package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.NetWorthSnapshot
import com.denebapps.patrimonio.domain.model.YearMonth
import kotlinx.coroutines.flow.Flow

interface NetWorthRepository {
    fun observeSnapshots(): Flow<List<NetWorthSnapshot>>

    /** The most recent snapshot strictly before [yearMonth] (not necessarily the calendar month
     *  immediately prior). Null when no earlier snapshot exists at all. */
    suspend fun findMostRecentBefore(yearMonth: YearMonth): NetWorthSnapshot?
}
