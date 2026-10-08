package com.denebapps.patrimonio.data.cloud

import kotlinx.coroutines.flow.Flow

/** Signals changes to the data that goes into a backup. */
fun interface LocalDataChanges {
    /** Emits once when collected, then after every committed change. */
    fun observe(): Flow<Unit>
}
