package io.github.windsnn.hyperlock.hook

import android.content.SharedPreferences
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.ImageView
import io.github.windsnn.hyperlock.HyperSystemUiModule
import io.github.windsnn.hyperlock.material.applyNativeSoftGlassMaterial
import io.github.windsnn.hyperlock.material.applySystemElementBlendMaterial
import io.github.windsnn.hyperlock.material.clearBloomStroke
import io.github.windsnn.hyperlock.material.clearPlatformMaterial
import io.github.windsnn.hyperlock.material.clearSoftGlassShader
import io.github.windsnn.hyperlock.player.LockscreenLyricsAppearance
import io.github.windsnn.hyperlock.player.LockscreenMiniPlayerController
import io.github.windsnn.hyperlock.player.MINI_PLAYER_BACKGROUND_ADVANCED
import io.github.windsnn.hyperlock.player.MINI_PLAYER_BACKGROUND_DEFAULT
import io.github.windsnn.hyperlock.player.MINI_PLAYER_BACKGROUND_SOFT_GLASS
import io.github.windsnn.hyperlock.player.MiniPlayerAppearance
import io.github.windsnn.hyperlock.readMiniPlayerHeight
import io.github.windsnn.hyperlock.settings.*
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_MINI_PLAYER_BACKGROUND_MODE
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_MINI_PLAYER_ENABLED
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_GAP
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_HIDE_ON_NOTIFICATION
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_KARAOKE
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_LEAD_MS
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_SHADOW
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_SHOW_ROMA
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_SHOW_TRANSLATION
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_TEXT_SIZE
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_MINI_PLAYER_WIDTH
import io.github.windsnn.hyperlock.settings.KEY_MINI_PLAYER_ADVANCED_MATERIAL_BLUR_RADIUS
import io.github.windsnn.hyperlock.settings.KEY_MINI_PLAYER_ADVANCED_MATERIAL_COLOR
import io.github.windsnn.hyperlock.settings.KEY_MINI_PLAYER_ADVANCED_MATERIAL_HIGHLIGHT
import io.github.windsnn.hyperlock.settings.KEY_MINI_PLAYER_ADVANCED_MATERIAL_OPACITY
import io.github.windsnn.hyperlock.settings.KEY_MINI_PLAYER_PURE_COLOR
import io.github.windsnn.hyperlock.settings.KEY_MINI_PLAYER_SOFT_GLASS_BACKDROP_BLUR_RADIUS
import io.github.windsnn.hyperlock.settings.KEY_MINI_PLAYER_SOFT_GLASS_BLUR_RADIUS
import io.github.windsnn.hyperlock.settings.KEY_MINI_PLAYER_SOFT_GLASS_COLOR
import io.github.windsnn.hyperlock.settings.KEY_MINI_PLAYER_SOFT_GLASS_LUMINANCE
import io.github.windsnn.hyperlock.settings.KEY_MINI_PLAYER_SOFT_GLASS_OPACITY
import io.github.windsnn.hyperlock.settings.KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS
import io.github.windsnn.hyperlock.settings.KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR
import io.github.windsnn.hyperlock.settings.KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT
import io.github.windsnn.hyperlock.settings.KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY
import io.github.windsnn.hyperlock.settings.KEY_SHORTCUT_PURE_COLOR
import io.github.windsnn.hyperlock.settings.KEY_SHORTCUT_PURE_OPACITY
import io.github.windsnn.hyperlock.settings.KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS
import io.github.windsnn.hyperlock.settings.KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS
import io.github.windsnn.hyperlock.settings.KEY_SHORTCUT_SOFT_GLASS_COLOR
import io.github.windsnn.hyperlock.settings.KEY_SHORTCUT_SOFT_GLASS_LUMINANCE
import io.github.windsnn.hyperlock.settings.KEY_SHORTCUT_SOFT_GLASS_OPACITY
import io.github.windsnn.hyperlock.settings.SHORTCUT_BACKGROUND_NONE
import io.github.windsnn.hyperlock.settings.SHORTCUT_PURE_COLOR

internal fun HyperSystemUiModule.installLockscreenMiniPlayer(
    root: View,
    shortcutController: Any?,
    preferences: SharedPreferences,
    classLoader: ClassLoader,
) {
    val shortcuts = findShortcutContainers(root)
    val legacyShortcuts = if (shortcuts.size >= 2) {
        val left = shortcuts.firstOrNull { it.idName() == "shortcut_view_left_layout" } ?: shortcuts[0]
        val right = shortcuts.firstOrNull { it.idName() == "shortcut_view_right_layout" } ?: shortcuts[1]
        left to right
    } else {
        null
    }
    // The plugin may recreate its shortcut content after addShortcutViews returns. Ask the
    // controller for the actual views rather than relying only on the plugin's layout IDs.
    val controllerShortcuts = resolveLockscreenShortcutViews(shortcutController)
    val (left, right) = controllerShortcuts ?: legacyShortcuts ?: return
    if (left === right) return
    val parent = commonShortcutParent(left, right) ?: return
    val old = synchronized(lockscreenMiniPlayerControllers) {
        lockscreenMiniPlayerControllers[parent]
    }
    if (!preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false)) {
        old?.destroy()
        lockscreenMiniPlayerControllers.remove(parent)
        return
    }
    if (old != null && !old.isDestroyed) return
    if (old != null) {
        // 控制器已在宿主 detach 时销毁，但注册表还留着它：先摘掉，下面重建。
        synchronized(lockscreenMiniPlayerControllers) {
            lockscreenMiniPlayerControllers.remove(parent)
        }
    }
    val controller = LockscreenMiniPlayerController(
        host = parent,
        leftShortcut = left,
        rightShortcut = right,
        enabled = { preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false) },
        lyricsMode = {
            preferences.getInt(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_MODE, LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_OFF)
                .coerceIn(LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_OFF, LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_MANUAL)
        },
        mediaNotificationMode = { lockscreenMediaNotificationMode(preferences) },
        appearance = { miniPlayerAppearance(preferences) },
        lyricsAppearance = { lockscreenLyricsAppearance(preferences) },
        applyPlatformMaterial = { view, appearance ->
            runCatching {
                applyMiniPlayerMaterial(view, appearance, classLoader)
            }.onFailure { error ->
                hookLog(Log.ERROR, TAG, "Could not initialize mini player material", error)
            }
        },
        onDestroyed = { destroyed ->
            // 只有当前登记的确实是它时才移除，避免误删同一宿主上刚重建的控制器。
            synchronized(lockscreenMiniPlayerControllers) {
                if (lockscreenMiniPlayerControllers[parent] === destroyed) {
                    lockscreenMiniPlayerControllers.remove(parent)
                }
            }
        },
    )
    synchronized(lockscreenMiniPlayerControllers) {
        lockscreenMiniPlayerControllers[parent] = controller
    }
}

internal fun HyperSystemUiModule.scheduleLockscreenMiniPlayerInstallation(
    root: View,
    shortcutController: Any?,
    preferences: SharedPreferences,
    classLoader: ClassLoader,
) {
    registerSettingsReloadSlot(LockscreenMiniPlayerReloadSlot(this))
    registerMiniPlayerColorCoordinatorListener(preferences, classLoader)
    fun install() {
        runCatching {
            installLockscreenMiniPlayer(root, shortcutController, preferences, classLoader)
        }.onFailure { error ->
            hookLog(Log.ERROR, TAG, "Could not apply lockscreen mini player", error)
        }
    }
    install()
    // 18.2.2.2.0 can finish rebuilding its shortcut content after the controller method
    // returns. Retry after attachment and after the next layout pass without retaining a
    // hierarchy listener for the lifetime of SystemUI.
    root.post(::install)
    root.postDelayed(::install, LOCKSCREEN_SHORTCUT_RETRY_DELAY_MS)
}

/** 设置变化后重建缺失的播放器卡片，并让已登记的控制器重读外观、尺寸与歌词。 */
internal class LockscreenMiniPlayerReloadSlot(private val module: HyperSystemUiModule) : SettingsReloadSlot {
    override val id = "lockscreen-mini-player"

    override fun reload(preferences: SharedPreferences, classLoader: ClassLoader) {
        if (preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false)) {
            val roots = synchronized(shortcutRoots) { shortcutRoots.toList() }
            for (root in roots) {
                if (!root.isAttachedToWindow && root.parent == null) continue
                module.scheduleLockscreenMiniPlayerInstallation(
                    root = root,
                    shortcutController = lastShortcutController,
                    preferences = preferences,
                    classLoader = classLoader,
                )
            }
        }
        val controllers = synchronized(lockscreenMiniPlayerControllers) {
            lockscreenMiniPlayerControllers.values.toList()
        }
        for (controller in controllers) {
            runCatching {
                controller.reloadSettings()
            }.onFailure { error ->
                module.hookLog(Log.ERROR, TAG, "Could not reload mini player settings", error)
            }
        }
    }
}

internal fun HyperSystemUiModule.resolveLockscreenShortcutViews(shortcutController: Any?): Pair<View, View>? = runCatching {
    val controller = shortcutController ?: return@runCatching null
    val action = controller.javaClass.methods.firstOrNull {
        it.name == "onSystemUIAction\$1" && it.parameterCount == 2
    } ?: return@runCatching null
    fun shortcut(isLeft: Boolean): View? {
        val bundle = android.os.Bundle().apply { putBoolean("isLeftShortcutView", isLeft) }
        return action.invoke(controller, bundle, "getShortcutView") as? View
    }
    val left = shortcut(true) ?: return@runCatching null
    val right = shortcut(false) ?: return@runCatching null
    left to right
}.getOrNull()

internal fun HyperSystemUiModule.miniPlayerAppearance(preferences: SharedPreferences): MiniPlayerAppearance {
    val width = preferences.getFloat(KEY_LOCKSCREEN_MINI_PLAYER_WIDTH, 240f).coerceIn(80f, 480f)
    val height = preferences.readMiniPlayerHeight()
    val requestedMode = preferences.getInt(KEY_LOCKSCREEN_MINI_PLAYER_BACKGROUND_MODE, 0).coerceIn(0, 3)
    val contentColorMode = preferences.getInt(KEY_MINI_PLAYER_CONTENT_COLOR_MODE, MINI_PLAYER_CONTENT_COLOR_AUTO).coerceIn(0, 2)
    val isDarkContent = when (contentColorMode) {
        MINI_PLAYER_CONTENT_COLOR_LIGHT -> false
        MINI_PLAYER_CONTENT_COLOR_DARK -> true
        else -> !LockscreenColorCoordinator.isBottomDeep
    }


    // Mirrors the shortcut renderer exactly: the pure colour's own alpha is ignored and the
    // "透明度" slider decides the alpha, so both sides stay identical.
    fun shortcutAppearance(mode: Int): MiniPlayerAppearance {
        val shortcutPureColor = preferences.getInt(KEY_SHORTCUT_PURE_COLOR, SHORTCUT_PURE_COLOR)
        val shortcutPureOpacity = preferences.getInt(KEY_SHORTCUT_PURE_OPACITY, 45).coerceIn(0, 100)
        val shortcutPureAlpha = (shortcutPureOpacity * 255 / 100).coerceIn(0, 255)
        return MiniPlayerAppearance(
            backgroundMode = mode,
            widthDp = width,
            heightDp = height,
            pureColor = Color.argb(
                shortcutPureAlpha,
                Color.red(shortcutPureColor),
                Color.green(shortcutPureColor),
                Color.blue(shortcutPureColor),
            ),
            advancedColor = preferences.getInt(
                KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR,
                ELEMENT_BLEND_DEFAULT_COLOR,
            ),
            advancedOpacity = preferences.getInt(
                KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY,
                ELEMENT_BLEND_DEFAULT_OPACITY,
            ),
            advancedBlurRadius = preferences.getInt(
                KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS,
                ELEMENT_BLEND_DEFAULT_BLUR_DP,
            ),
            advancedHighlight = preferences.getBoolean(KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT, false),
            softGlassColor = preferences.getInt(KEY_SHORTCUT_SOFT_GLASS_COLOR, SOFT_GLASS_DEFAULT_COLOR),
            softGlassOpacity = preferences.getInt(
                KEY_SHORTCUT_SOFT_GLASS_OPACITY,
                SOFT_GLASS_DEFAULT_OPACITY,
            ),
            softGlassBackdropBlurRadius = preferences.getInt(
                KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
                SOFT_GLASS_DEFAULT_BACKDROP_BLUR_RADIUS,
            ),
            softGlassBlurRadius = preferences.getInt(
                KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS,
                SOFT_GLASS_DEFAULT_BLUR_RADIUS,
            ),
            softGlassLuminance = preferences.getFloat(
                KEY_SHORTCUT_SOFT_GLASS_LUMINANCE,
                SOFT_GLASS_DEFAULT_LUMINANCE,
            ),
            contentColorMode = contentColorMode,
            isDarkContent = isDarkContent,
        )
    }
    if (requestedMode == MINI_PLAYER_BACKGROUND_DEFAULT) {
        val shortcutMode = shortcutBackgroundMode(preferences)
        return if (shortcutMode == SHORTCUT_BACKGROUND_NONE) {
            MiniPlayerAppearance(
                backgroundMode = MINI_PLAYER_BACKGROUND_DEFAULT,
                widthDp = width,
                heightDp = height,
                contentColorMode = contentColorMode,
                isDarkContent = isDarkContent,
            )
        } else {
            shortcutAppearance(shortcutMode)
        }
    }
    val pureColorConfigured = preferences.getInt(KEY_MINI_PLAYER_PURE_COLOR, MINI_PLAYER_PURE_COLOR)
    return MiniPlayerAppearance(
        backgroundMode = requestedMode,
        widthDp = width,
        heightDp = height,
        pureColor = pureColorConfigured,
        advancedColor = preferences.getInt(
            KEY_MINI_PLAYER_ADVANCED_MATERIAL_COLOR,
            ELEMENT_BLEND_DEFAULT_COLOR,
        ),
        advancedOpacity = preferences.getInt(
            KEY_MINI_PLAYER_ADVANCED_MATERIAL_OPACITY,
            ELEMENT_BLEND_DEFAULT_OPACITY,
        ),
        advancedBlurRadius = preferences.getInt(
            KEY_MINI_PLAYER_ADVANCED_MATERIAL_BLUR_RADIUS,
            ELEMENT_BLEND_DEFAULT_BLUR_DP,
        ),
        advancedHighlight = preferences.getBoolean(KEY_MINI_PLAYER_ADVANCED_MATERIAL_HIGHLIGHT, false),
        softGlassColor = preferences.getInt(KEY_MINI_PLAYER_SOFT_GLASS_COLOR, SOFT_GLASS_DEFAULT_COLOR),
        softGlassOpacity = preferences.getInt(
            KEY_MINI_PLAYER_SOFT_GLASS_OPACITY,
            SOFT_GLASS_DEFAULT_OPACITY,
        ),
        softGlassBackdropBlurRadius = preferences.getInt(
            KEY_MINI_PLAYER_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
            SOFT_GLASS_DEFAULT_BACKDROP_BLUR_RADIUS,
        ),
        softGlassBlurRadius = preferences.getInt(
            KEY_MINI_PLAYER_SOFT_GLASS_BLUR_RADIUS,
            SOFT_GLASS_DEFAULT_BLUR_RADIUS,
        ),
        softGlassLuminance = preferences.getFloat(
            KEY_MINI_PLAYER_SOFT_GLASS_LUMINANCE,
            SOFT_GLASS_DEFAULT_LUMINANCE,
        ),
        contentColorMode = contentColorMode,
        isDarkContent = isDarkContent,
    )
}

internal fun HyperSystemUiModule.lockscreenLyricsAppearance(preferences: SharedPreferences): LockscreenLyricsAppearance {
    val contentColorMode = preferences.getInt(KEY_MINI_PLAYER_CONTENT_COLOR_MODE, MINI_PLAYER_CONTENT_COLOR_AUTO).coerceIn(0, 2)
    val isDarkContent = when (contentColorMode) {
        MINI_PLAYER_CONTENT_COLOR_LIGHT -> false
        MINI_PLAYER_CONTENT_COLOR_DARK -> true
        else -> !LockscreenColorCoordinator.isBottomDeep
    }

    return LockscreenLyricsAppearance(
        showTranslation = preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_SHOW_TRANSLATION, true),
        showRoma = preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_SHOW_ROMA, false),
        textSizeSp = preferences.getFloat(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_TEXT_SIZE, 13.5f).coerceIn(8f, 32f),
        gapDp = preferences.getFloat(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_GAP, 8f).coerceIn(-12f, 48f),
        detailGapDp = preferences.getFloat(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_DETAIL_GAP, 2f).coerceIn(-8f, 20f),
        shadowEnabled = preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_SHADOW, true),
        isDarkContent = isDarkContent,
        hideOnNotification = preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_HIDE_ON_NOTIFICATION, true),
        leadMs = preferences.getInt(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_LEAD_MS, 250).coerceIn(0, 800),
        karaokeEnabled = preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_KARAOKE, true),
    )
}

private var miniPlayerColorListenerRegistered = false

internal fun HyperSystemUiModule.registerMiniPlayerColorCoordinatorListener(
    preferences: SharedPreferences,
    classLoader: ClassLoader,
) {
    if (miniPlayerColorListenerRegistered) return
    miniPlayerColorListenerRegistered = true

    LockscreenColorCoordinator.addListener(object : LockscreenColorCoordinator.BottomColorListener {
        override fun onBottomDeepChanged(isBottomDeep: Boolean) {
            val controllers = synchronized(lockscreenMiniPlayerControllers) {
                lockscreenMiniPlayerControllers.values.toList()
            }
            for (controller in controllers) {
                runCatching {
                    controller.reloadSettings()
                }
            }
        }
    })
}

internal fun HyperSystemUiModule.applyMiniPlayerMaterial(
    view: ImageView,
    appearance: MiniPlayerAppearance,
    classLoader: ClassLoader,
) {
    // 承载层在所有档位间复用，切档前必须释放上一档的材质：
    // 高级材质留下的是 blend 色层 + 背景模糊，柔光玻璃留下的是 OS4 shader，
    // 只清其中一套会让高级材质切换后残留色层与模糊。
    when (appearance.backgroundMode) {
        // 柔光玻璃没有高光档位，上一档（高级材质）注册的轮廓高光要在这里注销，
        // 否则切到柔光玻璃后仍会画着高级材质的高光。
        MINI_PLAYER_BACKGROUND_SOFT_GLASS -> clearBloomStroke(view)
        MINI_PLAYER_BACKGROUND_ADVANCED -> clearSoftGlassShader(view, classLoader)
        else -> clearPlatformMaterial(view, classLoader)
    }
    when (appearance.backgroundMode) {
        MINI_PLAYER_BACKGROUND_ADVANCED -> applySystemElementBlendMaterial(
            view = view,
            opacity = appearance.advancedOpacity,
            blurRadiusDp = appearance.advancedBlurRadius,
            color = appearance.advancedColor,
            showHighlight = appearance.advancedHighlight,
        )
        MINI_PLAYER_BACKGROUND_SOFT_GLASS -> {
            applyNativeSoftGlassMaterial(
                view = view,
                classLoader = classLoader,
                blurRadius = appearance.softGlassBlurRadius,
                backdropBlurRadius = appearance.softGlassBackdropBlurRadius,
                luminance = appearance.softGlassLuminance,
                color = appearance.softGlassColor,
                opacity = appearance.softGlassOpacity,
            )
        }
    }
}
