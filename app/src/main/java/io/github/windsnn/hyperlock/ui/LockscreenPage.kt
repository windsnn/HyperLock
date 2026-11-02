package io.github.windsnn.hyperlock.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.*
import io.github.windsnn.hyperlock.HookSettings
import io.github.windsnn.hyperlock.R
import io.github.windsnn.hyperlock.ScopeApplication
import io.github.windsnn.hyperlock.resetLockscreenDefaults
import io.github.windsnn.hyperlock.settings.LOCKSCREEN_TEXT_CHARGING
import io.github.windsnn.hyperlock.settings.LOCKSCREEN_TEXT_DND
import io.github.windsnn.hyperlock.settings.LOCKSCREEN_TEXT_NOTIFICATIONS
import kotlin.math.roundToInt

@Composable
internal fun Lock(
    s: HookSettings,
    activeScopes: Set<ScopeApplication>,
    update: ((HookSettings) -> HookSettings) -> Unit,
) {
    val context = LocalContext.current
    var showRestoreDefaultsDialog by remember { mutableStateOf(false) }
    var showBottomTextDialog by remember { mutableStateOf(false) }
    var showMiniPlayerGesturesDialog by remember { mutableStateOf(false) }

    AppPage(stringResource(R.string.page_lockscreen), onRestoreDefaults = if (activeScopes.isNotEmpty()) { { showRestoreDefaultsDialog = true } } else null) { p, scroll ->
        AppList(p, scroll, 88) {
            item {
                ScopeGroup(stringResource(R.string.group_depth_clock), ScopeApplication.AOD, activeScopes) {
                    SwitchPreference(
                        title = stringResource(R.string.pref_remove_depth_limit),
                        checked = s.removeDepthImageLimit,
                        onCheckedChange = { v -> update { it.copy(removeDepthImageLimit = v) } },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.pref_remove_clock_material_limit),
                        checked = s.removeClockMaterialLimit,
                        onCheckedChange = { v -> update { it.copy(removeClockMaterialLimit = v) } },
                    )
                }
            }
            item {
                ScopeGroup(stringResource(R.string.group_notification_fingerprint), ScopeApplication.SYSTEM_UI, activeScopes) {
                    SwitchPreference(
                        title = stringResource(R.string.pref_remove_notification_pos_limit),
                        checked = s.notificationFodPositionLimitRemoved,
                        onCheckedChange = { value ->
                            update { it.copy(notificationFodPositionLimitRemoved = value) }
                        },
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.pref_hide_fingerprint_icon),
                        items = listOf(
                            stringResource(R.string.fingerprint_hide_mode_none),
                            stringResource(R.string.fingerprint_hide_mode_lockscreen),
                            stringResource(R.string.fingerprint_hide_mode_global),
                        ),
                        selectedIndex = s.fingerprintHideMode,
                        onSelectedIndexChange = { value ->
                            update { it.copy(fingerprintHideMode = value) }
                        },
                    )
                }
            }
            item {
                ScopeGroup(stringResource(R.string.group_bottom_text), ScopeApplication.SYSTEM_UI, activeScopes) {
                    ArrowPreference(
                        title = stringResource(R.string.pref_hide_bottom_text),
                        summary = lockscreenBottomTextSummary(context, s.lockscreenBottomTextMask),
                        onClick = { showBottomTextDialog = true },
                    )
                }
            }
            item {
                ScopeGroup(stringResource(R.string.group_pin_entry), ScopeApplication.SYSTEM_UI, activeScopes) {
                    SwitchPreference(
                        title = stringResource(R.string.pref_pin_circle_background),
                        checked = s.lockscreenPinCircleBackgroundEnabled,
                        onCheckedChange = { value ->
                            update { it.copy(lockscreenPinCircleBackgroundEnabled = value) }
                        },
                    )
                    ExpandGroup(s.lockscreenPinCircleBackgroundEnabled) {
                        ParameterSlider(
                            title = stringResource(R.string.pref_pin_circle_row_spacing),
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
                ScopeGroup(stringResource(R.string.group_mini_player), ScopeApplication.SYSTEM_UI, activeScopes) {
                    SwitchPreference(
                        title = stringResource(R.string.pref_mini_player),
                        summary = stringResource(R.string.pref_mini_player_summary),
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
                            ArrowPreference(
                                title = stringResource(R.string.pref_mini_player_gestures),
                                onClick = { showMiniPlayerGesturesDialog = true },
                            )
                            OverlayDropdownPreference(
                                title = stringResource(R.string.pref_mini_player_media_notification),
                                items = listOf(
                                    stringResource(R.string.media_notification_mode_keep),
                                    stringResource(R.string.media_notification_mode_hide),
                                    stringResource(R.string.media_notification_mode_dynamic),
                                ),
                                selectedIndex = s.lockscreenMiniPlayerMediaNotificationMode,
                                onSelectedIndexChange = { value ->
                                    update { it.copy(lockscreenMiniPlayerMediaNotificationMode = value) }
                                },
                            )
                            OverlayDropdownPreference(
                                title = stringResource(R.string.pref_mini_player_content_color),
                                items = listOf(
                                    stringResource(R.string.color_mode_auto),
                                    stringResource(R.string.color_mode_light),
                                    stringResource(R.string.color_mode_dark),
                                ),
                                selectedIndex = s.miniPlayerContentColorMode,
                                onSelectedIndexChange = { value ->
                                    update { it.copy(miniPlayerContentColorMode = value) }
                                },
                            )
                            OverlayDropdownPreference(
                                title = stringResource(R.string.pref_mini_player_bg),
                                items = listOf(
                                    stringResource(R.string.player_bg_mode_follow),
                                    stringResource(R.string.player_bg_mode_pure),
                                    stringResource(R.string.player_bg_mode_advanced),
                                    stringResource(R.string.player_bg_mode_soft_glass),
                                ),
                                selectedIndex = s.lockscreenMiniPlayerBackgroundMode,
                                onSelectedIndexChange = { value ->
                                    update { it.copy(lockscreenMiniPlayerBackgroundMode = value) }
                                },
                            )
                            ParameterSlider(stringResource(R.string.pref_mini_player_width), s.lockscreenMiniPlayerWidth, 160f..360f, defaultValue = 240f) { value ->
                                update { it.copy(lockscreenMiniPlayerWidth = value) }
                            }
                            ParameterSlider(stringResource(R.string.pref_mini_player_height), s.lockscreenMiniPlayerHeight, 40f..80f, defaultValue = 48f) { value ->
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
                // 歌词卡片寄宿于迷你播放器，播放器关闭时联动隐藏整组配置
                ExpandGroup(s.lockscreenMiniPlayerEnabled) {
                    Group(stringResource(R.string.group_lyrics)) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.pref_lyrics_display),
                            summary = stringResource(R.string.pref_lyrics_display_summary),
                            items = listOf(
                                stringResource(R.string.lyrics_mode_off),
                                stringResource(R.string.lyrics_mode_always),
                                stringResource(R.string.lyrics_mode_long_press),
                            ),
                            selectedIndex = s.lockscreenMiniPlayerLyricsMode,
                            onSelectedIndexChange = { value ->
                                update { it.copy(lockscreenMiniPlayerLyricsMode = value) }
                            },
                        )
                        ExpandGroup(s.lockscreenMiniPlayerLyricsMode != 0) {
                            Column {
                                SwitchPreference(
                                    title = stringResource(R.string.pref_lyrics_translation),
                                    summary = stringResource(R.string.pref_lyrics_translation_summary),
                                    checked = s.lockscreenMiniPlayerLyricsShowTranslation,
                                    onCheckedChange = { value ->
                                        update { it.copy(lockscreenMiniPlayerLyricsShowTranslation = value) }
                                    },
                                )
                                SwitchPreference(
                                    title = stringResource(R.string.pref_lyrics_roma),
                                    summary = stringResource(R.string.pref_lyrics_roma_summary),
                                    checked = s.lockscreenMiniPlayerLyricsShowRoma,
                                    onCheckedChange = { value ->
                                        update { it.copy(lockscreenMiniPlayerLyricsShowRoma = value) }
                                    },
                                )
                                SwitchPreference(
                                    title = stringResource(R.string.pref_lyrics_karaoke),
                                    summary = stringResource(R.string.pref_lyrics_karaoke_summary),
                                    checked = s.lockscreenMiniPlayerLyricsKaraoke,
                                    onCheckedChange = { value ->
                                        update { it.copy(lockscreenMiniPlayerLyricsKaraoke = value) }
                                    },
                                )
                                ParameterSlider(stringResource(R.string.pref_lyrics_text_size), s.lockscreenMiniPlayerLyricsTextSize, 10f..20f, defaultValue = 13.5f, unit = "sp") { value ->
                                    update { it.copy(lockscreenMiniPlayerLyricsTextSize = value) }
                                }
                                ParameterSlider(stringResource(R.string.pref_lyrics_detail_gap), s.lockscreenMiniPlayerLyricsDetailGap, -8f..20f, defaultValue = 2f, unit = "dp") { value ->
                                    update { it.copy(lockscreenMiniPlayerLyricsDetailGap = value) }
                                }
                                ParameterSlider(stringResource(R.string.pref_lyrics_player_gap), s.lockscreenMiniPlayerLyricsGap, -4f..24f, defaultValue = 8f, unit = "dp") { value ->
                                    update { it.copy(lockscreenMiniPlayerLyricsGap = value) }
                                }
                                ParameterSlider(
                                    title = stringResource(R.string.pref_lyrics_lead_ms),
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
                                    title = stringResource(R.string.pref_lyrics_shadow),
                                    summary = stringResource(R.string.pref_lyrics_shadow_summary),
                                    checked = s.lockscreenMiniPlayerLyricsShadow,
                                    onCheckedChange = { value ->
                                        update { it.copy(lockscreenMiniPlayerLyricsShadow = value) }
                                    },
                                )
                                SwitchPreference(
                                    title = stringResource(R.string.pref_lyrics_hide_on_notification),
                                    summary = stringResource(R.string.pref_lyrics_hide_on_notification_summary),
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
                ScopeGroup(stringResource(R.string.group_shortcut_bg), ScopeApplication.SYSTEM_UI, activeScopes) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.pref_shortcut_bg),
                        items = listOf(
                            stringResource(R.string.shortcut_bg_mode_none),
                            stringResource(R.string.shortcut_bg_mode_pure),
                            stringResource(R.string.shortcut_bg_mode_advanced),
                            stringResource(R.string.shortcut_bg_mode_soft_glass),
                        ),
                        selectedIndex = s.lockscreenShortcutBackgroundMode,
                        onSelectedIndexChange = { value ->
                            update { it.copy(lockscreenShortcutBackgroundMode = value) }
                        },
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.pref_shortcut_icon_color),
                        items = listOf(
                            stringResource(R.string.color_mode_auto),
                            stringResource(R.string.color_mode_light),
                            stringResource(R.string.color_mode_dark),
                        ),
                        selectedIndex = s.shortcutIconColorMode,
                        onSelectedIndexChange = { value -> update { it.copy(shortcutIconColorMode = value) } },
                    )
                    ExpandGroup(s.lockscreenShortcutBackgroundMode != 0) {
                        Column {
                            ParameterSlider(stringResource(R.string.pref_shortcut_glass_radius), s.lockscreenShortcutGlassRadius, 20f..56f, defaultValue = 26f) { v ->
                                update { it.copy(lockscreenShortcutGlassRadius = v) }
                            }
                            Dim(
                                title = stringResource(R.string.pref_shortcut_bg_corner_radius),
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
                        title = stringResource(R.string.pref_shortcut_spacing),
                        enabled = s.lockscreenShortcutSpacingEnabled,
                        changeEnabled = { value -> update { it.copy(lockscreenShortcutSpacingEnabled = value) } },
                        value = s.lockscreenShortcutSpacing,
                        range = -10f..40f,
                        defaultValue = 0f,
                    ) { value ->
                        update { it.copy(lockscreenShortcutSpacing = value) }
                    }
                    Dim(
                        title = stringResource(R.string.pref_shortcut_icon_size),
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
            Toast.makeText(context, context.getString(R.string.toast_defaults_restored), Toast.LENGTH_SHORT).show()
        },
    )
    MiniPlayerGesturesDialog(
        show = showMiniPlayerGesturesDialog,
        onDismiss = { showMiniPlayerGesturesDialog = false },
    )
}

private fun lockscreenBottomTextSummary(context: Context, mask: Int): String = listOf(
    LOCKSCREEN_TEXT_CHARGING to context.getString(R.string.bottom_text_charging),
    LOCKSCREEN_TEXT_DND to context.getString(R.string.bottom_text_dnd),
    LOCKSCREEN_TEXT_NOTIFICATIONS to context.getString(R.string.bottom_text_notifications),
).filter { mask and it.first != 0 }.joinToString(" / ") { it.second }.ifBlank { context.getString(R.string.bottom_text_none) }
