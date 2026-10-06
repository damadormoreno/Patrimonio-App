package com.denebapps.patrimonio.data.platform

/**
 * Minimal cross-platform logging entry point for non-fatal failures (design.md FX Pipeline:
 * "Fetch wrapped in `runCatching`; failure ... logs via `Logger`"). `println` is available and
 * safe on every Kotlin Multiplatform target this project ships (JVM/Android, iOS Kotlin/Native),
 * so a plain common `object` is used instead of `expect`/`actual` — there is no platform-specific
 * behavior to diverge on.
 */
object AppLogger {
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val suffix = throwable?.let { " — ${it::class.simpleName}: ${it.message}" }.orEmpty()
        println("[$tag] ERROR: $message$suffix")
    }
}
