package io.github.windsnn.hyperlock.hook

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.windsnn.hyperlock.hook.*
import io.github.windsnn.hyperlock.HyperLog
import io.github.windsnn.hyperlock.HyperSystemUiModule
import io.github.windsnn.hyperlock.LOCKSCREEN_MEDIA_NOTIFICATION_ALWAYS_HIDE
import io.github.windsnn.hyperlock.LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE
import io.github.windsnn.hyperlock.LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC
import io.github.windsnn.hyperlock.player.LockscreenCustomizationMenuBridge
import io.github.windsnn.hyperlock.player.LockscreenKeyguardBridge
import io.github.windsnn.hyperlock.player.LockscreenMediaBridge
import io.github.windsnn.hyperlock.player.LockscreenMediaPresentationBridge
import io.github.windsnn.hyperlock.settings.*
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_MINI_PLAYER_ENABLED
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE
import io.github.libxposed.api.XposedInterface.ExceptionMode

internal fun HyperSystemUiModule.installLockscreenMediaNotificationHook(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    runCatching {
        installLockscreenMediaManagerBridgeHooks(classLoader)
        installLockscreenMediaHeaderHook(classLoader, preferences)
        installLockscreenCustomizationMenuHook(classLoader)
        installLockscreenMediaVisibilityProviderHooks(classLoader, preferences)
        // KeyguardCoordinator 的 notifFilter 只是转调 provider.shouldHideNotification(entry)，
        // 上面的 provider hook 已经覆盖该路径，无需再单独 hook 流程节点。
        installTinyLockscreenMediaHook(classLoader, preferences)
        val rowClass = classLoader.loadClass(EXPANDABLE_NOTIFICATION_ROW_CLASS)
        val getEntry = rowClass.getMethod("getEntry")
        val setOnKeyguard = rowClass.getMethod("setOnKeyguard", Boolean::class.javaPrimitiveType)
        val setVisibility = rowClass.declaredMethods.firstOrNull {
            it.name == "setVisibility" && it.parameterTypes.contentEquals(
                arrayOf(Int::class.javaPrimitiveType),
            )
        } ?: error("ExpandableNotificationRow.setVisibility was not found")
        attachHook(setOnKeyguard)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("lockscreen-hide-media-notification:keyguard")
            .intercept { chain ->
                val result = chain.proceed()
                val row = chain.thisObject as? View ?: return@intercept result
                val onKeyguard = chain.getArg(0) as? Boolean ?: false
                if (onKeyguard) {
                    lockscreenRows += row
                    if (isMediaRow(row, getEntry)) {
                        lockscreenMediaRows += row
                    } else {
                        ensureNotificationRowListeners(row)
                    }
                } else {
                    lockscreenRows -= row
                    lockscreenMediaRows -= row
                    if (lockscreenHiddenRows.remove(row)) {
                        // 还原成记录时的原值：系统自己收起的行不能被强行显示。
                        val previous = synchronized(lockscreenHiddenRowVisibility) {
                            lockscreenHiddenRowVisibility.remove(row)
                        }
                        if (previous != null) row.visibility = previous
                    }
                }
                if (onKeyguard && shouldHideLockscreenMedia(preferences) && isMediaRow(row, getEntry)) {
                    lockscreenHiddenRows += row
                    synchronized(lockscreenHiddenRowVisibility) {
                        lockscreenHiddenRowVisibility.getOrPut(row) { row.visibility }
                    }
                    row.visibility = View.GONE
                }
                scheduleUpdateActiveLockscreenNotifications()
                result
            }
        attachHook(setVisibility)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("lockscreen-hide-media-notification:visibility")
            .intercept { chain ->
                val row = chain.thisObject as? View
                val requested = chain.getArg(0) as? Int
                val isExiting = row != null && synchronized(lockscreenExitingMediaViews) {
                    row in lockscreenExitingMediaViews
                }
                val result = if (row != null && !isExiting && requested != null && requested != View.GONE &&
                    row in lockscreenRows && shouldHideLockscreenMedia(preferences) &&
                    isMediaRow(row, getEntry)
                ) {
                    synchronized(lockscreenHiddenRowVisibility) {
                        lockscreenHiddenRowVisibility.getOrPut(row) { requested }
                    }
                    chain.proceed(arrayOf(View.GONE))
                } else {
                    chain.proceed()
                }
                scheduleUpdateActiveLockscreenNotifications()
                result
            }
    }.onFailure { error ->
        hookLog(Log.ERROR, TAG, "Could not install lockscreen media-notification visibility hook", error)
    }
}

internal fun HyperSystemUiModule.installLockscreenMediaManagerBridgeHooks(classLoader: ClassLoader) {
    runCatching {
        val managerClass = classLoader.loadClass("com.android.systemui.media.NotificationMediaManager")
        val methods = managerClass.declaredMethods.filter {
            (it.name == "findAndUpdateMediaNotifications" ||
             it.name == "dispatchUpdateMediaMetaData") &&
                it.parameterCount == 0
        }
        check(methods.isNotEmpty()) { "NotificationMediaManager media-update methods were not found" }
        methods.forEachIndexed { index, method ->
            attachHook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-media-manager-bridge-$index")
                .intercept { chain ->
                    val result = chain.proceed()
                    val controller = readInstanceField(chain.thisObject, "mMediaController") as?
                        android.media.session.MediaController
                    val key = readInstanceField(chain.thisObject, "mMediaNotificationKey") as? String
                    val effectiveController = if (key.isNullOrBlank()) null else controller
                    val effectiveKey = if (controller == null) null else key
                    val artwork = if (effectiveKey != null) extractNotificationArtwork(chain.thisObject, effectiveKey) else null
                    LockscreenMediaBridge.update(effectiveController, effectiveKey, artwork)
                    result
            }
        }
    }.onFailure { error ->
        hookLog(Log.ERROR, TAG, "Could not install NotificationMediaManager controller bridge", error)
    }
}

private fun extractNotificationArtwork(manager: Any?, key: String?): Bitmap? {
    if (manager == null || key.isNullOrBlank()) return null
    return runCatching {
        val notifCollection = readInstanceField(manager, "mNotifCollection")
        val entry = notifCollection?.let { collection ->
            val getEntryMethod = collection.javaClass.getMethod("getEntry", String::class.java)
            getEntryMethod.invoke(collection, key)
        } ?: run {
            val pipeline = readInstanceField(manager, "mNotifPipeline")
            val allNotifs = pipeline?.javaClass?.getMethod("getAllNotifs")?.invoke(pipeline) as? Collection<*>
            allNotifs?.firstOrNull { item ->
                (readInstanceField(item, "mKey") as? String ?: readInstanceField(item, "key") as? String) == key
            }
        } ?: return null

        val sbn = readInstanceField(entry, "mSbn") as? android.service.notification.StatusBarNotification
            ?: return null
        val notification = sbn.notification ?: return null
        val context = (readInstanceField(manager, "mContext") as? Context)
            ?: (readInstanceField(entry, "mContext") as? Context)
            ?: return null
        val largeIcon = notification.getLargeIcon()
        if (largeIcon != null) {
            val drawable = largeIcon.loadDrawable(context)
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                return drawable.bitmap
            }
            if (drawable != null) {
                val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth.coerceAtMost(256) else 128
                val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight.coerceAtMost(256) else 128
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                return bitmap
            }
        }
        @Suppress("DEPRECATION")
        val bitmapIcon = notification.largeIcon
        if (bitmapIcon != null) return bitmapIcon
        null
    }.getOrNull()
}

internal fun HyperSystemUiModule.installLockscreenMediaHeaderHook(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    runCatching {
        val headerClass = classLoader.loadClass(
            MIUI_MEDIA_HEADER_VIEW_CLASS,
        )
        LockscreenMediaPresentationBridge.onPresentationChanged = {
            applyLockscreenMediaPresentation(preferences, animate = true)
        }
        // 拦截 MiuiMediaNotificationControllerImpl.setVisibility(boolean)，从源头避免系统在隐藏状态下
        // 触发先 generateAddAnimation 再被强行置 GONE 的无效动效抵消。
        runCatching {
            val controllerClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaNotificationControllerImpl",
            )
            val controllerSetVisibility = controllerClass.getMethod(
                "setVisibility",
                Boolean::class.javaPrimitiveType,
            )
            attachHook(controllerSetVisibility)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-hide-media-notification:controller-visibility")
                .intercept { chain ->
                    val controller = chain.thisObject
                    val requestedVisible = chain.getArg(0) as? Boolean ?: false
                    val header = controller?.let { readInstanceField(it, "mediaContainerView") as? View }
                    if (header != null) {
                        lockscreenMediaHeaders += header
                    }
                    val isExiting = header != null && synchronized(lockscreenExitingMediaViews) {
                        header in lockscreenExitingMediaViews
                    }
                    if (requestedVisible && !isExiting && shouldHideLockscreenMedia(preferences) &&
                        (lockscreenMediaKeyguardShowing || (header != null && isLockscreenMediaView(header)))
                    ) {
                        chain.proceed(arrayOf(false))
                    } else {
                        chain.proceed()
                    }
                }
        }.onFailure { error ->
            hookLog(Log.WARN, TAG, "Could not install MiuiMediaNotificationControllerImpl.setVisibility hook", error)
        }
        // 监听锁屏回调状态以同步 Keyguard 可见性
        runCatching {
            val callbackClass = classLoader.loadClass(
                "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaNotificationControllerImpl\$keyguardUpdateMonitorCallback\$1",
            )
            val stateMethods = callbackClass.declaredMethods.filter { method ->
                method.name.lowercase(java.util.Locale.ROOT).contains("keyguard") &&
                    method.parameterTypes.any { it == Boolean::class.javaPrimitiveType }
            }
            stateMethods.forEachIndexed { index, method ->
                attachHook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-hide-media-notification:keyguard-callback-$index")
                    .intercept { chain ->
                        val booleanIndex = method.parameterTypes.indexOfFirst {
                            it == Boolean::class.javaPrimitiveType
                        }
                        if (booleanIndex >= 0) {
                            val showing = chain.getArg(booleanIndex) as? Boolean ?: false
                            val changed = showing != lockscreenMediaKeyguardShowing
                            lockscreenMediaKeyguardShowing = showing
                            LockscreenKeyguardBridge.setKeyguardState(showing = showing)
                            // 每次锁屏状态变化都回到"自定义卡片优先"：动态模式下手选系统通知后，
                            // 下一次锁屏仍从模块卡片开始。若要保留用户手选，把条件收窄为 showing == true。
                            if (changed && !LockscreenMediaPresentationBridge.showMiniPlayer) {
                                LockscreenMediaPresentationBridge.setShowMiniPlayer(true)
                            }
                        }
                        chain.proceed()
                    }
            }
        }.onFailure { error ->
            hookLog(Log.WARN, TAG, "Could not install media keyguard callback hook", error)
        }
        // 拦截 View.setVisibility 并在调用前改写参数，避免先置 VISIBLE 再改回 GONE 引发双重布局计算
        val viewVisibility = View::class.java.getMethod(
            "setVisibility",
            Int::class.javaPrimitiveType,
        )
        attachHook(viewVisibility)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("lockscreen-hide-media-notification:media-header-visibility")
            .intercept { chain ->
                // 先做 instanceof 短路：SystemUI 动画与列表刷新期间 View.setVisibility 调用极频繁，
                // 只有真正的媒体头部才继续读偏好，避免在热路径上做 SharedPreferences 查询。
                val header = (chain.thisObject as? View)?.takeIf(headerClass::isInstance)
                    ?: return@intercept chain.proceed()
                lockscreenMediaHeaders += header
                val requested = chain.getArg(0) as? Int
                val isExiting = synchronized(lockscreenExitingMediaViews) {
                    header in lockscreenExitingMediaViews
                }
                if (!isExiting && requested != null && requested != View.GONE &&
                    shouldHideLockscreenMedia(preferences) &&
                    (lockscreenMediaKeyguardShowing || isLockscreenMediaView(header))
                ) {
                    HyperLog.d(TAG, "Lockscreen media header forced GONE")
                    chain.proceed(arrayOf(View.GONE))
                } else {
                    chain.proceed()
                }
            }

        // 绑定 ViewHolder 赋值时机，防止首个媒体卡片漏挂封面监听
        val mediaViewHolderClass = classLoader.loadClass(
            "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewHolder",
        )
        val setMediaViewHolder = headerClass.getMethod(
            "setMediaViewHolder",
            mediaViewHolderClass,
        )
        attachHook(setMediaViewHolder)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("lockscreen-media-presentation:media-view-holder")
            .intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? View)?.takeIf(headerClass::isInstance)?.let { header ->
                    lockscreenMediaHeaders += header
                    installLockscreenMediaArtworkClick(header, preferences)
                    // 延迟到下一绘制周期以兼容异步挂载的子视图
                    header.post {
                        installLockscreenMediaArtworkClick(header, preferences)
                    }
                }
                result
            }
        log(
            Log.INFO,
            TAG,
            "Installed MiuiMediaHeaderView lockscreen hide hook " +
                "(visibility=View.setVisibility, artwork=setMediaViewHolder)",
        )
        applyLockscreenMediaPresentation(preferences)
    }.onFailure { error ->
        hookLog(Log.ERROR, TAG, "Could not install MiuiMediaHeaderView lockscreen hide hook", error)
    }
}

internal fun HyperSystemUiModule.installLockscreenCustomizationMenuHook(classLoader: ClassLoader) {
    runCatching {
        val interactorClass = classLoader.loadClass(
            "com.android.systemui.keyguard.domain.interactor.KeyguardTouchHandlingInteractor",
        )
        val onLongPress = interactorClass.getMethod("onLongPress")
        attachHook(onLongPress)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("lockscreen-mini-player-customization-menu:show")
            .intercept { chain ->
                val result = chain.proceed()
                LockscreenCustomizationMenuBridge.setVisible(true)
                result
            }
        val hideMenu = interactorClass.getMethod("hideMenu")
        attachHook(hideMenu)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("lockscreen-mini-player-customization-menu:hide")
            .intercept { chain ->
                val result = chain.proceed()
                LockscreenCustomizationMenuBridge.setVisible(false)
                result
            }
    }.onFailure { error ->
        hookLog(Log.ERROR, TAG, "Could not install lockscreen customization-menu hooks", error)
    }
}

internal fun HyperSystemUiModule.installLockscreenMediaVisibilityProviderHooks(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    val entryClass = classLoader.loadClass(
        "com.android.systemui.statusbar.notification.collection.NotificationEntry",
    )
    val providerNames = listOf(
        "com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProviderImpl",
        "com.android.systemui.statusbar.notification.interruption.MiuiKeyguardNotificationVisibilityProvider",
    )
    providerNames.forEach { className ->
        runCatching {
            val providerClass = classLoader.loadClass(className)
            val methods = providerClass.declaredMethods.filter { method ->
                method.name == "shouldHideNotification" &&
                    method.parameterTypes.isNotEmpty() &&
                    method.parameterTypes[0].isAssignableFrom(entryClass)
            }
            methods.forEachIndexed { index, method ->
                attachHook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-hide-media-notification:provider:${className.substringAfterLast('.')}:$index")
                    .intercept { chain ->
                        val lockState = if (method.parameterCount > 1 &&
                            method.parameterTypes[1] == Boolean::class.javaPrimitiveType
                        ) {
                            chain.getArg(1) as? Boolean == true
                        } else {
                            true
                        }
                        if (lockState && shouldFilterLockscreenMedia(preferences) &&
                            isMediaEntry(chain.getArg(0))
                        ) {
                            true
                        } else {
                            chain.proceed()
                        }
                    }
            }
        }.onFailure { error ->
            hookLog(Log.WARN, TAG, "Could not install $className media visibility hook", error)
        }
    }
}

/**
 * 拦截 HyperOS 锁屏极小化面板直接基于 MediaData 构建的媒体卡片。
 */
internal fun HyperSystemUiModule.installTinyLockscreenMediaHook(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    runCatching {
        val dataStoreClass = classLoader.loadClass(
            "com.android.notification.tinypanel.FlipNotifDataStore",
        )
        val onMediaUpdate = dataStoreClass.declaredMethods.firstOrNull {
            it.name == "onMediaUpdate" && it.parameterCount == 1
        } ?: error("FlipNotifDataStore.onMediaUpdate was not found")
        attachHook(onMediaUpdate)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("lockscreen-hide-media-notification:tiny-panel-update")
            .intercept { chain ->
                if (shouldFilterLockscreenMedia(preferences)) {
                    chain.proceed(arrayOf<Any?>(null))
                } else {
                    chain.proceed()
                }
            }
        val merge = dataStoreClass.declaredMethods.firstOrNull {
            it.name == "merge" && it.parameterCount == 3
        } ?: error("FlipNotifDataStore.merge was not found")
        attachHook(merge)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("lockscreen-hide-media-notification:tiny-panel-merge")
            .intercept { chain ->
                if (shouldFilterLockscreenMedia(preferences)) {
                    chain.proceed(arrayOf(chain.getArg(0), null, chain.getArg(2)))
                } else {
                    chain.proceed()
                }
            }
    }.onFailure { error ->
        hookLog(Log.WARN, TAG, "Could not install tiny lockscreen media-notification hooks", error)
    }
}

internal fun HyperSystemUiModule.lockscreenMediaNotificationMode(preferences: SharedPreferences): Int =
    preferences.getInt(
        KEY_LOCKSCREEN_MINI_PLAYER_MEDIA_NOTIFICATION_MODE,
        LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE,
    ).coerceIn(LOCKSCREEN_MEDIA_NOTIFICATION_DO_NOT_HIDE, LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC)

internal fun HyperSystemUiModule.shouldHideLockscreenMedia(preferences: SharedPreferences): Boolean =
    preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false) && when (
        lockscreenMediaNotificationMode(preferences)
    ) {
        LOCKSCREEN_MEDIA_NOTIFICATION_ALWAYS_HIDE -> true
        LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC -> LockscreenMediaPresentationBridge.showMiniPlayer
        else -> false
    }

/** 动态模式下保留通知 Entry 供卡片点击还原展示。 */
internal fun HyperSystemUiModule.shouldFilterLockscreenMedia(preferences: SharedPreferences): Boolean =
    preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false) &&
        lockscreenMediaNotificationMode(preferences) == LOCKSCREEN_MEDIA_NOTIFICATION_ALWAYS_HIDE

internal fun HyperSystemUiModule.applyLockscreenMediaPresentation(
    preferences: SharedPreferences,
    animate: Boolean = false,
) {
    val hidden = shouldHideLockscreenMedia(preferences)
    val shouldAnimate = animate &&
        lockscreenMediaNotificationMode(preferences) == LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC &&
        lockscreenMediaKeyguardShowing
    val headers = synchronized(lockscreenMediaHeaders) { lockscreenMediaHeaders.toList() }
    headers.forEach { header ->
        if (!isMiuiMediaHeaderView(header)) {
            lockscreenMediaHeaders.remove(header)
            return@forEach
        }
        if (hidden || lockscreenMediaKeyguardShowing || isLockscreenMediaView(header)) {
            header.post {
                updateLockscreenMediaHeaderVisibility(header, hidden, shouldAnimate)
            }
        }
    }
    lockscreenMediaRows.toList().forEach { row ->
        if (row in lockscreenRows) {
            row.post {
                updateLockscreenMediaRowVisibility(row, hidden, shouldAnimate)
            }
        }
    }
}

private const val MEDIA_HEADER_ENTER_DURATION_MS = 280L
private const val MEDIA_HEADER_EXIT_DURATION_MS = 200L

internal fun HyperSystemUiModule.updateLockscreenMediaHeaderVisibility(
    header: View,
    hidden: Boolean,
    animate: Boolean = false,
) {
    if (!isMiuiMediaHeaderView(header)) return
    val holder = readInstanceField(header, "mediaViewHolder")
    // 针对内部 player (ConstraintLayout) 做属性动画，避免与 PropertiesFrameLayout / NSSL ViewState 冲突
    val contentView = (holder?.let { readInstanceField(it, "player") as? View }) ?: header
    val shiftPx = 22f * header.resources.displayMetrics.density

    contentView.animate().cancel()
    header.animate().cancel()

    if (!animate) {
        synchronized(lockscreenExitingMediaViews) {
            lockscreenExitingMediaViews.remove(header)
        }
        resetMediaContentViewTransform(contentView)
        resetMediaContentViewTransform(header)
        setMiuiMediaHeaderVisibilityWithStackSync(header, hidden)
        return
    }

    if (hidden) {
        if (header.visibility != View.VISIBLE) {
            synchronized(lockscreenExitingMediaViews) {
                lockscreenExitingMediaViews.remove(header)
            }
            resetMediaContentViewTransform(contentView)
            setMiuiMediaHeaderVisibilityWithStackSync(header, true)
            return
        }
        synchronized(lockscreenExitingMediaViews) {
            lockscreenExitingMediaViews.add(header)
        }
        contentView.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .translationY(shiftPx)
            .setDuration(MEDIA_HEADER_EXIT_DURATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                synchronized(lockscreenExitingMediaViews) {
                    lockscreenExitingMediaViews.remove(header)
                }
                resetMediaContentViewTransform(contentView)
                if (LockscreenMediaPresentationBridge.showMiniPlayer) {
                    setMiuiMediaHeaderVisibilityWithStackSync(header, true)
                }
            }
            .start()
    } else {
        synchronized(lockscreenExitingMediaViews) {
            lockscreenExitingMediaViews.remove(header)
        }
        // 先恢复可见性以触发 MiuiMediaNotificationControllerImpl -> NSSL.generateAddAnimation，
        // 让下方的普通通知卡片同步平滑下移让出空间。
        setMiuiMediaHeaderVisibilityWithStackSync(header, false)
        contentView.alpha = 0f
        contentView.scaleX = 0.92f
        contentView.scaleY = 0.92f
        contentView.translationY = shiftPx
        contentView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(MEDIA_HEADER_ENTER_DURATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                resetMediaContentViewTransform(contentView)
            }
            .start()
    }
}

private fun resetMediaContentViewTransform(view: View) {
    view.alpha = 1f
    view.scaleX = 1f
    view.scaleY = 1f
    view.translationY = 0f
}

/**
 * 通过 `MiuiMediaNotificationControllerImpl.setVisibility(boolean)` 更新可见性，
 * 驱动 `visibilityChangedListener` 触发 `NotificationStackScrollLayout` 的添加/移除重排动画。
 */
internal fun HyperSystemUiModule.setMiuiMediaHeaderVisibilityWithStackSync(header: View, hidden: Boolean) {
    val targetVisibility = if (hidden) View.GONE else View.VISIBLE
    val controller = readInstanceField(header, "mediaNotificationController")
    if (controller != null) {
        val invoked = runCatching {
            controller.javaClass
                .getMethod("setVisibility", Boolean::class.javaPrimitiveType)
                .invoke(controller, !hidden)
            true
        }.getOrDefault(false)
        if (invoked && header.visibility == targetVisibility) {
            scheduleUpdateActiveLockscreenNotifications()
            return
        }
    }
    header.visibility = targetVisibility
    scheduleUpdateActiveLockscreenNotifications()
}

internal fun HyperSystemUiModule.updateLockscreenMediaRowVisibility(
    row: View,
    hidden: Boolean,
    animate: Boolean = false,
) {
    val shiftPx = 22f * row.resources.displayMetrics.density
    row.animate().cancel()
    if (!animate) {
        synchronized(lockscreenExitingMediaViews) {
            lockscreenExitingMediaViews.remove(row)
        }
        row.alpha = 1f
        row.scaleX = 1f
        row.scaleY = 1f
        row.visibility = if (hidden) View.GONE else View.VISIBLE
        return
    }
    if (hidden) {
        if (row.visibility != View.VISIBLE) {
            synchronized(lockscreenExitingMediaViews) {
                lockscreenExitingMediaViews.remove(row)
            }
            row.visibility = View.GONE
            return
        }
        synchronized(lockscreenExitingMediaViews) {
            lockscreenExitingMediaViews.add(row)
        }
        val baseY = row.translationY
        row.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .translationY(baseY + shiftPx)
            .setDuration(MEDIA_HEADER_EXIT_DURATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                synchronized(lockscreenExitingMediaViews) {
                    lockscreenExitingMediaViews.remove(row)
                }
                row.alpha = 1f
                row.scaleX = 1f
                row.scaleY = 1f
                row.translationY = baseY
                if (LockscreenMediaPresentationBridge.showMiniPlayer) {
                    row.visibility = View.GONE
                }
            }
            .start()
    } else {
        synchronized(lockscreenExitingMediaViews) {
            lockscreenExitingMediaViews.remove(row)
        }
        row.visibility = View.VISIBLE
        row.alpha = 0f
        row.scaleX = 0.92f
        row.scaleY = 0.92f
        row.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(MEDIA_HEADER_ENTER_DURATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }
}

internal fun HyperSystemUiModule.installLockscreenMediaArtworkClick(header: View, preferences: SharedPreferences) {
    val holder = readInstanceField(header, "mediaViewHolder") ?: return
    val artworkViews = listOfNotNull(
        readInstanceField(holder, "albumImageView") as? View,
        readInstanceField(holder, "albumView") as? View,
    )
    artworkViews.forEach { artwork ->
        artwork.setOnTouchListener { _, event ->
            if (lockscreenMediaNotificationMode(preferences) != LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    HyperLog.d(TAG, "System media artwork tapped; showing mini player")
                    LockscreenMediaPresentationBridge.setShowMiniPlayer(true)
                }
            }
            // 消费完整手势避免原生点击事件覆盖展示状态
            true
        }
    }
}

internal fun HyperSystemUiModule.isLockscreenMediaView(target: Any?): Boolean = runCatching {
    val view = target as? View ?: return@runCatching false
    val keyguardManager = view.context.getSystemService(KeyguardManager::class.java)
        ?: return@runCatching false
    keyguardManager.isKeyguardLocked
}.getOrDefault(false)

internal fun HyperSystemUiModule.isMiuiMediaHeaderView(view: View): Boolean {
    var current: Class<*>? = view.javaClass
    while (current != null) {
        if (current.name == MIUI_MEDIA_HEADER_VIEW_CLASS) return true
        current = current.superclass
    }
    return false
}

internal fun HyperSystemUiModule.isMediaRow(row: View, getEntry: java.lang.reflect.Method): Boolean = runCatching {
    val entry = getEntry.invoke(row)
    if (isMediaEntry(entry)) return@runCatching true
    // 语义判据（entry key / 媒体会话包名 / isMediaNotification / mediaSession）都不成立时，
    // 才退到"按类名子串认定"：这是最后兜底，厂商新增/改名媒体视图类时可能需要同步。
    fun containsMediaView(view: View): Boolean {
        val name = view.javaClass.name.lowercase(java.util.Locale.ROOT)
        if (name.contains("mediaheader") || name.contains("mediarow") ||
            name.contains("mediacontrol") || name.contains("mediaholder")
        ) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (containsMediaView(view.getChildAt(index))) return true
            }
        }
        return false
    }
    containsMediaView(row)
}.getOrDefault(false)

internal fun HyperSystemUiModule.isMediaEntry(entry: Any?): Boolean = runCatching {
    if (entry == null) return@runCatching false
    val sbn = readInstanceField(entry, "mSbn")
        ?: entry.javaClass.methods.firstOrNull {
            it.name == "getSbn" && it.parameterCount == 0
        }?.invoke(entry)
        ?: return@runCatching false
    val entryKey = entry.javaClass.methods.firstOrNull {
        it.name == "getKey" && it.parameterCount == 0
    }?.invoke(entry) as? String
    if (entryKey != null && entryKey == LockscreenMediaBridge.notificationKey) {
        return@runCatching true
    }
    // 异步提交 key 窗口期内，回退通过媒体会话所属包名进行稳定识别
    val mediaPackage = LockscreenMediaBridge.controller?.packageName
    val sbnPackage = sbn.javaClass.methods.firstOrNull {
        it.name == "getPackageName" && it.parameterCount == 0
    }?.invoke(sbn) as? String
    if (!mediaPackage.isNullOrBlank() && sbnPackage == mediaPackage) {
        return@runCatching true
    }
    val mediaDataManagerMedia = runCatching {
        val managerClass = Class.forName(
            "com.android.systemui.media.controls.domain.pipeline.MediaDataManager",
            false,
            sbn.javaClass.classLoader,
        )
        // 该静态方法（接口上的 static，无 public 修饰）只能用 getDeclaredMethod 拿到。
        managerClass.getDeclaredMethod(
            "isMediaNotification",
            android.service.notification.StatusBarNotification::class.java,
        ).apply { isAccessible = true }.invoke(null, sbn) as? Boolean
    }.getOrNull()
    if (mediaDataManagerMedia == true) return@runCatching true
    val expandedMedia = (readInstanceField(sbn, "isMediaNotification") as? Boolean)
        ?: (sbn.javaClass.methods.firstOrNull {
            it.name == "isMediaNotification" && it.parameterCount == 0
        }?.invoke(sbn) as? Boolean)
    if (expandedMedia == true) return@runCatching true
    val notification = sbn.javaClass.methods.firstOrNull {
        it.name == "getNotification" && it.parameterCount == 0
    }?.invoke(sbn) ?: return@runCatching false
    val notificationMedia = notification.javaClass.methods.firstOrNull {
        it.name == "isMediaNotification" && it.parameterCount == 0
    }?.invoke(notification) as? Boolean
    if (notificationMedia == true) return@runCatching true
    val extras = notification.javaClass.getMethod("getExtras").invoke(notification) as? android.os.Bundle
    val category = notification.javaClass.getField("category").get(notification) as? String
    extras?.containsKey("android.mediaSession") == true || category == "transport"
}.getOrDefault(false)
