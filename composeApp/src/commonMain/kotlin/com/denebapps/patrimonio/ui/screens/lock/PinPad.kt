package com.denebapps.patrimonio.ui.screens.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.repository.PIN_LENGTH
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import kotlinx.coroutines.delay

/** The extra key at the bottom left of the keypad: the fingerprint or face, on the lock screen. */
data class PinPadExtraKey(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * The 4 PIN dots and the keypad (`lock.jsx`). [onPinComplete] gets each full PIN; the dots then clear.
 * Each time [wrongPinCount] grows the dots shake.
 */
@Composable
fun PinEntry(
    onPinComplete: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    wrongPinCount: Int = 0,
    extraKey: PinPadExtraKey? = null,
) {
    var digits by remember { mutableStateOf("") }
    LaunchedEffect(digits) {
        if (digits.length == PIN_LENGTH) {
            delay(150) // the last dot fills before the PIN goes
            onPinComplete(digits)
            digits = ""
        }
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PinDots(filled = digits.length, wrongPinCount = wrongPinCount)
        Keypad(
            enabled = enabled && digits.length < PIN_LENGTH,
            onDigit = { digits += it },
            onBackspace = { digits = digits.dropLast(1) },
            extraKey = extraKey,
            modifier = Modifier.padding(top = 36.dp),
        )
    }
}

@Composable
private fun PinDots(filled: Int, wrongPinCount: Int) {
    val colors = LocalAppColors.current
    val shake = remember { Animatable(0f) }
    LaunchedEffect(wrongPinCount) {
        if (wrongPinCount > 0) {
            shake.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 400
                    -8f at 80
                    8f at 160
                    -5f at 240
                    5f at 320
                },
            )
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .offset { IntOffset(shake.value.dp.roundToPx(), 0) }
            .semantics { contentDescription = "$filled de $PIN_LENGTH cifras" },
    ) {
        repeat(PIN_LENGTH) { index ->
            val on = index < filled
            Box(
                modifier = Modifier
                    .size(13.dp)
                    .background(if (on) colors.ink else colors.bg, CircleShape)
                    .border(1.5.dp, if (on) colors.ink else colors.muted2, CircleShape),
            )
        }
    }
}

@Composable
private fun Keypad(
    enabled: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    extraKey: PinPadExtraKey?,
    modifier: Modifier = Modifier,
) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
    Column(
        modifier = modifier.alpha(if (enabled) 1f else 0.5f),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { DigitKey(it, enabled, onDigit) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            if (extraKey != null) {
                IconKey(extraKey.icon, extraKey.label, enabled = true, onClick = extraKey.onClick)
            } else {
                Box(Modifier.size(KEY_SIZE))
            }
            DigitKey("0", enabled, onDigit)
            IconKey(AppIcons.arrowLeft, "Borrar", enabled, onBackspace)
        }
    }
}

@Composable
private fun DigitKey(digit: String, enabled: Boolean, onDigit: (String) -> Unit) {
    val colors = LocalAppColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(KEY_SIZE)
            .clip(CircleShape)
            .background(colors.surface, CircleShape)
            .border(1.dp, colors.line, CircleShape)
            .clickable(enabled = enabled) { onDigit(digit) },
    ) {
        Text(text = digit, color = colors.ink, fontSize = 26.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun IconKey(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(KEY_SIZE).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = LocalAppColors.current.ink2, modifier = Modifier.size(26.dp))
    }
}

private val KEY_SIZE = 72.dp
