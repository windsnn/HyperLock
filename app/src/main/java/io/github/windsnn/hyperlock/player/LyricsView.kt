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
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.github.proify.lyricon.lyric.model.LyricWord
import kotlin.math.abs
import kotlin.math.max

/**
 * 行切换过渡时长（毫秒）。
 *
 * 与「歌词提前量」配合：提前量决定什么时候换，过渡决定换得多顺。
 */
private const val LYRIC_TRANSITION_DURATION_MS = 280L

/** 行切换过渡轻微滑动的位移量（dp）：微移 + 优雅交叉淡入淡出。 */
private const val LYRIC_TRANSITION_TRAVEL_DP = 10f

/**
 * 苹果音乐风格逐字卡拉OK与推镜跟随歌词文本控件：
 * 1. 毫秒级逐字点亮：未唱部分半透白（主行45% / 副行35%），已唱部分纯白高亮（主行100% / 副行85%）并伴有立体柔光；
 * 2. 人声进度驱动推镜（Camera Tracking）：镜头紧随当前 Active Word 稳健推移，唱在句首稳停句首，唱到句末稳停句末，绝无机械跑马灯；
 * 3. 左右边缘 20dp 软渐隐羽化（Soft Edge Alpha Fade）：利用硬件加速 DST_OUT 蒙版，文字自然隐入背景，杜绝生硬截断；
 * 4. 自适应高度与呼吸留白，从根本上解决大字号上下裁切。
 */
private class AppleLyricTextView(
    context: Context,
    val isDetailLine: Boolean = false,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val fadeWidth = dp(20f).toFloat()

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDetailLine) Color.argb(90, 255, 255, 255) else Color.argb(115, 255, 255, 255)
        isSubpixelText = true
    }

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDetailLine) Color.argb(220, 255, 255, 255) else Color.WHITE
        isSubpixelText = true
    }

    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    private var leftFadeShader: LinearGradient? = null
    private var rightFadeShader: LinearGradient? = null
    private var lastShaderWidth = 0

    var isBold: Boolean = false
        set(value) {
            field = value
            val tf = if (value) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            inactivePaint.typeface = tf
            activePaint.typeface = tf
            measureLayout()
            requestLayout()
            invalidate()
        }

    var textSizeSp: Float = 14f
        set(value) {
            if (field == value) return
            field = value
            val px = value * density
            inactivePaint.textSize = px
            activePaint.textSize = px
            measureLayout()
            requestLayout()
            invalidate()
        }

    var isDarkContent: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyThemeAndShadow()
            invalidate()
        }

    var shadowEnabled: Boolean = true
        set(value) {
            field = value
            applyThemeAndShadow()
            invalidate()
        }

    var karaokeEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            applyThemeAndShadow()
            invalidate()
        }

    var text: String = ""
        private set

    private var words: List<LyricWord> = emptyList()
    private var lineBegin: Long = 0L
    private var lineEnd: Long = 0L
    private var currentPosition: Long = 0L

    private class WordBox(
        val begin: Long,
        val end: Long,
        val startX: Float,
        val endX: Float,
    )

    private val wordBoxes = ArrayList<WordBox>()
    private var textWidth: Float = 0f

    // 镜头跟随平滑值
    private var displayScrollX: Float = 0f
    private var targetScrollX: Float = 0f
    private var isFirstFrameOfLine: Boolean = true

    init {
        applyThemeAndShadow()
    }

    private fun applyThemeAndShadow() {
        if (!isDarkContent) {
            inactivePaint.color = if (isDetailLine) Color.argb(90, 255, 255, 255) else Color.argb(115, 255, 255, 255)
            activePaint.color = if (isDetailLine) Color.argb(220, 255, 255, 255) else Color.WHITE
            if (shadowEnabled) {
                if (isDetailLine) {
                    inactivePaint.setShadowLayer(dp(2.4f).toFloat(), 0f, 0f, Color.argb(120, 0, 0, 0))
                    if (karaokeEnabled) {
                        activePaint.setShadowLayer(dp(3.0f).toFloat(), 0f, 0f, Color.argb(140, 255, 255, 255))
                    } else {
                        activePaint.setShadowLayer(dp(2.6f).toFloat(), 0f, 0f, Color.argb(130, 0, 0, 0))
                    }
                } else {
                    inactivePaint.setShadowLayer(dp(3.2f).toFloat(), 0f, 0f, Color.argb(140, 0, 0, 0))
                    if (karaokeEnabled) {
                        activePaint.setShadowLayer(dp(4.2f).toFloat(), 0f, 0f, Color.argb(180, 255, 255, 255))
                    } else {
                        activePaint.setShadowLayer(dp(3.4f).toFloat(), 0f, 0f, Color.argb(150, 0, 0, 0))
                    }
                }
            } else {
                inactivePaint.setShadowLayer(0f, 0f, 0f, 0)
                activePaint.setShadowLayer(0f, 0f, 0f, 0)
            }
        } else {
            inactivePaint.color = if (isDetailLine) Color.argb(120, 50, 50, 50) else Color.argb(140, 35, 35, 35)
            activePaint.color = if (isDetailLine) Color.argb(220, 25, 25, 25) else Color.argb(245, 15, 15, 15)
            if (shadowEnabled) {
                if (isDetailLine) {
                    inactivePaint.setShadowLayer(dp(2.4f).toFloat(), 0f, 0f, Color.argb(80, 255, 255, 255))
                    activePaint.setShadowLayer(dp(3.0f).toFloat(), 0f, 0f, Color.argb(120, 255, 255, 255))
                } else {
                    inactivePaint.setShadowLayer(dp(2.8f).toFloat(), 0f, 0f, Color.argb(80, 255, 255, 255))
                    activePaint.setShadowLayer(dp(3.8f).toFloat(), 0f, 0f, Color.argb(140, 255, 255, 255))
                }
            } else {
                inactivePaint.setShadowLayer(0f, 0f, 0f, 0)
                activePaint.setShadowLayer(0f, 0f, 0f, 0)
            }
        }
    }

    fun setText(
        newText: String,
        newWords: List<LyricWord>,
        begin: Long,
        end: Long,
        position: Long,
        snapScroll: Boolean = false,
    ) {
        val textChanged = text != newText
        text = newText
        words = newWords
        lineBegin = begin
        lineEnd = end
        currentPosition = position

        if (textChanged || snapScroll) {
            isFirstFrameOfLine = true
            displayScrollX = 0f
            targetScrollX = 0f
            if (textChanged) {
                measureLayout()
                requestLayout()
            }
        }
        invalidate()
    }

    fun updatePosition(position: Long) {
        if (currentPosition == position) return
        currentPosition = position
        invalidate()
    }

    private fun measureLayout() {
        if (text.isEmpty()) {
            textWidth = 0f
            wordBoxes.clear()
            return
        }
        textWidth = inactivePaint.measureText(text)
        wordBoxes.clear()
        if (words.isNotEmpty()) {
            var currentX = 0f
            for (w in words) {
                val wText = w.text.orEmpty()
                val wWidth = if (wText.isEmpty()) 0f else inactivePaint.measureText(wText)
                val begin = w.begin
                val end = if (w.end > begin) w.end else begin + w.duration.coerceAtLeast(100L)
                wordBoxes.add(WordBox(begin, end, currentX, currentX + wWidth))
                currentX += wWidth
            }
        }
    }

    private fun computeFillX(): Float {
        if (text.isEmpty()) return 0f
        // 关闭卡拉OK逐字染色，或纯静态文本（无时间轴）：全行以高亮色呈现
        if (!karaokeEnabled || (wordBoxes.isEmpty() && lineBegin <= 0L && lineEnd <= 0L)) {
            return textWidth
        }
        return computeVocalProgressX()
    }

    /**
     * 计算人声演唱的实际位置（无论是否开启变色染色，均用于长句推镜运镜跟随）。
     */
    private fun computeVocalProgressX(): Float {
        if (text.isEmpty()) return 0f
        // 1. 优先使用高精度逐字数据
        if (wordBoxes.isNotEmpty()) {
            val pos = currentPosition
            if (pos < wordBoxes.first().begin) return 0f
            if (pos >= wordBoxes.last().end) return textWidth

            for (box in wordBoxes) {
                if (pos < box.begin) {
                    return box.startX
                }
                if (pos <= box.end) {
                    val dur = (box.end - box.begin).coerceAtLeast(1L)
                    val ratio = (pos - box.begin).toFloat() / dur
                    return box.startX + (box.endX - box.startX) * ratio.coerceIn(0f, 1f)
                }
            }
            return textWidth
        }

        // 2. 无逐字数据时回退至整行推进
        val dur = (lineEnd - lineBegin).coerceAtLeast(1L)
        val progress = ((currentPosition - lineBegin).toFloat() / dur).coerceIn(0f, 1f)
        return textWidth * progress
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val fm = inactivePaint.fontMetrics
        val textHeight = fm.bottom - fm.top
        // 预留垂直阴影发光呼吸空间，彻底消除垂直截断
        val desiredHeight = (textHeight + dp(8f) + .5f).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (text.isEmpty()) return

        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        if (availableWidth <= 0f) return

        val vocalX = computeVocalProgressX()
        val fillX = computeFillX()
        val fm = inactivePaint.fontMetrics
        val textHeight = fm.bottom - fm.top
        val baseline = (height - textHeight) / 2f - fm.top

        val isOverflow = textWidth > availableWidth
        val baseX: Float
        val maxScrollX: Float

        if (!isOverflow) {
            // 短歌词优雅居中
            baseX = paddingLeft + (availableWidth - textWidth) / 2f
            displayScrollX = 0f
            targetScrollX = 0f
        } else {
            baseX = paddingLeft.toFloat()
            maxScrollX = textWidth - availableWidth
            // 视窗将当前唱到的字锚定在视窗前约 38% 位置，后方留足 62% 视野阅读后文
            targetScrollX = (vocalX - availableWidth * 0.38f).coerceIn(0f, maxScrollX)

            if (isFirstFrameOfLine) {
                displayScrollX = targetScrollX
                isFirstFrameOfLine = false
            } else {
                // 平滑阻尼跟随运镜
                displayScrollX += (targetScrollX - displayScrollX) * 0.22f
                if (abs(displayScrollX - targetScrollX) < 0.5f) {
                    displayScrollX = targetScrollX
                } else {
                    postInvalidateOnAnimation()
                }
            }
        }

        val drawX = baseX - displayScrollX
        val highlightRight = drawX + fillX

        val needLeftFade = isOverflow && displayScrollX > 1f
        val needRightFade = isOverflow && displayScrollX < (textWidth - availableWidth - 1f)
        val needFade = needLeftFade || needRightFade

        val saveCount = if (needFade) {
            canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        } else {
            canvas.save()
        }

        // 1. 底层：未激活歌词
        canvas.drawText(text, drawX, baseline, inactivePaint)

        // 2. 顶层：高亮歌词（精确裁切至当前唱到的字像素点）
        if (fillX > 0f) {
            canvas.save()
            canvas.clipRect(drawX, 0f, highlightRight, height.toFloat())
            canvas.drawText(text, drawX, baseline, activePaint)
            canvas.restore()
        }

        // 3. 左右 20dp 柔和透明度渐隐羽化（DST_OUT 蒙版，适应任意背景壁纸）
        if (needFade) {
            ensureShaders(width)
            if (needLeftFade) {
                fadePaint.shader = leftFadeShader
                canvas.drawRect(paddingLeft.toFloat(), 0f, paddingLeft + fadeWidth, height.toFloat(), fadePaint)
            }
            if (needRightFade) {
                fadePaint.shader = rightFadeShader
                canvas.drawRect(width - paddingRight - fadeWidth, 0f, width - paddingRight.toFloat(), height.toFloat(), fadePaint)
            }
        }

        canvas.restoreToCount(saveCount)
    }

    private fun ensureShaders(w: Int) {
        if (w != lastShaderWidth || leftFadeShader == null) {
            lastShaderWidth = w
            val pl = paddingLeft.toFloat()
            val pr = paddingRight.toFloat()
            leftFadeShader = LinearGradient(
                pl, 0f, pl + fadeWidth, 0f,
                Color.BLACK, Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            rightFadeShader = LinearGradient(
                w - pr - fadeWidth, 0f, w - pr, 0f,
                Color.TRANSPARENT, Color.BLACK,
                Shader.TileMode.CLAMP
            )
        }
    }

    private fun dp(value: Float): Int = (value * density + .5f).toInt()
}

/** 单行歌词视块：主行 + 副行（翻译 / 罗马音 / 次要文本）。 */
private class LyricLineView(
    context: Context,
    private val density: Float,
) : LinearLayout(context) {

    val mainLine = AppleLyricTextView(context, isDetailLine = false).apply {
        isBold = true
    }
    val detailLine = AppleLyricTextView(context, isDetailLine = true).apply {
        isBold = false
    }

    var hasDetail: Boolean = false
        private set

    init {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(mainLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(detailLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2f)
        })
    }

    fun bind(
        lyric: LockscreenLyricText,
        appearance: LockscreenLyricsAppearance,
        position: Long,
        snapScroll: Boolean = false,
    ) {
        hasDetail = lyric.detail.isNotBlank()
        mainLine.setText(
            newText = lyric.text,
            newWords = lyric.words,
            begin = lyric.begin,
            end = lyric.end,
            position = position,
            snapScroll = snapScroll,
        )
        if (hasDetail) {
            detailLine.visibility = View.VISIBLE
            detailLine.setText(
                newText = lyric.detail,
                newWords = emptyList(),
                begin = lyric.begin,
                end = lyric.end,
                position = position,
                snapScroll = snapScroll,
            )
        } else {
            detailLine.visibility = View.GONE
            detailLine.setText("", emptyList(), 0L, 0L, 0L)
        }
        applyAppearance(appearance)
    }

    fun syncProgress(position: Long) {
        mainLine.updatePosition(position)
        if (hasDetail && detailLine.visibility == View.VISIBLE) {
            detailLine.updatePosition(position)
        }
    }

    fun applyAppearance(appearance: LockscreenLyricsAppearance) {
        detailLine.visibility = if (hasDetail) View.VISIBLE else View.GONE
        val targetMargin = dp(appearance.detailGapDp)
        val lp = detailLine.layoutParams as? MarginLayoutParams
        if (lp != null && lp.topMargin != targetMargin) {
            lp.topMargin = targetMargin
            detailLine.layoutParams = lp
        }
        mainLine.textSizeSp = if (hasDetail) appearance.textSizeSp else appearance.textSizeSp + 1f
        detailLine.textSizeSp = appearance.textSizeSp * 0.82f
        mainLine.isDarkContent = appearance.isDarkContent
        detailLine.isDarkContent = appearance.isDarkContent
        mainLine.shadowEnabled = appearance.shadowEnabled
        detailLine.shadowEnabled = appearance.shadowEnabled
        mainLine.karaokeEnabled = appearance.karaokeEnabled
        detailLine.karaokeEnabled = appearance.karaokeEnabled
    }

    private fun dp(value: Float): Int = (value * density + .5f).toInt()
}

/**
 * 锁屏歌词卡片。
 *
 * 内部常驻两个同构文本视块并交替承担「当前行 / 待入场行」：
 * 换行时上一行向上微滑并淡出、下一行从下方微滑并淡入。
 * 每一行内部由 AppleLyricTextView 提供苹果音乐高精度逐字染色与人声推镜跟随。
 */
internal class LockscreenLyricsView(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val lineA = LyricLineView(context, density)
    private val lineB = LyricLineView(context, density)
    private var front = lineA
    private var back = lineB
    private var shownText: String? = null
    private var transitionGeneration = 0
    private var needsSnapOnNextBind = false

    val hasDetail: Boolean
        get() = front.hasDetail

    init {
        setBackgroundColor(Color.TRANSPARENT)
        clipToOutline = false
        // 取消写死的截断限制，让文字阴影与大字号自由呼吸展开
        clipChildren = false
        addView(lineA, lineParams())
        lineB.alpha = 0f
        addView(lineB, lineParams())
    }

    fun markNeedsSnap() {
        needsSnapOnNextBind = true
        cancelAllAnimations()
    }

    fun bind(
        lyric: LockscreenLyricText,
        appearance: LockscreenLyricsAppearance,
        position: Long = 0L,
    ) {
        if (lyric.text.isBlank()) return
        val mustSnap = needsSnapOnNextBind || shownText == null || !isShown
        needsSnapOnNextBind = false
        if (lyric.text == shownText && !mustSnap) {
            // 同一句且无需强刷：同步副行与实时位置，更新外观
            front.bind(lyric, appearance, position)
            back.applyAppearance(appearance)
            return
        }
        shownText = lyric.text
        if (mustSnap) {
            // 首次落字、重回前台或亮屏：直接就位，杜绝上一句的残留与退场过渡
            snapTo(lyric, appearance, position)
            return
        }
        transitionTo(lyric, appearance, position)
    }

    fun syncProgress(position: Long) {
        front.syncProgress(position)
    }

    /** 取消当前正在进行的换行淡入淡出动画，重置到静态就绪态，杜绝退场幽灵动画。 */
    fun cancelAllAnimations() {
        transitionGeneration++
        needsSnapOnNextBind = true
        front.animate().cancel()
        back.animate().cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllAnimations()
    }

    /** 直接落字（无过渡），并让尚未结束的过渡回调失效。 */
    private fun snapTo(
        lyric: LockscreenLyricText,
        appearance: LockscreenLyricsAppearance,
        position: Long,
    ) {
        transitionGeneration++
        front.animate().cancel()
        back.animate().cancel()
        front.bind(lyric, appearance, position, snapScroll = true)
        front.alpha = 1f
        front.translationY = 0f
        back.alpha = 0f
        back.translationY = 0f
    }

    private fun transitionTo(
        lyric: LockscreenLyricText,
        appearance: LockscreenLyricsAppearance,
        position: Long,
    ) {
        val outgoing = front
        val incoming = back
        val travel = dp(LYRIC_TRANSITION_TRAVEL_DP).toFloat()

        incoming.bind(lyric, appearance, position)
        incoming.animate().cancel()
        outgoing.animate().cancel()
        incoming.bringToFront()
        incoming.alpha = 0f
        incoming.translationY = travel
        incoming.visibility = View.VISIBLE

        val generation = ++transitionGeneration
        outgoing.animate()
            .alpha(0f)
            .translationY(-travel)
            .setDuration(LYRIC_TRANSITION_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (transitionGeneration != generation) return@withEndAction
                outgoing.alpha = 0f
                outgoing.translationY = 0f
            }
            .start()
        incoming.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(LYRIC_TRANSITION_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        front = incoming
        back = outgoing
    }

    private fun lineParams(): LayoutParams = LayoutParams(-1, -2, Gravity.CENTER).apply {
        val inset = dp(8f)
        leftMargin = inset
        rightMargin = inset
    }

    private fun dp(value: Float): Int = (value * density + .5f).toInt()

    companion object {
        /**
         * 动态计算歌词卡片高度：
         * 根据主行字号、副行字号与文字阴影留白自适应计算，彻底解决字号放大后的上下裁切问题。
         */
        fun lyricsCardHeight(
            context: Context,
            appearance: LockscreenLyricsAppearance,
            hasDetail: Boolean,
        ): Int {
            val density = context.resources.displayMetrics.density
            val mainSp = if (hasDetail) appearance.textSizeSp else appearance.textSizeSp + 1f
            val detailSp = appearance.textSizeSp * 0.82f
            val mainPx = mainSp * density * 1.38f
            val detailPx = if (hasDetail) {
                detailSp * density * 1.38f + (appearance.detailGapDp + 2f) * density
            } else {
                0f
            }
            val padding = 14f * density
            val total = (mainPx + detailPx + padding + .5f).toInt()
            return max(total, (46f * density + .5f).toInt())
        }
    }
}
