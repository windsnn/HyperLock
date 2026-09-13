package io.github.windsnn.hyperlock.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.graphics.Rect
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

internal class LockscreenMiniPlayerView(context: Context) : FrameLayout(context) {
    private val materialLayer = ImageView(context)
    private val contentContainer = FrameLayout(context)
    private val artwork = ImageView(context)
    private val trackView = LockscreenTrackView(context)
    private val toggle = ImageButton(context)
    private val playPauseDrawable = MorphingPlayPauseDrawable(resources.displayMetrics.density)
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastAppearance: MiniPlayerAppearance? = null
    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private var lastArtwork: Bitmap? = null
    private var lastPlaying: Boolean? = null
    private var onSkipToPrevious: (() -> Unit)? = null
    private var onSkipToNext: (() -> Unit)? = null
    private var onShowSystemMediaNotification: (() -> Unit)? = null
    private var onToggleLyrics: (() -> Unit)? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downRawX = 0f
    private var downRawY = 0f
    private var isDragging = false
    private var thresholdDirection = 0
    private var isTouchDown = false
    private var longPressTriggered = false
    private var longPressScheduled = false
    private var isTouchOnToggle = false
    private val toggleHitRect = Rect()

    private fun isTouchOnToggle(event: MotionEvent): Boolean {
        toggle.getHitRect(toggleHitRect)
        return toggleHitRect.contains(event.x.toInt(), event.y.toInt())
    }
    private val longPressRunnable = Runnable {
        longPressScheduled = false
        longPressTriggered = true
        performHapticFeedbackSafely(HapticFeedbackConstants.LONG_PRESS)
        animateTouchScale(1.02f, 110L, DecelerateInterpolator()) {
            animateTouchScale(1.0f, 160L, DecelerateInterpolator())
        }
        onToggleLyrics?.invoke()
    }

    private fun startLongPressCheck() {
        if (onToggleLyrics == null) return
        if (longPressScheduled) return
        longPressScheduled = true
        removeCallbacks(longPressRunnable)
        longPressTriggered = false
        postDelayed(longPressRunnable, 450L)
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        longPressScheduled = false
        removeCallbacks(longPressRunnable)
    }

    private var baseTranslationX = 0f
    private var currentDampedDx = 0f
    private var dragResetAnimator: ValueAnimator? = null
    private var squeezeScaleX = 1.0f
    private var squeezeScaleY = 1.0f
    private var touchScale = 1.0f
    private var touchScaleAnimator: ValueAnimator? = null

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        materialLayer.scaleType = ImageView.ScaleType.FIT_XY
        materialLayer.clipToOutline = true
        materialLayer.outlineProvider = outlineProvider
        addView(materialLayer, LayoutParams(-1, -1))

        contentContainer.clipChildren = false
        contentContainer.clipToPadding = false
        contentContainer.cameraDistance = density * 8000f
        addView(contentContainer, LayoutParams(-1, -1))

        artwork.scaleType = ImageView.ScaleType.CENTER_CROP
        artwork.clipToOutline = true
        artwork.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
            }
        }
        artwork.setImageDrawable(DefaultArtworkDrawable())
        contentContainer.addView(artwork)

        contentContainer.addView(trackView)

        toggle.scaleType = ImageView.ScaleType.CENTER
        toggle.setPadding(dp(8), dp(8), dp(8), dp(8))
        val rippleColor = ColorStateList.valueOf(Color.argb(50, 255, 255, 255))
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        toggle.background = RippleDrawable(rippleColor, null, mask)
        toggle.contentDescription = "播放或暂停"
        toggle.setImageDrawable(playPauseDrawable)
        addView(toggle)
    }

    fun setBaseTranslation(baseX: Float, baseY: Float) {
        baseTranslationX = baseX
        translationX = baseX + currentDampedDx
        translationY = baseY
    }

    private fun applyCombinedScale() {
        val sx = squeezeScaleX * touchScale
        val sy = squeezeScaleY * touchScale
        scaleX = sx
        scaleY = sy
        val invX = if (sx > 0.001f) 1f / sx else 1f
        val invY = if (sy > 0.001f) 1f / sy else 1f
        artwork.scaleX = invX
        artwork.scaleY = invY
        toggle.scaleX = invX
        toggle.scaleY = invY
    }

    fun setSqueezeScale(sx: Float, sy: Float) {
        squeezeScaleX = sx
        squeezeScaleY = sy
        applyCombinedScale()
    }

    private fun animateTouchScale(
        target: Float,
        durationMs: Long,
        interpolator: android.animation.TimeInterpolator,
        onEnd: (() -> Unit)? = null,
    ) {
        touchScaleAnimator?.cancel()
        touchScaleAnimator = ValueAnimator.ofFloat(touchScale, target).apply {
            duration = durationMs
            this.interpolator = interpolator
            addUpdateListener { va ->
                touchScale = va.animatedValue as Float
                applyCombinedScale()
            }
            if (onEnd != null) {
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (touchScaleAnimator === animation) onEnd()
                    }
                })
            }
            start()
        }
    }

    private fun getPointerRawX(event: MotionEvent, pointerId: Int): Float {
        val index = event.findPointerIndex(pointerId)
        return if (index >= 0 && index < event.pointerCount) event.getRawX(index) else event.rawX
    }

    private fun getPointerRawY(event: MotionEvent, pointerId: Int): Float {
        val index = event.findPointerIndex(pointerId)
        return if (index >= 0 && index < event.pointerCount) event.getRawY(index) else event.rawY
    }

    private fun handleTouchDown(event: MotionEvent) {
        if (isTouchDown) return
        activePointerId = event.getPointerId(0)
        downRawX = getPointerRawX(event, activePointerId)
        downRawY = getPointerRawY(event, activePointerId)
        isTouchDown = true
        isDragging = false
        thresholdDirection = 0
        isTouchOnToggle = isTouchOnToggle(event)
        parent?.requestDisallowInterceptTouchEvent(true)
        dragResetAnimator?.cancel()
        if (!isTouchOnToggle) {
            animateTouchScale(0.95f, 350L, DecelerateInterpolator())
            if (onToggleLyrics != null) {
                startLongPressCheck()
            }
        }
    }

    private fun handlePointerUp(event: MotionEvent) {
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        if (pointerId == activePointerId) {
            val newPointerIndex = if (pointerIndex == 0) 1 else 0
            if (newPointerIndex < event.pointerCount) {
                activePointerId = event.getPointerId(newPointerIndex)
                val currentDx = getPointerRawX(event, pointerId) - downRawX
                val currentDy = getPointerRawY(event, pointerId) - downRawY
                downRawX = event.getRawX(newPointerIndex) - currentDx
                downRawY = event.getRawY(newPointerIndex) - currentDy
            }
        }
    }

    private fun updateDrag(dx: Float) {
        val maxDrag = dp(80f).toFloat().coerceAtLeast(1f)
        val dampedDx = dp(28f) * kotlin.math.tanh((dx / maxDrag).toDouble()).toFloat()
        setDampedDx(dampedDx)

        val threshold = dp(48f).toFloat()
        val resetThreshold = (threshold - dp(8f).toFloat()).coerceAtLeast(0f)

        when {
            dx >= threshold -> {
                if (thresholdDirection != 1) {
                    thresholdDirection = 1
                    performHapticFeedbackSafely(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            dx <= -threshold -> {
                if (thresholdDirection != -1) {
                    thresholdDirection = -1
                    performHapticFeedbackSafely(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            dx in -resetThreshold..resetThreshold -> {
                thresholdDirection = 0
            }
        }
    }

    private fun setDampedDx(dampedDx: Float) {
        currentDampedDx = dampedDx
        translationX = baseTranslationX + currentDampedDx
    }

    private fun resetDampedDx() {
        dragResetAnimator?.cancel()
        val start = currentDampedDx
        if (abs(start) < 0.5f) {
            setDampedDx(0f)
            return
        }
        dragResetAnimator = ValueAnimator.ofFloat(start, 0f).apply {
            duration = 240L
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener { va ->
                setDampedDx(va.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (dragResetAnimator === animation) dragResetAnimator = null
                }
            })
            start()
        }
    }

    fun startSkipTransition(isNext: Boolean) {
        val direction = if (isNext) -1f else 1f
        val exitDx = direction * dp(24f).toFloat()
        val exitRot = direction * 12f

        contentContainer.animate().cancel()
        contentContainer.animate()
            .translationX(exitDx)
            .rotationY(exitRot)
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(160L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                contentContainer.translationX = -exitDx
                contentContainer.rotationY = -exitRot
                contentContainer.alpha = 0f
                contentContainer.scaleX = 0.92f
                contentContainer.scaleY = 0.92f

                contentContainer.animate()
                    .translationX(0f)
                    .rotationY(0f)
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(260L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handleTouchDown(event)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    return true
                }
                if (longPressTriggered) {
                    return true
                }
                if (!isTouchDown) {
                    return false
                }
                val rawX = getPointerRawX(event, activePointerId)
                val rawY = getPointerRawY(event, activePointerId)
                val dx = rawX - downRawX
                val dy = rawY - downRawY
                val absDx = abs(dx)
                val absDy = abs(dy)

                if (absDx > touchSlop || absDy > touchSlop) {
                    cancelLongPress()
                }

                if (absDy > touchSlop && absDy > absDx) {
                    isTouchDown = false
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    resetDampedDx()
                    animateTouchScale(1.0f, 160L, DecelerateInterpolator())
                    return false
                }

                if (absDx > touchSlop && absDx > absDy) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    updateDrag(dx)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                handlePointerUp(event)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                val wasToggleTouch = isTouchOnToggle
                isTouchDown = false
                isDragging = false
                thresholdDirection = 0
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                resetDampedDx()
                if (!longPressTriggered && !wasToggleTouch) {
                    animateTouchScale(1.0f, 220L, OvershootInterpolator(1.4f))
                }
                longPressTriggered = false
                isTouchOnToggle = false
                return false
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handleTouchDown(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTouchDown) {
                    return false
                }
                if (longPressTriggered) {
                    return true
                }
                val rawX = getPointerRawX(event, activePointerId)
                val rawY = getPointerRawY(event, activePointerId)
                val dx = rawX - downRawX
                val dy = rawY - downRawY
                val absDx = abs(dx)
                val absDy = abs(dy)

                if (absDx > touchSlop || absDy > touchSlop) {
                    cancelLongPress()
                }

                if (!isDragging) {
                    if (absDy > touchSlop && absDy > absDx) {
                        isTouchDown = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                        resetDampedDx()
                        animateTouchScale(1.0f, 160L, DecelerateInterpolator())
                        return false
                    }
                    if (absDx > touchSlop && absDx > absDy) {
                        isDragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }

                if (isDragging) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    updateDrag(dx)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                handlePointerUp(event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelLongPress()
                val wasToggleTouch = isTouchOnToggle
                val rawX = getPointerRawX(event, activePointerId)
                val rawY = getPointerRawY(event, activePointerId)
                val dx = rawX - downRawX
                val dy = rawY - downRawY
                val threshold = dp(48f).toFloat()
                val reachedThreshold = isDragging && (thresholdDirection != 0 || abs(dx) >= threshold)

                if (!longPressTriggered) {
                    if (reachedThreshold) {
                        performHapticFeedbackSafely(HapticFeedbackConstants.CONTEXT_CLICK)
                        val isNext = if (thresholdDirection != 0) thresholdDirection == -1 else dx < 0f
                        startSkipTransition(isNext)
                        if (isNext) onSkipToNext?.invoke() else onSkipToPrevious?.invoke()
                    } else if (!isDragging && abs(dx) <= touchSlop && abs(dy) <= touchSlop) {
                        onShowSystemMediaNotification?.invoke()
                    }
                }

                isTouchDown = false
                isDragging = false
                thresholdDirection = 0
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                resetDampedDx()
                if (!longPressTriggered && !wasToggleTouch) {
                    animateTouchScale(1.0f, 220L, OvershootInterpolator(1.4f))
                }
                longPressTriggered = false
                isTouchOnToggle = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                val wasToggleTouch = isTouchOnToggle
                isTouchDown = false
                isDragging = false
                thresholdDirection = 0
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                resetDampedDx()
                if (!longPressTriggered && !wasToggleTouch) {
                    animateTouchScale(1.0f, 220L, OvershootInterpolator(1.4f))
                }
                longPressTriggered = false
                isTouchOnToggle = false
                return true
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        touchScaleAnimator?.cancel()
        touchScaleAnimator = null
        touchScale = 1.0f
        isTouchDown = false
        isDragging = false
        thresholdDirection = 0
        activePointerId = MotionEvent.INVALID_POINTER_ID
        dragResetAnimator?.cancel()
        dragResetAnimator = null
        currentDampedDx = 0f
        cancelLongPress()
        longPressTriggered = false
        isTouchOnToggle = false
        trackView.isAwake = false
    }

    fun setAwake(awake: Boolean) {
        trackView.isAwake = awake
    }

    fun bind(
        title: String,
        artist: String,
        artwork: Bitmap?,
        playing: Boolean,
        appearance: MiniPlayerAppearance,
        applyPlatformMaterial: (ImageView, MiniPlayerAppearance) -> Unit,
        onToggle: () -> Unit,
        onSkipToPrevious: () -> Unit,
        onSkipToNext: () -> Unit,
        onShowSystemMediaNotification: () -> Unit,
        onToggleLyrics: (() -> Unit)? = null,
    ) {
        if (lastAppearance != appearance) {
            lastAppearance = appearance
            applyAppearance(appearance, applyPlatformMaterial)
            updateGeometry(appearance.heightDp)
        }
        lastTitle = title
        lastArtist = artist
        trackView.bind(title, artist, playing)
        if (lastArtwork !== artwork) {
            lastArtwork = artwork
            if (artwork != null) {
                this.artwork.setImageBitmap(artwork)
            } else {
                this.artwork.setImageDrawable(DefaultArtworkDrawable())
            }
        }
        if (lastPlaying != playing) {
            val animate = lastPlaying != null
            lastPlaying = playing
            playPauseDrawable.setPlaying(playing, animate = animate)
        }
        toggle.setOnClickListener {
            toggle.performHapticFeedbackSafely(HapticFeedbackConstants.KEYBOARD_TAP)
            onToggle()
        }
        this.onSkipToPrevious = onSkipToPrevious
        this.onSkipToNext = onSkipToNext
        this.onShowSystemMediaNotification = onShowSystemMediaNotification
        this.onToggleLyrics = onToggleLyrics
    }

    private fun applyAppearance(
        appearance: MiniPlayerAppearance,
        applyPlatformMaterial: (ImageView, MiniPlayerAppearance) -> Unit,
    ) {
        materialLayer.setImageDrawable(
            when (appearance.backgroundMode) {
                MINI_PLAYER_BACKGROUND_PURE -> rounded(appearance.pureColor, dp(40).toFloat())
                // Only a 1/255 anchor so the compositor keeps a drawable to register; the
                // visible tint comes solely from the platform blend, exactly like the shortcut.
                MINI_PLAYER_BACKGROUND_ADVANCED -> rounded(Color.argb(1, 255, 255, 255), dp(40).toFloat())
                MINI_PLAYER_BACKGROUND_SOFT_GLASS -> rounded(Color.argb(30, 255, 255, 255), dp(40).toFloat())
                else -> rounded(Color.argb(158, 31, 35, 36), dp(40).toFloat()).apply {
                    setStroke(dp(1), Color.argb(78, 255, 255, 255))
                }
            },
        )
        // Handed over for every mode so the carrier can release the previous mode's material even
        // when the new one needs no platform material (pure colour / built-in default).
        applyPlatformMaterial(materialLayer, appearance)

        trackView.setContentTheme(appearance.isDarkContent)

        val tint = if (appearance.isDarkContent) Color.argb(240, 20, 20, 20) else Color.WHITE
        playPauseDrawable.tintColor = tint

        val rippleColor = if (appearance.isDarkContent) {
            ColorStateList.valueOf(Color.argb(40, 0, 0, 0))
        } else {
            ColorStateList.valueOf(Color.argb(50, 255, 255, 255))
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        toggle.background = RippleDrawable(rippleColor, null, mask)
    }

    private fun updateGeometry(heightDp: Float) {
        val height = dp(heightDp).coerceAtLeast(dp(20))
        val verticalPadding = max(dp(2), height / 9)
        val availableHeight = (height - verticalPadding * 2).coerceAtLeast(dp(16))
        val artworkSize = (availableHeight * .85f).toInt().coerceAtLeast(dp(16))
        val horizontalPadding = max(dp(6), height / 7)
        artwork.layoutParams = LayoutParams(artworkSize, artworkSize, Gravity.CENTER_VERTICAL).apply {
            leftMargin = horizontalPadding
        }
        val toggleSize = min(dp(40), availableHeight.coerceAtLeast(dp(16)))
        toggle.layoutParams = LayoutParams(toggleSize, toggleSize, Gravity.CENTER_VERTICAL or Gravity.END).apply {
            rightMargin = max(dp(6), verticalPadding)
        }
        trackView.layoutParams = LayoutParams(-1, -1, Gravity.CENTER_VERTICAL).apply {
            leftMargin = horizontalPadding + artworkSize + max(dp(6), height / 8)
            rightMargin = toggleSize + max(dp(6), verticalPadding)
        }
        artwork.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, artworkSize * .2f)
            }
        }
        artwork.pivotX = artworkSize / 2f
        artwork.pivotY = artworkSize / 2f
        toggle.pivotX = toggleSize / 2f
        toggle.pivotY = toggleSize / 2f
        invalidateOutline()
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * density + .5f).toInt()
    private fun dp(value: Float): Int = (value * density + .5f).toInt()
}
