package com.denebapps.patrimonio.testing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent

/**
 * Runs everything that is due, including what the collectors of `backgroundScope` are waiting to do:
 * `advanceUntilIdle` alone leaves those for last and can stop before they see an event.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.settle() {
    advanceUntilIdle()
    runCurrent()
}
