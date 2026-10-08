package com.denebapps.patrimonio.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.components.HeaderIconBtn
import com.denebapps.patrimonio.ui.components.PrimaryButton
import com.denebapps.patrimonio.ui.components.SettingsCard
import com.denebapps.patrimonio.ui.components.SettingsRow
import com.denebapps.patrimonio.ui.components.TextField
import com.denebapps.patrimonio.ui.components.TextLink
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import org.koin.compose.viewmodel.koinViewModel

/**
 * The optional cloud account, pushed from Ajustes. Signed out: sign in or create the account (email and
 * password) and reset a forgotten password. Signed in: who it is, sign out and delete the account.
 */
@Composable
fun AccountScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: AccountViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val colors = LocalAppColors.current

    Column(modifier = modifier.fillMaxSize().background(colors.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderIconBtn(icon = AppIcons.chevronL, onClick = onBack, label = "Volver a Ajustes")
            Text(
                text = "Cuenta",
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
            if (state.user == null) {
                SignedOutContent(state, viewModel)
            } else {
                SignedInContent(state, viewModel)
            }
            state.error?.let { Message(it, isError = true) }
            state.info?.let { Message(it, isError = false) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ColumnScope.SignedOutContent(state: AccountUiState, viewModel: AccountViewModel) {
    val colors = LocalAppColors.current
    val signUp = state.mode == AccountMode.SIGN_UP
    Text(
        text = "Con una cuenta podrás guardar tus datos en la nube y recuperarlos en otro móvil. " +
            "Es opcional: sin cuenta, todo sigue funcionando en este dispositivo.",
        color = colors.ink2,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeTab("Iniciar sesión", !signUp, Modifier.weight(1f)) { viewModel.onModeChange(AccountMode.SIGN_IN) }
        ModeTab("Crear cuenta", signUp, Modifier.weight(1f)) { viewModel.onModeChange(AccountMode.SIGN_UP) }
    }
    TextField(
        value = state.email,
        onValueChange = viewModel::onEmailChange,
        label = "Correo electrónico",
        leadingIcon = AppIcons.user,
    )
    Spacer(Modifier.height(10.dp))
    TextField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = if (signUp) "Contraseña (mínimo 6 caracteres)" else "Contraseña",
        leadingIcon = AppIcons.lock,
        isPassword = true,
    )
    PrimaryButton(
        text = if (signUp) "Crear cuenta" else "Entrar",
        onClick = viewModel::onSubmit,
        enabled = state.canSubmit,
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
    )
    if (!signUp) {
        TextLink(
            text = "¿Olvidaste tu contraseña?",
            onClick = viewModel::onForgotPassword,
            modifier = Modifier.padding(top = 14.dp).align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun SignedInContent(state: AccountUiState, viewModel: AccountViewModel) {
    val colors = LocalAppColors.current
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    SettingsCard {
        SettingsRow(
            title = state.user?.email.orEmpty(),
            subtitle = "Sesión iniciada",
            leading = { Icon(AppIcons.cloud, contentDescription = null, tint = colors.brand) },
            showDivider = false,
        )
    }
    Text(
        text = "Muy pronto tus datos se guardarán solos en la nube con esta cuenta. " +
            "Mientras tanto, siguen solo en este móvil.",
        color = colors.muted,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
    )
    SettingsCard {
        SettingsRow(title = "Cerrar sesión", onClick = { confirmSignOut = true })
        SettingsRow(title = "Borrar cuenta", onClick = { confirmDelete = true }, showDivider = false, danger = true)
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("¿Cerrar sesión?") },
            text = { Text("Tus datos se quedan en este móvil.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        viewModel.onSignOut()
                    },
                ) { Text("Cerrar sesión") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancelar") } },
        )
    }
    if (confirmDelete) {
        DeleteAccountDialog(
            onConfirm = { password ->
                confirmDelete = false
                viewModel.onDeleteAccount(password)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Firebase asks for a recent sign-in before deleting an account, so the dialog asks for the password. */
@Composable
private fun DeleteAccountDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Borrar la cuenta?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Se borra la cuenta para siempre. Tus datos siguen en este móvil. Escribe tu contraseña:")
                TextField(value = password, onValueChange = { password = it }, label = "Contraseña", isPassword = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) { Text("Borrar cuenta") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(12.dp)
    Text(
        text = label,
        color = if (selected) colors.bg else colors.ink2,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(if (selected) colors.ink else colors.surface, shape)
            .border(1.dp, if (selected) colors.ink else colors.line, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 11.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Message(text: String, isError: Boolean) {
    val colors = LocalAppColors.current
    Text(
        text = text,
        color = if (isError) colors.expense else colors.income,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(top = 14.dp),
    )
}
