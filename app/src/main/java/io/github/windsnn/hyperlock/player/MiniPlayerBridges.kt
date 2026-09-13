package io.github.windsnn.hyperlock.player

import android.media.session.MediaController
import java.util.Collections
import java.util.WeakHashMap

/** Coordinates notification-driven lyrics collision avoidance. */
internal object LockscreenLyricsNotificationBridge {
    private val listeners = Collections.newSetFromMap(WeakHashMap<LockscreenMiniPlayerController, Boolean>())
    @Volatile var hasActiveNotifications: Boolean = false
        private set

    /**
     * 主动校验钩子，由 hook 层注入。
     *
     * 锁屏通知行的可见性变化不一定触发布局回调，[hasActiveNotifications] 可能滞后，
     * 因此歌词卡片在依据该状态隐藏自己之前先调用 [refreshActiveNotifications] 复查一次。
     */
    @Volatile var verifyActiveNotifications: (() -> Unit)? = null

    fun refreshActiveNotifications() {
        verifyActiveNotifications?.invoke()
    }

    fun register(controller: LockscreenMiniPlayerController) {
        synchronized(listeners) { listeners += controller }
        controller.onLockscreenNotificationsChanged(hasActiveNotifications)
    }

    fun unregister(controller: LockscreenMiniPlayerController) {
        synchronized(listeners) { listeners -= controller }
    }

    fun setHasActiveNotifications(value: Boolean) {
        if (hasActiveNotifications == value) return
        hasActiveNotifications = value
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it.onLockscreenNotificationsChanged(value) }
    }
}

/** Values sourced from SystemUI's NotificationMediaManager rather than an inferred session list. */
internal object LockscreenMediaBridge {
    private val listeners = Collections.newSetFromMap(WeakHashMap<LockscreenMiniPlayerController, Boolean>())

    @Volatile var controller: MediaController? = null
        private set
    @Volatile var notificationKey: String? = null
        private set

    fun register(controller: LockscreenMiniPlayerController) {
        synchronized(listeners) { listeners += controller }
    }

    fun unregister(controller: LockscreenMiniPlayerController) {
        synchronized(listeners) { listeners -= controller }
    }

    fun update(controller: MediaController?, notificationKey: String?) {
        this.controller = controller
        this.notificationKey = notificationKey
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it.onPlatformMediaControllerUpdated() }
    }
}

/** Coordinates the dynamic switch between the custom card and SystemUI's media notification. */
internal object LockscreenMediaPresentationBridge {
    private val listeners = Collections.newSetFromMap(WeakHashMap<LockscreenMiniPlayerController, Boolean>())

    @Volatile var showMiniPlayer: Boolean = true
        private set
    @Volatile var onPresentationChanged: ((Boolean) -> Unit)? = null

    fun register(controller: LockscreenMiniPlayerController) {
        synchronized(listeners) { listeners += controller }
        controller.onMediaPresentationChanged(showMiniPlayer)
    }

    fun unregister(controller: LockscreenMiniPlayerController) {
        synchronized(listeners) { listeners -= controller }
    }

    fun setShowMiniPlayer(value: Boolean) {
        if (showMiniPlayer == value) return
        showMiniPlayer = value
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it.onMediaPresentationChanged(value) }
        onPresentationChanged?.invoke(value)
    }
}

/** The SystemUI long-press menu is not part of the shortcut view hierarchy. */
internal object LockscreenCustomizationMenuBridge {
    private val listeners = Collections.newSetFromMap(WeakHashMap<LockscreenMiniPlayerController, Boolean>())
    @Volatile private var visible = false

    fun register(controller: LockscreenMiniPlayerController) {
        synchronized(listeners) { listeners += controller }
        controller.onCustomizationMenuVisibilityChanged(visible)
    }

    fun unregister(controller: LockscreenMiniPlayerController) {
        synchronized(listeners) { listeners -= controller }
    }

    fun setVisible(value: Boolean) {
        visible = value
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it.onCustomizationMenuVisibilityChanged(value) }
    }
}

/**
 * 监听并维护锁屏真实展现生命周期（展示 / 解锁 / 遮挡）。
 *
 * 由 KeyguardUpdateMonitor / 状态栏 Hook 注入状态，统一对播放器和歌词模块分发，
 * 解决底层锁屏因透明度渐隐退场而未能触发原生 View.isShown 隐藏判定的问题。
 */
internal object LockscreenKeyguardBridge {
    private val listeners = Collections.newSetFromMap(WeakHashMap<LockscreenKeyguardListener, Boolean>())

    @Volatile var isKeyguardShowing: Boolean = true
        private set

    @Volatile var isKeyguardOccluded: Boolean = false
        private set

    @Volatile var isScreenInteractive: Boolean = true
        private set

    val isLockscreenVisible: Boolean
        get() = isKeyguardShowing && !isKeyguardOccluded && isScreenInteractive

    fun register(listener: LockscreenKeyguardListener) {
        synchronized(listeners) { listeners += listener }
        listener.onKeyguardVisibilityChanged(isLockscreenVisible)
    }

    fun unregister(listener: LockscreenKeyguardListener) {
        synchronized(listeners) { listeners -= listener }
    }

    fun setKeyguardState(
        showing: Boolean? = null,
        occluded: Boolean? = null,
        interactive: Boolean? = null,
    ) {
        val newShowing = showing ?: isKeyguardShowing
        val newOccluded = occluded ?: isKeyguardOccluded
        val newInteractive = interactive ?: isScreenInteractive
        if (isKeyguardShowing == newShowing && isKeyguardOccluded == newOccluded && isScreenInteractive == newInteractive) return
        isKeyguardShowing = newShowing
        isKeyguardOccluded = newOccluded
        isScreenInteractive = newInteractive
        val visible = newShowing && !newOccluded && newInteractive
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it.onKeyguardVisibilityChanged(visible) }
    }
}

internal interface LockscreenKeyguardListener {
    fun onKeyguardVisibilityChanged(isLockscreenVisible: Boolean)
}
