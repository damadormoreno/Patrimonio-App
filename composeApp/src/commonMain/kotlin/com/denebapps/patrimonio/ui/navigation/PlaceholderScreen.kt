package com.denebapps.patrimonio.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/**
 * Reusable placeholder destination: shows [title] identifying the route and
 * nothing else — no real screen content, data, or state. Every destination
 * introduced by `navigation-shell` (5 tabs, Settings, Perfil, Add, AddPatrimonio)
 * renders this until its dedicated screen change lands. Styled purely from
 * [AppTheme][com.denebapps.patrimonio.ui.theme.AppTheme] tokens.
 */
@Composable
fun PlaceholderScreen(title: String, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current

    Box(
        modifier = modifier.fillMaxSize().background(color = colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = colors.ink,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
