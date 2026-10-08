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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.repository.AccountProvider
import com.denebapps.patrimonio.domain.repository.CloudBackupState
import com.denebapps.patrimonio.ui.components.GhostButton
import com.denebapps.patrimonio.ui.components.HeaderIconBtn
import com.denebapps.patrimonio.ui.components.PrimaryButton
import com.denebapps.patrimonio.ui.components.SettingsCard
import com.denebapps.patrimonio.ui.components.SettingsRow
import com.denebapps.patrimonio.ui.components.TextField
import com.denebapps.patrimonio.ui.components.TextLink
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import org.koin.compose.viewmodel.koinViewModel

/**
 * The optional cloud account, pushed from Ajustes. Signed out: sign in with Google (Android), or sign in or
 * create the account with email and password and reset a forgotten password. Signed in: who it is, its cloud backup, sign out and delete the
 * account.
 */
@Composable
fun AccountScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: AccountViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val colors = LocalAppColors.current
    val requestGoogleIdToken = rememberGoogleIdTokenRequester()

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
                SignedOutContent(state, viewModel, requestGoogleIdToken)
            } else {
                SignedInContent(state, viewModel, requestGoogleIdToken)
            }
            state.error?.let { Message(it, isError = true) }
            state.info?.let { Message(it, isError = false) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ColumnScope.SignedOutContent(
    state: AccountUiState,
    viewModel: AccountViewModel,
    requestGoogleIdToken: (suspend () -> GoogleIdTokenResult)?,
) {
    val colors = LocalAppColors.current
    val signUp = state.mode == AccountMode.SIGN_UP
    val scope = rememberCoroutineScope()
    Text(
        text = "Con una cuenta podrás guardar tus datos en la nube y recuperarlos en otro móvil. " +
            "Es opcional: sin cuenta, todo sigue funcionando en este dispositivo.",
        color = colors.ink2,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
    if (requestGoogleIdToken != null) {
        GhostButton(
            text = "Continuar con Google",
            onClick = { scope.launch { viewModel.onGoogleResult(requestGoogleIdToken()) } },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        )
        Text(
            text = "o con tu correo",
            color = colors.muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 14.dp).align(Alignment.CenterHorizontally),
        )
    }
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
private fun SignedInContent(
    state: AccountUiState,
    viewModel: AccountViewModel,
    requestGoogleIdToken: (suspend () -> GoogleIdTokenResult)?,
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val google = state.user?.provider == AccountProvider.GOOGLE

    SettingsCard {
        SettingsRow(
            title = state.user?.email.orEmpty(),
            subtitle = if (google) "Sesión iniciada con Google" else "Sesión iniciada",
            leading = { Icon(AppIcons.cloud, contentDescription = null, tint = colors.brand) },
            showDivider = false,
        )
    }
    Spacer(Modifier.height(12.dp))
    CloudBackupSection(state.cloud, viewModel)
    Spacer(Modifier.height(12.dp))
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
    if (confirmDelete && google) {
        DeleteGoogleAccountDialog(
            canConfirm = requestGoogleIdToken != null,
            onConfirm = {
                confirmDelete = false
                requestGoogleIdToken?.let { request ->
                    scope.launch { viewModel.onDeleteAccountWithGoogle(request()) }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    } else if (confirmDelete) {
        DeleteAccountDialog(
            onConfirm = { password ->
                confirmDelete = false
                viewModel.onDeleteAccount(password)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private enum class ConflictChoice { USE_CLOUD, KEEP_LOCAL }

/**
 * The automatic backup: the passphrase to create or type, the choice to make when the cloud holds other data,
 * or when it last saved.
 */
@Composable
private fun CloudBackupSection(cloud: CloudBackupState, viewModel: AccountViewModel) {
    val colors = LocalAppColors.current
    val zone = remember { TimeZone.currentSystemDefault() }
    var confirm by rememberSaveable { mutableStateOf<ConflictChoice?>(null) }
    var changingPassphrase by rememberSaveable { mutableStateOf(false) }

    if (cloud is CloudBackupState.NeedsPassphrase && !cloud.unlock) {
        Paragraph(
            "Tus datos se cifran en este móvil antes de subir a la nube: nadie más puede leerlos, ni siquiera " +
                "nosotros. Elige una frase de cifrado; te la pediremos al iniciar sesión en otro móvil.",
        )
        Paragraph(
            "Si la olvidas no se puede recuperar la copia de la nube, aunque tus datos de este móvil no se pierden.",
            muted = true,
        )
        NewPassphraseForm(
            buttonText = "Activar la copia cifrada",
            working = cloud.working,
            onSubmit = viewModel::onSubmitPassphrase,
        )
    } else if (cloud is CloudBackupState.NeedsPassphrase) {
        UnlockForm(cloud, viewModel)
    } else if (cloud is CloudBackupState.Conflict) {
        Text(
            text = "Esta cuenta ya tiene una copia en la nube con otros datos, guardada el " +
                "${formatBackupTime(cloud.cloudSavedAt, zone)}. ¿Con cuáles te quedas?",
            color = colors.ink2,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        PrimaryButton(
            text = "Usar la copia de la nube",
            onClick = { confirm = ConflictChoice.USE_CLOUD },
            modifier = Modifier.padding(top = 14.dp),
        )
        GhostButton(
            text = "Mantener los datos de este móvil",
            onClick = { confirm = ConflictChoice.KEEP_LOCAL },
            modifier = Modifier.padding(top = 10.dp),
        )
    } else {
        SettingsCard {
            SettingsRow(
                title = "Copia en la nube",
                subtitle = cloudSubtitle(cloud, zone),
                leading = { Icon(AppIcons.cloud, contentDescription = null, tint = colors.brand) },
                showDivider = cloud is CloudBackupState.CheckFailed || cloud is CloudBackupState.Active,
            )
            when {
                cloud is CloudBackupState.CheckFailed ->
                    SettingsRow(title = "Reintentar", onClick = viewModel::onRetryCloud, showDivider = false)
                cloud is CloudBackupState.Active && !cloud.backingUp ->
                    SettingsRow(title = "Guardar ahora", onClick = viewModel::onBackUpNow)
                cloud is CloudBackupState.Active -> SettingsRow(title = "Guardando…")
            }
            if (cloud is CloudBackupState.Active) {
                SettingsRow(
                    title = "Cambiar frase de cifrado",
                    onClick = { changingPassphrase = true },
                    showDivider = false,
                )
            }
        }
    }

    if (changingPassphrase) {
        ChangePassphraseDialog(
            onConfirm = { passphrase ->
                changingPassphrase = false
                viewModel.onChangePassphrase(passphrase)
            },
            onDismiss = { changingPassphrase = false },
        )
    }

    when (confirm) {
        ConflictChoice.USE_CLOUD -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("¿Usar la copia de la nube?") },
            text = {
                Text(
                    "Los datos de este móvil se sustituirán por los de la copia. Si quieres conservarlos, " +
                        "exporta antes una copia desde Ajustes.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        viewModel.onUseCloudCopy()
                    },
                ) { Text("Usar la copia") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
        )
        ConflictChoice.KEEP_LOCAL -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("¿Mantener los datos de este móvil?") },
            text = { Text("La copia de la nube se sustituirá por los datos de este móvil.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        viewModel.onKeepLocalData()
                    },
                ) { Text("Mantener") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
        )
        null -> Unit
    }
}

/** The cloud copy is encrypted and this device has no key: the passphrase, or starting over without it. */
@Composable
private fun UnlockForm(cloud: CloudBackupState.NeedsPassphrase, viewModel: AccountViewModel) {
    val colors = LocalAppColors.current
    var passphrase by remember { mutableStateOf("") }
    var forgotten by rememberSaveable { mutableStateOf(false) }
    var confirmStartOver by remember { mutableStateOf<String?>(null) }

    if (!forgotten) {
        Paragraph("Tu copia de la nube está cifrada. Escribe tu frase de cifrado para abrirla.")
        TextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = "Frase de cifrado",
            leadingIcon = AppIcons.lock,
            isPassword = true,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (cloud.wrongPassphrase) {
            Text(
                text = "Esa frase no abre la copia.",
                color = colors.expense,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        PrimaryButton(
            text = if (cloud.working) "Abriendo…" else "Abrir la copia",
            onClick = { viewModel.onSubmitPassphrase(passphrase) },
            enabled = passphrase.isNotEmpty() && !cloud.working,
            modifier = Modifier.padding(top = 14.dp),
        )
        TextLink(
            text = "He olvidado la frase",
            onClick = { forgotten = true },
            modifier = Modifier.padding(top = 14.dp),
        )
    } else {
        Paragraph(
            "Sin la frase, la copia de la nube no se puede abrir. Puedes empezar de cero: la copia se sustituye " +
                "por los datos de este móvil, cifrados con una frase nueva.",
        )
        NewPassphraseForm(
            buttonText = "Empezar de cero",
            working = cloud.working,
            onSubmit = { confirmStartOver = it },
        )
        TextLink(
            text = "Volver a probar con mi frase",
            onClick = { forgotten = false },
            modifier = Modifier.padding(top = 14.dp),
        )
    }

    confirmStartOver?.let { newPassphrase ->
        AlertDialog(
            onDismissRequest = { confirmStartOver = null },
            title = { Text("¿Empezar de cero?") },
            text = { Text("La copia de la nube se borra y se sustituye por los datos de este móvil.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStartOver = null
                        viewModel.onStartOver(newPassphrase)
                    },
                ) { Text("Empezar de cero") }
            },
            dismissButton = { TextButton(onClick = { confirmStartOver = null }) { Text("Cancelar") } },
        )
    }
}

/** A new passphrase typed twice; [onSubmit] gets it once it is long enough and both match. */
@Composable
private fun NewPassphraseForm(buttonText: String, working: Boolean, onSubmit: (String) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val problem = passphraseProblem(passphrase, confirmation)

    PassphraseFields(passphrase, { passphrase = it }, confirmation, { confirmation = it }, problem)
    PrimaryButton(
        text = if (working) "Preparando el cifrado…" else buttonText,
        onClick = { onSubmit(passphrase) },
        enabled = problem == null && !working,
        modifier = Modifier.padding(top = 14.dp),
    )
}

@Composable
private fun ChangePassphraseDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val problem = passphraseProblem(passphrase, confirmation)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cambiar frase de cifrado") },
        text = {
            Column {
                Text("La copia de la nube se abrirá con la frase nueva. No hace falta la anterior.")
                PassphraseFields(passphrase, { passphrase = it }, confirmation, { confirmation = it }, problem)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(passphrase) }, enabled = problem == null) { Text("Cambiar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun PassphraseFields(
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    confirmation: String,
    onConfirmationChange: (String) -> Unit,
    problem: String?,
) {
    TextField(
        value = passphrase,
        onValueChange = onPassphraseChange,
        label = "Frase de cifrado (mínimo $MIN_PASSPHRASE_LENGTH caracteres)",
        leadingIcon = AppIcons.lock,
        isPassword = true,
        modifier = Modifier.padding(top = 12.dp),
    )
    TextField(
        value = confirmation,
        onValueChange = onConfirmationChange,
        label = "Repite la frase",
        leadingIcon = AppIcons.lock,
        isPassword = true,
        modifier = Modifier.padding(top = 10.dp),
    )
    // Only once both fields have something, so the hint does not nag while typing the first one.
    if (problem != null && passphrase.isNotEmpty() && confirmation.isNotEmpty()) {
        Text(
            text = problem,
            color = LocalAppColors.current.muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Paragraph(text: String, muted: Boolean = false) {
    val colors = LocalAppColors.current
    Text(
        text = text,
        color = if (muted) colors.muted else colors.ink2,
        fontSize = if (muted) 13.sp else 14.sp,
        lineHeight = if (muted) 18.sp else 20.sp,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

private fun cloudSubtitle(cloud: CloudBackupState, zone: TimeZone): String = when (cloud) {
    CloudBackupState.SignedOut, CloudBackupState.Checking -> "Comprobando la copia de la nube…"
    CloudBackupState.Resolving -> "Aplicando tu elección…"
    is CloudBackupState.Conflict, is CloudBackupState.NeedsPassphrase -> ""
    is CloudBackupState.CheckFailed -> cloudMessageFor(cloud.error)
    is CloudBackupState.Active -> cloud.failure?.let { "No se pudo guardar la última copia. ${cloudMessageFor(it)}" }
        ?: "Última copia: ${formatBackupTime(cloud.lastBackupAt, zone)}. Cifrada y se guarda sola con cada cambio."
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
                Text(
                    "Se borran para siempre la cuenta y su copia en la nube. Tus datos siguen en este móvil. " +
                        "Escribe tu contraseña:",
                )
                TextField(value = password, onValueChange = { password = it }, label = "Contraseña", isPassword = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) { Text("Borrar cuenta") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Firebase asks for a recent sign-in before deleting an account: the user picks the Google account again.
 *  Not [canConfirm] where Google sign-in is not available (the account cannot be deleted from here). */
@Composable
private fun DeleteGoogleAccountDialog(canConfirm: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Borrar la cuenta?") },
        text = {
            Text(
                "Se borran para siempre la cuenta y su copia en la nube. Tus datos siguen en este móvil. " +
                    if (canConfirm) {
                        "Para confirmarlo, elige de nuevo tu cuenta de Google."
                    } else {
                        "Para borrarla, entra con Google desde la app de Android."
                    },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canConfirm) { Text("Borrar cuenta") }
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
