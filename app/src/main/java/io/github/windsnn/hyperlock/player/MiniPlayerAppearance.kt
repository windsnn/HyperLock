package io.github.windsnn.hyperlock.player

import io.github.windsnn.hyperlock.settings.ELEMENT_BLEND_DEFAULT_BLUR_DP
import io.github.windsnn.hyperlock.settings.ELEMENT_BLEND_DEFAULT_COLOR
import io.github.windsnn.hyperlock.settings.ELEMENT_BLEND_DEFAULT_OPACITY
import io.github.windsnn.hyperlock.settings.SOFT_GLASS_DEFAULT_BACKDROP_BLUR_RADIUS
import io.github.windsnn.hyperlock.settings.SOFT_GLASS_DEFAULT_BLUR_RADIUS
import io.github.windsnn.hyperlock.settings.SOFT_GLASS_DEFAULT_COLOR
import io.github.windsnn.hyperlock.settings.SOFT_GLASS_DEFAULT_LUMINANCE
import io.github.windsnn.hyperlock.settings.SOFT_GLASS_DEFAULT_OPACITY

internal const val MINI_PLAYER_BACKGROUND_DEFAULT = 0
internal const val MINI_PLAYER_BACKGROUND_PURE = 1
internal const val MINI_PLAYER_BACKGROUND_ADVANCED = 2
internal const val MINI_PLAYER_BACKGROUND_SOFT_GLASS = 3

internal data class MiniPlayerAppearance(
    val backgroundMode: Int,
    val widthDp: Float,
    val heightDp: Float,
    val pureColor: Int = 0x73000000,
    val advancedColor: Int = ELEMENT_BLEND_DEFAULT_COLOR,
    val advancedOpacity: Int = ELEMENT_BLEND_DEFAULT_OPACITY,
    val advancedBlurRadius: Int = ELEMENT_BLEND_DEFAULT_BLUR_DP,
    val advancedHighlight: Boolean = false,
    val softGlassColor: Int = SOFT_GLASS_DEFAULT_COLOR,
    val softGlassOpacity: Int = SOFT_GLASS_DEFAULT_OPACITY,
    val softGlassBackdropBlurRadius: Int = SOFT_GLASS_DEFAULT_BACKDROP_BLUR_RADIUS,
    val softGlassBlurRadius: Int = SOFT_GLASS_DEFAULT_BLUR_RADIUS,
    val softGlassLuminance: Float = SOFT_GLASS_DEFAULT_LUMINANCE,
    val contentColorMode: Int = 0,
    val isDarkContent: Boolean = false,
)

internal data class LockscreenLyricsAppearance(
    val showTranslation: Boolean = true,
    val showRoma: Boolean = false,
    val textSizeSp: Float = 13.5f,
    val gapDp: Float = 8f,
    val detailGapDp: Float = 2f,
    val shadowEnabled: Boolean = true,
    val isDarkContent: Boolean = false,
    val hideOnNotification: Boolean = true,
    /** 提前切行的时间（毫秒）：在下一行真正唱到之前就切过去，留出阅读第一个词的时间。 */
    val leadMs: Int = 250,
    /** 是否启用逐字卡拉OK点亮染色；关闭时整行文字统一高亮显示，仅进行平滑推镜。 */
    val karaokeEnabled: Boolean = true,
)
