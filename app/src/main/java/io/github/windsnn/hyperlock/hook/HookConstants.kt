package io.github.windsnn.hyperlock.hook

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.windsnn.hyperlock.hook.*
import io.github.windsnn.hyperlock.HyperLog
import io.github.windsnn.hyperlock.player.LockscreenLyricsNotificationBridge
import io.github.windsnn.hyperlock.player.LockscreenMiniPlayerController
import io.github.windsnn.hyperlock.settings.*
import java.util.Collections
import java.util.WeakHashMap

internal const val TAG = "HyperSystemUIHook"
internal const val SYSTEM_UI = "com.android.systemui"
internal const val AOD = "com.miui.aod"
internal val SYSTEM_UI_TARGETS = setOf(SYSTEM_UI, AOD)
internal const val DEPTH_EVALUATOR_CLASS =
    "com.miui.clock.utils.avoid.DepthAvoidEvaluator"
internal const val DEPTH_THRESHOLD_CLASS =
    "com.miui.clock.utils.avoid.DepthAvoidEvaluator\$Threshold"
internal const val HIERARCHY_AVOID_CONTROLLER_CLASS =
    "com.miui.keyguard.editor.utils.HierarchyImageAvoidController"
internal const val USER_OPEN_HIERARCHY_FIELD = "isUserOpenHierarchy"
internal const val FOD_SHELF_SPACE_FLOW_CLASS =
    "com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor\$useExtraShelfSpace\$1"
internal const val FOD_NOTIFICATION_POSITION_FLOW_PREFIX =
    "com.android.keyguard.panel.KeyguardPanelViewController\$nsslLockYPosition_delegate\$lambda\$"
internal const val FOD_NOTIFICATION_POSITION_FLOW_SUFFIX = "\$\$inlined\$combine\$1"
internal val FOD_NOTIFICATION_POSITION_FLOW_ORDINAL_RANGE = 64..160
internal const val MIUI_GXZW_ICON_VIEW_CLASS =
    "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView"
internal const val MIUI_GXZW_ANIM_MANAGER_CLASS =
    "com.miui.keyguard.biometrics.fod.MiuiGxzwAnimManager"
internal const val FOD_DISMISS_ICON_METHOD = "dismissFingerpirntIcon"
internal const val MIUI_GXZW_KEYGUARD_AUTHEN_FIELD = "mKeyguardAuthen"
internal const val MIUI_GXZW_ANIMATION_ITEMS_FIELD = "mAnimItemMap"
internal const val MIUI_GXZW_FINGER_ICON_RESOURCE_METHOD = "getFingerIconResource"
internal const val MIUI_GXZW_RECOGNIZING_ANIM_ITEM_METHOD = "getRecognizingAnimItem"
internal const val KEYGUARD_DEPTH_INTERACTOR_CLASS =
    "com.android.keyguard.depth.KeyguardDepthInteractor"
internal const val KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS =
    "com.android.keyguard.panel.KeyguardPanelViewController"
internal const val WALLPAPER_INFO_CLASS =
    "com.android.keyguard.wallpaper.entity.WallpaperInfo"
internal const val LARGE_SCREEN_HIERARCHY_ENABLE_CLASS =
    "com.android.keyguard.wallpaper.entity.LargeScreenHierarchyEnable"
internal const val AOD_WALLPAPER_INFO_CLASS =
    "com.miui.keyguard.editor.data.bean.WallpaperInfo"
internal const val AOD_LARGE_SCREEN_HIERARCHY_ENABLE_CLASS =
    "com.miui.keyguard.editor.data.bean.LargeScreenHierarchyEnable"
internal const val WALLPAPER_CONTROLLER_CLASS =
    "com.miui.keyguard.editor.edit.wallpaper.WallpaperController"
internal const val WALLPAPER_CONTROLLER_COMPANION_CLASS =
    "com.miui.keyguard.editor.edit.wallpaper.WallpaperController\$Companion"
internal const val KEYGUARD_INDICATION_CONTROLLER_CLASS =
    "com.android.systemui.statusbar.KeyguardIndicationController"
internal const val NOTIFICATION_NUM_STATE_VIEW_CLASS =
    "com.miui.systemui.notification.view.NotificationNumStateView"
internal const val NUM_STATE_VIEW_ANIMATE_EXT_CLASS =
    "com.miui.systemui.notification.ext.NumStateViewAnimateExt"
internal const val MIUI_SHORTCUT_CONTROLLER_CLASS =
    "com.android.keyguard.shortcut.MiuiShortcutController"
internal const val SHORTCUT_VIEW_LAYOUT_CONTROLLER_CLASS =
    "com.miui.keyguard.shortcuts.controller.ShortcutViewLayoutController"
internal const val KEYGUARD_PIN_VIEW_CLASS = "com.android.keyguard.KeyguardPINView"
internal val LOCKSCREEN_PIN_KEY_IDS = setOf(
    "key0", "key1", "key2", "key3", "key4",
    "key5", "key6", "key7", "key8", "key9",
)
internal val LOCKSCREEN_PIN_ROW_IDS = listOf("row1", "row2", "row3", "row4")
internal const val LOCKSCREEN_PIN_CIRCLE_TAG = "hyperlock.lockscreen.pin.material"
internal const val LOCKSCREEN_PIN_CIRCLE_RIPPLE_COLOR = 0x40FFFFFF
internal const val PIN_CIRCLE_FALLBACK_FILL_ALPHA = 8
internal const val PIN_CIRCLE_EDGE_ALPHA = 24
internal const val PIN_CIRCLE_EDGE_WIDTH = 1
internal const val PIN_ROW_SPACING_MIN = -16f
internal const val PIN_ROW_SPACING_MAX = 24f
internal const val CENTER_EPSILON = 0.01f
internal const val EXPANDABLE_NOTIFICATION_ROW_CLASS =
    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
internal const val MIUI_MEDIA_HEADER_VIEW_CLASS =
    "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView"
internal const val MI_GLASS_COMPAT_CLASS = "com.miui.systemui.util.MiGlassCompat"
internal const val MI_BLUR_COMPAT_CLASS = "com.miui.systemui.util.MiBlurCompat"

/** KeyguardIndicationController 充电指示类型常量。 */
internal const val CHARGING_INDICATION_TYPE = 3
internal const val IMAGE_THRESHOLD_FIELD = "IMAGE_THRESHOLD"
internal const val THRESHOLD_RATE_FIELD = "rate"

/** DepthAvoidEvaluator.IMAGE_THRESHOLD Threshold.rate 判定阈值，1.0 表示解除景深避让比例限制。 */
internal const val UNLIMITED_DEPTH_IMAGE_THRESHOLD = 1.0

/**
 * 隐藏指纹图标时使用的资源 ID。
 *
 * MiuiGxzwFrameAnimation 将 0 视作空背景位图（null），从而安全隐藏图标。
 */
internal const val LOCKSCREEN_HIDDEN_FINGERPRINT_ICON_RESOURCE = 0
internal const val CLOCK_UTILITY_CLASS = "com.miui.clock.allInOne.AllInOneUtil"
internal const val CLOCK_UTILITY_METHOD = "applyOtaClockParams"
internal const val CLOCK_BEAN_CLASS = "com.miui.clock.module.ClockBean"

/**
 * 时钟材质效果类型枚举取值：
 * 0 纯色 / 1 混色 / 2 叠加 / 3 辉光 / 4 渐变模糊 / 5 玻璃。
 *
 * 系统在 `applyOtaClockParams` / `applyOtaColorEffect` 中会将非 0/2 效果统一降级改写为 1；
 * 模块将其还原为 5 以保留玻璃材质。
 */
internal const val CLOCK_EFFECT_GLASS = 5
/** 系统对非 0/2 时钟效果改写的降级目标值（容器透传模糊）。 */
internal const val CLOCK_EFFECT_BLUR_MIX = 1
internal const val DEFAULT_SHORTCUT_GLASS_RADIUS = 26f
internal const val MIN_SHORTCUT_GLASS_RADIUS = 20f
internal const val MAX_SHORTCUT_GLASS_RADIUS = 56f
internal const val MINI_PLAYER_PURE_COLOR = 0x73000000
internal const val DEFAULT_ADVANCED_MATERIAL_COLOR = 0xFFFFFFFF.toInt()
internal const val MAX_SHORTCUT_OPACITY = 100
internal const val MAX_SHORTCUT_BACKDROP_BLUR_RADIUS = 120
internal const val MAX_SHORTCUT_GLASS_BLUR_RADIUS = 100

/** 玻璃模糊半径上限（大模糊 = 背景模糊度 × 10，默认 50 -> 500）。 */
internal const val MAX_SHORTCUT_GLASS_LARGE_BLUR_RADIUS = 1000
internal const val MAX_SHORTCUT_GLASS_LUMINANCE = 0.5f
internal const val DEFAULT_ADVANCED_MATERIAL_OPACITY = 14
/** PIN 数字键圆形的系统玻璃基线（快捷功能与播放器的同项已改为「跟随系统」）。 */
internal const val DEFAULT_SOFT_GLASS_BLUR_RADIUS = 50
internal const val DEFAULT_SOFT_GLASS_LUMINANCE = 0.15f
internal const val LOCKSCREEN_SHORTCUT_RETRY_DELAY_MS = 250L
internal val LOCKSCREEN_SHORTCUT_CONTAINER_IDS = setOf(
    "shortcut_view_left_layout",
    "shortcut_view_right_layout",
)
internal const val GLASS_PARAM_COUNT = 42

/**
 * SystemUI 的玻璃参数并不写在代码里：`NotifiFullAodController.parseFloatArrayRes` 与各
 * `*GlassEffect` 都是从 string-array 资源里读出这 42 个浮点，所以资源才是当前 ROM 的唯一真值来源。
 * 按优先级从「锁屏静止态 → 锁屏焦点态 → 普通态」尝试，取到第一组可用参数。
 */
internal val SYSTEM_GLASS_PARAM_ARRAY_NAMES = listOf(
    "notification_glass_params_on_keyguard",
    "focus_notification_glass_params_on_keyguard",
    "notification_glass_params_normal",
)

/**
 * 资源读取失败时的兜底参数，取自 `notification_glass_params_on_keyguard` 在
 * HyperOS `17.03.260226.r` 上的取值。仅作为最后一道保险，正常运行时应始终走资源。
 */
internal val FALLBACK_GLASS_BASE_PARAMETERS = floatArrayOf(
    0f, 2f, .3f, .4f, .15f, 1.4f, .08f, .2f, .6f, 1f, .03f,
    1f, 1f, 1f, .15f, .15f, .4f, 1.2f, 1f, 72f, 3.8f,
    80f, 1000f, 1f, .8f, -.4f, .6f, -.8f, 1.3f, .6f, .8f,
    1.15f, 3f, 2.4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
)

internal var depthEffectHookInstalled = false
internal var lockscreenNotificationHookInstalled = false
internal var fingerprintIconHookInstalled = false
internal var systemUiDepthHookInstalled = false
internal var lockscreenChargingHookInstalled = false
internal var lockscreenShortcutGlassHookInstalled = false
internal var lockscreenPinCircleBackgroundHookInstalled = false
internal var systemUiClockMaterialLimitHookInstalled = false
internal var aodClockMaterialLimitHookInstalled = false
@Volatile internal var lockscreenMediaKeyguardShowing = false
internal val lockscreenRows = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
internal val lockscreenHiddenRows = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
/** 模块把媒体行压成 GONE 之前记下的原始 visibility，退出锁屏时按原值还原。 */
internal val lockscreenHiddenRowVisibility = Collections.synchronizedMap(
    WeakHashMap<View, Int>(),
)
internal val lockscreenMediaRows = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
internal val monitoredNotificationRows = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
/** 快捷功能容器原始 margin 记录，用于恢复默认边距。 */
internal val shortcutOriginalMargins = Collections.synchronizedMap(
    WeakHashMap<View, IntArray>(),
)
/** 已完成单行属性归一化的 PIN 键英文字母副标题。 */
internal val lockscreenPinKlondikeNormalized = Collections.newSetFromMap(WeakHashMap<TextView, Boolean>())
internal var updateActiveNotificationsPosted = false

internal val lockscreenNotificationRowAttachListener = object : View.OnAttachStateChangeListener {
    override fun onViewAttachedToWindow(v: View) {
        scheduleUpdateActiveLockscreenNotifications()
    }
    override fun onViewDetachedFromWindow(v: View) {
        synchronized(lockscreenRows) {
            lockscreenRows -= v
            lockscreenMediaRows -= v
            lockscreenHiddenRows -= v
        }
        scheduleUpdateActiveLockscreenNotifications()
    }
}

internal val lockscreenNotificationRowLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
    scheduleUpdateActiveLockscreenNotifications()
}

internal fun ensureNotificationRowListeners(row: View) {
    synchronized(monitoredNotificationRows) {
        if (monitoredNotificationRows.add(row)) {
            row.addOnAttachStateChangeListener(lockscreenNotificationRowAttachListener)
            row.addOnLayoutChangeListener(lockscreenNotificationRowLayoutListener)
        }
    }
}

internal fun scheduleUpdateActiveLockscreenNotifications() {
    if (updateActiveNotificationsPosted) return
    updateActiveNotificationsPosted = true
    mainHandler.post {
        updateActiveNotificationsPosted = false
        updateActiveLockscreenNotifications()
    }
}

internal fun updateActiveLockscreenNotifications() {
    val snapshot = synchronized(lockscreenRows) { lockscreenRows.toList() }
    // 仅检测稳定状态：忽略动画中间态，检查已附着且可见的非媒体通知行
    val hasActive = snapshot.any { row ->
        row !in lockscreenMediaRows &&
            row !in lockscreenHiddenRows &&
            row.visibility == View.VISIBLE &&
            row.isAttachedToWindow
    }
    if (LockscreenLyricsNotificationBridge.hasActiveNotifications != hasActive) {
        HyperLog.i(TAG, "Lockscreen notifications active=$hasActive (rows=${snapshot.size})")
    }
    LockscreenLyricsNotificationBridge.setHasActiveNotifications(hasActive)
}
internal val lockscreenMediaHeaders = Collections.synchronizedSet(
    Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
)
internal val lockscreenMiniPlayerControllers = Collections.synchronizedMap(
    WeakHashMap<View, LockscreenMiniPlayerController>(),
)
internal val fodEnrollmentFlowOverrides = WeakHashMap<Any, Any>()
internal val shortcutRoots = Collections.synchronizedSet(
    Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
)
@Volatile internal var lastShortcutController: Any? = null
@Volatile internal var reloadReceiverRegistered = false
internal var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
internal val mainHandler = Handler(Looper.getMainLooper())
internal var pendingReloadRunnable: Runnable? = null
internal val bounceBoundContainers = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
