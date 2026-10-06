package com.denebapps.patrimonio.ui.theme

/**
 * Returns `true` when the running binary is a debug build, for gating debug-only affordances out
 * of release builds — `commonMain` has no `BuildConfig`, so this is the KMP-correct
 * `expect`/`actual` boundary.
 *
 * Android: `BuildConfig.DEBUG` (generated class, requires `buildFeatures.buildConfig = true`).
 * iOS: `kotlin.native.Platform.isDebugBinary`.
 */
expect fun isDebug(): Boolean
