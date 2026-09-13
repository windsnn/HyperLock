package io.github.windsnn.hyperlock.player

import io.github.windsnn.hyperlock.HyperLog
import io.github.windsnn.hyperlock.LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC
import io.github.windsnn.hyperlock.settings.LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_ALWAYS
import io.github.windsnn.hyperlock.settings.LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_MANUAL
import io.github.windsnn.hyperlock.settings.LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_OFF
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.AudioManager
import android.view.KeyEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import java.lang.ref.WeakReference

/** Native lockscreen card; SystemUI owns media sessions and the final view hierarchy. */
internal class LockscreenMiniPlayerController(
    private val host: ViewGroup,
    private val leftShortcut: View,
    private val rightShortcut: View,
    private val enabled: () -> Boolean,
    private val lyricsMode: () -> Int,
    private val mediaNotificationMode: () -> Int,
    private val appearance: () -> MiniPlayerAppearance,
    private val lyricsAppearance: () -> LockscreenLyricsAppearance,
    private val applyPlatformMaterial: (ImageView, MiniPlayerAppearance) -> Unit,
    private val onDestroyed: (LockscreenMiniPlayerController) -> Unit,
) : LockscreenKeyguardListener {
    private val context: Context = host.context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var activeController: MediaController? = null
    private var player: LockscreenMiniPlayerView? = null
    private var lyricsCard: LockscreenLyricsView? = null
    private var lyricSubscriber: LyriconLockscreenSubscriber? = null
    private var refreshPosted = false
    private var positionPosted = false
    private var baseTranslationX = 0f
    private var baseTranslationY = 0f
    private var lyricsBaseTranslationX = 0f
    private var lyricsBaseTranslationY = 0f
    private var customizationLift = 0f
    private var customizationVisible = false
    private var customizationMenuVisible = false
    private var customizationButtonRef: WeakReference<View>? = null
    private var mediaPresentationExitAnimating = false
    private var lyricsNotificationExitAnimating = false
    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        updateCustomizationLift()
    }

    private var stableRestCenterY = 0f
    private var hasStableCenterY = false
    private var squeezeBounceOffset = 0f
    private var squeezeScaleX = 1.0f
    private var squeezeScaleY = 1.0f
    private var isSqueezing = false
    private var squeezeAnimator: Animator? = null
    private var lyricsManualActive = false
    private var notificationVerifyInProgress = false
    internal var isDestroyed = false
        private set
    private val shortcutLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        schedulePosition()
    }
    private val hostLayoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            hasStableCenterY = false
            schedulePosition()
        }
        updateLyricsAwakeState()
        player?.setAwake(LockscreenKeyguardBridge.isLockscreenVisible)
    }

    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> onScreenInteractiveChanged(true)
                Intent.ACTION_SCREEN_OFF -> onScreenInteractiveChanged(false)
            }
        }
    }

    private fun onScreenInteractiveChanged(interactive: Boolean) {
        LockscreenKeyguardBridge.setKeyguardState(interactive = interactive)
    }

    private val hostAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            destroy()
        }
    }

    private val sessionCallback = object : MediaSessionManager.OnActiveSessionsChangedListener {
        override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
            selectController(controllers.orEmpty())
            refresh()
        }
    }
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { scheduleRefresh() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { scheduleRefresh() }
    }

    private fun unclipAncestors(view: View) {
        var current = view.parent
        while (current is ViewGroup) {
            current.clipChildren = false
            current.clipToPadding = false
            if (current === view.rootView) break
            current = current.parent
        }
    }

    init {
        // The card is centered between the shortcuts and may be taller than the shortcut
        // container's measured bounds. Keep the configured dp height from being clipped.
        host.clipChildren = false
        host.clipToPadding = false
        unclipAncestors(host)
        host.rootView.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        leftShortcut.addOnLayoutChangeListener(shortcutLayoutListener)
        rightShortcut.addOnLayoutChangeListener(shortcutLayoutListener)
        LockscreenCustomizationMenuBridge.register(this)
        LockscreenMediaPresentationBridge.register(this)
        LockscreenMediaBridge.register(this)
        LockscreenLyricsNotificationBridge.register(this)
        LockscreenKeyguardBridge.register(this)
        host.addOnLayoutChangeListener(hostLayoutListener)
        host.addOnAttachStateChangeListener(hostAttachListener)
        val initialInteractive = powerManager?.isInteractive ?: true
        LockscreenKeyguardBridge.setKeyguardState(interactive = initialInteractive)
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            context.applicationContext?.registerReceiver(screenReceiver, filter)
                ?: context.registerReceiver(screenReceiver, filter)
            screenReceiverRegistered = true
        }.onFailure { error ->
            HyperLog.w("MiniPlayer", "Could not register screen state receiver", error)
        }
        runCatching {
            sessions?.addOnActiveSessionsChangedListener(sessionCallback, null, mainHandler)
            selectController(sessions?.getActiveSessions(null).orEmpty())
            refresh()
        }.onFailure { scheduleRefresh() }
    }

    fun reloadSettings() {
        // 设置页改动必须立即生效：清掉长按手动唤出状态。
        lyricsManualActive = false
        mainHandler.post {
            refresh()
        }
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        if (screenReceiverRegistered) {
            screenReceiverRegistered = false
            runCatching {
                (context.applicationContext ?: context).unregisterReceiver(screenReceiver)
            }
        }
        LockscreenKeyguardBridge.unregister(this)
        LockscreenCustomizationMenuBridge.unregister(this)
        LockscreenMediaPresentationBridge.unregister(this)
        LockscreenMediaBridge.unregister(this)
        LockscreenLyricsNotificationBridge.unregister(this)
        isLyricsAwake = false
        lyricsNotificationExitAnimating = false
        squeezeAnimator?.cancel()
        squeezeAnimator = null
        squeezeBounceOffset = 0f
        squeezeScaleX = 1.0f
        squeezeScaleY = 1.0f
        isSqueezing = false
        lyricsManualActive = false
        customizationButtonRef = null
        player?.setSqueezeScale(1.0f, 1.0f)
        hasStableCenterY = false
        stableRestCenterY = 0f
        runCatching { host.removeOnLayoutChangeListener(hostLayoutListener) }
        runCatching { host.removeOnAttachStateChangeListener(hostAttachListener) }
        runCatching { host.rootView.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener) }
        runCatching { leftShortcut.removeOnLayoutChangeListener(shortcutLayoutListener) }
        runCatching { rightShortcut.removeOnLayoutChangeListener(shortcutLayoutListener) }
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionCallback) }
        runCatching { activeController?.unregisterCallback(controllerCallback) }
        runCatching { player?.let(host::removeView) }
        runCatching { lyricsCard?.let(host::removeView) }
        lyricSubscriber?.destroy()
        activeController = null
        player = null
        lyricsCard = null
        lyricSubscriber = null
        mainHandler.removeCallbacksAndMessages(null)
        positionPosted = false
        // 宿主 detach 触发的销毁同样要释放注册表条目，否则安装逻辑会把死控制器当成已安装。
        onDestroyed(this)
    }

    private fun cancelCurrentSqueezeAnimator() {
        val running = squeezeAnimator
        squeezeAnimator = null
        running?.cancel()
        squeezeBounceOffset = 0f
        squeezeScaleX = 1.0f
        squeezeScaleY = 1.0f
        player?.setSqueezeScale(1.0f, 1.0f)
        isSqueezing = false
        schedulePosition()
    }

    internal fun triggerSqueezeBounce(fromRight: Boolean) {
        val action = {
            if (player != null && host.width > 0) {
                val isUnlockingOrExiting = host.translationY < -dp(4f) || host.alpha < 0.98f
                if (!isUnlockingOrExiting) {
                    cancelCurrentSqueezeAnimator()
                    isSqueezing = true

                    val maxOffset = if (fromRight) -dp(10f).toFloat() else dp(10f).toFloat()
                    val totalDurationMs = 360L

                    val waveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = totalDurationMs
                        interpolator = LinearInterpolator()
                        addUpdateListener { va ->
                            val fraction = va.animatedValue as Float
                            val t = fraction * (totalDurationMs / 1000f)
                            // 黄金阻尼二阶物理弹簧冲击响应解析解: 3.37 * exp(-15.6 * t) * sin(17.45 * t)
                            val wave = (3.37f * exp(-15.6f * t) * sin(17.45f * t)).toFloat()

                            squeezeBounceOffset = maxOffset * wave
                            val scaleReduction = 0.075f * wave
                            squeezeScaleX = 1.0f - scaleReduction
                            // 泊松等容积呼吸: 横向受挤收缩时纵向自然微凸
                            squeezeScaleY = 1.0f + 0.45f * scaleReduction
                            schedulePosition()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (squeezeAnimator === animation) {
                                    resetSqueezeState()
                                }
                            }

                            override fun onAnimationCancel(animation: Animator) {
                                if (squeezeAnimator === animation) {
                                    resetSqueezeState()
                                }
                            }
                        })
                    }
                    squeezeAnimator = waveAnimator
                    waveAnimator.start()
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun resetSqueezeState() {
        squeezeAnimator = null
        squeezeBounceOffset = 0f
        squeezeScaleX = 1.0f
        squeezeScaleY = 1.0f
        player?.setSqueezeScale(1.0f, 1.0f)
        isSqueezing = false
        schedulePosition()
    }

    internal fun cancelSqueezeBounce() {
        val action = {
            val startOffset = squeezeBounceOffset
            val startSx = squeezeScaleX
            cancelCurrentSqueezeAnimator()

            if (abs(startOffset) < 0.5f && abs(startSx - 1.0f) < 0.01f) {
                squeezeBounceOffset = 0f
                squeezeScaleX = 1.0f
                squeezeScaleY = 1.0f
                isSqueezing = false
                player?.setSqueezeScale(1.0f, 1.0f)
                schedulePosition()
            } else {
                val resetAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 160L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { va ->
                        val fraction = va.animatedValue as Float
                        squeezeBounceOffset = startOffset * (1f - fraction)
                        val sx = startSx + (1.0f - startSx) * fraction
                        squeezeScaleX = sx
                        squeezeScaleY = 1.0f - 0.40f * (sx - 1.0f)
                        schedulePosition()
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (squeezeAnimator === animation) {
                                squeezeAnimator = null
                                squeezeBounceOffset = 0f
                                squeezeScaleX = 1.0f
                                squeezeScaleY = 1.0f
                                player?.setSqueezeScale(1.0f, 1.0f)
                                isSqueezing = false
                                schedulePosition()
                            }
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            if (squeezeAnimator === animation) {
                                squeezeAnimator = null
                                squeezeBounceOffset = 0f
                                squeezeScaleX = 1.0f
                                squeezeScaleY = 1.0f
                                player?.setSqueezeScale(1.0f, 1.0f)
                                isSqueezing = false
                                schedulePosition()
                            }
                        }
                    })
                }
                squeezeAnimator = resetAnim
                resetAnim.start()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun updateCustomizationLift() {
        val view = player ?: return
        val root = host.rootView as? ViewGroup ?: return
        if (!customizationMenuVisible) {
            animateCustomizationLift(false, 0f)
            return
        }
        val customButton = findCustomizationButton(root)
        if (customButton == null) {
            // onLongPress fires before the menu view has completed its first layout.
            animateCustomizationLift(true, -dp(64f).toFloat())
            return
        }
        val playerLocation = IntArray(2).also(view::getLocationOnScreen)
        val buttonLocation = IntArray(2).also(customButton::getLocationOnScreen)
        val overlap = playerLocation[1] + view.height - buttonLocation[1]
        val lift = if (overlap > 0) -(overlap + dp(12f)).toFloat() else 0f
        animateCustomizationLift(true, lift)
    }

    private fun animateCustomizationLift(visible: Boolean, lift: Float) {
        if (customizationVisible == visible && kotlin.math.abs(customizationLift - lift) < 1f) return
        customizationVisible = visible
        customizationLift = lift
        fun animate(view: View, baseY: Float) {
            view.animate()
                .translationY(baseY + lift)
                .setDuration(280L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
        player?.let { animate(it, baseTranslationY) }
        lyricsCard?.let { animate(it, lyricsBaseTranslationY) }
    }

    internal fun onCustomizationMenuVisibilityChanged(visible: Boolean) {
        customizationMenuVisible = visible
        // 菜单每次都是新视图，重新打开时必须让上次的定位缓存失效。
        customizationButtonRef = null
        mainHandler.post { updateCustomizationLift() }
    }

    internal fun onPlatformMediaControllerUpdated() {
        mainHandler.post { scheduleRefresh() }
    }

    internal fun onMediaPresentationChanged(showMiniPlayer: Boolean) {
        mainHandler.post {
            if (showMiniPlayer) {
                mediaPresentationExitAnimating = false
                refresh()
                player?.let(::animateMiniPlayerIn)
                lyricsCard?.takeIf { it.visibility == View.VISIBLE }?.let(::animateMiniPlayerIn)
            } else {
                animateMiniPlayerOut()
            }
        }
    }

    private fun animateMiniPlayerIn(view: View) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        val targetY = (if (view === lyricsCard) lyricsBaseTranslationY else baseTranslationY) + customizationLift
        view.translationY = targetY - dp(24f).toFloat()
        view.alpha = 0f
        view.scaleX = .92f
        view.scaleY = .92f
        view.animate()
            .translationY(targetY)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(MEDIA_PRESENTATION_ENTER_DURATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun animateMiniPlayerOut() {
        val views = listOfNotNull(player, lyricsCard).filter { it.visibility == View.VISIBLE }
        if (views.isEmpty()) {
            mediaPresentationExitAnimating = false
            refresh()
            return
        }
        mediaPresentationExitAnimating = true
        var pending = views.size
        fun onDone() {
            pending -= 1
            if (pending == 0 && !LockscreenMediaPresentationBridge.showMiniPlayer) {
                mediaPresentationExitAnimating = false
                player?.let { card ->
                    card.visibility = View.GONE
                    card.alpha = 1f
                    card.setSqueezeScale(1f, 1f)
                    card.setBaseTranslation(baseTranslationX, baseTranslationY + customizationLift)
                }
                lyricsCard?.let { card ->
                    card.visibility = View.GONE
                    card.alpha = 1f
                    card.scaleX = 1f
                    card.scaleY = 1f
                    card.translationY = lyricsBaseTranslationY + customizationLift
                }
                refresh()
            }
        }
        player?.takeIf { it.visibility == View.VISIBLE }?.let { view ->
            view.animate().cancel()
            val targetY = baseTranslationY + customizationLift - dp(36f).toFloat()
            view.animate()
                .translationY(targetY)
                .alpha(0f)
                .scaleX(.92f)
                .scaleY(.92f)
                .setDuration(MEDIA_PRESENTATION_EXIT_DURATION_MS)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction(::onDone)
                .start()
        }
        lyricsCard?.takeIf { it.visibility == View.VISIBLE }?.let { view ->
            view.animate().cancel()
            val targetY = lyricsBaseTranslationY + customizationLift - dp(44f).toFloat()
            view.animate()
                .translationY(targetY)
                .alpha(0f)
                .scaleX(.90f)
                .scaleY(.90f)
                .setDuration(MEDIA_PRESENTATION_EXIT_DURATION_MS)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction(::onDone)
                .start()
        }
    }

    private fun findCustomizationButton(root: ViewGroup): View? {
        // 命中后缓存：菜单可见期间全局布局回调会反复触发，每次全树扫描代价过高。
        customizationButtonRef?.get()?.let { cached ->
            if (cached.isAttachedToWindow && isCustomizationMenuButton(cached)) return cached
        }
        val found = findViewByPredicate(root) { view ->
            view !== player && isCustomizationMenuButton(view)
        }
        customizationButtonRef = found?.let { WeakReference(it) }
        return found
    }

    /**
     * 厂商没有暴露菜单按钮的 id 或接口，只能按可见文案反查；候选文案见
     * [CUSTOMIZATION_MENU_KEYWORDS]，换语言或厂商改文案时匹配会失败，
     * 此时调用方退化为固定的上抬距离。
     */
    private fun isCustomizationMenuButton(view: View): Boolean {
        if (view.visibility != View.VISIBLE || view.alpha <= 0f || view.width <= 0 || view.height <= 0) return false
        val text = (view as? TextView)?.text?.toString().orEmpty()
        val description = view.contentDescription?.toString().orEmpty()
        val idName = runCatching { context.resources.getResourceEntryName(view.id) }.getOrDefault("")
        val haystack = "$text $description $idName".lowercase(java.util.Locale.ROOT)
        return CUSTOMIZATION_MENU_KEYWORDS.any(haystack::contains) ||
            (haystack.contains("custom") && haystack.contains("lock"))
    }

    private fun findViewByPredicate(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findViewByPredicate(root.getChildAt(index), predicate)?.let { return it }
            }
        }
        return null
    }

    private fun selectController(controllers: List<MediaController>) {
        val platformController = LockscreenMediaBridge.controller
        val selected = platformController?.takeIf(::isUsable)
            ?: controllers.firstOrNull(::isUsable)
        setActiveController(selected)
    }

    private fun setActiveController(selected: MediaController?) {
        if (selected?.sessionToken != activeController?.sessionToken) {
            val prevPackage = activeController?.packageName
            runCatching { activeController?.unregisterCallback(controllerCallback) }
            activeController = selected
            runCatching {
                selected?.registerCallback(controllerCallback, mainHandler)
            }.onFailure { error ->
                HyperLog.e("MiniPlayer", "Failed to register callback for ${selected?.packageName}", error)
            }
            if (selected != null) {
                HyperLog.d("MiniPlayer", "Active media controller changed: ${selected.packageName}")
            }
            if (selected == null || (prevPackage != null && selected.packageName != prevPackage)) {
                // 会话切换到其它应用（如退出音乐后打开视频应用），立即清空词幕缓存并隐藏歌词，杜绝幽灵歌词残留
                lyricSubscriber?.clearCache()
                lyricsCard?.visibility = View.GONE
                lyricsManualActive = false
            }
        }
    }

    private fun isUsable(controller: MediaController): Boolean = when (controller.playbackState?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING -> true
        else -> false
    }

    private fun refresh() {
        runCatching { refreshUnsafe() }.onFailure { error ->
            HyperLog.e("MiniPlayer", "Unexpected error refreshing mini player", error)
        }
    }

    private fun scheduleRefresh() {
        if (refreshPosted) return
        refreshPosted = true
        mainHandler.post {
            refreshPosted = false
            refresh()
        }
    }

    private fun refreshUnsafe() {
        // NotificationMediaManager can publish a controller whose playback state is already
        // destroyed. Do not call selectController() here: that method refreshes synchronously,
        // and selecting an unusable bridge controller used to recurse until SystemUI crashed.
        val platformController = LockscreenMediaBridge.controller
        if (platformController != null) {
            if (platformController.sessionToken != activeController?.sessionToken) {
                setActiveController(platformController.takeIf(::isUsable))
            }
        } else {
            // Fall back to active system sessions if the bridge controller is null
            val fallback = sessions?.getActiveSessions(null).orEmpty().firstOrNull(::isUsable)
            if (fallback != null) {
                if (fallback.sessionToken != activeController?.sessionToken) {
                    setActiveController(fallback)
                }
            } else {
                setActiveController(null)
            }
        }
        val controller = activeController
        val state = controller?.playbackState
        if (!enabled() || controller == null || !isUsable(controller)) {
            player?.visibility = View.GONE
            lyricsCard?.visibility = View.GONE
            lyricsManualActive = false
            return
        }
        val mode = mediaNotificationMode()
        if (mode != LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC && !LockscreenMediaPresentationBridge.showMiniPlayer) {
            LockscreenMediaPresentationBridge.setShowMiniPlayer(true)
        }
        val currentAppearance = appearance()
        val view = player ?: LockscreenMiniPlayerView(context).also {
            player = it
            it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedulePosition() }
            // The first refresh can happen before the shortcut row receives its final layout.
            // Start with the configured dimensions instead of a transient default size.
            host.addView(
                it,
                ViewGroup.LayoutParams(
                    dp(currentAppearance.widthDp),
                    dp(currentAppearance.heightDp).coerceAtLeast(dp(20f)),
                ),
            )
        }
        val showMiniPlayer = !(
            mode == LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC && !LockscreenMediaPresentationBridge.showMiniPlayer
        )
        if (showMiniPlayer) {
            view.visibility = View.VISIBLE
        } else if (!mediaPresentationExitAnimating) {
            view.visibility = View.GONE
        }
        val rawTitle = controller.metadata?.let { meta ->
            meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()
                ?: meta.description?.title?.toString()
        }?.trim()
        val title = if (!rawTitle.isNullOrBlank()) rawTitle else "正在播放"

        val rawArtist = controller.metadata?.let { meta ->
            meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()
                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: meta.description?.subtitle?.toString()
                ?: meta.getString(MediaMetadata.METADATA_KEY_AUTHOR)
        }?.trim().orEmpty()

        view.bind(
            title = title,
            artist = rawArtist,
            artwork = controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            playing = state?.state == PlaybackState.STATE_PLAYING,
            appearance = currentAppearance,
            applyPlatformMaterial = applyPlatformMaterial,
            onToggle = ::togglePlaybackSafely,
            onSkipToPrevious = { skipTrackSafely(next = false) },
            onSkipToNext = { skipTrackSafely(next = true) },
            onShowSystemMediaNotification = {
                if (mediaNotificationMode() == LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC) {
                    LockscreenMediaPresentationBridge.setShowMiniPlayer(false)
                } else {
                    runCatching {
                        val sessionIntent = (LockscreenMediaBridge.controller ?: activeController)?.sessionActivity
                        sessionIntent?.send()
                    }.onFailure { error ->
                        HyperLog.w("MiniPlayer", "Failed to launch media session activity", error)
                    }
                }
            },
            onToggleLyrics = if (lyricsMode() == LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_MANUAL) ::toggleLyricsManual else null,
        )
        player?.setAwake(LockscreenKeyguardBridge.isLockscreenVisible)
        bindLyrics(lyricsAppearance(), showMiniPlayer)
        updateLyricsAwakeState()
        position()
    }

    @Volatile private var isLyricsAwake = false

    private fun updateLyricsAwakeState() {
        isLyricsAwake = !isDestroyed &&
            LockscreenKeyguardBridge.isLockscreenVisible &&
            isLyricsActive() &&
            host.isAttachedToWindow &&
            host.visibility == View.VISIBLE &&
            !shouldHideLyricsForNotification(lyricsAppearance())
    }

    private fun isLyricsAwake(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateLyricsAwakeState()
        }
        return isLyricsAwake
    }

    override fun onKeyguardVisibilityChanged(isLockscreenVisible: Boolean) {
        mainHandler.post {
            if (isDestroyed) return@post
            player?.setAwake(isLockscreenVisible)
            updateLyricsAwakeState()
            if (!isLockscreenVisible) {
                lyricsCard?.cancelAllAnimations()
            } else {
                lyricsCard?.markNeedsSnap()
                scheduleRefresh()
            }
        }
    }

    private fun isLyricSessionMatching(subscriber: LyriconLockscreenSubscriber): Boolean {
        val activePkg = activeController?.packageName?.lowercase() ?: return false
        val lyricPkg = subscriber.currentPlayerPackageName?.lowercase() ?: return false
        if (activePkg == lyricPkg) return true
        if (activePkg.startsWith("$lyricPkg.") || lyricPkg.startsWith("$activePkg.")) return true
        if (activePkg.startsWith("$lyricPkg:") || lyricPkg.startsWith("$activePkg:")) return true
        return false
    }

    private fun obtainLyricSubscriber(): LyriconLockscreenSubscriber {
        return lyricSubscriber ?: LyriconLockscreenSubscriber(
            context = context,
            onLineChanged = { updateLyricsOnly() },
            onProgressTick = { pos -> onLyricProgressTick(pos) },
            isAwake = { isLyricsAwake() },
        ).also {
            lyricSubscriber = it
        }
    }

    private fun onLyricProgressTick(position: Long) {
        val card = lyricsCard ?: return
        if (card.visibility != View.VISIBLE) return
        card.syncProgress(position)
    }

    private fun isLyricsActive(): Boolean = when (lyricsMode()) {
        LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_ALWAYS -> true
        LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_MANUAL -> lyricsManualActive
        else -> false
    }

    fun toggleLyricsManual() {
        if (lyricsMode() != LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_MANUAL) return
        val isShowing = lyricsCard != null && lyricsCard?.visibility == View.VISIBLE && (lyricsCard?.alpha ?: 0f) > 0.1f
        if (isShowing) {
            lyricsManualActive = false
            updateLyricsAwakeState()
            lyricsCard?.animate()
                ?.alpha(0f)
                ?.translationYBy(-dp(10f).toFloat())
                ?.setDuration(180L)
                ?.withEndAction {
                    lyricsCard?.visibility = View.GONE
                    updateLyricsAwakeState()
                }
                ?.start()
        } else {
            lyricsManualActive = true
            val lyApp = lyricsAppearance()
            if (shouldHideLyricsForNotification(lyApp)) {
                updateLyricsAwakeState()
                return
            }
            val subscriber = obtainLyricSubscriber()
            if (!isLyricSessionMatching(subscriber)) {
                lyricsCard?.visibility = View.GONE
                updateLyricsAwakeState()
                return
            }
            val lyric = subscriber.currentLyric(lyApp)
            if (lyric != null) {
                val view = ensureLyricsCard(lyric, lyApp)
                view.bind(lyric, lyApp, subscriber.currentPosition)
                view.visibility = View.VISIBLE
                unclipAncestors(host)
                player?.let(::positionLyrics)
                view.animate().cancel()
                view.alpha = 0f
                view.translationY = lyricsBaseTranslationY + dp(12f).toFloat()
                view.animate()
                    .alpha(1f)
                    .translationY(lyricsBaseTranslationY + customizationLift)
                    .setDuration(240L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                updateLyricsAwakeState()
            } else {
                lyricsCard?.visibility = View.GONE
                updateLyricsAwakeState()
            }
        }
    }

    private fun ensureLyricsCard(lyric: LockscreenLyricText, appearance: LockscreenLyricsAppearance): LockscreenLyricsView {
        val existing = lyricsCard
        val requiredHeight = LockscreenLyricsView.lyricsCardHeight(context, appearance, lyric.detail.isNotBlank())
        if (existing != null) {
            if (existing.layoutParams != null && existing.layoutParams.height != requiredHeight) {
                existing.layoutParams.height = requiredHeight
                existing.requestLayout()
                schedulePosition()
            }
            return existing
        }
        val created = LockscreenLyricsView(context)
        lyricsCard = created
        created.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedulePosition() }
        host.addView(created, ViewGroup.LayoutParams(dp(260f), requiredHeight))
        unclipAncestors(host)
        return created
    }

    private fun bindLyrics(lyricsApp: LockscreenLyricsAppearance, showMiniPlayer: Boolean) {
        val enabled = isLyricsActive()
        val shouldHideForNotification = shouldHideLyricsForNotification(lyricsApp)
        if (!enabled || shouldHideForNotification || !showMiniPlayer) {
            lyricsCard?.visibility = View.GONE
            updateLyricsAwakeState()
            if (lyricsMode() == LOCKSCREEN_MINI_PLAYER_LYRICS_MODE_OFF && !shouldHideForNotification) {
                lyricSubscriber?.destroy()
                lyricSubscriber = null
            }
            return
        }
        val subscriber = obtainLyricSubscriber()
        if (!isLyricSessionMatching(subscriber)) {
            lyricsCard?.visibility = View.GONE
            updateLyricsAwakeState()
            return
        }
        val lyric = subscriber.currentLyric(lyricsApp) ?: run {
            lyricsCard?.visibility = View.GONE
            updateLyricsAwakeState()
            return
        }
        val view = ensureLyricsCard(lyric, lyricsApp)
        view.bind(lyric, lyricsApp, subscriber.currentPosition)
        view.visibility = View.VISIBLE
        unclipAncestors(host)
        updateLyricsAwakeState()
    }

    private fun updateLyricsOnly() {
        val enabled = isLyricsActive()
        val currentApp = lyricsAppearance()
        val shouldHideForNotification = shouldHideLyricsForNotification(currentApp)
        if (!enabled || shouldHideForNotification) {
            lyricsCard?.visibility = View.GONE
            updateLyricsAwakeState()
            return
        }
        val subscriber = lyricSubscriber ?: return
        if (!isLyricSessionMatching(subscriber)) {
            lyricsCard?.visibility = View.GONE
            updateLyricsAwakeState()
            return
        }
        val lyric = subscriber.currentLyric(currentApp)
        if (lyric == null) {
            lyricsCard?.visibility = View.GONE
            updateLyricsAwakeState()
            return
        }
        val mode = mediaNotificationMode()
        val showMiniPlayer = !(
            mode == LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC && !LockscreenMediaPresentationBridge.showMiniPlayer
        )
        if (!showMiniPlayer) {
            lyricsCard?.visibility = View.GONE
            updateLyricsAwakeState()
            return
        }
        val view = ensureLyricsCard(lyric, currentApp)
        view.bind(lyric, currentApp, subscriber.currentPosition)
        view.visibility = View.VISIBLE
        unclipAncestors(host)
        updateLyricsAwakeState()
        player?.let(::positionLyrics)
    }

    private fun togglePlaybackSafely() {
        // TransportControls is a Binder proxy. A player can disappear between the click and
        // the command, so dispatch on the SystemUI looper and contain every Binder failure.
        runCatching {
            mainHandler.post {
                runCatching {
                    // Refresh the session list at click time. Media apps can replace their
                    // session without emitting an active-session callback to SystemUI.
                    val current = sessions?.getActiveSessions(null).orEmpty()
                    if (current.isNotEmpty()) selectController(current)
                    val controller = LockscreenMediaBridge.controller ?: activeController
                    val state = controller?.playbackState
                    val playing = state?.state == PlaybackState.STATE_PLAYING
                    val action = if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
                    val supportsAction = state != null && state.actions and action != 0L
                    if (controller != null && supportsAction) {
                        runCatching {
                            if (playing) controller.transportControls.pause() else controller.transportControls.play()
                        }.onFailure { dispatchMediaKeyFallback(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
                    } else {
                        dispatchMediaKeyFallback(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                }
            }
        }
    }

    private fun skipTrackSafely(next: Boolean) {
        // Use the active session when possible so the command follows the media app selected by
        // SystemUI, then fall back to the standard media key for sessions without transport APIs.
        runCatching {
            mainHandler.post {
                runCatching {
                    val current = sessions?.getActiveSessions(null).orEmpty()
                    if (current.isNotEmpty()) selectController(current)
                    val controller = LockscreenMediaBridge.controller ?: activeController
                    val state = controller?.playbackState
                    val action = if (next) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    val keyCode = if (next) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    val supportsAction = state != null && state.actions and action != 0L
                    if (controller != null && supportsAction) {
                        runCatching {
                            if (next) controller.transportControls.skipToNext()
                            else controller.transportControls.skipToPrevious()
                        }.onFailure { dispatchMediaKeyFallback(keyCode) }
                    } else {
                        dispatchMediaKeyFallback(keyCode)
                    }
                }
            }
        }
    }

    private fun dispatchMediaKeyFallback(keyCode: Int) {
        val manager = audioManager ?: return
        val now = android.os.SystemClock.uptimeMillis()
        runCatching {
            manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            manager.dispatchMediaKeyEvent(KeyEvent(now, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
        }.onFailure { error ->
            HyperLog.w("MiniPlayer", "Failed to dispatch media key fallback for keyCode=$keyCode", error)
        }
    }

    private fun schedulePosition() {
        if (positionPosted) return
        positionPosted = true
        host.postOnAnimation {
            positionPosted = false
            position()
        }
    }

    private fun position() {
        val view = player ?: return
        if (host.width <= 0 || host.height <= 0) return
        val hostLocation = IntArray(2).also(host::getLocationOnScreen)
        val shortcutsLaidOut = leftShortcut.width > 0 && leftShortcut.height > 0 &&
            rightShortcut.width > 0 && rightShortcut.height > 0
        val leftLocation = if (shortcutsLaidOut) IntArray(2).also(leftShortcut::getLocationOnScreen) else null
        val rightLocation = if (shortcutsLaidOut) IntArray(2).also(rightShortcut::getLocationOnScreen) else null

        val isUnlockingOrExiting = host.translationY < -dp(4f) || host.alpha < 0.98f
        val restCenterX = host.width / 2f
        val centerX = restCenterX + (if (!isUnlockingOrExiting) squeezeBounceOffset else 0f)

        val shortcutsPressed = leftShortcut.isPressed || rightShortcut.isPressed ||
            ((leftShortcut as? ViewGroup)?.let { g -> (0 until g.childCount).any { g.getChildAt(it).isPressed } } == true) ||
            ((rightShortcut as? ViewGroup)?.let { g -> (0 until g.childCount).any { g.getChildAt(it).isPressed } } == true)

        val validShortcutLocations = shortcutsLaidOut &&
            leftLocation != null && rightLocation != null &&
            leftLocation[1] > 0 && rightLocation[1] > 0 &&
            rightLocation[0] > leftLocation[0]

        val rawRestCenterY = if (validShortcutLocations) {
            val leftCenterY = leftLocation[1] - hostLocation[1] - leftShortcut.translationY + leftShortcut.height / 2f
            val rightCenterY = rightLocation[1] - hostLocation[1] - rightShortcut.translationY + rightShortcut.height / 2f
            (leftCenterY + rightCenterY) / 2f
        } else {
            host.height / 2f
        }
        val isQuietState = !shortcutsPressed && !isUnlockingOrExiting && !isSqueezing &&
            abs(squeezeBounceOffset) < 0.01f && abs(squeezeScaleX - 1.0f) < 0.001f
        if (validShortcutLocations && !hasStableCenterY && isQuietState) {
            stableRestCenterY = rawRestCenterY
            hasStableCenterY = true
        }
        val centerY = if (hasStableCenterY) stableRestCenterY else rawRestCenterY
        val requested = appearance()
        val horizontalRoom = host.width - dp(24f)
        val maxWidth = min((host.width * .64f).toInt(), horizontalRoom)
        val width = min(dp(requested.widthDp), maxWidth)
            .coerceAtLeast(min(dp(60f), host.width))
        val height = dp(requested.heightDp).coerceAtLeast(dp(20f))
        val params = view.layoutParams
        if (params == null || params.width != width || params.height != height) {
            val updated = params ?: ViewGroup.LayoutParams(width, height)
            updated.width = width
            updated.height = height
            view.layoutParams = updated
            // Do not calculate against the old child bounds. The parent will assign new
            // left/top/width/height during the next layout pass, then the layout listener above
            // will apply the final translation.
            schedulePosition()
            return
        }
        val actualWidth = view.width
        val actualHeight = view.height
        if (actualWidth <= 0 || actualHeight <= 0) {
            schedulePosition()
            return
        }
        view.pivotX = actualWidth / 2f
        view.pivotY = actualHeight / 2f
        // Preserve the requested center across parent layout passes.
        baseTranslationX = centerX - actualWidth / 2f - view.left
        baseTranslationY = centerY - actualHeight / 2f - view.top
        view.setBaseTranslation(baseTranslationX, baseTranslationY + customizationLift)
        if (isSqueezing) {
            view.setSqueezeScale(squeezeScaleX, squeezeScaleY)
        } else {
            view.setSqueezeScale(1.0f, 1.0f)
        }
        positionLyrics(view)
    }

    private fun positionLyrics(playerView: View) {
        val view = lyricsCard ?: return
        if (view.visibility != View.VISIBLE) return
        val lyApp = lyricsAppearance()
        val width = min(host.width - dp(32f), max(playerView.width, dp(260f)))
        val height = LockscreenLyricsView.lyricsCardHeight(context, lyApp, view.hasDetail)
        val params = view.layoutParams
        if (params == null || params.width != width || params.height != height) {
            val updated = params ?: ViewGroup.LayoutParams(width, height)
            updated.width = width
            updated.height = height
            view.layoutParams = updated
            schedulePosition()
            return
        }
        if (view.width <= 0 || view.height <= 0) {
            schedulePosition()
            return
        }
        val playerCenterX = playerView.left + baseTranslationX + playerView.width / 2f
        lyricsBaseTranslationX = playerCenterX - view.width / 2f - view.left
        val playerTop = playerView.top + baseTranslationY
        val lyricTop = playerTop - dp(lyApp.gapDp) - view.height
        lyricsBaseTranslationY = lyricTop - view.top
        view.translationX = lyricsBaseTranslationX
        if (!lyricsNotificationExitAnimating) {
            view.translationY = lyricsBaseTranslationY + customizationLift
        }
        unclipAncestors(host)
    }

    internal fun onLockscreenNotificationsChanged(hasNotifications: Boolean) {
        mainHandler.post {
            updateLyricsNotificationState(hasNotifications)
        }
    }

    /**
     * 是否需要因为锁屏通知而隐藏歌词。
     *
     * 通知状态由 hook 层缓存，而通知行的可见性变化不一定触发布局回调，缓存值可能滞后。
     * 因此在准备隐藏前主动复查一次真实状态，避免歌词被已经消失的通知一直挡住。
     */
    private fun shouldHideLyricsForNotification(lyricsApp: LockscreenLyricsAppearance): Boolean {
        if (!lyricsApp.hideOnNotification) return false
        if (!LockscreenLyricsNotificationBridge.hasActiveNotifications) return false
        if (!notificationVerifyInProgress) {
            notificationVerifyInProgress = true
            try {
                LockscreenLyricsNotificationBridge.refreshActiveNotifications()
            } finally {
                notificationVerifyInProgress = false
            }
        }
        return LockscreenLyricsNotificationBridge.hasActiveNotifications
    }

    private fun updateLyricsNotificationState(hasNotifications: Boolean) {
        val lyApp = lyricsAppearance()
        if (!lyApp.hideOnNotification) return
        val enabled = isLyricsActive()
        val mode = mediaNotificationMode()
        val showMiniPlayer = !(
            mode == LOCKSCREEN_MEDIA_NOTIFICATION_DYNAMIC && !LockscreenMediaPresentationBridge.showMiniPlayer
        )
        if (!enabled || !showMiniPlayer) {
            lyricsCard?.visibility = View.GONE
            updateLyricsAwakeState()
            return
        }
        if (hasNotifications) {
            val card = lyricsCard
            if (card != null && card.visibility == View.VISIBLE && card.alpha > 0.01f) {
                lyricsNotificationExitAnimating = true
                card.animate().cancel()
                val targetY = lyricsBaseTranslationY + customizationLift + dp(8f).toFloat()
                card.animate()
                    .alpha(0f)
                    .translationY(targetY)
                    .setDuration(180L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        lyricsNotificationExitAnimating = false
                        if (LockscreenLyricsNotificationBridge.hasActiveNotifications) {
                            card.visibility = View.GONE
                            card.alpha = 1f
                            card.translationY = lyricsBaseTranslationY + customizationLift
                            updateLyricsAwakeState()
                        }
                    }
                    .start()
            } else {
                lyricsCard?.visibility = View.GONE
                updateLyricsAwakeState()
            }
        } else {
            lyricsNotificationExitAnimating = false
            val subscriber = obtainLyricSubscriber()
            if (!isLyricSessionMatching(subscriber)) {
                lyricsCard?.visibility = View.GONE
                updateLyricsAwakeState()
                return
            }
            val lyric = subscriber.currentLyric(lyApp)
            if (lyric != null) {
                val view = ensureLyricsCard(lyric, lyApp)
                view.bind(lyric, lyApp, subscriber.currentPosition)
                view.visibility = View.VISIBLE
                unclipAncestors(host)
                player?.let(::positionLyrics)
                view.animate().cancel()
                view.alpha = 0f
                view.translationY = lyricsBaseTranslationY + customizationLift + dp(8f).toFloat()
                view.animate()
                    .alpha(1f)
                    .translationY(lyricsBaseTranslationY + customizationLift)
                    .setDuration(240L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                updateLyricsAwakeState()
            } else {
                lyricsCard?.visibility = View.GONE
                updateLyricsAwakeState()
            }
        }
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density + .5f).toInt()

    private companion object {
        const val MEDIA_PRESENTATION_ENTER_DURATION_MS = 260L
        const val MEDIA_PRESENTATION_EXIT_DURATION_MS = 220L
        /** 厂商长按菜单按钮的候选文案：当前 ROM 为「自定义锁屏」，其余为兜底候选。 */
        val CUSTOMIZATION_MENU_KEYWORDS = listOf("自定义锁屏", "custom lock")
    }
}
