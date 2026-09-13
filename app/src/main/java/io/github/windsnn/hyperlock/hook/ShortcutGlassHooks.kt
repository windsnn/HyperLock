package io.github.windsnn.hyperlock.hook

import android.content.res.ColorStateList
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Outline
import android.os.SystemClock
import android.util.Log
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import io.github.windsnn.hyperlock.hook.*
import io.github.windsnn.hyperlock.HyperSystemUiModule
import io.github.windsnn.hyperlock.material.applyNativeSoftGlassMaterial
import io.github.windsnn.hyperlock.material.applySystemElementBlendMaterial
import io.github.windsnn.hyperlock.player.performHapticFeedbackSafely
import io.github.windsnn.hyperlock.settings.*
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS_ENABLED
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_SHORTCUT_GLASS_RADIUS
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE_ENABLED
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_SHORTCUT_SPACING
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_SHORTCUT_SPACING_ENABLED
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
import io.github.windsnn.hyperlock.settings.SHORTCUT_BACKGROUND_ADVANCED_MATERIAL
import io.github.windsnn.hyperlock.settings.SHORTCUT_BACKGROUND_NONE
import io.github.windsnn.hyperlock.settings.SHORTCUT_BACKGROUND_PURE_COLOR
import io.github.windsnn.hyperlock.settings.SHORTCUT_BACKGROUND_SOFT_GLASS
import io.github.windsnn.hyperlock.settings.SHORTCUT_GLASS_TAG
import io.github.windsnn.hyperlock.settings.SHORTCUT_ICON_COLOR_AUTO
import io.github.windsnn.hyperlock.settings.SHORTCUT_ICON_COLOR_DARK
import io.github.windsnn.hyperlock.settings.SHORTCUT_ICON_COLOR_LIGHT
import io.github.windsnn.hyperlock.settings.SHORTCUT_ICON_DARK_COLOR
import io.github.windsnn.hyperlock.settings.SHORTCUT_ICON_LIGHT_COLOR
import io.github.windsnn.hyperlock.player.LockscreenKeyguardBridge
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.WeakHashMap

internal fun HyperSystemUiModule.installLockscreenShortcutGlassHook(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    runCatching {
        val controllerClass = classLoader.loadClass(MIUI_SHORTCUT_CONTROLLER_CLASS)
        val shortcutMethods = controllerClass.declaredMethods
            .filter { it.name == "addShortcutViews" && it.parameterCount == 1 }
        check(shortcutMethods.isNotEmpty()) { "MiuiShortcutController.addShortcutViews was not found" }
        shortcutMethods.forEachIndexed { index, method ->
            attachHook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-shortcut-glass-$index")
                .intercept { chain ->
                    val result = chain.proceed()
                    val root = chain.getArg(0) as? View ?: return@intercept result
                    shortcutRoots += root
                    lastShortcutController = chain.thisObject
                    registerReloadReceiver(root.context, classLoader, preferences)
                    runCatching {
                        LockscreenColorCoordinator.tryRefreshIfMissing()
                        // Keep the shortcut containers fully laid out even when the user
                        // selects "不显示". In that mode the layer is transparent, so this
                        // preserves geometry without adding a visible shortcut background.
                        installShortcutGlassBackgrounds(root, preferences, classLoader)
                    }.onFailure { error ->
                        hookLog(Log.ERROR, TAG, "Could not apply lockscreen shortcut glass", error)
                    }
                    runCatching {
                        applyLockscreenShortcutGeometry(root, preferences)
                    }.onFailure { error ->
                        hookLog(Log.ERROR, TAG, "Could not apply lockscreen shortcut geometry", error)
                    }
                    scheduleLockscreenMiniPlayerInstallation(
                        root = root,
                        shortcutController = chain.thisObject,
                        preferences = preferences,
                        classLoader = classLoader,
                    )
                    result
                }
        }
    }.onFailure { error ->
        hookLog(Log.ERROR, TAG, "Could not install lockscreen shortcut glass hook", error)
    }
    registerShortcutColorCoordinatorListener(preferences, classLoader)
    installLockscreenKeyguardStateHooks(classLoader)
    registerSettingsReloadSlot(LockscreenShortcutReloadSlot(this))
}

internal fun HyperSystemUiModule.installLockscreenKeyguardStateHooks(classLoader: ClassLoader) {
    runCatching {
        val callbackClass = classLoader.loadClass(
            "com.android.keyguard.shortcut.MiuiShortcutController\$miuiKeyguardUpdateMonitorCallback\$1",
        )
        val onShowingChanged = callbackClass.declaredMethods.firstOrNull {
            it.name == "onKeyguardShowingChanged" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        }
        if (onShowingChanged != null) {
            attachHook(onShowingChanged)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-keyguard-state:showing")
                .intercept { chain ->
                    val showing = chain.getArg(0) as? Boolean ?: true
                    LockscreenKeyguardBridge.setKeyguardState(showing = showing)
                    chain.proceed()
                }
        }
        val onOccludedChanged = callbackClass.declaredMethods.firstOrNull {
            it.name == "onKeyguardOccludedChanged" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        }
        if (onOccludedChanged != null) {
            attachHook(onOccludedChanged)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-keyguard-state:occluded")
                .intercept { chain ->
                    val occluded = chain.getArg(0) as? Boolean ?: false
                    LockscreenKeyguardBridge.setKeyguardState(occluded = occluded)
                    chain.proceed()
                }
        }
    }.onFailure { error ->
        hookLog(Log.WARN, TAG, "Could not install shortcut keyguard state hooks", error)
    }
}

/** 设置变化后就地重做快捷按钮的背景与几何；只处理仍挂在窗口上的快捷区。 */
internal class LockscreenShortcutReloadSlot(private val module: HyperSystemUiModule) : SettingsReloadSlot {
    override val id = "lockscreen-shortcut"

    override fun reload(preferences: SharedPreferences, classLoader: ClassLoader) {
        val roots = synchronized(shortcutRoots) { shortcutRoots.toList() }
        for (root in roots) {
            if (!root.isAttachedToWindow && root.parent == null) continue
            runCatching {
                module.installShortcutGlassBackgrounds(root, preferences, classLoader)
            }.onFailure { error ->
                module.hookLog(Log.ERROR, TAG, "Could not reload shortcut glass backgrounds", error)
            }
            runCatching {
                module.applyLockscreenShortcutGeometry(root, preferences)
            }.onFailure { error ->
                module.hookLog(Log.ERROR, TAG, "Could not reload shortcut geometry", error)
            }
        }
    }
}

internal fun HyperSystemUiModule.applyMaterialToTarget(
    target: View,
    backgroundMode: Int,
    backgroundRadiusEnabled: Boolean,
    backgroundRadius: Float,
    preferences: SharedPreferences,
    classLoader: ClassLoader,
) {
    when (backgroundMode) {
        SHORTCUT_BACKGROUND_NONE -> {
            target.background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        }
        SHORTCUT_BACKGROUND_PURE_COLOR -> {
            val color = preferences.getInt(KEY_SHORTCUT_PURE_COLOR, SHORTCUT_PURE_COLOR)
            val opacity = preferences.getInt(KEY_SHORTCUT_PURE_OPACITY, 45).coerceIn(0, 100)
            val targetColor = Color.argb(
                (opacity * 255 / 100).coerceIn(0, 255),
                Color.red(color),
                Color.green(color),
                Color.blue(color),
            )
            target.background = GradientDrawable().apply {
                setColor(targetColor)
            }
        }
        SHORTCUT_BACKGROUND_ADVANCED_MATERIAL -> {
            runCatching {
                applySystemElementBlendMaterial(
                    view = target,
                    opacity = preferences.getInt(
                        KEY_SHORTCUT_ADVANCED_MATERIAL_OPACITY,
                        ELEMENT_BLEND_DEFAULT_OPACITY,
                    ),
                    blurRadiusDp = preferences.getInt(
                        KEY_SHORTCUT_ADVANCED_MATERIAL_BLUR_RADIUS,
                        ELEMENT_BLEND_DEFAULT_BLUR_DP,
                    ),
                    color = preferences.getInt(
                        KEY_SHORTCUT_ADVANCED_MATERIAL_COLOR,
                        ELEMENT_BLEND_DEFAULT_COLOR,
                    ),
                    showHighlight = preferences.getBoolean(KEY_SHORTCUT_ADVANCED_MATERIAL_HIGHLIGHT, false),
                )
            }.onFailure { error -> hookLog(Log.ERROR, TAG, "Could not initialize shortcut backdrop on target", error) }
        }
        SHORTCUT_BACKGROUND_SOFT_GLASS -> {
            runCatching {
                applyNativeSoftGlassMaterial(
                    view = target,
                    classLoader = classLoader,
                    blurRadius = preferences.getInt(
                        KEY_SHORTCUT_SOFT_GLASS_BLUR_RADIUS,
                        SOFT_GLASS_DEFAULT_BLUR_RADIUS,
                    ),
                    backdropBlurRadius = preferences.getInt(
                        KEY_SHORTCUT_SOFT_GLASS_BACKDROP_BLUR_RADIUS,
                        SOFT_GLASS_DEFAULT_BACKDROP_BLUR_RADIUS,
                    ),
                    luminance = preferences.getFloat(
                        KEY_SHORTCUT_SOFT_GLASS_LUMINANCE,
                        SOFT_GLASS_DEFAULT_LUMINANCE,
                    ),
                    color = preferences.getInt(KEY_SHORTCUT_SOFT_GLASS_COLOR, SOFT_GLASS_DEFAULT_COLOR),
                    opacity = preferences.getInt(KEY_SHORTCUT_SOFT_GLASS_OPACITY, SOFT_GLASS_DEFAULT_OPACITY),
                )
            }.onFailure { error -> hookLog(Log.ERROR, TAG, "Could not initialize OS4 shortcut glass on target", error) }
        }
    }
    target.clipToOutline = true
    target.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            if (backgroundRadiusEnabled) {
                val radiusPx = backgroundRadius * view.resources.displayMetrics.density
                outline.setRoundRect(
                    0,
                    0,
                    view.width,
                    view.height,
                    radiusPx.coerceAtMost(minOf(view.width, view.height) / 2f),
                )
            } else {
                    outline.setOval(0, 0, view.width, view.height)
            }
        }
    }
    target.invalidateOutline()
}

internal fun HyperSystemUiModule.installShortcutGlassBackgrounds(
    root: View,
    preferences: SharedPreferences,
    classLoader: ClassLoader,
) {
    val backgroundMode = shortcutBackgroundMode(preferences)
    val radius = preferences.getFloat(KEY_LOCKSCREEN_SHORTCUT_GLASS_RADIUS, DEFAULT_SHORTCUT_GLASS_RADIUS)
        .coerceIn(MIN_SHORTCUT_GLASS_RADIUS, MAX_SHORTCUT_GLASS_RADIUS)
    val backgroundRadiusEnabled = preferences.getBoolean(
        KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS_ENABLED,
        false,
    )
    val backgroundRadius = preferences.getFloat(
        KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_RADIUS,
        24f,
    ).coerceIn(0f, 100f)
    val diameter = (radius * root.resources.displayMetrics.density).toInt().coerceAtLeast(1) * 2

    findShortcutContainers(root).forEach { shortcutContainer ->
        shortcutContainer.clipChildren = false
        shortcutContainer.clipToPadding = false
        var parent = shortcutContainer.parent
        while (parent is ViewGroup) {
            parent.clipChildren = false
            parent.clipToPadding = false
            if (parent === root) break
            parent = parent.parent
        }
        for (index in shortcutContainer.childCount - 1 downTo 0) {
            val child = shortcutContainer.getChildAt(index)
            if (child.tag == SHORTCUT_GLASS_TAG) shortcutContainer.removeViewAt(index)
        }

        val glassBackground = ImageView(shortcutContainer.context).apply {
            tag = SHORTCUT_GLASS_TAG
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            applyMaterialToTarget(
                target = this,
                backgroundMode = backgroundMode,
                backgroundRadiusEnabled = backgroundRadiusEnabled,
                backgroundRadius = backgroundRadius,
                preferences = preferences,
                classLoader = classLoader,
            )
        }

        shortcutContainer.addView(
            glassBackground,
            0,
            FrameLayout.LayoutParams(diameter, diameter, Gravity.CENTER),
        )

        applyShortcutIconColorMode(shortcutContainer, shortcutIconColorMode(preferences))
        bindShortcutBounceAnimation(shortcutContainer)
        shortcutContainer.requestLayout()
        shortcutContainer.invalidate()
    }

    log(
        Log.INFO,
        TAG,
        "Applied shortcut glass backgrounds: mode=$backgroundMode, radius=${radius}dp",
    )
}

internal fun HyperSystemUiModule.bindShortcutBounceAnimation(shortcutContainer: ViewGroup) {
    if (shortcutContainer in bounceBoundContainers) return
    bounceBoundContainers += shortcutContainer
    val isRight = shortcutContainer.idName() == "shortcut_view_right_layout"
    val touchSlop = ViewConfiguration.get(shortcutContainer.context).scaledTouchSlop
    var downX = 0f
    var downY = 0f
    var downTime = 0L
    var isSliding = false
    var lastClickTime = 0L

    val getController = {
        synchronized(lockscreenMiniPlayerControllers) {
            (shortcutContainer.parent as? View)?.let { lockscreenMiniPlayerControllers[it] }
                ?: lockscreenMiniPlayerControllers.values.firstOrNull()
        }
    }

    val findTargetChild = {
        (0 until shortcutContainer.childCount)
            .map { shortcutContainer.getChildAt(it) }
            .firstOrNull { it.tag != SHORTCUT_GLASS_TAG && it.visibility == View.VISIBLE }
            ?: (0 until shortcutContainer.childCount)
                .map { shortcutContainer.getChildAt(it) }
                .firstOrNull { it.tag != SHORTCUT_GLASS_TAG }
            ?: shortcutContainer
    }

    val findGlassBg = {
        (0 until shortcutContainer.childCount)
            .map { shortcutContainer.getChildAt(it) }
            .firstOrNull { it.tag == SHORTCUT_GLASS_TAG }
    }

    // preDraw 监听器必须成对移除：ViewTreeObserver 属于 window 级 root，视图 detach 不会
    // 自动摘除，反复重建快捷区会让回调数与对旧视图的引用持续增长。
    // 移除必须用绑定时捕获的 observer：视图 detach 后再取 viewTreeObserver 会拿到一个空的
    // 浮动 observer，对它 remove 不会影响 window observer 上的注册。
    val preDrawBindings = ArrayList<Triple<View, ViewTreeObserver, ViewTreeObserver.OnPreDrawListener>>()
    val bindGlassFollow: (View) -> Unit = { target ->
        val observer = target.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            val glass = findGlassBg()
            if (glass != null && glass.isAttachedToWindow) {
                glass.translationX = target.translationX
                glass.translationY = target.translationY
            }
            true
        }
        observer.addOnPreDrawListener(listener)
        preDrawBindings += Triple(target, observer, listener)
    }
    val unbindGlassFollow: () -> Unit = {
        preDrawBindings.forEach { (_, observer, listener) ->
            runCatching { observer.removeOnPreDrawListener(listener) }
        }
        preDrawBindings.clear()
    }
    findTargetChild().let {
        if (it !== shortcutContainer) bindGlassFollow(it)
    }

    val onTouchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downTime = event.downTime
                isSliding = false

                shortcutContainer.performHapticFeedbackSafely(HapticFeedbackConstants.KEYBOARD_TAP)

                if (shortcutContainer.width > 0) shortcutContainer.pivotX = shortcutContainer.width / 2f
                if (shortcutContainer.height > 0) shortcutContainer.pivotY = shortcutContainer.height / 2f
                shortcutContainer.animate().cancel()
                shortcutContainer.animate()
                    .scaleX(0.88f)
                    .scaleY(0.88f)
                    .setDuration(110L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop && !isSliding) {
                    isSliding = true
                    shortcutContainer.animate().cancel()
                    shortcutContainer.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(160L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    getController()?.cancelSqueezeBounce()
                }
            }
            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val duration = event.eventTime - downTime
                val dx = event.rawX - downX
                val dy = event.rawY - downY

                if (!isSliding) {
                    shortcutContainer.animate().cancel()
                    shortcutContainer.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(220L)
                        .setInterpolator(OvershootInterpolator(1.35f))
                        .start()

                    if (Math.hypot(dx.toDouble(), dy.toDouble()) <= touchSlop && duration < 500L) {
                        if (now - lastClickTime > 80L) {
                            lastClickTime = now
                            getController()?.triggerSqueezeBounce(fromRight = isRight)
                        }
                    }
                }
                isSliding = false
            }
            MotionEvent.ACTION_CANCEL -> {
                isSliding = false
                shortcutContainer.animate().cancel()
                shortcutContainer.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(180L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                getController()?.cancelSqueezeBounce()
            }
        }
        false
    }

    fun attachTouchRecursively(view: View) {
        view.setOnTouchListener(onTouchListener)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                attachTouchRecursively(view.getChildAt(index))
            }
        }
    }
    attachTouchRecursively(shortcutContainer)
    shortcutContainer.addOnHierarchyChangeListenerSafe(object : ViewGroup.OnHierarchyChangeListener {
        override fun onChildViewAdded(parent: View?, child: View?) {
            child?.let {
                attachTouchRecursively(it)
                if (it.tag != SHORTCUT_GLASS_TAG) {
                    bindGlassFollow(it)
                }
            }
        }
        override fun onChildViewRemoved(parent: View?, child: View?) {
            if (child == null) return
            val iterator = preDrawBindings.iterator()
            while (iterator.hasNext()) {
                val (target, observer, listener) = iterator.next()
                if (target === child) {
                    runCatching { observer.removeOnPreDrawListener(listener) }
                    iterator.remove()
                }
            }
        }
    })
    shortcutContainer.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            // detach 时移除了 preDraw 监听器，重新挂载后要重新绑定当前图标。
            if (preDrawBindings.isEmpty()) {
                findTargetChild().let { if (it !== shortcutContainer) bindGlassFollow(it) }
            }
        }

        override fun onViewDetachedFromWindow(v: View) {
            unbindGlassFollow()
        }
    })
}

internal class CompositeHierarchyChangeListener : ViewGroup.OnHierarchyChangeListener {
    val listeners = mutableListOf<ViewGroup.OnHierarchyChangeListener>()
    override fun onChildViewAdded(parent: View?, child: View?) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it.onChildViewAdded(parent, child) }
    }
    override fun onChildViewRemoved(parent: View?, child: View?) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it.onChildViewRemoved(parent, child) }
    }
}

internal val hierarchyListeners = WeakHashMap<ViewGroup, CompositeHierarchyChangeListener>()
internal val shortcutIconColorListeners = WeakHashMap<ViewGroup, ShortcutIconColorListener>()

internal fun ViewGroup.addOnHierarchyChangeListenerSafe(listener: ViewGroup.OnHierarchyChangeListener) {
    val composite = synchronized(hierarchyListeners) {
        hierarchyListeners.getOrPut(this) {
            CompositeHierarchyChangeListener().also { created ->
                // ViewGroup 只暴露 setter：先反射取回系统/插件已注册的监听器并纳入转发，
                // 否则直接 set 会把厂商回调静默顶掉。
                currentHierarchyChangeListener()
                    ?.takeIf { it !== created && it !is CompositeHierarchyChangeListener }
                    ?.let { created.listeners.add(it) }
                setOnHierarchyChangeListener(created)
            }
        }
    }
    synchronized(composite.listeners) {
        if (!composite.listeners.contains(listener)) {
            composite.listeners.add(listener)
        }
    }
}

/**
 * 反射读取 ViewGroup 上已有的 OnHierarchyChangeListener：公开 API 只有 setter，
 * 读不回来时返回 null（此时行为与原来的直接覆盖一致）。
 */
private fun ViewGroup.currentHierarchyChangeListener(): ViewGroup.OnHierarchyChangeListener? = runCatching {
    val field = ViewGroup::class.java.getDeclaredField("mOnHierarchyChangeListener")
    field.isAccessible = true
    field.get(this) as? ViewGroup.OnHierarchyChangeListener
}.getOrNull()

internal fun HyperSystemUiModule.findShortcutContainers(root: View): List<FrameLayout> {
    val result = ArrayList<FrameLayout>(2)
    fun visit(view: View) {
        if (view is FrameLayout && view.idName() in LOCKSCREEN_SHORTCUT_CONTAINER_IDS) {
            result += view
            return
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
    }
    visit(root)
    return result
}

internal fun HyperSystemUiModule.applyLockscreenShortcutGeometry(root: View, preferences: SharedPreferences) {
    val shortcuts = findShortcutContainers(root)
    if (shortcuts.size < 2) return
    val density = root.resources.displayMetrics.density
    val spacingEnabled = preferences.getBoolean(KEY_LOCKSCREEN_SHORTCUT_SPACING_ENABLED, false)
    val spacing = (preferences.getFloat(KEY_LOCKSCREEN_SHORTCUT_SPACING, 0f).coerceIn(-10f, 40f) *
        density + .5f).toInt()
    val iconEnabled = preferences.getBoolean(KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE_ENABLED, false)
    val iconSizeDp = preferences.getFloat(KEY_LOCKSCREEN_SHORTCUT_ICON_SIZE, 32f).coerceIn(20f, 56f)
    val targetScale = if (iconEnabled) {
        (iconSizeDp / 32f).coerceIn(0.6f, 1.75f)
    } else {
        1.0f
    }
    shortcuts.forEachIndexed { index, shortcut ->
        shortcut.clipChildren = false
        shortcut.clipToPadding = false
        var parent = shortcut.parent
        while (parent is ViewGroup) {
            parent.clipChildren = false
            parent.clipToPadding = false
            if (parent === root) break
            parent = parent.parent
        }

        // 首次处理该视图时记下原始 margin：关闭"快捷按钮间距"后要写回原值，
        // 否则同一次锁屏会话内开关看起来失效（只有系统重建快捷区才会复位）。
        val originalMargins = synchronized(shortcutOriginalMargins) {
            shortcutOriginalMargins.getOrPut(shortcut) {
                val params = shortcut.layoutParams as? ViewGroup.MarginLayoutParams
                intArrayOf(
                    params?.leftMargin ?: 0,
                    params?.topMargin ?: 0,
                    params?.rightMargin ?: 0,
                    params?.bottomMargin ?: 0,
                )
            }
        }
        (shortcut.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            val isLeft = when (shortcut.idName()) {
                "shortcut_view_left_layout" -> true
                "shortcut_view_right_layout" -> false
                else -> index == 0
            }
            val before = intArrayOf(
                params.leftMargin,
                params.topMargin,
                params.rightMargin,
                params.bottomMargin,
            )
            if (spacingEnabled) {
                if (isLeft) params.leftMargin = spacing else params.rightMargin = spacing
                params.bottomMargin = spacing
            } else {
                params.leftMargin = originalMargins[0]
                params.topMargin = originalMargins[1]
                params.rightMargin = originalMargins[2]
                params.bottomMargin = originalMargins[3]
            }
            if (before[0] != params.leftMargin || before[1] != params.topMargin ||
                before[2] != params.rightMargin || before[3] != params.bottomMargin
            ) {
                shortcut.layoutParams = params
            }
        }

        val images = ArrayList<ImageView>()
        fun collect(view: View) {
            if (view is ImageView && view.tag != SHORTCUT_GLASS_TAG) images += view
            if (view is ViewGroup) {
                for (childIndex in 0 until view.childCount) collect(view.getChildAt(childIndex))
            }
        }
        collect(shortcut)
        images.forEach { image ->
            image.scaleX = targetScale
            image.scaleY = targetScale
        }
        shortcut.requestLayout()
    }
    root.requestLayout()
}

/**
 * Holds the current icon colour mode so one listener can serve every reload.
 */
internal class ShortcutIconColorListener(private val module: HyperSystemUiModule) : ViewGroup.OnHierarchyChangeListener {
    var mode: Int = SHORTCUT_ICON_COLOR_AUTO

    override fun onChildViewAdded(parent: View?, child: View?) {
        val target = child ?: return
        module.applyShortcutIconColor(target, mode)
        if (target is ViewGroup) {
            target.addOnHierarchyChangeListenerSafe(this)
        }
    }

    override fun onChildViewRemoved(parent: View?, child: View?) = Unit
}

internal fun HyperSystemUiModule.applyShortcutIconColor(view: View, mode: Int) {
    if (view is ImageView && view.tag != SHORTCUT_GLASS_TAG) {
        when (mode) {
            SHORTCUT_ICON_COLOR_LIGHT -> {
                view.setColorFilter(SHORTCUT_ICON_LIGHT_COLOR)
                view.imageTintList = ColorStateList.valueOf(SHORTCUT_ICON_LIGHT_COLOR)
            }
            SHORTCUT_ICON_COLOR_DARK -> {
                view.setColorFilter(SHORTCUT_ICON_DARK_COLOR)
                view.imageTintList = ColorStateList.valueOf(SHORTCUT_ICON_DARK_COLOR)
            }
            else -> {
                val effectiveColor = if (LockscreenColorCoordinator.isBottomDeep) {
                    SHORTCUT_ICON_LIGHT_COLOR
                } else {
                    SHORTCUT_ICON_DARK_COLOR
                }
                view.setColorFilter(effectiveColor)
                view.imageTintList = ColorStateList.valueOf(effectiveColor)
            }
        }
    }
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) applyShortcutIconColor(view.getChildAt(index), mode)
    }
}

internal fun HyperSystemUiModule.applyShortcutIconColorMode(container: ViewGroup, mode: Int) {
    applyShortcutIconColor(container, mode)
    val listener = synchronized(shortcutIconColorListeners) {
        shortcutIconColorListeners.getOrPut(container) {
            ShortcutIconColorListener(this).also {
                container.addOnHierarchyChangeListenerSafe(it)
            }
        }
    }
    listener.mode = mode
    // Vendor content can still be attached after addShortcutViews returns.
    container.post { applyShortcutIconColor(container, mode) }
    container.postDelayed({ applyShortcutIconColor(container, mode) }, 300L)
    container.postDelayed({ applyShortcutIconColor(container, mode) }, 1000L)
}

private var shortcutColorListenerRegistered = false

internal fun HyperSystemUiModule.registerShortcutColorCoordinatorListener(
    preferences: SharedPreferences,
    classLoader: ClassLoader,
) {
    if (shortcutColorListenerRegistered) return
    shortcutColorListenerRegistered = true

    LockscreenColorCoordinator.addListener(object : LockscreenColorCoordinator.BottomColorListener {
        override fun onBottomDeepChanged(isBottomDeep: Boolean) {
            val roots = synchronized(shortcutRoots) { shortcutRoots.toList() }
            for (root in roots) {
                if (!root.isAttachedToWindow && root.parent == null) continue
                val iconMode = shortcutIconColorMode(preferences)
                if (iconMode == SHORTCUT_ICON_COLOR_AUTO) {
                    findShortcutContainers(root).forEach { container ->
                        applyShortcutIconColor(container, iconMode)
                    }
                }
            }
        }
    })
}
