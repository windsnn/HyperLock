package io.github.windsnn.hyperlock.settings

import android.graphics.Color


internal const val FINGERPRINT_HIDE_NONE = 0
internal const val FINGERPRINT_HIDE_LOCKSCREEN = 1
internal const val FINGERPRINT_HIDE_GLOBAL = 2

/** MiGlassCompat 材质类型：1 为玻璃效果分支（并在下发时同步 SDF 边界尺寸）。 */
internal const val SHORTCUT_GLASS_MATERIAL_TYPE = 1

/** MiBlurCompat 视图模糊模式：1 为开启模糊，0 为关闭。 */
internal const val SHORTCUT_GLASS_BLUR_MODE = 1

/**
 * 成员混合模式 101：对应系统 `MiuiBlurUtils.setMemberBlendColor` 所用的基础背景混合模式。
 * 注：系统通知元素混合所用的三元组模式为 100(linear_light)、106(lab)、3(pure)，语义不同。
 */
internal const val SHORTCUT_GLASS_BLEND_MODE = 101
internal const val SHORTCUT_PURE_COLOR = 0x73FFFFFF
internal const val SHORTCUT_ICON_LIGHT_COLOR = Color.WHITE
internal const val SHORTCUT_ICON_DARK_COLOR = Color.BLACK
internal const val SHORTCUT_BACKGROUND_NONE = 0
internal const val SHORTCUT_BACKGROUND_PURE_COLOR = 1
internal const val SHORTCUT_BACKGROUND_ADVANCED_MATERIAL = 2
internal const val SHORTCUT_BACKGROUND_SOFT_GLASS = 3
internal const val SHORTCUT_ICON_COLOR_AUTO = 0
internal const val SHORTCUT_ICON_COLOR_LIGHT = 1
internal const val SHORTCUT_ICON_COLOR_DARK = 2
internal const val SHORTCUT_GLASS_TAG = "hyperlock.lockscreen.shortcut.glass"

/**
 * 柔光玻璃参数默认值，对齐系统锁屏通知组（`notification_glass_params_on_keyguard`）基线：
 * 默认白色内漫射（1, 1, 1 @ 0.15），模糊半径沿用 50 / 500。
 */
internal const val SOFT_GLASS_DEFAULT_COLOR = 0xFFFFFFFF.toInt()
internal const val SOFT_GLASS_DEFAULT_OPACITY = 15
internal const val SOFT_GLASS_DEFAULT_BLUR_RADIUS = 50
internal const val SOFT_GLASS_DEFAULT_BACKDROP_BLUR_RADIUS = 50
internal const val SOFT_GLASS_DEFAULT_LUMINANCE = 0.15f

/**
 * 高级材质参数默认值，对齐系统锁屏媒体卡片基线：
 * 基底色对齐 `media_notification_element_blend_keyguard_color_1` (#A0A0A0)，
 * 容器模糊半径对齐 `notification_container_blur_radius` (100dp)。
 */
internal const val ELEMENT_BLEND_DEFAULT_COLOR = 0xFFA0A0A0.toInt()
internal const val ELEMENT_BLEND_DEFAULT_OPACITY = 100
internal const val ELEMENT_BLEND_DEFAULT_BLUR_DP = 100

/**
 * 系统元素混合的三层参数资源名，对齐锁屏媒体卡片（`MediaViewBlurOnKeyguardEffect`）。
 */
internal val SYSTEM_ELEMENT_BLEND_COLOR_RESOURCES = listOf(
    "media_notification_element_blend_keyguard_color_1",
    "media_notification_element_blend_keyguard_color_2",
    "media_notification_element_blend_keyguard_color_3",
)
internal val SYSTEM_ELEMENT_BLEND_MODE_RESOURCES = listOf(
    "media_notification_blend_mode_linear_light",
    "media_notification_blend_mode_lab",
    "media_notification_blend_mode_pure",
)

/** 容器背景模糊半径资源与读取失败时的兜底默认值 (dp)。 */
internal const val SYSTEM_ELEMENT_BLEND_BLUR_RESOURCE = "notification_container_blur_radius"
internal const val FALLBACK_ELEMENT_BLEND_BLUR_DP = 100

/** 资源读取失败时的兜底颜色与模式三元组。 */
internal val FALLBACK_ELEMENT_BLEND_COLORS = intArrayOf(
    0xFFA0A0A0.toInt(),
    0xFFCCF200.toInt(),
    0x6EFFFFFF,
)
internal val FALLBACK_ELEMENT_BLEND_MODES = intArrayOf(100, 106, 3)

/** 元素混合使用的 view blur 与 background blur 模式。 */
internal const val ELEMENT_BLEND_VIEW_BLUR_MODE = 1
internal const val ELEMENT_BLEND_BACKGROUND_BLUR_MODE = 1



/**
 * 轮廓高光参数向量，布局对齐 `HyperBloomStrokeUtils.setBloomStrokeConfig`：
 * 下标 0 为 bloomStrokeWidth，下标 6 为 normalWidth（单位为 dp，需按屏幕密度动态换算）；
 * 其余槽位为渐变角度、颜色及两组光源坐标。
 */
internal val SHORTCUT_BLOOM_STROKE_DP = floatArrayOf(
    0.8f, 180f, 1f, 1f, 1f, 0.05f, 1.2f, 0.5f, 0.5f, -0.5f, 1f,
    1f, 1f, 0.6f, 0.5f, 0.95f, -0.5f, 1f, 1f, 1f, 0.35f,
)

private const val BLOOM_STROKE_WIDTH_INDEX = 0
private const val BLOOM_STROKE_NORMAL_WIDTH_INDEX = 6

/**
 * 生成下发给 `View.setMiBloomStroke` 的高光参数，dp 槽位按 `dp * density + 0.5` 换算。
 */
internal fun shortcutBloomStrokeParameters(density: Float): FloatArray {
    val params = SHORTCUT_BLOOM_STROKE_DP.clone()
    params[BLOOM_STROKE_WIDTH_INDEX] = params[BLOOM_STROKE_WIDTH_INDEX] * density + 0.5f
    params[BLOOM_STROKE_NORMAL_WIDTH_INDEX] = params[BLOOM_STROKE_NORMAL_WIDTH_INDEX] * density + 0.5f
    return params
}

/**
 * 注销轮廓高光时下发的等长全零参数（对齐 `HyperBloomStrokeUtils.clearBloomStroke`）。
 */
internal fun clearedBloomStrokeParameters(): FloatArray = FloatArray(SHORTCUT_BLOOM_STROKE_DP.size)

internal const val KEY_REMOVE_DEPTH_IMAGE_LIMIT = "remove_depth_image_limit"
internal const val KEY_NOTIFICATION_FOD_POSITION_LIMIT_REMOVED =
    "notification_fod_position_limit_removed"
internal const val KEY_FINGERPRINT_HIDE_MODE = "fingerprint_hide_mode"
internal const val KEY_LOCKSCREEN_BOTTOM_TEXT_MASK = "lockscreen_bottom_text_mask"
internal const val LOCKSCREEN_TEXT_CHARGING = 1
internal const val LOCKSCREEN_TEXT_DND = 2
internal const val LOCKSCREEN_TEXT_NOTIFICATIONS = 4
internal const val KEY_LOCKSCREEN_PIN_CIRCLE_BACKGROUND_ENABLED =
    "lockscreen_pin_circle_background_enabled"
internal const val KEY_LOCKSCREEN_PIN_CIRCLE_ROW_SPACING =
    "lockscreen_pin_circle_row_spacing"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_ENABLED = "lockscreen_mini_player_enabled"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_MODE =
    "lockscreen_mini_player_lyrics_mode"
internal const val LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_OFF = 0
internal const val LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_ALWAYS = 1
internal const val LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_MANUAL = 2
internal const val KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_SHOW_TRANSLATION =
    "lockscreen_mini_player_lyrics_show_translation"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_SHOW_ROMA =
    "lockscreen_mini_player_lyrics_show_roma"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_TEXT_SIZE =
    "lockscreen_mini_player_lyrics_text_size"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_GAP =
    "lockscreen_mini_player_lyrics_gap"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_DETAIL_GAP =
    "lockscreen_mini_player_lyrics_detail_gap"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_SHADOW =
    "lockscreen_mini_player_lyrics_shadow"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_HIDE_ON_NOTIFICATION =
    "lockscreen_mini_player_lyrics_hide_on_notification"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_LEAD_MS =
    "lockscreen_mini_player_lyrics_lead_ms"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_KARAOKE =
    "lockscreen_mini_player_lyrics_karaoke"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE =
    "lockscreen_mini_player_media_notification_mode"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_BACKGROUND_MODE =
    "lockscreen_mini_player_background_mode"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_WIDTH = "lockscreen_mini_player_width"
internal const val KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT = "lockscreen_mini_player_height"
internal const val KEY_MINI_PLAYER_PURE_COLOR = "mini_player_pure_color"
internal const val KEY_MINI_PLAYER_ADVANCED_MATERIAL_COLOR = "mini_player_advanced_material_color"
internal const val KEY_MINI_PLAYER_ADVANCED_MATERIAL_OPACITY =
    "mini_player_advanced_material_opacity"
internal const val KEY_MINI_PLAYER_ADVANCED_MATERIAL_BLUR_RADIUS =
    "mini_player_advanced_material_blur_radius"
internal const val KEY_MINI_PLAYER_ADVANCED_MATERIAL_HIGHLIGHT =
    "mini_player_advanced_material_highlight"
internal const val KEY_MINI_PLAYER_SOFT_GLASS_COLOR = "mini_player_soft_glass_color"
internal const val KEY_MINI_PLAYER_SOFT_GLASS_OPACITY = "mini_player_soft_glass_opacity"
internal const val KEY_MINI_PLAYER_SOFT_GLASS_BACKDROP_BLUR_RADIUS =
    "mini_player_soft_glass_backdrop_blur_radius"
internal const val KEY_MINI_PLAYER_SOFT_GLASS_BLUR_RADIUS = "mini_player_soft_glass_blur_radius"
internal const val KEY_MINI_PLAYER_SOFT_GLASS_LUMINANCE = "mini_player_soft_glass_luminance"
internal const val KEY_REMOVE_CLOCK_MATERIAL_LIMIT = "remove_clock_material_limit"
internal const val KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_MODE = "lockscreen_shortcut_background_mode"
internal const val KEY_LOCKSCREEN_SHORTCUT_GLASS_RADIUS = "lockscreen_shortcut_glass_radius"
internal const val KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS_ENABLED =
    "lockscreen_shortcut_background_radius_enabled"
internal const val KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS =
    "lockscreen_shortcut_background_radius"
internal const val KEY_LOCKSCREEN_SHORTCUT_SPACING_ENABLED = "lockscreen_shortcut_spacing_enabled"
internal const val KEY_LOCKSCREEN_SHORTCUT_SPACING = "lockscreen_shortcut_spacing"
internal const val KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE_ENABLED = "lockscreen_shortcut_icon_size_enabled"
internal const val KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE = "lockscreen_shortcut_icon_size"
internal const val KEY_SHORTCUT_ICON_COLOR_MODE = "shortcut_icon_color_mode"
internal const val KEY_SHORTCUT_PURE_COLOR = "shortcut_pure_color"
internal const val KEY_SHORTCUT_PURE_OPACITY = "shortcut_pure_opacity"
internal const val KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR = "shortcut_advanced_material_color"
internal const val KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY = "shortcut_advanced_material_opacity"
internal const val KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS = "shortcut_advanced_material_blur_radius"
internal const val KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT = "shortcut_advanced_material_highlight"
internal const val KEY_SHORTCUT_SOFT_GLASS_COLOR = "shortcut_soft_glass_color"
internal const val KEY_SHORTCUT_SOFT_GLASS_OPACITY = "shortcut_soft_glass_opacity"
internal const val KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS = "shortcut_soft_glass_backdrop_blur_radius"
internal const val KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS = "shortcut_soft_glass_blur_radius"
internal const val KEY_SHORTCUT_SOFT_GLASS_LUMINANCE = "shortcut_soft_glass_luminance"

internal const val MINI_PLAYER_CONTENT_COLOR_AUTO = 0
internal const val MINI_PLAYER_CONTENT_COLOR_LIGHT = 1
internal const val MINI_PLAYER_CONTENT_COLOR_DARK = 2

internal const val KEY_MINI_PLAYER_CONTENT_COLOR_MODE = "mini_player_content_color_mode"
