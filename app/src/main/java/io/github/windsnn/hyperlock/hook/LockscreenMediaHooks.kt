package io.github.windsnn.hyperlock.hook

import android.app.KeyguardManager
import android.content.SharedPreferences
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
                val result = if (row != null && requested != null && requested != View.GONE &&
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
                    LockscreenMediaBridge.update(effectiveController, effectiveKey)
                    result
            }
        }
    }.onFailure { error ->
        hookLog(Log.ERROR, TAG, "Could not install NotificationMediaManager controller bridge", error)
    }
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
            applyLockscreenMediaPresentation(preferences)
        }
        // The media controller's keyguard callback is a generated nested class on this ROM.
        // KeyguardManager is not reliable from the SystemUI process during transitions, so
        // mirror the callback's boolean state instead.
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
                            // 每次锁屏状态变化都回到"自定义卡片优先"：动态模式下手选系统通知后，
                            // 下一次锁屏仍从模块卡片开始。若要保留用户手选，把条件收窄为 showing == true。
                            if (showing != lockscreenMediaKeyguardShowing && !LockscreenMediaPresentationBridge.showMiniPlayer) {
                                LockscreenMediaPresentationBridge.setShowMiniPlayer(true)
                            }
                            lockscreenMediaKeyguardShowing = showing
                            LockscreenKeyguardBridge.setKeyguardState(showing = showing)
                        }
                        chain.proceed()
                    }
            }
        }.onFailure { error ->
            hookLog(Log.WARN, TAG, "Could not install media keyguard callback hook", error)
        }
        // The vendor controller writes the header's inherited View.visibility property
        // directly. Hook View.setVisibility and only apply a post-call correction when the
        // receiver is the actual media header. We deliberately keep the original call and
        // receiver untouched; replacing arguments on a shared View method can crash other
        // View subclasses inside SystemUI.
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
                val result = chain.proceed()
                if (header == null) return@intercept result
                lockscreenMediaHeaders += header
                if (shouldHideLockscreenMedia(preferences) &&
                    (lockscreenMediaKeyguardShowing || isLockscreenMediaView(header)) &&
                    header.visibility != View.GONE
                ) {
                    HyperLog.d(TAG, "Lockscreen media header forced GONE")
                    header.visibility = View.GONE
                }
                result
            }

        // The holder is assigned independently from media-data updates on this ROM.  Bind
        // after that assignment as well, otherwise the first shown media card can miss the
        // artwork listener entirely.
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
                    // Some holder children are attached on the next traversal.
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
 * HyperOS's tiny lockscreen panel builds its media card directly from MediaData rather than a
 * notification row. Filtering NotificationEntry alone therefore leaves that card visible.
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

/** Dynamic mode must retain the notification entry so it can be shown after a card tap. */
internal fun HyperSystemUiModule.shouldFilterLockscreenMedia(preferences: SharedPreferences): Boolean =
    preferences.getBoolean(KEY_LOCKSCREEN_MINI_PLAYER_ENABLED, false) &&
        lockscreenMediaNotificationMode(preferences) == LOCKSCREEN_MEDIA_NOTIFICATION_ALWAYS_HIDE

internal fun HyperSystemUiModule.applyLockscreenMediaPresentation(preferences: SharedPreferences) {
    val hidden = shouldHideLockscreenMedia(preferences)
    val headers = synchronized(lockscreenMediaHeaders) { lockscreenMediaHeaders.toList() }
    headers.forEach { header ->
        if (!isMiuiMediaHeaderView(header)) {
            lockscreenMediaHeaders.remove(header)
            return@forEach
        }
        if (hidden || lockscreenMediaKeyguardShowing || isLockscreenMediaView(header)) {
            // SystemUI hosts the fingerprint and notification surfaces on separate loopers.
            // A presentation change originates from the mini player, so apply each update
            // through the target view's own queue instead of assuming the caller's thread.
            // MiuiMediaHeaderView is shared by the keyguard and notification shade.
            // Do not animate transforms on this real SystemUI view: an unlock or shade
            // rebind can detach it before the animation end action runs, leaving the
            // notification permanently scaled or transparent.
            header.post {
                updateLockscreenMediaHeaderVisibility(header, hidden)
            }
        }
    }
    lockscreenMediaRows.toList().forEach { row ->
        if (row in lockscreenRows) {
            val visibility = if (hidden) View.GONE else View.VISIBLE
            row.post { row.visibility = visibility }
        }
    }
}

internal fun HyperSystemUiModule.updateLockscreenMediaHeaderVisibility(header: View, hidden: Boolean) {
    if (!isMiuiMediaHeaderView(header)) return
    // Cancel any vendor animation targeting this shared view and restore the neutral visual
    // state before changing visibility, so no stuck transform or alpha survives the toggle.
    header.animate().cancel()
    header.alpha = 1f
    header.scaleX = 1f
    header.scaleY = 1f
    header.visibility = if (hidden) View.GONE else View.VISIBLE
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
            // Consume the complete gesture so the vendor click listener cannot replace the
            // presentation change after the artwork tap.
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
    // HyperOS commits mMediaNotificationKey asynchronously. During that window the
    // notification is still identifiable by the package owning the active media session.
    // This is the stable signal used by the lockscreen media card itself.
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
