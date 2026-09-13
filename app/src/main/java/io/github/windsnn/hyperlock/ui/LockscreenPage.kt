package io.github.windsnn.hyperlock.ui

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.*
import io.github.windsnn.hyperlock.HookSettings
import io.github.windsnn.hyperlock.resetLockscreenDefaults
import io.github.windsnn.hyperlock.settings.LOCKSCREEN_TEXT_CHARGING
import io.github.windsnn.hyperlock.settings.LOCKSCREEN_TEXT_DND
import io.github.windsnn.hyperlock.settings.LOCKSCREEN_TEXT_NOTIFICATIONS
import kotlin.math.roundToInt

@Composable
internal fun Lock(
    s: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
) {
    val context = LocalContext.current
    var showRestoreDefaultsDialog by remember { mutableStateOf(false) }
    var showBottomTextDialog by remember { mutableStateOf(false) }

    AppPage("锁屏", onRestoreDefaults = { showRestoreDefaultsDialog = true }) { p, scroll ->
        AppList(p, scroll, 88) {
            item {
                Group("景深与时钟") {
                    SwitchPreference(
                        title = "去除景深限制",
                        checked = s.removeDepthImageLimit,
                        onCheckedChange = { v -> update { it.copy(removeDepthImageLimit = v) } },
                    )
                    SwitchPreference(
                        title = "解除“玻璃”时钟材质限制",
                        checked = s.removeClockMaterialLimit,
                        onCheckedChange = { v -> update { it.copy(removeClockMaterialLimit = v) } },
                    )
                }
            }
            item {
                Group("通知下沉与指纹") {
                    SwitchPreference(
                        title = "去除通知下沉位置限制",
                        checked = s.notificationFodPositionLimitRemoved,
                        onCheckedChange = { value ->
                            update { it.copy(notificationFodPositionLimitRemoved = value) }
                        },
                    )
                    OverlayDropdownPreference(
                        title = "隐藏指纹图标",
                        items = listOf("不隐藏", "仅锁屏隐藏", "全局隐藏"),
                        selectedIndex = s.fingerprintHideMode,
                        onSelectedIndexChange = { value ->
                            update { it.copy(fingerprintHideMode = value) }
                        },
                    )
                }
            }
            item {
                Group("锁屏底部文本") {
                    ArrowPreference(
                        title = "隐藏锁屏底部文本",
                        summary = lockscreenBottomTextSummary(s.lockscreenBottomTextMask),
                        onClick = { showBottomTextDialog = true },
                    )
                }
            }
            item {
                Group("锁屏输入密码界面") {
                    SwitchPreference(
                        title = "数字圆形背景",
                        checked = s.lockscreenPinCircleBackgroundEnabled,
                        onCheckedChange = { value ->
                            update { it.copy(lockscreenPinCircleBackgroundEnabled = value) }
                        },
                    )
                    ExpandGroup(s.lockscreenPinCircleBackgroundEnabled) {
                        ParameterSlider(
                            title = "按键行间距",
                            value = s.lockscreenPinCircleRowSpacing,
                            range = -16f..24f,
                            defaultValue = 0f,
                        ) { value ->
                            update { it.copy(lockscreenPinCircleRowSpacing = value) }
                        }
                    }
                }
            }
            item {
                Group("迷你音乐播放器") {
                    SwitchPreference(
                        title = "锁屏迷你音乐播放器",
                        summary = "显示在底部快捷按钮之间，跟随当前媒体会话",
                        checked = s.lockscreenMiniPlayerEnabled,
                        onCheckedChange = { value ->
                            update {
                                it.copy(
                                    lockscreenMiniPlayerEnabled = value,
                                )
                            }
                        },
                    )
                    ExpandGroup(s.lockscreenMiniPlayerEnabled) {
                        Column {
                            OverlayDropdownPreference(
                                title = "锁屏媒体通知",
                                items = listOf("不隐藏", "始终隐藏", "动态显示"),
                                selectedIndex = s.lockscreenMiniPlayerMediaNotificationMode,
                                onSelectedIndexChange = { value ->
                                    update { it.copy(lockscreenMiniPlayerMediaNotificationMode = value) }
                                },
                            )
                            OverlayDropdownPreference(
                                title = "播放器与歌词颜色",
                                items = listOf("自动模式", "浅色", "深色"),
                                selectedIndex = s.miniPlayerContentColorMode,
                                onSelectedIndexChange = { value ->
                                    update { it.copy(miniPlayerContentColorMode = value) }
                                },
                            )
                            OverlayDropdownPreference(
                                title = "迷你播放器背景",
                                items = listOf("跟随快捷功能背景", "纯色", "高级材质", "柔光玻璃"),
                                selectedIndex = s.lockscreenMiniPlayerBackgroundMode,
                                onSelectedIndexChange = { value ->
                                    update { it.copy(lockscreenMiniPlayerBackgroundMode = value) }
                                },
                            )
                            ParameterSlider("播放器宽度", s.lockscreenMiniPlayerWidth, 160f..360f, defaultValue = 240f) { value ->
                                update { it.copy(lockscreenMiniPlayerWidth = value) }
                            }
                            ParameterSlider("播放器高度", s.lockscreenMiniPlayerHeight, 40f..80f, defaultValue = 48f) { value ->
                                update { it.copy(lockscreenMiniPlayerHeight = value) }
                            }
                        }
                    }
                    ExpandGroup(s.lockscreenMiniPlayerEnabled && s.lockscreenMiniPlayerBackgroundMode != 0) {
                        Column {
                            MaterialParamGroup(
                                mode = s.lockscreenMiniPlayerBackgroundMode,
                                titles = MaterialParamTitles.PLAYER,
                                spec = s.miniPlayerMaterialSpec(),
                                showPureOpacity = false,
                            ) { spec -> update { it.withMiniPlayerMaterialSpec(spec) } }
                        }
                    }
                }
            }
            item {
                // 歌词依赖迷你播放器卡片存在（卡片位置与歌词订阅都由控制器创建），
                // 因此播放器关闭时整组隐藏，与拆分前的可见性语义保持一致。
                ExpandGroup(s.lockscreenMiniPlayerEnabled) {
                    Group("锁屏歌词") {
                        OverlayDropdownPreference(
                            title = "锁屏歌词显示",
                            summary = "选择锁屏歌词的触发与展示方式",
                            items = listOf("完全关闭", "常驻显示", "长按手动开关"),
                            selectedIndex = s.lockscreenMiniPlayerLyricsMode,
                            onSelectedIndexChange = { value ->
                                update { it.copy(lockscreenMiniPlayerLyricsMode = value) }
                            },
                        )
                        ExpandGroup(s.lockscreenMiniPlayerLyricsMode != 0) {
                            Column {
                                SwitchPreference(
                                    title = "显示歌词翻译",
                                    summary = "展示外语歌曲对应的中文译文",
                                    checked = s.lockscreenMiniPlayerLyricsShowTranslation,
                                    onCheckedChange = { value ->
                                        update { it.copy(lockscreenMiniPlayerLyricsShowTranslation = value) }
                                    },
                                )
                                SwitchPreference(
                                    title = "显示罗马音",
                                    summary = "展示日韩等语种歌曲的罗马拼音",
                                    checked = s.lockscreenMiniPlayerLyricsShowRoma,
                                    onCheckedChange = { value ->
                                        update { it.copy(lockscreenMiniPlayerLyricsShowRoma = value) }
                                    },
                                )
                                SwitchPreference(
                                    title = "逐字染色（卡拉OK）",
                                    summary = "随人声演唱进度逐字点亮高亮染色；关闭后整句歌词保持常亮，仅进行平滑推镜",
                                    checked = s.lockscreenMiniPlayerLyricsKaraoke,
                                    onCheckedChange = { value ->
                                        update { it.copy(lockscreenMiniPlayerLyricsKaraoke = value) }
                                    },
                                )
                                ParameterSlider("歌词字号大小", s.lockscreenMiniPlayerLyricsTextSize, 10f..20f, defaultValue = 13.5f, unit = "sp") { value ->
                                    update { it.copy(lockscreenMiniPlayerLyricsTextSize = value) }
                                }
                                ParameterSlider("歌词与副行间距", s.lockscreenMiniPlayerLyricsDetailGap, -8f..20f, defaultValue = 2f, unit = "dp") { value ->
                                    update { it.copy(lockscreenMiniPlayerLyricsDetailGap = value) }
                                }
                                ParameterSlider("与播放器间距", s.lockscreenMiniPlayerLyricsGap, -4f..24f, defaultValue = 8f, unit = "dp") { value ->
                                    update { it.copy(lockscreenMiniPlayerLyricsGap = value) }
                                }
                                ParameterSlider(
                                    title = "歌词提前量",
                                    value = s.lockscreenMiniPlayerLyricsLeadMs.toFloat(),
                                    range = 0f..800f,
                                    defaultValue = 250f,
                                    decimals = 0,
                                    unit = "ms",
                                    spaceBeforeUnit = false,
                                ) { value ->
                                    update { it.copy(lockscreenMiniPlayerLyricsLeadMs = value.roundToInt()) }
                                }
                                SwitchPreference(
                                    title = "强化歌词阴影",
                                    summary = "根据文字深浅自适应增强边缘投影或柔光晕，显著提升复杂壁纸下的辨识度",
                                    checked = s.lockscreenMiniPlayerLyricsShadow,
                                    onCheckedChange = { value ->
                                        update { it.copy(lockscreenMiniPlayerLyricsShadow = value) }
                                    },
                                )
                                SwitchPreference(
                                    title = "有通知时自动隐藏",
                                    summary = "当锁屏收到常规通知卡片时自动隐藏浮动歌词，避免遮挡排版；划除通知后自动恢复",
                                    checked = s.lockscreenMiniPlayerLyricsHideOnNotification,
                                    onCheckedChange = { value ->
                                        update { it.copy(lockscreenMiniPlayerLyricsHideOnNotification = value) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item {
                Group("快捷功能背景") {
                    OverlayDropdownPreference(
                        title = "锁屏快捷功能背景",
                        items = listOf("不显示", "纯色", "高级材质", "柔光玻璃"),
                        selectedIndex = s.lockscreenShortcutBackgroundMode,
                        onSelectedIndexChange = { value ->
                            update { it.copy(lockscreenShortcutBackgroundMode = value) }
                        },
                    )
                    OverlayDropdownPreference(
                        title = "快捷按钮图标颜色",
                        items = listOf("自动模式", "浅色", "深色"),
                        selectedIndex = s.shortcutIconColorMode,
                        onSelectedIndexChange = { value -> update { it.copy(shortcutIconColorMode = value) } },
                    )
                    ExpandGroup(s.lockscreenShortcutBackgroundMode != 0) {
                        Column {
                            ParameterSlider("圆形半径", s.lockscreenShortcutGlassRadius, 20f..56f, defaultValue = 26f) { v ->
                                update { it.copy(lockscreenShortcutGlassRadius = v) }
                            }
                            Dim(
                                title = "背景圆角半径",
                                enabled = s.lockscreenShortcutBackgroundRadiusEnabled,
                                changeEnabled = { value ->
                                    update { it.copy(lockscreenShortcutBackgroundRadiusEnabled = value) }
                                },
                                value = s.lockscreenShortcutBackgroundRadius,
                                range = 0f..50f,
                                defaultValue = 24f,
                            ) { value ->
                                update { it.copy(lockscreenShortcutBackgroundRadius = value) }
                            }
                        }
                    }
                    Dim(
                        title = "快捷按钮间距",
                        enabled = s.lockscreenShortcutSpacingEnabled,
                        changeEnabled = { value -> update { it.copy(lockscreenShortcutSpacingEnabled = value) } },
                        value = s.lockscreenShortcutSpacing,
                        range = -10f..40f,
                        defaultValue = 0f,
                    ) { value ->
                        update { it.copy(lockscreenShortcutSpacing = value) }
                    }
                    Dim(
                        title = "快捷按钮图标大小",
                        enabled = s.lockscreenShortcutIconSizeEnabled,
                        changeEnabled = { value -> update { it.copy(lockscreenShortcutIconSizeEnabled = value) } },
                        value = s.lockscreenShortcutIconSize,
                        range = 20f..56f,
                        defaultValue = 32f,
                    ) { value ->
                        update { it.copy(lockscreenShortcutIconSize = value) }
                    }
                    ExpandGroup(s.lockscreenShortcutBackgroundMode != 0) {
                        Column {
                            MaterialParamGroup(
                                mode = s.lockscreenShortcutBackgroundMode,
                                titles = MaterialParamTitles(),
                                spec = s.shortcutMaterialSpec(),
                                showPureOpacity = true,
                            ) { spec -> update { it.withShortcutMaterialSpec(spec) } }
                        }
                    }
                }
            }
        }
    }
    LockScreenBottomTextDialog(
        show = showBottomTextDialog,
        mask = s.lockscreenBottomTextMask,
        onDismiss = { showBottomTextDialog = false },
        onApply = { value ->
            update { it.copy(lockscreenBottomTextMask = value) }
            showBottomTextDialog = false
        },
    )
    RestoreDefaultsDialog(
        show = showRestoreDefaultsDialog,
        onDismiss = { showRestoreDefaultsDialog = false },
        onConfirm = {
            update { it.resetLockscreenDefaults() }
            showRestoreDefaultsDialog = false
            Toast.makeText(context, "已恢复锁屏默认设置", Toast.LENGTH_SHORT).show()
        },
    )
}

private fun lockscreenBottomTextSummary(mask: Int): String = listOf(
    LOCKSCREEN_TEXT_CHARGING to "充电中",
    LOCKSCREEN_TEXT_DND to "勿扰",
    LOCKSCREEN_TEXT_NOTIFICATIONS to "X个通知",
).filter { mask and it.first != 0 }.joinToString(" / ") { it.second }.ifBlank { "未隐藏" }
