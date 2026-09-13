package io.github.windsnn.hyperlock.ui

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 统一的参数滑块：decimals 决定滑动取整精度、芯片文本小数位与输入对话框的键盘类型。
 * 默认值吸附按区间跨度的 3.5% 计算，整数档（decimals = 0）额外保证至少 1 的吸附半径。
 */
@Composable
internal fun ParameterSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    defaultValue: Float? = null,
    decimals: Int = 1,
    unit: String = "dp",
    spaceBeforeUnit: Boolean = true,
    save: (Float) -> Unit,
) {
    val view = LocalView.current
    var current by remember(value) { mutableFloatStateOf(value) }
    var showDialog by remember { mutableStateOf(false) }
    var wasSnapped by remember { mutableStateOf(defaultValue != null && abs(value - defaultValue) < 0.001f) }
    val snapRadius = if (defaultValue != null) {
        val span = range.endInclusive - range.start
        if (decimals == 0) maxOf(1f, span * 0.035f) else span * 0.035f
    } else {
        0f
    }

    fun roundToStep(raw: Float): Float = when (decimals) {
        0 -> raw.roundToInt().toFloat()
        2 -> (raw * 100f).roundToInt() / 100f
        else -> (raw * 10f).roundToInt() / 10f
    }

    val displayValue = when (decimals) {
        0 -> "${current.roundToInt()}"
        2 -> "${(current * 100f).roundToInt() / 100f}"
        else -> if (current == current.toInt().toFloat()) {
            "${current.toInt()}"
        } else {
            "${(current * 10f).roundToInt() / 10f}"
        }
    }
    val displayText = when {
        unit.isBlank() -> displayValue
        spaceBeforeUnit -> "$displayValue $unit"
        else -> "$displayValue$unit"
    }

    SliderPreference(
        value = current.coerceIn(range.start, range.endInclusive),
        onValueChange = { raw ->
            if (defaultValue != null && abs(raw - defaultValue) <= snapRadius) {
                if (!wasSnapped) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    wasSnapped = true
                }
                current = defaultValue
            } else {
                wasSnapped = false
                current = roundToStep(raw)
            }
        },
        onValueChangeFinished = {
            save(
                if (decimals == 0) {
                    current.roundToInt().toFloat().coerceIn(range.start, range.endInclusive)
                } else {
                    current
                },
            )
        },
        title = title,
        endActions = {
            ValueInputChip(
                text = displayText,
                onClick = { showDialog = true },
            )
        },
        valueRange = range,
        steps = 0,
    )

    ValueInputDialog(
        show = showDialog,
        title = title,
        initialValue = displayValue,
        defaultValue = defaultValue,
        unit = unit,
        min = range.start,
        max = range.endInclusive,
        isDecimal = decimals > 0,
        decimals = decimals,
        onDismiss = { showDialog = false },
        onConfirm = { newVal ->
            val accepted = if (decimals == 0) newVal.roundToInt().toFloat() else newVal
            current = accepted
            save(accepted)
        },
    )
}

@Composable
internal fun Dim(
    title: String,
    enabled: Boolean,
    changeEnabled: (Boolean) -> Unit,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    defaultValue: Float? = null,
    unit: String = "dp",
    save: (Float) -> Unit,
) {
    SwitchPreference(title = "自定义$title", checked = enabled, onCheckedChange = changeEnabled)
    if (enabled) ParameterSlider(title, value, range, defaultValue = defaultValue, unit = unit, save = save)
}
