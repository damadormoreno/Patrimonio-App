package com.denebapps.patrimonio.data.platform

/**
 * Opaque wrapper around the platform-specific handle needed for file-system paths (Room DB,
 * DataStore). Android: wraps `android.content.Context`. iOS: wraps nothing (`value == null`) —
 * paths derive from `NSHomeDirectory()` directly.
 *
 * A plain common class (not `expect`/`actual`) — Kotlin rejects `actual typealias` targets whose
 * modality differs from the (implicitly final) `expect class` declaration, and `android.content.Context`
 * is `abstract` (confirmed at apply time), so a typealias cannot be used here.
 */
class PlatformContext(val value: Any?)
