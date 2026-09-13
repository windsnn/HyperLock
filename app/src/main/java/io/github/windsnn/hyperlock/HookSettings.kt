package io.github.windsnn.hyperlock

import android.content.Context
import io.github.windsnn.hyperlock.settings.*
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

const val REMOTE_PREFERENCE_GROUP = "hyperlock"
const val ACTION_RELOAD_SETTINGS = "io.github.windsnn.hyperlock.ACTION_RELOAD_SETTINGS"
const val PERMISSION_RELOAD_SETTINGS = "io.github.windsnn.hyperlock.permission.RELOAD_SETTINGS"

const val LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE = 0
const val LOCKSCREEN_MEDIA_NOTIFICATION_ALWAYS_HIDE = 1
const val LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC = 2


data class HookSettings(
    val removeDepthImageLimit: Boolean = false,
    val notificationFodPositionLimitRemoved: Boolean = false,
    val fingerprintHideMode: Int = 0,
    /** Bit mask: charging=1, do-not-disturb=2, notification count=4. */
    val lockscreenBottomTextMask: Int = 0,
    val lockscreenPinCircleBackgroundEnabled: Boolean = false,
    val lockscreenPinCircleRowSpacing: Float = 0f,
    val lockscreenShortcutBackgroundMode: Int = 0,
    val lockscreenShortcutGlassRadius: Float = 26f,
    val lockscreenShortcutBackgroundRadiusEnabled: Boolean = false,
    val lockscreenShortcutBackgroundRadius: Float = 24f,
    val lockscreenShortcutSpacingEnabled: Boolean = false,
    val lockscreenShortcutSpacing: Float = 0f,
    val lockscreenShortcutIconSizeEnabled: Boolean = false,
    val lockscreenShortcutIconSize: Float = 32f,
    val shortcutIconColorMode: Int = 0,
    val shortcutPureColor: Int = 0x73FFFFFF,
    val shortcutPureOpacity: Int = 45,
    val shortcutAdvancedMaterialColor: Int = ELEMENT_BLEND_DEFAULT_COLOR,
    val shortcutAdvancedMaterialOpacity: Int = ELEMENT_BLEND_DEFAULT_OPACITY,
    val shortcutAdvancedMaterialBlurRadius: Int = ELEMENT_BLEND_DEFAULT_BLUR_DP,
    val shortcutAdvancedMaterialHighlight: Boolean = false,
    val shortcutSoftGlassColor: Int = SOFT_GLASS_DEFAULT_COLOR,
    val shortcutSoftGlassOpacity: Int = SOFT_GLASS_DEFAULT_OPACITY,
    val shortcutSoftGlassBackdropBlurRadius: Int = SOFT_GLASS_DEFAULT_BACKDROP_BLUR_RADIUS,
    val shortcutSoftGlassBlurRadius: Int = SOFT_GLASS_DEFAULT_BLUR_RADIUS,
    val shortcutSoftGlassLuminance: Float = SOFT_GLASS_DEFAULT_LUMINANCE,
    val lockscreenMiniPlayerEnabled: Boolean = false,
    val lockscreenMiniPlayerLyricsMode: Int = LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_OFF,
    val lockscreenMiniPlayerLyricsShowTranslation: Boolean = true,
    val lockscreenMiniPlayerLyricsShowRoma: Boolean = false,
    val lockscreenMiniPlayerLyricsTextSize: Float = 13.5f,
    val lockscreenMiniPlayerLyricsGap: Float = 8f,
    val lockscreenMiniPlayerLyricsDetailGap: Float = 2f,
    val lockscreenMiniPlayerLyricsShadow: Boolean = true,
    val lockscreenMiniPlayerLyricsHideOnNotification: Boolean = true,
    val lockscreenMiniPlayerLyricsLeadMs: Int = 250,
    val lockscreenMiniPlayerLyricsKaraoke: Boolean = true,
    val lockscreenMiniPlayerMediaNotificationMode: Int = LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE,
    val lockscreenMiniPlayerBackgroundMode: Int = 0,
    val miniPlayerContentColorMode: Int = MINI_PLAYER_CONTENT_COLOR_AUTO,
    val lockscreenMiniPlayerWidth: Float = 240f,
    val lockscreenMiniPlayerHeight: Float = 48f,
    val miniPlayerPureColor: Int = 0x73000000,
    val miniPlayerAdvancedMaterialColor: Int = ELEMENT_BLEND_DEFAULT_COLOR,
    val miniPlayerAdvancedMaterialOpacity: Int = ELEMENT_BLEND_DEFAULT_OPACITY,
    val miniPlayerAdvancedMaterialBlurRadius: Int = ELEMENT_BLEND_DEFAULT_BLUR_DP,
    val miniPlayerAdvancedMaterialHighlight: Boolean = false,
    val miniPlayerSoftGlassColor: Int = SOFT_GLASS_DEFAULT_COLOR,
    val miniPlayerSoftGlassOpacity: Int = SOFT_GLASS_DEFAULT_OPACITY,
    val miniPlayerSoftGlassBackdropBlurRadius: Int = SOFT_GLASS_DEFAULT_BACKDROP_BLUR_RADIUS,
    val miniPlayerSoftGlassBlurRadius: Int = SOFT_GLASS_DEFAULT_BLUR_RADIUS,
    val miniPlayerSoftGlassLuminance: Float = SOFT_GLASS_DEFAULT_LUMINANCE,
    val removeClockMaterialLimit: Boolean = false,
    val themeMode: String = "system",
    val verboseLog: Boolean = false,
) {
}

class HookSettingsStore(private val context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: HookSettings = local.toSettings()
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        // 服务未连接期间改过设置时以本地为准上报，避免用户的改动被远端旧值静默覆盖。
        val pendingLocalWrite = local.getBoolean(KEY_PENDING_LOCAL_WRITE, false) &&
            remote.contains(KEY_INITIALIZED)
        settings = if (!pendingLocalWrite && remote.contains(KEY_INITIALIZED)) {
            remote.toSettings()
        } else {
            settings
        }
        remote.write(settings)
        local.write(settings)
        local.edit().putBoolean(KEY_PENDING_LOCAL_WRITE, false).apply()
    }

    fun update(service: XposedService?, transform: (HookSettings) -> HookSettings) {
        settings = transform(settings)
        local.write(settings)
        val remote = service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        if (remote == null) {
            // hook 侧只读远端偏好：服务未连接时先记下"待上报"，重连后由 syncRemote 推送。
            local.edit().putBoolean(KEY_PENDING_LOCAL_WRITE, true).apply()
        } else {
            remote.write(settings)
            local.edit().putBoolean(KEY_PENDING_LOCAL_WRITE, false).apply()
        }
        runCatching {
            val intent = android.content.Intent(ACTION_RELOAD_SETTINGS).apply {
                setPackage("com.android.systemui")
            }
            // 接收端按同一权限校验发送方，避免第三方应用触发重载。
            context.sendBroadcast(intent, PERMISSION_RELOAD_SETTINGS)
        }
    }
}

private const val KEY_INITIALIZED = "initialized"
private const val KEY_PENDING_LOCAL_WRITE = "pending_local_write"
private const val KEY_THEME_MODE = "theme_mode"
const val KEY_VERBOSE_LOG = "verbose_log"

fun HookSettings.resetLockscreenDefaults(): HookSettings = HookSettings(
    themeMode = this.themeMode,
    verboseLog = this.verboseLog,
)

internal fun SharedPreferences.readMiniPlayerHeight(): Float =
    getFloat(KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT, 48f).coerceIn(40f, 80f)


fun SharedPreferences.toSettings(): HookSettings = HookSettings(
    removeDepthImageLimit = getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false),
    notificationFodPositionLimitRemoved = getBoolean(KEY_NOTIFICATION_FOD_POSITION_LIMIT_REMOVED, false),
    fingerprintHideMode = getInt(KEY_FINGERPRINT_HIDE_MODE, 0).coerceIn(0, 2),
    lockscreenBottomTextMask = getInt(KEY_LOCKSCREEN_BOTTOM_TEXT_MASK, 0).coerceIn(0, 7),
    lockscreenPinCircleBackgroundEnabled = getBoolean(KEY_LOCKSCREEN_PIN_CIRCLE_BACKGROUND_ENABLED, false),
    lockscreenPinCircleRowSpacing = getFloat(KEY_LOCKSCREEN_PIN_CIRCLE_ROW_SPACING, 0f).coerceIn(-16f, 24f),
    lockscreenShortcutBackgroundMode = getInt(KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_MODE, 0).coerceIn(0, 3),
    lockscreenShortcutGlassRadius = getFloat(KEY_LOCKSCREEN_SHORTCUT_GLASS_RADIUS, 26f).coerceIn(20f, 56f),
    lockscreenShortcutBackgroundRadiusEnabled = getBoolean(KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS_ENABLED, false),
    lockscreenShortcutBackgroundRadius = getFloat(KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS, 24f).coerceIn(0f, 50f),
    lockscreenShortcutSpacingEnabled = getBoolean(KEY_LOCKSCREEN_SHORTCUT_SPACING_ENABLED, false),
    lockscreenShortcutSpacing = getFloat(KEY_LOCKSCREEN_SHORTCUT_SPACING, 0f).coerceIn(-10f, 40f),
    lockscreenShortcutIconSizeEnabled = getBoolean(KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE_ENABLED, false),
    lockscreenShortcutIconSize = getFloat(KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE, 32f).coerceIn(20f, 56f),
    shortcutIconColorMode = getInt(KEY_SHORTCUT_ICON_COLOR_MODE, 0).coerceIn(0, 2),
    shortcutPureColor = getInt(KEY_SHORTCUT_PURE_COLOR, 0x73FFFFFF),
    shortcutPureOpacity = getInt(KEY_SHORTCUT_PURE_OPACITY, 45).coerceIn(0, 100),
    shortcutAdvancedMaterialColor = getInt(
        KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR,
        ELEMENT_BLEND_DEFAULT_COLOR,
    ),
    shortcutAdvancedMaterialOpacity = getInt(
        KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY,
        ELEMENT_BLEND_DEFAULT_OPACITY,
    ).coerceIn(0, 100),
    shortcutAdvancedMaterialBlurRadius = getInt(
        KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS,
        ELEMENT_BLEND_DEFAULT_BLUR_DP,
    ).coerceIn(0, 100),
    shortcutAdvancedMaterialHighlight = getBoolean(KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT, false),
    shortcutSoftGlassColor = getInt(KEY_SHORTCUT_SOFT_GLASS_COLOR, SOFT_GLASS_DEFAULT_COLOR),
    shortcutSoftGlassOpacity = getInt(KEY_SHORTCUT_SOFT_GLASS_OPACITY, SOFT_GLASS_DEFAULT_OPACITY)
        .coerceIn(0, 100),
    shortcutSoftGlassBackdropBlurRadius = getInt(
        KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
        SOFT_GLASS_DEFAULT_BACKDROP_BLUR_RADIUS,
    )
        .coerceIn(0, 100),
    shortcutSoftGlassBlurRadius = getInt(KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS, SOFT_GLASS_DEFAULT_BLUR_RADIUS)
        .coerceIn(0, 100),
    shortcutSoftGlassLuminance = getFloat(
        KEY_SHORTCUT_SOFT_GLASS_LUMINANCE,
        SOFT_GLASS_DEFAULT_LUMINANCE,
    )
        .coerceIn(0f, 0.5f),
    lockscreenMiniPlayerEnabled = getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false),
    lockscreenMiniPlayerLyricsMode = getInt(
        KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_MODE,
        LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_OFF,
    ).coerceIn(LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_OFF, LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_MANUAL),
    lockscreenMiniPlayerLyricsShowTranslation = getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_SHOW_TRANSLATION, true),
    lockscreenMiniPlayerLyricsShowRoma = getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_SHOW_ROMA, false),
    lockscreenMiniPlayerLyricsTextSize = getFloat(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_TEXT_SIZE, 13.5f).coerceIn(10f, 20f),
    lockscreenMiniPlayerLyricsGap = getFloat(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_GAP, 8f).coerceIn(-4f, 24f),
    lockscreenMiniPlayerLyricsDetailGap = getFloat(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_DETAIL_GAP, 2f).coerceIn(-8f, 20f),
    lockscreenMiniPlayerLyricsShadow = getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_SHADOW, true),
    lockscreenMiniPlayerLyricsHideOnNotification = getBoolean(
        KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_HIDE_ON_NOTIFICATION,
        true,
    ),
    lockscreenMiniPlayerLyricsLeadMs =
        getInt(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_LEAD_MS, 250).coerceIn(0, 800),
    lockscreenMiniPlayerLyricsKaraoke = getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_KARAOKE, true),
    lockscreenMiniPlayerMediaNotificationMode = getInt(
        KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE,
        LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE,
    ).coerceIn(LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE, LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC),
    lockscreenMiniPlayerBackgroundMode = getInt(KEY_LOCKSCREEN_MINI_PLAYER_BACKGROUND_MODE, 0).coerceIn(0, 3),
    miniPlayerContentColorMode = getInt(KEY_MINI_PLAYER_CONTENT_COLOR_MODE, MINI_PLAYER_CONTENT_COLOR_AUTO).coerceIn(0, 2),
    lockscreenMiniPlayerWidth = getFloat(KEY_LOCKSCREEN_MINI_PLAYER_WIDTH, 240f).coerceIn(160f, 360f),
    lockscreenMiniPlayerHeight = readMiniPlayerHeight(),
    miniPlayerPureColor = getInt(KEY_MINI_PLAYER_PURE_COLOR, 0x73000000),
    miniPlayerAdvancedMaterialColor = getInt(
        KEY_MINI_PLAYER_ADVANCED_MATERIAL_COLOR,
        ELEMENT_BLEND_DEFAULT_COLOR,
    ),
    miniPlayerAdvancedMaterialOpacity = getInt(
        KEY_MINI_PLAYER_ADVANCED_MATERIAL_OPACITY,
        ELEMENT_BLEND_DEFAULT_OPACITY,
    ).coerceIn(0, 100),
    miniPlayerAdvancedMaterialBlurRadius = getInt(
        KEY_MINI_PLAYER_ADVANCED_MATERIAL_BLUR_RADIUS,
        ELEMENT_BLEND_DEFAULT_BLUR_DP,
    ).coerceIn(0, 100),
    miniPlayerAdvancedMaterialHighlight = getBoolean(KEY_MINI_PLAYER_ADVANCED_MATERIAL_HIGHLIGHT, false),
    miniPlayerSoftGlassColor = getInt(KEY_MINI_PLAYER_SOFT_GLASS_COLOR, SOFT_GLASS_DEFAULT_COLOR),
    miniPlayerSoftGlassOpacity = getInt(KEY_MINI_PLAYER_SOFT_GLASS_OPACITY, SOFT_GLASS_DEFAULT_OPACITY)
        .coerceIn(0, 100),
    miniPlayerSoftGlassBackdropBlurRadius = getInt(
        KEY_MINI_PLAYER_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
        SOFT_GLASS_DEFAULT_BACKDROP_BLUR_RADIUS,
    )
        .coerceIn(0, 100),
    miniPlayerSoftGlassBlurRadius = getInt(KEY_MINI_PLAYER_SOFT_GLASS_BLUR_RADIUS, SOFT_GLASS_DEFAULT_BLUR_RADIUS)
        .coerceIn(0, 100),
    miniPlayerSoftGlassLuminance = getFloat(
        KEY_MINI_PLAYER_SOFT_GLASS_LUMINANCE,
        SOFT_GLASS_DEFAULT_LUMINANCE,
    )
        .coerceIn(0f, 0.5f),
    removeClockMaterialLimit = getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false),
    themeMode = getString(KEY_THEME_MODE, "system").orEmpty().ifBlank { "system" },
    verboseLog = getBoolean(KEY_VERBOSE_LOG, false),
)


fun SharedPreferences.write(value: HookSettings) {
    edit()
        .putBoolean(KEY_INITIALIZED, true)
        .putBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, value.removeDepthImageLimit)
        .putBoolean(KEY_NOTIFICATION_FOD_POSITION_LIMIT_REMOVED, value.notificationFodPositionLimitRemoved)
        .putInt(KEY_FINGERPRINT_HIDE_MODE, value.fingerprintHideMode)
        .putInt(KEY_LOCKSCREEN_BOTTOM_TEXT_MASK, value.lockscreenBottomTextMask.coerceIn(0, 7))
        .putBoolean(KEY_LOCKSCREEN_PIN_CIRCLE_BACKGROUND_ENABLED, value.lockscreenPinCircleBackgroundEnabled)
        .putFloat(KEY_LOCKSCREEN_PIN_CIRCLE_ROW_SPACING, value.lockscreenPinCircleRowSpacing.coerceIn(-16f, 24f))
        .putInt(KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_MODE, value.lockscreenShortcutBackgroundMode)
        .putFloat(KEY_LOCKSCREEN_SHORTCUT_GLASS_RADIUS, value.lockscreenShortcutGlassRadius.coerceIn(20f, 56f))
        .putBoolean(KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS_ENABLED, value.lockscreenShortcutBackgroundRadiusEnabled)
        .putFloat(KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS, value.lockscreenShortcutBackgroundRadius.coerceIn(0f, 50f))
        .putBoolean(KEY_LOCKSCREEN_SHORTCUT_SPACING_ENABLED, value.lockscreenShortcutSpacingEnabled)
        .putFloat(KEY_LOCKSCREEN_SHORTCUT_SPACING, value.lockscreenShortcutSpacing.coerceIn(-10f, 40f))
        .putBoolean(KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE_ENABLED, value.lockscreenShortcutIconSizeEnabled)
        .putFloat(KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE, value.lockscreenShortcutIconSize.coerceIn(20f, 56f))
        .putInt(KEY_SHORTCUT_ICON_COLOR_MODE, value.shortcutIconColorMode)
        .putInt(KEY_SHORTCUT_PURE_COLOR, value.shortcutPureColor)
        .putInt(KEY_SHORTCUT_PURE_OPACITY, value.shortcutPureOpacity)
        .putInt(KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR, value.shortcutAdvancedMaterialColor)
        .putInt(
            KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY,
            value.shortcutAdvancedMaterialOpacity.coerceIn(0, 100),
        )
        .putInt(
            KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS,
            value.shortcutAdvancedMaterialBlurRadius.coerceIn(0, 100),
        )
        .putBoolean(KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT, value.shortcutAdvancedMaterialHighlight)
        .putInt(KEY_SHORTCUT_SOFT_GLASS_COLOR, value.shortcutSoftGlassColor)
        .putInt(KEY_SHORTCUT_SOFT_GLASS_OPACITY, value.shortcutSoftGlassOpacity.coerceIn(0, 100))
        .putInt(
            KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
            value.shortcutSoftGlassBackdropBlurRadius.coerceIn(0, 100),
        )
        .putInt(
            KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS,
            value.shortcutSoftGlassBlurRadius.coerceIn(0, 100),
        )
        .putFloat(
            KEY_SHORTCUT_SOFT_GLASS_LUMINANCE,
            value.shortcutSoftGlassLuminance.coerceIn(0f, 0.5f),
        )
        .putBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, value.lockscreenMiniPlayerEnabled)
        .putInt(
            KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_MODE,
            value.lockscreenMiniPlayerLyricsMode.coerceIn(
                LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_OFF,
                LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_MANUAL,
            ),
        )
        .putBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_SHOW_TRANSLATION, value.lockscreenMiniPlayerLyricsShowTranslation)
        .putBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_SHOW_ROMA, value.lockscreenMiniPlayerLyricsShowRoma)
        .putFloat(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_TEXT_SIZE, value.lockscreenMiniPlayerLyricsTextSize.coerceIn(10f, 20f))
        .putFloat(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_GAP, value.lockscreenMiniPlayerLyricsGap.coerceIn(-4f, 24f))
        .putFloat(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_DETAIL_GAP, value.lockscreenMiniPlayerLyricsDetailGap.coerceIn(-8f, 20f))
        .putBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_SHADOW, value.lockscreenMiniPlayerLyricsShadow)
        .putBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_HIDE_ON_NOTIFICATION, value.lockscreenMiniPlayerLyricsHideOnNotification)
        .putInt(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_LEAD_MS, value.lockscreenMiniPlayerLyricsLeadMs.coerceIn(0, 800))
        .putBoolean(KEY_LOCKSCREEN_MINI_PLAYER_LYRICS_KARAOKE, value.lockscreenMiniPlayerLyricsKaraoke)
        .putInt(
            KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE,
            value.lockscreenMiniPlayerMediaNotificationMode.coerceIn(
                LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE,
                LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC,
            ),
        )
        .putInt(KEY_LOCKSCREEN_MINI_PLAYER_BACKGROUND_MODE, value.lockscreenMiniPlayerBackgroundMode)
        .putInt(KEY_MINI_PLAYER_CONTENT_COLOR_MODE, value.miniPlayerContentColorMode)
        .putFloat(KEY_LOCKSCREEN_MINI_PLAYER_WIDTH, value.lockscreenMiniPlayerWidth.coerceIn(160f, 360f))
        .putFloat(KEY_LOCKSCREEN_MINI_PLAYER_HEIGHT, value.lockscreenMiniPlayerHeight.coerceIn(40f, 80f))
        .putInt(KEY_MINI_PLAYER_PURE_COLOR, value.miniPlayerPureColor)
        .putInt(KEY_MINI_PLAYER_ADVANCED_MATERIAL_COLOR, value.miniPlayerAdvancedMaterialColor)
        .putInt(
            KEY_MINI_PLAYER_ADVANCED_MATERIAL_OPACITY,
            value.miniPlayerAdvancedMaterialOpacity.coerceIn(0, 100),
        )
        .putInt(
            KEY_MINI_PLAYER_ADVANCED_MATERIAL_BLUR_RADIUS,
            value.miniPlayerAdvancedMaterialBlurRadius.coerceIn(0, 100),
        )
        .putBoolean(KEY_MINI_PLAYER_ADVANCED_MATERIAL_HIGHLIGHT, value.miniPlayerAdvancedMaterialHighlight)
        .putInt(KEY_MINI_PLAYER_SOFT_GLASS_COLOR, value.miniPlayerSoftGlassColor)
        .putInt(
            KEY_MINI_PLAYER_SOFT_GLASS_OPACITY,
            value.miniPlayerSoftGlassOpacity.coerceIn(0, 100),
        )
        .putInt(
            KEY_MINI_PLAYER_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
            value.miniPlayerSoftGlassBackdropBlurRadius.coerceIn(0, 100),
        )
        .putInt(
            KEY_MINI_PLAYER_SOFT_GLASS_BLUR_RADIUS,
            value.miniPlayerSoftGlassBlurRadius.coerceIn(0, 100),
        )
        .putFloat(
            KEY_MINI_PLAYER_SOFT_GLASS_LUMINANCE,
            value.miniPlayerSoftGlassLuminance.coerceIn(0f, 0.5f),
        )
        .putBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, value.removeClockMaterialLimit)
        .putString(KEY_THEME_MODE, value.themeMode)
        .putBoolean(KEY_VERBOSE_LOG, value.verboseLog)
        .apply()
}
