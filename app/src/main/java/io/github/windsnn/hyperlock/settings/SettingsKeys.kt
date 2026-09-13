package io.github.windsnn.hyperlock.settings

import android.graphics.Color


internal const val FINGERPRINT_HIDE_NONE = 0
internal const val FINGERPRINT_HIDE_LOCKSCREEN = 1
internal const val FINGERPRINT_HIDE_GLOBAL = 2
/** 已核对：MiGlassCompat 对 i == 1 走玻璃分支（同时下发 SDF 尺寸），系统各 *GlassEffect 也传 1。 */
internal const val SHORTCUT_GLASS_MATERIAL_TYPE = 1

/** 已核对：MiBlurCompat.setMiViewBlurModeCompat(1, view) 表示开启模糊，0 表示关闭。 */
internal const val SHORTCUT_GLASS_BLUR_MODE = 1

/**
 * 混合模式 101 在本版本确有出处：`com.miui.clock.utils.MiuiBlurUtils.setMemberBlendColor`
 * 与 `setMemberBlendColors` 都用 `addMiBackgroundBlendColor(color, 101)` 做文字/成员混合，
 * 另以 103/105 追加暗色层。系统资源里的 3（pure）、100（linear_light）、106（lab）
 * 是通知元素混合用的三元组（NotificationUtil.applyElementViewBlend），语义不同，不能直接替换。
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
 * 材质档的默认值就是系统原值：用户第一次打开时这些参数等于系统自身使用的取值，
 * 只有主动拖动滑块后才会与系统值不同。
 *
 * 柔光玻璃取本 ROM 锁屏组（`notification_glass_params_on_keyguard`）的下标 4 与 11..14
 * （白色内漫射 1,1,1 @ 0.15），模糊沿用系统玻璃面的 50 / 500（即 Glass 50、背景模糊度 50）。
 */
internal const val SOFT_GLASS_DEFAULT_COLOR = 0xFFFFFFFF.toInt()
internal const val SOFT_GLASS_DEFAULT_OPACITY = 15
internal const val SOFT_GLASS_DEFAULT_BLUR_RADIUS = 50
internal const val SOFT_GLASS_DEFAULT_BACKDROP_BLUR_RADIUS = 50
internal const val SOFT_GLASS_DEFAULT_LUMINANCE = 0.15f

/**
 * 高级材质的三项默认同样等于系统值：基底色取
 * `media_notification_element_blend_keyguard_color_1`（#A0A0A0），
 * 系统元素混合不做 alpha 缩放（100%），容器模糊半径取
 * `notification_container_blur_radius`（100dp）。
 */
internal const val ELEMENT_BLEND_DEFAULT_COLOR = 0xFFA0A0A0.toInt()
internal const val ELEMENT_BLEND_DEFAULT_OPACITY = 100
internal const val ELEMENT_BLEND_DEFAULT_BLUR_DP = 100

/**
 * 系统元素混合的三层：颜色与模式分别取自这两组资源，
 * 锁屏媒体卡片（`MediaViewBlurOnKeyguardEffect`）用的就是它们。
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

/** 容器模糊半径资源（`applyContainerViewBlur` 未显式传值时使用）与资源缺失时的兜底值。 */
internal const val SYSTEM_ELEMENT_BLEND_BLUR_RESOURCE = "notification_container_blur_radius"
internal const val FALLBACK_ELEMENT_BLEND_BLUR_DP = 100

/** 资源读取失败时的兜底三元组，取自本 ROM 的 `media_notification_element_blend_keyguard_*`。 */
internal val FALLBACK_ELEMENT_BLEND_COLORS = intArrayOf(
    0xFFA0A0A0.toInt(),
    0xFFCCF200.toInt(),
    0x6EFFFFFF,
)
internal val FALLBACK_ELEMENT_BLEND_MODES = intArrayOf(100, 106, 3)

/** 元素混合使用的 view blur mode 与背景模糊 mode，与系统调用点一致。 */
internal const val ELEMENT_BLEND_VIEW_BLUR_MODE = 1
internal const val ELEMENT_BLEND_BACKGROUND_BLUR_MODE = 1



/**
 * 轮廓高光参数，布局与系统 `miuix.core.util.HyperBloomStrokeUtils.setBloomStrokeConfig` 一致：
 * 下标 0 = bloomStrokeWidth、下标 6 = normalWidth，两者单位是 dp，需要按屏幕密度换算；
 * 其余槽位是渐变角度、颜色与两组光源坐标。
 *
 * 数值取自 `miuix.theme.token.BloomStrokeToken.Glass_Stroke_Small_Light`。
 * 下标 1、7、9 是 HyperLock 的定向调整（渐变角度与光源位置），非系统原值。
 * 原先下标 0 与 6 存的是预先换算好的像素（3 与 8），换任何非 3.0 密度的机型都会失真，
 * 现在改为保存 dp 并在运行时按系统公式换算。
 */
internal val SHORTCUT_BLOOM_STROKE_DP = floatArrayOf(
    0.8f, 180f, 1f, 1f, 1f, 0.05f, 1.2f, 0.5f, 0.5f, -0.5f, 1f,
    1f, 1f, 0.6f, 0.5f, 0.95f, -0.5f, 1f, 1f, 1f, 0.35f,
)

private const val BLOOM_STROKE_WIDTH_INDEX = 0
private const val BLOOM_STROKE_NORMAL_WIDTH_INDEX = 6

/**
 * 生成下发给 `View.setMiBloomStroke` 的参数，dp 槽位按系统算法换算：`dp * density + 0.5`。
 */
internal fun shortcutBloomStrokeParameters(density: Float): FloatArray {
    val params = SHORTCUT_BLOOM_STROKE_DP.clone()
    params[BLOOM_STROKE_WIDTH_INDEX] = params[BLOOM_STROKE_WIDTH_INDEX] * density + 0.5f
    params[BLOOM_STROKE_NORMAL_WIDTH_INDEX] = params[BLOOM_STROKE_NORMAL_WIDTH_INDEX] * density + 0.5f
    return params
}

/**
 * 关闭轮廓高光时下发给 `View.setMiBloomStroke` 的全零参数。
 *
 * 系统自己的清理入口就是这一份数据：`miuix.core.util.HyperBloomStrokeUtils.clearBloomStroke`
 * 内部即 `setBloomStroke(view, EMPTY)`，EMPTY 是与 [SHORTCUT_BLOOM_STROKE_DP] 同长度的全零数组。
 * View 收到后会把已注册的高光描边整个注销；若关闭时不下发，复用中的承载层会一直保留最后一次
 * 注册的高光（表现为「关掉开关后播放器仍有高光，重启作用域才消失」）。
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
