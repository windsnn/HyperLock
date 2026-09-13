package io.github.windsnn.hyperlock.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.view.View
import kotlin.math.min

/**
 * 锁屏迷你播放器专用歌名与歌手自绘控件：
 * 1. 双行/单行智能排版：无歌手或纯音轨时副行完全隐藏，主行绝对垂直居中；
 * 2. 首尾衔接循环跑马灯（Loop Tape）：超长文本以 30dp/s 匀速流动，首尾咬合无限循环，永不倒带抽搐；
 * 3. 左右硬件加速边缘羽化（DST_OUT 蒙版），文字严格约束在净空视口内，永不穿模封面与按钮；
 * 4. 播放状态与息屏感知：暂停或灭屏时彻底停止帧回调，零后台功耗。
 */
internal class LockscreenTrackView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val targetFadeWidth = dp(14f).toFloat()
    private val gap = dp(32f).toFloat()
    private val speedPxPerMs = (30f * density) / 1000f
    private val initialDelayMs = 1800L

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
        color = Color.argb(210, 255, 255, 255)
        typeface = Typeface.DEFAULT
    }

    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    private var leftFadeShader: LinearGradient? = null
    private var rightFadeShader: LinearGradient? = null
    private var lastShaderWidth = 0f
    private var activeFadeWidth = 0f

    var title: String = ""
        private set
    var artist: String = ""
        private set
    private var isPlaying: Boolean = false

    private var hasArtist: Boolean = false
    private var titleWidth: Float = 0f
    private var artistWidth: Float = 0f
    private var titleLoopDist: Float = 0f
    private var artistLoopDist: Float = 0f
    private var titleOverflow: Boolean = false
    private var artistOverflow: Boolean = false

    private var startUptimeMs: Long = 0L
    private var pausedElapsedMs: Long = 0L
    private var isFrameScheduled: Boolean = false

    private val frameCallback = Runnable {
        isFrameScheduled = false
        if (!shouldAnimate()) return@Runnable
        invalidate()
        scheduleNextFrame()
    }

    private var isDarkText: Boolean = false

    init {
        applyTheme()
    }

    fun setContentTheme(isDarkText: Boolean) {
        if (this.isDarkText == isDarkText) return
        this.isDarkText = isDarkText
        applyTheme()
        invalidate()
    }

    private fun applyTheme() {
        if (!isDarkText) {
            titlePaint.color = Color.WHITE
            artistPaint.color = Color.argb(210, 255, 255, 255)
        } else {
            titlePaint.color = Color.argb(240, 20, 20, 20)
            artistPaint.color = Color.argb(180, 45, 45, 45)
        }
    }

    private fun dp(value: Float): Int = (value * density + .5f).toInt()

    fun bind(newTitle: String, newArtist: String, playing: Boolean) {
        val contentChanged = title != newTitle || artist != newArtist
        val playStateChanged = isPlaying != playing

        title = newTitle
        artist = newArtist
        isPlaying = playing

        if (contentChanged) {
            startUptimeMs = SystemClock.uptimeMillis()
            pausedElapsedMs = 0L
            measureTextDimensions()
            requestLayout()
            invalidate()
        }

        if (playStateChanged) {
            val now = SystemClock.uptimeMillis()
            if (playing) {
                startUptimeMs = now - pausedElapsedMs
                scheduleNextFrame()
            } else {
                pausedElapsedMs = (now - startUptimeMs).coerceAtLeast(0L)
                removeCallbacks(frameCallback)
                isFrameScheduled = false
                invalidate()
            }
        }
    }

    private fun measureTextDimensions() {
        hasArtist = artist.isNotBlank() && !artist.equals(title, ignoreCase = true)
        titlePaint.textSize = if (hasArtist) dp(12.5f).toFloat() else dp(13.6f).toFloat()
        artistPaint.textSize = dp(11.2f).toFloat()
        titleWidth = if (title.isNotEmpty()) titlePaint.measureText(title) else 0f
        artistWidth = if (hasArtist && artist.isNotEmpty()) artistPaint.measureText(artist) else 0f
        titleLoopDist = titleWidth + gap
        artistLoopDist = artistWidth + gap
        updateOverflowState()
    }

    var isAwake: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                startUptimeMs = SystemClock.uptimeMillis()
                if (shouldAnimate()) {
                    scheduleNextFrame()
                }
                invalidate()
            } else {
                removeCallbacks(frameCallback)
                isFrameScheduled = false
            }
        }

    private fun updateOverflowState() {
        val avail = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(0f)
        titleOverflow = avail > 0f && titleWidth > avail
        artistOverflow = avail > 0f && hasArtist && artistWidth > avail
        if (shouldAnimate()) {
            scheduleNextFrame()
        }
    }

    private fun shouldAnimate(): Boolean {
        return isAwake && isPlaying && isShown && windowVisibility == VISIBLE && (titleOverflow || artistOverflow)
    }

    private fun scheduleNextFrame() {
        if (isFrameScheduled || !shouldAnimate()) return
        isFrameScheduled = true
        postOnAnimation(frameCallback)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateOverflowState()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            startUptimeMs = SystemClock.uptimeMillis()
            if (shouldAnimate()) {
                scheduleNextFrame()
            }
            invalidate()
        } else {
            removeCallbacks(frameCallback)
            isFrameScheduled = false
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && shouldAnimate()) {
            scheduleNextFrame()
        } else {
            removeCallbacks(frameCallback)
            isFrameScheduled = false
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(frameCallback)
        isFrameScheduled = false
    }

    private fun ensureShaders(w: Float) {
        val fade = min(targetFadeWidth, w / 4f)
        if (w == lastShaderWidth && fade == activeFadeWidth && leftFadeShader != null) return
        lastShaderWidth = w
        activeFadeWidth = fade
        leftFadeShader = LinearGradient(
            0f, 0f, fade, 0f,
            Color.BLACK, Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        rightFadeShader = LinearGradient(
            w - fade, 0f, w, 0f,
            Color.TRANSPARENT, Color.BLACK,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (title.isEmpty() && artist.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val avail = (w - paddingLeft - paddingRight).coerceAtLeast(0f)
        if (avail <= 0f) return

        val now = SystemClock.uptimeMillis()
        val elapsed = if (isPlaying) (now - startUptimeMs) - initialDelayMs else pausedElapsedMs - initialDelayMs

        val titleScrollX = if (titleOverflow && elapsed > 0 && titleLoopDist > 0f) {
            (elapsed * speedPxPerMs) % titleLoopDist
        } else {
            0f
        }
        val artistScrollX = if (artistOverflow && elapsed > 0 && artistLoopDist > 0f) {
            (elapsed * speedPxPerMs) % artistLoopDist
        } else {
            0f
        }

        val needLeftFade = (titleOverflow && titleScrollX > 1f) || (artistOverflow && artistScrollX > 1f)
        val needRightFade = titleOverflow || artistOverflow
        val needFade = needLeftFade || needRightFade

        val saveCount = if (needFade) {
            canvas.saveLayer(0f, 0f, w, h, null)
        } else {
            canvas.save()
        }

        if (hasArtist) {
            val titleFm = titlePaint.fontMetrics
            val artistFm = artistPaint.fontMetrics
            val titleH = titleFm.descent - titleFm.ascent
            val artistH = artistFm.descent - artistFm.ascent
            val lineGap = dp(2f).toFloat()
            val totalH = titleH + artistH + lineGap
            val topY = (h - totalH) / 2f
            val titleBaseline = topY - titleFm.ascent
            val artistBaseline = topY + titleH + lineGap - artistFm.ascent
            val splitY = topY + titleH + lineGap / 2f

            drawMarqueeLine(canvas, title, titleWidth, titleScrollX, titleOverflow, titleLoopDist, titleBaseline, titlePaint)
            drawMarqueeLine(canvas, artist, artistWidth, artistScrollX, artistOverflow, artistLoopDist, artistBaseline, artistPaint)

            if (needFade) {
                ensureShaders(w)
                val fade = activeFadeWidth
                // 1. 歌名行独立羽化（仅在上半区 [0, splitY] 生效，绝不向下侵入歌手行）
                if (titleOverflow) {
                    if (titleScrollX > 1f) {
                        fadePaint.shader = leftFadeShader
                        canvas.drawRect(0f, 0f, fade, splitY, fadePaint)
                    }
                    fadePaint.shader = rightFadeShader
                    canvas.drawRect(w - fade, 0f, w, splitY, fadePaint)
                }
                // 2. 歌手行独立羽化（仅在歌手自身超长且流动时，在下半区 [splitY, h] 生效）
                if (artistOverflow) {
                    if (artistScrollX > 1f) {
                        fadePaint.shader = leftFadeShader
                        canvas.drawRect(0f, splitY, fade, h, fadePaint)
                    }
                    fadePaint.shader = rightFadeShader
                    canvas.drawRect(w - fade, splitY, w, h, fadePaint)
                }
            }
        } else {
            val titleFm = titlePaint.fontMetrics
            val titleH = titleFm.descent - titleFm.ascent
            val titleBaseline = (h - titleH) / 2f - titleFm.ascent
            drawMarqueeLine(canvas, title, titleWidth, titleScrollX, titleOverflow, titleLoopDist, titleBaseline, titlePaint)

            if (needFade) {
                ensureShaders(w)
                val fade = activeFadeWidth
                if (needLeftFade) {
                    fadePaint.shader = leftFadeShader
                    canvas.drawRect(0f, 0f, fade, h, fadePaint)
                }
                if (needRightFade) {
                    fadePaint.shader = rightFadeShader
                    canvas.drawRect(w - fade, 0f, w, h, fadePaint)
                }
            }
        }

        canvas.restoreToCount(saveCount)
    }

    private fun drawMarqueeLine(
        canvas: Canvas,
        text: String,
        textW: Float,
        scrollX: Float,
        overflow: Boolean,
        loopDist: Float,
        baseline: Float,
        paint: TextPaint,
    ) {
        if (!overflow || loopDist <= 0f) {
            canvas.drawText(text, paddingLeft.toFloat(), baseline, paint)
            return
        }
        var x = paddingLeft.toFloat() - scrollX
        while (x < width - paddingRight) {
            if (x + textW > paddingLeft) {
                canvas.drawText(text, x, baseline, paint)
            }
            x += loopDist
        }
    }
}
