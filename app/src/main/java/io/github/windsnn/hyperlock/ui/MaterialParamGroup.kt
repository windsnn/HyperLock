package io.github.windsnn.hyperlock.ui

import androidx.compose.runtime.Composable
import io.github.windsnn.hyperlock.HookSettings
import io.github.windsnn.hyperlock.settings.ELEMENT_BLEND_DEFAULT_BLUR_DP
import io.github.windsnn.hyperlock.settings.ELEMENT_BLEND_DEFAULT_OPACITY
import io.github.windsnn.hyperlock.settings.SOFT_GLASS_DEFAULT_BACKDROP_BLUR_RADIUS
import io.github.windsnn.hyperlock.settings.SOFT_GLASS_DEFAULT_BLUR_RADIUS
import io.github.windsnn.hyperlock.settings.SOFT_GLASS_DEFAULT_LUMINANCE
import io.github.windsnn.hyperlock.settings.SOFT_GLASS_DEFAULT_OPACITY
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** 快捷功能背景与迷你播放器背景共用的材质参数值。 */
internal data class MaterialSpec(
    val pureColor: Int,
    val pureOpacity: Int,
    val advancedColor: Int,
    val advancedOpacity: Int,
    val advancedBlur: Int,
    val advancedHighlight: Boolean,
    val softColor: Int,
    val softOpacity: Int,
    val softBackdropBlur: Int,
    val softGlassBlur: Int,
    val softLuminance: Float,
)

/** 标题文案：默认取快捷功能背景一侧，播放器侧见 [PLAYER]。 */
internal data class MaterialParamTitles(
    val pureColor: String = "背景颜色",
    val pureOpacity: String = "透明度",
    val advancedColor: String = "背景颜色",
    val advancedOpacity: String = "透明度",
    val advancedBlur: String = "背景模糊度",
    val advancedHighlight: String = "显示高光",
    val softColor: String = "混色颜色",
    val softOpacity: String = "透明度",
    val softBackdropBlur: String = "背景模糊度",
    val softGlassBlur: String = "Glass 模糊度",
    val softLuminance: String = "柔光强度",
) {
    companion object {
        val PLAYER = MaterialParamTitles(
            pureColor = "播放器背景颜色",
            pureOpacity = "播放器透明度",
            advancedColor = "播放器混色颜色",
            advancedOpacity = "播放器透明度",
            advancedBlur = "播放器背景模糊度",
            advancedHighlight = "播放器显示高光",
            softColor = "播放器混色颜色",
            softOpacity = "播放器透明度",
            softBackdropBlur = "播放器背景模糊度",
            softGlassBlur = "播放器 Glass 模糊度",
            softLuminance = "播放器柔光强度",
        )
    }
}

/**
 * 纯色 / 高级材质 / 柔光玻璃三档的参数块，快捷功能背景与迷你播放器背景共用同一实现。
 * 只有纯色档是否显示透明度由 [showPureOpacity] 控制（快捷侧显示，播放器侧不显示）。
 */
@Composable
internal fun MaterialParamGroup(
    mode: Int,
    titles: MaterialParamTitles,
    spec: MaterialSpec,
    showPureOpacity: Boolean,
    onChange: (MaterialSpec) -> Unit,
) {
    when (mode) {
        1 -> {
            ShortcutBackgroundColorPreference(
                title = titles.pureColor,
                color = spec.pureColor,
                onColorChange = { onChange(spec.copy(pureColor = it)) },
            )
            if (showPureOpacity) {
                ParameterSlider(
                    title = titles.pureOpacity,
                    value = spec.pureOpacity.toFloat(),
                    range = 0f..100f,
                    defaultValue = 45f,
                    decimals = 0,
                    unit = "%",
                    spaceBeforeUnit = false,
                ) { onChange(spec.copy(pureOpacity = it.roundToInt())) }
            }
        }
        2 -> {
            ShortcutBackgroundColorPreference(
                title = titles.advancedColor,
                color = spec.advancedColor,
                onColorChange = { onChange(spec.copy(advancedColor = it)) },
            )
            ParameterSlider(
                title = titles.advancedOpacity,
                value = spec.advancedOpacity.toFloat(),
                range = 0f..100f,
                defaultValue = ELEMENT_BLEND_DEFAULT_OPACITY.toFloat(),
                decimals = 0,
                unit = "%",
                spaceBeforeUnit = false,
            ) { onChange(spec.copy(advancedOpacity = it.roundToInt())) }
            ParameterSlider(
                title = titles.advancedBlur,
                value = spec.advancedBlur.toFloat(),
                range = 0f..100f,
                defaultValue = ELEMENT_BLEND_DEFAULT_BLUR_DP.toFloat(),
                decimals = 0,
                unit = "",
                spaceBeforeUnit = false,
            ) { onChange(spec.copy(advancedBlur = it.roundToInt())) }
            SwitchPreference(
                title = titles.advancedHighlight,
                checked = spec.advancedHighlight,
                onCheckedChange = { onChange(spec.copy(advancedHighlight = it)) },
            )
        }
        3 -> {
            ShortcutBackgroundColorPreference(
                title = titles.softColor,
                color = spec.softColor,
                onColorChange = { onChange(spec.copy(softColor = it)) },
            )
            ParameterSlider(
                title = titles.softOpacity,
                value = spec.softOpacity.toFloat(),
                range = 0f..100f,
                defaultValue = SOFT_GLASS_DEFAULT_OPACITY.toFloat(),
                decimals = 0,
                unit = "%",
                spaceBeforeUnit = false,
            ) { onChange(spec.copy(softOpacity = it.roundToInt())) }
            ParameterSlider(
                title = titles.softBackdropBlur,
                value = spec.softBackdropBlur.toFloat(),
                range = 0f..100f,
                defaultValue = SOFT_GLASS_DEFAULT_BACKDROP_BLUR_RADIUS.toFloat(),
                decimals = 0,
                unit = "",
                spaceBeforeUnit = false,
            ) { onChange(spec.copy(softBackdropBlur = it.roundToInt())) }
            ParameterSlider(
                title = titles.softGlassBlur,
                value = spec.softGlassBlur.toFloat(),
                range = 0f..100f,
                defaultValue = SOFT_GLASS_DEFAULT_BLUR_RADIUS.toFloat(),
                decimals = 0,
                unit = "",
                spaceBeforeUnit = false,
            ) { onChange(spec.copy(softGlassBlur = it.roundToInt())) }
            ParameterSlider(
                title = titles.softLuminance,
                value = spec.softLuminance,
                range = 0f..0.5f,
                defaultValue = SOFT_GLASS_DEFAULT_LUMINANCE,
                decimals = 2,
                unit = "",
                spaceBeforeUnit = false,
            ) { onChange(spec.copy(softLuminance = it)) }
        }
    }
}

internal fun HookSettings.shortcutMaterialSpec(): MaterialSpec = MaterialSpec(
    pureColor = shortcutPureColor,
    pureOpacity = shortcutPureOpacity,
    advancedColor = shortcutAdvancedMaterialColor,
    advancedOpacity = shortcutAdvancedMaterialOpacity,
    advancedBlur = shortcutAdvancedMaterialBlurRadius,
    advancedHighlight = shortcutAdvancedMaterialHighlight,
    softColor = shortcutSoftGlassColor,
    softOpacity = shortcutSoftGlassOpacity,
    softBackdropBlur = shortcutSoftGlassBackdropBlurRadius,
    softGlassBlur = shortcutSoftGlassBlurRadius,
    softLuminance = shortcutSoftGlassLuminance,
)

internal fun HookSettings.withShortcutMaterialSpec(spec: MaterialSpec): HookSettings = copy(
    shortcutPureColor = spec.pureColor,
    shortcutPureOpacity = spec.pureOpacity,
    shortcutAdvancedMaterialColor = spec.advancedColor,
    shortcutAdvancedMaterialOpacity = spec.advancedOpacity,
    shortcutAdvancedMaterialBlurRadius = spec.advancedBlur,
    shortcutAdvancedMaterialHighlight = spec.advancedHighlight,
    shortcutSoftGlassColor = spec.softColor,
    shortcutSoftGlassOpacity = spec.softOpacity,
    shortcutSoftGlassBackdropBlurRadius = spec.softBackdropBlur,
    shortcutSoftGlassBlurRadius = spec.softGlassBlur,
    shortcutSoftGlassLuminance = spec.softLuminance,
)

internal fun HookSettings.miniPlayerMaterialSpec(): MaterialSpec = MaterialSpec(
    pureColor = miniPlayerPureColor,
    pureOpacity = 0,
    advancedColor = miniPlayerAdvancedMaterialColor,
    advancedOpacity = miniPlayerAdvancedMaterialOpacity,
    advancedBlur = miniPlayerAdvancedMaterialBlurRadius,
    advancedHighlight = miniPlayerAdvancedMaterialHighlight,
    softColor = miniPlayerSoftGlassColor,
    softOpacity = miniPlayerSoftGlassOpacity,
    softBackdropBlur = miniPlayerSoftGlassBackdropBlurRadius,
    softGlassBlur = miniPlayerSoftGlassBlurRadius,
    softLuminance = miniPlayerSoftGlassLuminance,
)

internal fun HookSettings.withMiniPlayerMaterialSpec(spec: MaterialSpec): HookSettings = copy(
    miniPlayerPureColor = spec.pureColor,
    miniPlayerAdvancedMaterialColor = spec.advancedColor,
    miniPlayerAdvancedMaterialOpacity = spec.advancedOpacity,
    miniPlayerAdvancedMaterialBlurRadius = spec.advancedBlur,
    miniPlayerAdvancedMaterialHighlight = spec.advancedHighlight,
    miniPlayerSoftGlassColor = spec.softColor,
    miniPlayerSoftGlassOpacity = spec.softOpacity,
    miniPlayerSoftGlassBackdropBlurRadius = spec.softBackdropBlur,
    miniPlayerSoftGlassBlurRadius = spec.softGlassBlur,
    miniPlayerSoftGlassLuminance = spec.softLuminance,
)
