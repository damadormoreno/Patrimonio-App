package com.denebapps.patrimonio.domain.model

enum class AccountKind { ASSET, LIABILITY }

/** The palette custom types pick from; the UI maps each to one of the theme's category colours. */
enum class TypeColor { BLUE, GREEN, SAGE, TERRACOTTA, PURPLE, GOLD, CARAMEL, GREY }

/**
 * An account type the user created, next to the built-in [Asset.AssetGroup] / [Liability.LiabilityGroup]
 * ones. It is shown with its [emoji] on its [color]; [position] orders the user's types after the
 * built-in ones.
 */
data class CustomAccountType(
    val id: String,
    val kind: AccountKind,
    val name: String,
    val emoji: String,
    val color: TypeColor,
    val position: Int,
)
