package io.github.windsnn.hyperlock.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.PathInterpolator
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.min
import kotlin.math.abs

/**
 * Canvas 绘制的播放/暂停变形 Drawable：progress = 0f 为暂停态（双立柱），progress = 1f 为播放态（三角形）。
 * 两态之间的路径连续插值，避免切换时的视觉跳变。
 */
internal class MorphingPlayPauseDrawable(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val pathLeft = Path()
    private val pathRight = Path()
    private val oval1 = RectF()
    private val oval2 = RectF()
    private val oval2_1 = RectF()
    private val oval2_2 = RectF()

    var tintColor: Int = Color.WHITE
        set(value) {
            if (field != value) {
                field = value
                paint.color = value
                invalidateSelf()
            }
        }

    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                invalidateSelf()
            }
        }

    private var currentAnimator: ValueAnimator? = null

    fun setPlaying(playing: Boolean, animate: Boolean = true) {
        val target = if (playing) 0f else 1f
        if (!animate) {
            currentAnimator?.cancel()
            currentAnimator = null
            progress = target
            return
        }
        if (abs(progress - target) < 0.001f) return
        currentAnimator?.cancel()
        val start = progress
        currentAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 280L
            interpolator = PathInterpolator(0.3f, 0.0f, 0.1f, 1.0f)
            addUpdateListener { va ->
                progress = va.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (currentAnimator === animation) currentAnimator = null
                }
            })
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val size = min(w, h)

        canvas.save()
        canvas.translate(cx, cy)

        // 优化形变过程中的轻度呼吸缩放：1.0 -> 0.95 -> 1.0
        val bounce = 1.0f - 0.05f * kotlin.math.sin(progress * kotlin.math.PI.toFloat())
        canvas.scale(bounce, bounce)

        drawMorphing(canvas, size, progress)

        canvas.restore()
    }

    private fun drawMorphing(canvas: Canvas, size: Float, p: Float) {
        val iconHeight = size * 0.46f
        val triSide = iconHeight
        val triWidth = triSide * 0.8660254f // side * sqrt(3)/2
        val xOffset = 1.2f * density
        val xBack = xOffset - triWidth / 2f
        val xTip = xOffset + triWidth / 2f
        val yTri = triSide / 2f
        val rTri = triSide * 0.13f
        val t = rTri * 1.7320508f // r * sqrt(3)

        val barHeight = iconHeight
        val barWidth = triSide * 0.25f
        val barGap = triSide * 0.30f
        val rBar = barWidth / 2f

        val left1 = -(barGap / 2f + barWidth)
        val right1 = -barGap / 2f
        val xLMid = (left1 + right1) / 2f

        val left2 = barGap / 2f
        val right2 = barGap / 2f + barWidth
        val xRMid = (left2 + right2) / 2f

        val cTopX = xBack + rTri
        val cTopY = -yTri + t

        val cTipX = xTip - 2f * rTri
        val cTipY = 0f

        val cBottomX = xBack + rTri
        val cBottomY = yTri - t

        val seamOverlap = 0.5f * density * p

        val cx1 = (1f - p) * xLMid + p * cTopX
        val cy1 = (1f - p) * (-barHeight / 2f + rBar) + p * cTopY
        val r1 = (1f - p) * rBar + p * rTri
        val sweep1 = 180f - 60f * p

        val cx2 = (1f - p) * xLMid + p * cTipX
        val cy2 = (1f - p) * (barHeight / 2f - rBar) + p * cTipY
        val r2 = (1f - p) * rBar + p * rTri
        val theta1 = 360f - 60f * p
        val sweep2 = 180f - 120f * p

        val k1x = (1f - p) * left1 + p * xBack
        val k1y = (1f - p) * (barHeight / 2f - rBar) + p * seamOverlap

        pathLeft.reset()
        pathLeft.moveTo(cx1 - r1, cy1)
        oval1.set(cx1 - r1, cy1 - r1, cx1 + r1, cy1 + r1)
        pathLeft.arcTo(oval1, 180f, sweep1, false)
        oval2.set(cx2 - r2, cy2 - r2, cx2 + r2, cy2 + r2)
        pathLeft.arcTo(oval2, theta1, sweep2, false)
        pathLeft.lineTo(k1x, k1y)
        pathLeft.close()

        val cx2_1 = (1f - p) * xRMid + p * cTipX
        val cy2_1 = (1f - p) * (-barHeight / 2f + rBar) + p * cTipY
        val r2_1 = (1f - p) * rBar + p * rTri
        val theta2 = 180f + 180f * p
        val sweep2_1 = 180f - 120f * p

        val cx2_2 = (1f - p) * xRMid + p * cBottomX
        val cy2_2 = (1f - p) * (barHeight / 2f - rBar) + p * cBottomY
        val r2_2 = (1f - p) * rBar + p * rTri
        val alpha2 = 60f * p
        val sweep2_2 = 180f - 60f * p

        val k2x = (1f - p) * left2 + p * xBack
        val k2y = (1f - p) * (-barHeight / 2f + rBar) - p * seamOverlap

        pathRight.reset()
        pathRight.moveTo(k2x, k2y)
        oval2_1.set(cx2_1 - r2_1, cy2_1 - r2_1, cx2_1 + r2_1, cy2_1 + r2_1)
        pathRight.arcTo(oval2_1, theta2, sweep2_1, false)
        oval2_2.set(cx2_2 - r2_2, cy2_2 - r2_2, cx2_2 + r2_2, cy2_2 + r2_2)
        pathRight.arcTo(oval2_2, alpha2, sweep2_2, false)
        pathRight.close()

        canvas.drawPath(pathLeft, paint)
        canvas.drawPath(pathRight, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = (32f * density + 0.5f).toInt()
    override fun getIntrinsicHeight(): Int = (32f * density + 0.5f).toInt()
}

internal class DefaultArtworkDrawable : Drawable() {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 255, 255, 255)
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0 || h <= 0) return
        canvas.drawRoundRect(0f, 0f, w, h, w * 0.22f, h * 0.22f, bgPaint)

        val scale = min(w, h) / 48f
        val stemLeft = 25f * scale
        val stemTop = 13f * scale
        val stemBottom = 31f * scale
        val stemWidth = 2.5f * scale
        val headRadiusX = 5.5f * scale
        val headRadiusY = 4f * scale
        val headCenterX = 21f * scale
        val headCenterY = 31f * scale

        canvas.drawRect(stemLeft, stemTop, stemLeft + stemWidth, stemBottom, notePaint)

        canvas.save()
        canvas.rotate(-25f, headCenterX, headCenterY)
        canvas.drawOval(
            headCenterX - headRadiusX,
            headCenterY - headRadiusY,
            headCenterX + headRadiusX,
            headCenterY + headRadiusY,
            notePaint,
        )
        canvas.restore()

        val flagPath = Path().apply {
            moveTo(stemLeft + stemWidth, stemTop)
            cubicTo(
                stemLeft + stemWidth + 8f * scale, stemTop + 3f * scale,
                stemLeft + stemWidth + 7f * scale, stemTop + 11f * scale,
                stemLeft + stemWidth + 3f * scale, stemTop + 14f * scale,
            )
            cubicTo(
                stemLeft + stemWidth + 5f * scale, stemTop + 9f * scale,
                stemLeft + stemWidth + 4f * scale, stemTop + 4f * scale,
                stemLeft + stemWidth, stemTop + 3f * scale,
            )
            close()
        }
        canvas.drawPath(flagPath, notePaint)
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = (45 * alpha) / 255
        notePaint.alpha = (200 * alpha) / 255
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        bgPaint.colorFilter = colorFilter
        notePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal fun View.performHapticFeedbackSafely(constant: Int): Boolean = runCatching {
    if (!isHapticFeedbackEnabled) isHapticFeedbackEnabled = true
    performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
}.recoverCatching {
    performHapticFeedback(constant)
}.getOrDefault(false)
