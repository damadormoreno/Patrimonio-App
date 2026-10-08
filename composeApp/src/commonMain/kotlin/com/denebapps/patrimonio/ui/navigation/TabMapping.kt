package com.denebapps.patrimonio.ui.navigation

import kotlinx.serialization.serializer

/**
 * The `Main` zone tabs, in display order. [iconName] is an `AppIcons` property name, resolved to
 * an `ImageVector` by [MainScaffold] via `AppIcons.get`.
 */
enum class TabKey(val id: String, val label: String, val iconName: String) {
    PATRIMONIO("patrimonio", "Patrimonio", "wallet"),
    SUBSCRIPTIONS("subs", "Suscripciones", "receipt"),
    ;

    companion object {
        /** Looks up a [TabKey] by its [id] (as used by `TabBar.onSelect`); `null` if unknown. */
        fun byId(id: String): TabKey? = entries.find { it.id == id }
    }
}

/**
 * Pure route↔tab mapping helper — no Compose/navigation-library dependency, unit-tested
 * directly (see `TabMappingTest`). [tabForRoute] takes `NavDestination.route` (the
 * type-safe route's [routeName]) and returns the matching [TabKey], or `null`
 * for non-tab (pushed) routes such as [Settings]/[Profile]/[AddPatrimonio].
 */
/**
 * The route name navigation gives a type-safe destination without arguments: its serial name, a string
 * fixed at compile time. Not `T::class.qualifiedName`, which R8 renames in minified builds.
 */
inline fun <reified T : Any> routeName(): String = serializer<T>().descriptor.serialName

object TabMapping {
    /** The tabs, in [TabBar][com.denebapps.patrimonio.ui.components.TabBar] display order. */
    val tabs: List<TabKey> = TabKey.entries.toList()

    /** The tab bar is only worth its space once there is more than one tab to switch between. */
    val tabBarVisible: Boolean get() = tabs.size > 1

    private val routeNameToTab: Map<String?, TabKey> = mapOf(
        routeName<Patrimonio>() to TabKey.PATRIMONIO,
        routeName<Subscriptions>() to TabKey.SUBSCRIPTIONS,
    )

    /** Maps a destination's route name to its [TabKey], or `null` if it is not a tab route. */
    fun tabForRoute(routeName: String?): TabKey? = routeName?.let { routeNameToTab[it] }

    /** `true` iff [routeName] is a tab route — the FAB is hidden otherwise. */
    fun fabVisible(routeName: String?): Boolean = tabForRoute(routeName) != null
}
