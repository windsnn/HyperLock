package io.github.windsnn.hyperlock.ui

import android.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.github.windsnn.hyperlock.R
import io.github.windsnn.hyperlock.settings.LOCKSCREEN_TEXT_CHARGING
import io.github.windsnn.hyperlock.settings.LOCKSCREEN_TEXT_DND
import io.github.windsnn.hyperlock.settings.LOCKSCREEN_TEXT_NOTIFICATIONS
import kotlin.math.roundToInt

@Composable
internal fun LockScreenBottomTextDialog(
    show: Boolean,
    mask: Int,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit,
) {
    var selected by remember(show, mask) { mutableIntStateOf(mask) }
    val items = listOf(
        LOCKSCREEN_TEXT_CHARGING to stringResource(R.string.bottom_text_charging),
        LOCKSCREEN_TEXT_DND to stringResource(R.string.bottom_text_dnd),
        LOCKSCREEN_TEXT_NOTIFICATIONS to stringResource(R.string.bottom_text_notifications),
    )
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.dialog_bottom_text_title), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            items.forEach { (bit, title) ->
                val toggle = { selected = if (selected and bit != 0) selected and bit.inv() else selected or bit }
                Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state = if (selected and bit != 0) ToggleableState.On else ToggleableState.Off, onClick = toggle)
                    Text(title, style = MiuixTheme.textStyles.body1, modifier = Modifier.padding(start = 10.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onDismiss, Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
                Button({ onApply(selected) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(stringResource(R.string.action_apply)) }
            }
        }
    }
}

@Composable
internal fun RestoreDefaultsDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.dialog_restore_defaults_title),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.dialog_restore_defaults_desc),
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onDismiss, Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(stringResource(R.string.action_restore_defaults))
                }
            }
        }
    }
}

@Composable
internal fun MiniPlayerGesturesDialog(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.dialog_mini_player_gestures_title),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.dialog_mini_player_gestures_content),
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        }
    }
}

@Composable
internal fun ShortcutBackgroundColorPreference(
    title: String,
    color: Int,
    onColorChange: (Int) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { showPicker = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body1,
        )
        Box(
            Modifier.size(28.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = .45f), CircleShape)
                .padding(2.dp)
                .background(ComposeColor(color), CircleShape),
        )
    }
    ShortcutBackgroundColorDialog(
        show = showPicker,
        initialColor = color,
        onDismiss = { showPicker = false },
        onConfirm = { selected ->
            onColorChange(selected)
            showPicker = false
        },
    )
}

@Composable
internal fun ShortcutBackgroundColorDialog(
    show: Boolean,
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var draftColor by remember(show, initialColor) { mutableIntStateOf(initialColor) }
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.dialog_choose_color),
                modifier = Modifier.fillMaxWidth(),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start,
            )
            ColorPalette(
                color = ComposeColor(draftColor or 0xFF000000.toInt()),
                onColorChanged = { selected ->
                    draftColor = (draftColor and 0xFF000000.toInt()) or (selected.toArgb() and 0x00FFFFFF)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onDismiss, Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = { onConfirm(draftColor) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(stringResource(R.string.action_confirm)) }
            }
        }
    }
}

@Composable
internal fun ValueInputChip(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun ValueInputDialog(
    show: Boolean,
    title: String,
    initialValue: String,
    defaultValue: Float? = null,
    unit: String = "",
    min: Float,
    max: Float,
    isDecimal: Boolean = true,
    decimals: Int = 1,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    if (!show) return
    var text by remember(show, initialValue) { mutableStateOf(initialValue) }
    val parsed = text.toFloatOrNull()
    val minText = if (min == min.toInt().toFloat()) "${min.toInt()}" else "$min"
    val maxText = if (max == max.toInt().toFloat()) "${max.toInt()}" else "$max"
    val unitSuffix = if (unit.isNotBlank()) " $unit" else ""
    val rangeDesc = "$minText ~ $maxText$unitSuffix"
    val defaultText = defaultValue?.let {
        if (it == it.toInt().toFloat()) "${it.toInt()}" else "$it"
    }

    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.dialog_custom_value_prefix, title),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.dialog_valid_range, rangeDesc),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
                if (defaultText != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.dialog_default_recommended, "$defaultText$unitSuffix"),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                        Text(
                            text = stringResource(R.string.dialog_fill_default),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { text = defaultText }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            TextField(
                value = text,
                onValueChange = { text = it.trim() },
                label = if (unit.isNotBlank()) stringResource(R.string.dialog_input_value_with_unit_label, unit) else stringResource(R.string.dialog_input_value_label),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (min < 0) KeyboardType.Text else if (isDecimal) KeyboardType.Decimal else KeyboardType.Number,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        if (parsed != null) {
                            val clamped = parsed.coerceIn(min, max)
                            val factor = if (decimals == 2) 100f else if (decimals == 1) 10f else 1f
                            val rounded = (clamped * factor).roundToInt() / factor
                            onConfirm(rounded)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = parsed != null,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        }
    }
}
