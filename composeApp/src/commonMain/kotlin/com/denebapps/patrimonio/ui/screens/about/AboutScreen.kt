package com.denebapps.patrimonio.ui.screens.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.platform.appVersionName
import com.denebapps.patrimonio.ui.components.HeaderIconBtn
import com.denebapps.patrimonio.ui.components.SettingsCard
import com.denebapps.patrimonio.ui.components.SettingsRow
import com.denebapps.patrimonio.ui.components.SettingsSection
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/**
 * «Acerca de», pushed from Ajustes → Información: the app and its version, where its data comes from, the
 * open-source licenses and, when they are configured, the privacy policy and the contact.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val content = remember { aboutContent(versionName = appVersionName()) }
    val uriHandler = LocalUriHandler.current
    AboutContentView(
        content = content,
        onBack = onBack,
        // No browser or mail app installed: opening is a no-op rather than a crash.
        onOpen = { uri -> runCatching { uriHandler.openUri(uri) } },
        modifier = modifier,
    )
}

@Composable
private fun AboutContentView(
    content: AboutContent,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current

    Column(modifier = modifier.fillMaxSize().background(colors.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderIconBtn(icon = AppIcons.chevronL, onClick = onBack, label = "Volver a Ajustes")
            Text(
                text = "Acerca de",
                color = colors.ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.02).sp,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(text = content.appName, color = colors.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = "Versión ${content.versionName}",
                color = colors.muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (content.links.isNotEmpty()) {
                SettingsSection(title = "Privacidad y contacto", modifier = Modifier.padding(top = 12.dp))
                SettingsCard {
                    content.links.forEachIndexed { index, link ->
                        SettingsRow(
                            title = link.title,
                            subtitle = link.subtitle,
                            trailing = { Chevron() },
                            onClick = { onOpen(link.uri) },
                            showDivider = index != content.links.lastIndex,
                        )
                    }
                }
            }
            SettingsSection(title = "Fuentes de datos", modifier = Modifier.padding(top = 12.dp))
            SettingsCard {
                content.dataSources.forEachIndexed { index, source ->
                    val url = source.url
                    SettingsRow(
                        title = source.name,
                        subtitle = "${source.covers}. ${source.note}",
                        trailing = if (url != null) ({ Chevron() }) else null,
                        onClick = url?.let { { onOpen(it) } },
                        showDivider = index != content.dataSources.lastIndex,
                    )
                }
            }
            SettingsSection(title = "Licencias de código abierto", modifier = Modifier.padding(top = 12.dp))
            SettingsCard {
                content.licenses.forEachIndexed { index, entry ->
                    SettingsRow(
                        title = entry.name,
                        subtitle = listOfNotNull(entry.license, entry.notice).joinToString("\n"),
                        showDivider = index != content.licenses.lastIndex,
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = AppIcons.chevronR,
        contentDescription = null,
        tint = LocalAppColors.current.muted,
        modifier = Modifier.size(18.dp),
    )
}
