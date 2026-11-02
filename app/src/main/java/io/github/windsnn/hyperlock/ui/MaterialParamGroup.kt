package io.github.windsnn.hyperlock.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.windsnn.hyperlock.HookSettings
import io.github.windsnn.hyperlock.R
import io.github.windsnn.hyperlock.settings.ELEMENT_BLEND_DEFAULT_BLUR_DP
import io.github.windsnn.hyperlock.settings.ELEMENT_BLEND_DEFAULT_OPACITY
import io.github.windsnn.hyperlock.settings.SOFT_GLASS_DEFAULT_BACKDROP_BLUR_RADIUS
import io.github.windsnn.hyperlock.settings.SOFT_GLASS_DEFAULT_BLUR_RADIUS
import io.github.windsnn.hyperlock.settings.SOFT_GLASS_DEFAULT_LUMINANCE
import io.github.windsnn.hyperlock.settings.SOFT_GLASS_DEFAULT_OPACITY
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** 快捷功能背景与迷你播放器背景共用的材质参数。 */
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

/** 标题文案配置：默认取快捷功能背景配置项，播放器配置见 [PLAYER]。 */
internal data class MaterialParamTitles(
    val pureColorRes: Int = R.string.mat_bg_color,
    val pureOpacityRes: Int = R.string.mat_opacity,
    val advancedColorRes: Int = R.string.mat_bg_color,
    val advancedOpacityRes: Int = R.string.mat_opacity,
    val advancedBlurRes: Int = R.string.mat_bg_blur,
    val advancedHighlightRes: Int = R.string.mat_highlight,
    val softColorRes: Int = R.string.mat_blend_color,
    val softOpacityRes: Int = R.string.mat_opacity,
    val softBackdropBlurRes: Int = R.string.mat_bg_blur,
    val softGlassBlurRes: Int = R.string.mat_glass_blur,
    val softLuminanceRes: Int = R.string.mat_luminance,
) {
    companion object {
        val PLAYER = MaterialParamTitles(
            pureColorRes = R.string.mat_player_bg_color,
            pureOpacityRes = R.string.mat_player_opacity,
            advancedColorRes = R.string.mat_player_blend_color,
            advancedOpacityRes = R.string.mat_player_opacity,
            advancedBlurRes = R.string.mat_player_bg_blur,
            advancedHighlightRes = R.string.mat_player_highlight,
            softColorRes = R.string.mat_player_blend_color,
            softOpacityRes = R.string.mat_player_opacity,
            softBackdropBlurRes = R.string.mat_player_bg_blur,
            softGlassBlurRes = R.string.mat_player_glass_blur,
            softLuminanceRes = R.string.mat_player_luminance,
        )
    }
}

/**
 * 纯色 / 高级材质 / 柔光玻璃通用参数调节组件。
 *
 * 由快捷功能背景与迷你播放器背景共用。[showPureOpacity] 控制纯色模式下是否显示透明度调节。
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
                title = stringResource(titles.pureColorRes),
                color = spec.pureColor,
                onColorChange = { onChange(spec.copy(pureColor = it)) },
            )
            if (showPureOpacity) {
                ParameterSlider(
                    title = stringResource(titles.pureOpacityRes),
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
                title = stringResource(titles.advancedColorRes),
                color = spec.advancedColor,
                onColorChange = { onChange(spec.copy(advancedColor = it)) },
            )
            ParameterSlider(
                title = stringResource(titles.advancedOpacityRes),
                value = spec.advancedOpacity.toFloat(),
                range = 0f..100f,
                defaultValue = ELEMENT_BLEND_DEFAULT_OPACITY.toFloat(),
                decimals = 0,
                unit = "%",
                spaceBeforeUnit = false,
            ) { onChange(spec.copy(advancedOpacity = it.roundToInt())) }
            ParameterSlider(
                title = stringResource(titles.advancedBlurRes),
                value = spec.advancedBlur.toFloat(),
                range = 0f..100f,
                defaultValue = ELEMENT_BLEND_DEFAULT_BLUR_DP.toFloat(),
                decimals = 0,
                unit = "",
                spaceBeforeUnit = false,
            ) { onChange(spec.copy(advancedBlur = it.roundToInt())) }
            SwitchPreference(
                title = stringResource(titles.advancedHighlightRes),
                checked = spec.advancedHighlight,
                onCheckedChange = { onChange(spec.copy(advancedHighlight = it)) },
            )
        }
        3 -> {
            ShortcutBackgroundColorPreference(
                title = stringResource(titles.softColorRes),
                color = spec.softColor,
                onColorChange = { onChange(spec.copy(softColor = it)) },
            )
            ParameterSlider(
                title = stringResource(titles.softOpacityRes),
                value = spec.softOpacity.toFloat(),
                range = 0f..100f,
                defaultValue = SOFT_GLASS_DEFAULT_OPACITY.toFloat(),
                decimals = 0,
                unit = "%",
                spaceBeforeUnit = false,
            ) { onChange(spec.copy(softOpacity = it.roundToInt())) }
            ParameterSlider(
                title = stringResource(titles.softBackdropBlurRes),
                value = spec.softBackdropBlur.toFloat(),
                range = 0f..100f,
                defaultValue = SOFT_GLASS_DEFAULT_BACKDROP_BLUR_RADIUS.toFloat(),
                decimals = 0,
                unit = "",
                spaceBeforeUnit = false,
            ) { onChange(spec.copy(softBackdropBlur = it.roundToInt())) }
            ParameterSlider(
                title = stringResource(titles.softGlassBlurRes),
                value = spec.softGlassBlur.toFloat(),
                range = 0f..100f,
                defaultValue = SOFT_GLASS_DEFAULT_BLUR_RADIUS.toFloat(),
                decimals = 0,
                unit = "",
                spaceBeforeUnit = false,
            ) { onChange(spec.copy(softGlassBlur = it.roundToInt())) }
            ParameterSlider(
                title = stringResource(titles.softLuminanceRes),
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
