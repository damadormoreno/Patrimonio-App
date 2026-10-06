package com.denebapps.patrimonio.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Stroke-based icon set ported 1:1 from `design-reference/shared.jsx` (`ICONS`),
 * `grupos.jsx`, `perfil.jsx`, and the inline eye/eye-off toggle in `login.jsx`.
 *
 * Every icon is a 24x24 viewport `ImageVector` drawn as a stroke (never a fill):
 * round line cap, round line join — matching the JSX `<Icon>` component's fixed
 * SVG attributes. Recoloring happens at draw time via `Icon(tint = ...)`; the
 * placeholder stroke color here (`Color.Black`) is irrelevant to callers.
 *
 * Stroke width is contextual in the JSX source (`shared.jsx` uses 1.7 as the
 * base, but individual call sites request heavier weights — e.g. category icons
 * use 1.9, the `Fab` plus icon uses 2.2, the active `TabBar` item uses 2.0).
 * Each icon property below defaults to 1.7 but can be rebuilt at any stroke
 * width via [get]; the cache key is `(name, strokeWidth)` so both variants
 * coexist without rebuilding.
 */
object AppIcons {
    private const val VIEWPORT = 24f
    const val DEFAULT_STROKE_WIDTH = 1.7f

    private val cache = mutableMapOf<Pair<String, Float>, ImageVector>()
    private val paths = mutableMapOf<String, List<String>>()

    /** Builds a 24x24 stroke [ImageVector] from one or more raw SVG `d` path strings. */
    private fun strokeVector(strokeWidth: Float, ds: List<String>): ImageVector {
        val builder = ImageVector.Builder(
            defaultWidth = VIEWPORT.dp,
            defaultHeight = VIEWPORT.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        )
        ds.forEach { d ->
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    private fun icon(name: String, vararg ds: String): ImageVector {
        paths.getOrPut(name) { ds.toList() }
        return cache.getOrPut(name to DEFAULT_STROKE_WIDTH) { strokeVector(DEFAULT_STROKE_WIDTH, ds.toList()) }
    }

    /**
     * Returns [name]'s [ImageVector] rebuilt at [strokeWidth]. Use this when a call
     * site needs a heavier/lighter stroke than the shared 1.7 default (e.g. category icons
     * at 1.9, `Fab`'s plus icon at 2.2, the active `TabBar` item at 2.0).
     */
    fun get(name: String, strokeWidth: Float = DEFAULT_STROKE_WIDTH): ImageVector {
        if (name !in paths) touch(name)
        val ds = paths[name] ?: error("Unknown icon name: $name")
        return cache.getOrPut(name to strokeWidth) { strokeVector(strokeWidth, ds) }
    }

    /**
     * Forces path registration for [name] by touching its val getter, so [get] works
     * regardless of whether any call site accessed the val before (registration is
     * otherwise lazy and order-dependent).
     */
    private fun touch(name: String) {
        when (name) {
            "home" -> home
            "list" -> list
            "chart" -> chart
            "pie" -> pie
            "cog" -> cog
            "plus" -> plus
            "minus" -> minus
            "arrowUp" -> arrowUp
            "arrowDown" -> arrowDown
            "arrowRight" -> arrowRight
            "arrowLeft" -> arrowLeft
            "search" -> search
            "filter" -> filter
            "calendar" -> calendar
            "chevronD" -> chevronD
            "chevronR" -> chevronR
            "chevronL" -> chevronL
            "close" -> close
            "check" -> check
            "pencil" -> pencil
            "trash" -> trash
            "download" -> download
            "upload" -> upload
            "globe" -> globe
            "bell" -> bell
            "tag" -> tag
            "cart" -> cart
            "utensils" -> utensils
            "car" -> car
            "house" -> house
            "music" -> music
            "spark" -> spark
            "briefcase" -> briefcase
            "receipt" -> receipt
            "user" -> user
            "sun" -> sun
            "moon" -> moon
            "info" -> info
            "trend" -> trend
            "swap" -> swap
            "wallet" -> wallet
            "bank" -> bank
            "card" -> card
            "building" -> building
            "coins" -> coins
            "plusMin" -> plusMin
            "folder" -> folder
            "grip" -> grip
            "camera" -> camera
            "cloud" -> cloud
            "lock" -> lock
            "faceid" -> faceid
            "finger" -> finger
            "eye" -> eye
            "eyeOff" -> eyeOff
        }
    }

    // ── shared.jsx · ICONS (36) ─────────────────────────────────────────
    val home: ImageVector
        get() = icon("home", "M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z")

    val list: ImageVector
        get() = icon("list", "M4 6h16M4 12h16M4 18h10")

    val chart: ImageVector
        get() = icon("chart", "M4 19V10M10 19V5M16 19v-7M22 19V8")

    val pie: ImageVector
        get() = icon("pie", "M12 3a9 9 0 1 0 9 9h-9V3z")

    val cog: ImageVector
        get() = icon(
            "cog",
            "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zm9.4 3l-2 1.2.2 2.3-2 1-1.4-1.8-2.3.4-.7 2.2H9.8l-.7-2.2-2.3-.4L5.4 17" +
                "l-2-1 .2-2.3-2-1.2 2-1.2-.2-2.3 2-1 1.4 1.8 2.3-.4.7-2.2h2.4l.7 2.2 2.3.4 1.4-1.8 2 1-.2 2.3z",
        )

    val plus: ImageVector
        get() = icon("plus", "M12 5v14M5 12h14")

    val minus: ImageVector
        get() = icon("minus", "M5 12h14")

    val arrowUp: ImageVector
        get() = icon("arrowUp", "M7 14l5-5 5 5")

    val arrowDown: ImageVector
        get() = icon("arrowDown", "M7 10l5 5 5-5")

    val arrowRight: ImageVector
        get() = icon("arrowRight", "M5 12h14M13 5l7 7-7 7")

    val arrowLeft: ImageVector
        get() = icon("arrowLeft", "M19 12H5M11 5l-7 7 7 7")

    val search: ImageVector
        get() = icon(
            "search",
            "M11 4a7 7 0 1 0 4.6 12.3l4.4 4.4M16 11a5 5 0 1 1-10 0 5 5 0 0 1 10 0",
        )

    val filter: ImageVector
        get() = icon("filter", "M3 5h18l-7 9v6l-4-2v-4z")

    val calendar: ImageVector
        get() = icon(
            "calendar",
            "M5 4h14a1 1 0 0 1 1 1v15a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM3 9h18M8 2v4M16 2v4",
        )

    val chevronD: ImageVector
        get() = icon("chevronD", "M6 9l6 6 6-6")

    val chevronR: ImageVector
        get() = icon("chevronR", "M9 6l6 6-6 6")

    val chevronL: ImageVector
        get() = icon("chevronL", "M15 6l-6 6 6 6")

    val close: ImageVector
        get() = icon("close", "M5 5l14 14M19 5L5 19")

    val check: ImageVector
        get() = icon("check", "M5 12l4 4L19 7")

    val pencil: ImageVector
        get() = icon("pencil", "M14 4l6 6-11 11H3v-6L14 4z")

    val trash: ImageVector
        get() = icon(
            "trash",
            "M4 7h16M9 7V4h6v3M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13",
        )

    val download: ImageVector
        get() = icon("download", "M12 4v12m0 0l-5-5m5 5l5-5M4 20h16")

    val upload: ImageVector
        get() = icon("upload", "M12 20V8m0 0l-5 5m5-5l5 5M4 4h16")

    val globe: ImageVector
        get() = icon(
            "globe",
            "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zm-9 9h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18",
        )

    val bell: ImageVector
        get() = icon("bell", "M6 16V11a6 6 0 0 1 12 0v5l2 2H4l2-2zM10 20a2 2 0 0 0 4 0")

    val tag: ImageVector
        get() = icon(
            "tag",
            "M3 12l9-9h7v7l-9 9-7-7zM15 8a1 1 0 1 0 0-2 1 1 0 0 0 0 2z",
        )

    val cart: ImageVector
        get() = icon(
            "cart",
            "M3 4h2l2 12h11l2-8H6M9 20a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM17 20a1 1 0 1 0 0-2 1 1 0 0 0 0 2z",
        )

    val utensils: ImageVector
        get() = icon("utensils", "M7 3v9a3 3 0 0 1-3 3M7 3v18M14 3c-1 2-1 6 0 8h4M18 3v18")

    val car: ImageVector
        get() = icon(
            "car",
            "M4 11l2-5h12l2 5M3 17V11h18v6M5 17v2H3v-2M21 17v2h-2v-2M7 14h.01M17 14h.01",
        )

    val house: ImageVector
        get() = icon("house", "M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-4v-7h-8v7H4a1 1 0 0 1-1-1z")

    val music: ImageVector
        get() = icon(
            "music",
            "M9 18V5l11-2v13M9 18a3 3 0 1 1-3-3 3 3 0 0 1 3 3zM20 16a3 3 0 1 1-3-3 3 3 0 0 1 3 3z",
        )

    val spark: ImageVector
        get() = icon("spark", "M12 3l2.4 5.6L20 11l-5.6 2.4L12 19l-2.4-5.6L4 11l5.6-2.4L12 3z")

    val briefcase: ImageVector
        get() = icon(
            "briefcase",
            "M3 8h18v12H3zM8 8V5a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v3M3 14h18",
        )

    val receipt: ImageVector
        get() = icon("receipt", "M5 3h14v18l-3-2-2 2-2-2-2 2-2-2-3 2zM8 8h8M8 12h8M8 16h5")

    val user: ImageVector
        get() = icon("user", "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0")

    val sun: ImageVector
        get() = icon(
            "sun",
            "M12 4V2M12 22v-2M4 12H2M22 12h-2M5.6 5.6L4.2 4.2M19.8 19.8l-1.4-1.4M5.6 18.4l-1.4 1.4M19.8 4.2l-1.4 1.4" +
                "M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10z",
        )

    val moon: ImageVector
        get() = icon("moon", "M20 14a8 8 0 0 1-10-10 8 8 0 1 0 10 10z")

    val info: ImageVector
        get() = icon("info", "M12 21a9 9 0 1 1 0-18 9 9 0 0 1 0 18zM12 11v6M12 7.5h.01")

    val trend: ImageVector
        get() = icon("trend", "M3 17l6-6 4 4 8-8M14 7h7v7")

    val swap: ImageVector
        get() = icon("swap", "M7 4l-4 4 4 4M3 8h14M17 20l4-4-4-4M21 16H7")

    val wallet: ImageVector
        get() = icon(
            "wallet",
            "M3 7v11a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7h-5.5a2.5 2.5 0 0 1 0-5H21V6a1 1 0 0 0-1-1H5a2 2 0 0 0-2 2z" +
                "M17 13h.01",
        )

    val bank: ImageVector
        get() = icon("bank", "M3 10l9-6 9 6M5 10v8M9 10v8M15 10v8M19 10v8M3 19h18M3 22h18")

    val card: ImageVector
        get() = icon(
            "card",
            "M3 7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM3 11h18M7 16h3",
        )

    val building: ImageVector
        get() = icon(
            "building",
            "M5 21V5a1 1 0 0 1 1-1h7a1 1 0 0 1 1 1v16M14 9h4a1 1 0 0 1 1 1v11M3 21h18M9 8h.01M9 12h.01M9 16h.01" +
                "M17 13h.01M17 17h.01",
        )

    val coins: ImageVector
        get() = icon(
            "coins",
            "M9 5a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM15 11a4 4 0 1 1 0 8 4 4 0 0 1 0-8M9 13v2a4 4 0 0 0 4 4",
        )

    val plusMin: ImageVector
        get() = icon("plusMin", "M12 6v12M6 12h12")

    // ── grupos.jsx extras ───────────────────────────────────────────────
    val folder: ImageVector
        get() = icon(
            "folder",
            "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
        )

    val grip: ImageVector
        get() = icon("grip", "M4 9h16M4 15h16")

    // ── perfil.jsx extras ────────────────────────────────────────────────
    val camera: ImageVector
        get() = icon(
            "camera",
            "M3 9a1 1 0 0 1 1-1h2.5l1.8-2.6A1 1 0 0 1 9.1 5h5.8a1 1 0 0 1 .8.4L17.5 8H20a1 1 0 0 1 1 1v10a1 1 0 0 1-1" +
                " 1H4a1 1 0 0 1-1-1zM12 17a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z",
        )

    val cloud: ImageVector
        get() = icon("cloud", "M7 18h10a4 4 0 0 0 .8-7.9A6 6 0 0 0 6.2 9.5 4.2 4.2 0 0 0 7 18z")

    val lock: ImageVector
        get() = icon(
            "lock",
            "M7 11V8a5 5 0 0 1 10 0v3M5 11h14v9a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1zM12 15v2.5",
        )

    val faceid: ImageVector
        get() = icon(
            "faceid",
            "M4 8V6a2 2 0 0 1 2-2h2M20 8V6a2 2 0 0 0-2-2h-2M4 16v2a2 2 0 0 0 2 2h2M20 16v2a2 2 0 0 1-2 2h-2" +
                "M9 10v1.5M15 10v1.5M12 10v3.5l-1.2.8M9.5 16.5c1.4 1 3.6 1 5 0",
        )

    val finger: ImageVector
        get() = icon(
            "finger",
            "M12 11a2 2 0 0 0-2 2c0 2.5-.4 4.4-1.2 6M12 11a2 2 0 0 1 2 2c0 3-.3 5-1 7M8.5 9.5A5 5 0 0 1 17 13c0 2.4" +
                "-.2 4.4-.7 6M6.2 12c-.1.4-.2.7-.2 1 0 1.8-.3 3.4-.8 4.7M5 8a8 8 0 0 1 14 2",
        )

    // ── login.jsx · PwdToggle (eye / eye-off) ───────────────────────────
    // Open eye — password hidden, tap to reveal (SVG: outline path + circle pupil).
    val eye: ImageVector
        get() = icon(
            "eye",
            "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z",
            "M9 12a3 3 0 1 0 6 0 3 3 0 1 0 -6 0",
        )

    // Crossed-out eye — password shown, tap to hide.
    val eyeOff: ImageVector
        get() = icon(
            "eyeOff",
            "M3 3l18 18",
            "M10.6 10.6a2 2 0 0 0 2.8 2.8",
            "M9.9 4.2A10 10 0 0 1 22 12c-.6 1.1-1.3 2.1-2.1 3M6.1 6.1A10 10 0 0 0 2 12a10 10 0 0 0 15 4",
        )
}
