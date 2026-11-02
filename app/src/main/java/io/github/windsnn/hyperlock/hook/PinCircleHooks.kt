package io.github.windsnn.hyperlock.hook

import android.content.res.ColorStateList
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Outline
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import io.github.windsnn.hyperlock.hook.*
import io.github.windsnn.hyperlock.HyperSystemUiModule
import io.github.windsnn.hyperlock.material.applyLegacyBackdropMaterial
import io.github.windsnn.hyperlock.material.applySystemGlassMaterial
import io.github.windsnn.hyperlock.settings.*
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_PIN_CIRCLE_BACKGROUND_ENABLED
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_PIN_CIRCLE_ROW_SPACING
import io.github.windsnn.hyperlock.write
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 保存应用 PIN 键圆形视觉样式前的原生状态，用于关闭功能时还原。
 */
internal class LockscreenPinKeyState(
    val layoutListener: View.OnLayoutChangeListener,
    val stockBackground: Drawable?,
)

internal class LockscreenPinLabelSnapshot(view: TextView) {
    // 持有弱引用避免内存泄漏
    private val viewRef = WeakReference(view)
    val view: TextView? get() = viewRef.get()
    private val includeFontPadding = view.includeFontPadding
    private val textAlignment = view.textAlignment
    private val gravity = view.gravity
    private val paddingLeft = view.paddingLeft
    private val paddingTop = view.paddingTop
    private val paddingRight = view.paddingRight
    private val paddingBottom = view.paddingBottom
    private val ellipsize = view.ellipsize
    private val singleLine = view.isSingleLine
    private val maxLines = view.maxLines
    private val text = view.text
    private val textScaleX = view.textScaleX
    private val textColors = view.textColors
    private val visibility = view.visibility
    private val background = view.background
    private val translationX = view.translationX
    private val translationY = view.translationY

    fun restore() {
        val view = viewRef.get() ?: return
        view.includeFontPadding = includeFontPadding
        view.textAlignment = textAlignment
        view.gravity = gravity
        view.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
        view.ellipsize = ellipsize
        // isSingleLine 会重置 maxLines，因此必须优先赋值
        view.isSingleLine = singleLine
        view.maxLines = maxLines
        view.text = text
        view.textScaleX = textScaleX
        view.setTextColor(textColors)
        view.visibility = visibility
        view.background = background
        view.translationX = translationX
        view.translationY = translationY
    }
}

internal val lockscreenPinRoots = Collections.synchronizedSet(
    Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
)
internal val lockscreenPinKeyStates = WeakHashMap<View, LockscreenPinKeyState>()
internal val lockscreenPinLabelSnapshots = WeakHashMap<ViewGroup, List<LockscreenPinLabelSnapshot>>()

internal fun HyperSystemUiModule.installLockscreenPinCircleBackgroundHook(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    runCatching {
        val pinViewClass = classLoader.loadClass(KEYGUARD_PIN_VIEW_CLASS)
        val onFinishInflate = pinViewClass.getDeclaredMethod("onFinishInflate")
        attachHook(onFinishInflate)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("lockscreen-pin-circle-background")
            .intercept { chain ->
                val result = chain.proceed()
                val pinView = chain.thisObject as? View
                pinView?.let { view ->
                    // 缓存 View 引用以支持配置动态热重载
                    lockscreenPinRoots += view
                    view.post {
                        if (!preferences.getBoolean(KEY_LOCKSCREEN_PIN_CIRCLE_BACKGROUND_ENABLED, false)) {
                            return@post
                        }
                        installLockscreenPinCircleBackgrounds(view, classLoader, preferences)
                    }
                }
                result
            }
    }.onFailure { error ->
        hookLog(Log.ERROR, TAG, "Could not install lockscreen PIN circle-background hook", error)
    }
    registerSettingsReloadSlot(LockscreenPinCircleReloadSlot(this))
}

/** 设置变化后就地切换 PIN 数字键圆形背景：开启时重新下发，关闭时按快照还原。 */
internal class LockscreenPinCircleReloadSlot(private val module: HyperSystemUiModule) : SettingsReloadSlot {
    override val id = "lockscreen-pin-circle"

    override fun reload(preferences: SharedPreferences, classLoader: ClassLoader) {
        val roots = synchronized(lockscreenPinRoots) { lockscreenPinRoots.toList() }
        for (root in roots) {
            // 无论当前是否挂载均执行应用，排队任务将在挂载时生效
            runCatching {
                if (preferences.getBoolean(KEY_LOCKSCREEN_PIN_CIRCLE_BACKGROUND_ENABLED, false)) {
                    module.installLockscreenPinCircleBackgrounds(root, classLoader, preferences)
                } else {
                    module.removeLockscreenPinCircleBackgrounds(root)
                }
            }.onFailure { error ->
                module.hookLog(Log.ERROR, TAG, "Could not reload PIN circle background", error)
            }
        }
    }
}

internal fun HyperSystemUiModule.installLockscreenPinCircleBackgrounds(
    root: View,
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    val keys = lockscreenPinKeys(root)
    keys.forEach { key ->
        key.post {
            val keyGroup = key as? ViewGroup ?: return@post
            val diameter = minOf(key.width, key.height)
            if (diameter <= 0) return@post
            for (index in keyGroup.childCount - 1 downTo 0) {
                if (keyGroup.getChildAt(index).tag == LOCKSCREEN_PIN_CIRCLE_TAG) {
                    keyGroup.removeViewAt(index)
                }
            }
            val material = ImageView(key.context).apply {
                tag = LOCKSCREEN_PIN_CIRCLE_TAG
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_XY
                // 合成器需要有效 Drawable 触发绘制，此处设置低透明度兜底背景，Shader 渲染于相同边界
                setImageDrawable(
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(PIN_CIRCLE_FALLBACK_FILL_ALPHA, 255, 255, 255))
                    },
                )
                val ripple = RippleDrawable(
                    ColorStateList.valueOf(LOCKSCREEN_PIN_CIRCLE_RIPPLE_COLOR),
                    null,
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.WHITE)
                    },
                )
                // 在 Foreground 绘制边缘描边以维持抗锯齿效果
                foreground = LayerDrawable(
                    arrayOf(
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.TRANSPARENT)
                            setStroke(
                                PIN_CIRCLE_EDGE_WIDTH,
                                Color.argb(PIN_CIRCLE_EDGE_ALPHA, 255, 255, 255),
                            )
                        },
                        ripple,
                    ),
                )
                // 保留 Outline 供原生玻璃材质识别边界，避免裁剪
                clipToOutline = false
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(target: View, outline: Outline) {
                        val radius = minOf(target.width, target.height) / 2f
                        outline.setRoundRect(0, 0, target.width, target.height, radius)
                    }
                }
            }
            keyGroup.addView(material, 0, ViewGroup.LayoutParams(diameter, diameter))
            // 清除 NumPadKey 原生按压扩散背景，改由圆形涟漪提供视觉反馈
            val previousState = lockscreenPinKeyStates.remove(key)
            previousState?.let { key.removeOnLayoutChangeListener(it.layoutListener) }
            val stockBackground = if (previousState != null) {
                previousState.stockBackground
            } else {
                key.background
            }
            key.background = null
            var materialUpdatePosted = false
            fun placeMaterial(target: View, layer: View) {
                val size = minOf(target.width, target.height)
                if (size <= 0) return
                // 仅在尺寸变更时更新 layoutParams，避免触发不必要的 requestLayout
                val params = layer.layoutParams
                if (params == null || params.width != size || params.height != size) {
                    val updated = params ?: ViewGroup.LayoutParams(size, size)
                    updated.width = size
                    updated.height = size
                    layer.layoutParams = updated
                }
                val left = ((target.width - size) / 2f).roundToInt()
                val top = ((target.height - size) / 2f).roundToInt()
                if (layer.left != left || layer.top != top ||
                    layer.right != left + size || layer.bottom != top + size
                ) {
                    layer.layout(left, top, left + size, top + size)
                    layer.invalidateOutline()
                }
            }
            // 仅通过弱引用持有 View，避免泄漏
            val keyRef = WeakReference(key)
            val materialRef = WeakReference(material)
            // 仅在真实尺寸变更时触发重新布局
            val layoutListener = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    changed: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                ) {
                    if (left == oldLeft && top == oldTop && right == oldRight && bottom == oldBottom) {
                        return
                    }
                    if (materialUpdatePosted) return
                    materialUpdatePosted = true
                    val self = this
                    val target = keyRef.get() ?: return
                    target.post {
                        materialUpdatePosted = false
                        val currentTarget = keyRef.get() ?: return@post
                        val layer = materialRef.get() ?: return@post
                        if (!currentTarget.isAttachedToWindow) return@post
                        // 跳过功能已关闭后的遗留任务
                        if (lockscreenPinKeyStates[currentTarget]?.layoutListener !== self) return@post
                        placeMaterial(currentTarget, layer)
                        (currentTarget as? ViewGroup)?.let { configureLockscreenPinLabels(it) }
                    }
                }
            }
            key.addOnLayoutChangeListener(layoutListener)
            placeMaterial(key, material)
            key.setOnTouchListener { _, event ->
                material.isPressed = event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_MOVE
                false
            }
            lockscreenPinKeyStates[key] = LockscreenPinKeyState(layoutListener, stockBackground)
            configureLockscreenPinLabels(keyGroup)
            runCatching {
                // 保留混合与高光但置零背景模糊半径，防止与 Bionics 叠加形成双重模糊
                applyLegacyBackdropMaterial(
                    view = material,
                    opacity = DEFAULT_ADVANCED_MATERIAL_OPACITY,
                    blurRadius = 0,
                    color = DEFAULT_ADVANCED_MATERIAL_COLOR,
                    showHighlight = true,
                )
                applySystemGlassMaterial(
                    view = material,
                    classLoader = classLoader,
                    blurRadius = DEFAULT_SOFT_GLASS_BLUR_RADIUS,
                    luminance = DEFAULT_SOFT_GLASS_LUMINANCE,
                )
            }.onFailure { error ->
                hookLog(Log.ERROR, TAG, "Could not initialize PIN key material", error)
            }
        }
    }
    applyLockscreenPinRowSpacing(
        root = root,
        spacingDp = preferences.getFloat(KEY_LOCKSCREEN_PIN_CIRCLE_ROW_SPACING, 0f),
    )
    log(
        Log.INFO,
        TAG,
        "Applied PIN material to ${keys.size} key(s), rowSpacing=" +
            "${preferences.getFloat(KEY_LOCKSCREEN_PIN_CIRCLE_ROW_SPACING, 0f)}dp",
    )
}

/** 还原由 [installLockscreenPinCircleBackgrounds] 所做的全部 PIN 键视图修改。 */
internal fun HyperSystemUiModule.removeLockscreenPinCircleBackgrounds(root: View) {
    var touched = false
    lockscreenPinKeys(root).forEach { key ->
        val keyGroup = key as? ViewGroup ?: return@forEach
        for (index in keyGroup.childCount - 1 downTo 0) {
            if (keyGroup.getChildAt(index).tag == LOCKSCREEN_PIN_CIRCLE_TAG) {
                keyGroup.removeViewAt(index)
                touched = true
            }
        }
        lockscreenPinKeyStates.remove(key)?.let { state ->
            key.removeOnLayoutChangeListener(state.layoutListener)
            key.setOnTouchListener(null)
            key.background = state.stockBackground
            touched = true
        }
        lockscreenPinLabelSnapshots.remove(keyGroup)?.forEach { snapshot ->
            snapshot.view?.let { lockscreenPinKlondikeNormalized.remove(it) }
            snapshot.restore()
            touched = true
        }
    }
    // 仅在曾修改过行间距时执行归零复原，避免覆盖原生偏移
    if (touched) applyLockscreenPinRowSpacing(root = root, spacingDp = 0f)
}

internal fun HyperSystemUiModule.lockscreenPinKeys(root: View): List<View> {
    val keys = ArrayList<View>(10)
    fun visit(view: View) {
        if (view.idName() in LOCKSCREEN_PIN_KEY_IDS) keys += view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
    }
    visit(root)
    return keys
}

internal fun HyperSystemUiModule.configureLockscreenPinLabels(key: ViewGroup) {
    val density = key.resources.displayMetrics.density
    val keyId = key.idName().orEmpty()
    var digitTextView: TextView? = null
    var klondikeTextView: TextView? = null

    fun visit(view: View) {
        if (view is TextView) {
            if (view.idName() == "digit_text") {
                digitTextView = view
            } else if (view.idName() == "klondike_text") {
                klondikeTextView = view
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
    }
    visit(key)

    // 首次修改前捕获快照，用于功能关闭时还原原生标签
    if (!lockscreenPinLabelSnapshots.containsKey(key)) {
        lockscreenPinLabelSnapshots[key] = listOfNotNull(digitTextView, klondikeTextView)
            .map { LockscreenPinLabelSnapshot(it) }
    }

    val digitText = digitTextView?.text?.toString().orEmpty()
    val isKey1 = keyId == "key1" || digitText == "1"
    val isKey0 = keyId == "key0" || digitText == "0"

    val circleCenterX = key.width / 2f
    val circleCenterY = key.height / 2f

    klondikeTextView?.let { kv ->
        kv.includeFontPadding = false
        kv.textAlignment = View.TEXT_ALIGNMENT_CENTER
        kv.gravity = Gravity.CENTER
        kv.setPadding(0, 0, 0, 0)
        kv.ellipsize = null
        // isSingleLine 会内部重置 maxLines，故仅首次设置
        if (lockscreenPinKlondikeNormalized.add(kv)) {
            kv.isSingleLine = false
        }
        kv.maxLines = 1
        kv.setHorizontallyScrolling(false)

        if (isKey1) {
            kv.visibility = View.INVISIBLE
            kv.background = null
            kv.setTextColor(Color.TRANSPARENT)
            // 避免重复写入相同文本触发 requestLayout
            if (kv.text.toString() != "ABC") {
                kv.text = "ABC"
            }
            kv.translationX = 0f
            kv.translationY = 0f
        } else if (isKey0) {
            kv.visibility = View.VISIBLE
            kv.background = null
            if (kv.text.isNullOrEmpty() || kv.text == " ") {
                kv.text = "+"
            }
            if (kv.textScaleX != 1.0f) {
                kv.textScaleX = 1.0f
            }
        } else {
            kv.visibility = View.VISIBLE
            kv.background = null
            if (kv.textScaleX != 0.86f) {
                kv.textScaleX = 0.86f
            }
        }
    }

    digitTextView?.let { dv ->
        dv.includeFontPadding = false
        dv.textAlignment = View.TEXT_ALIGNMENT_CENTER
        dv.gravity = Gravity.CENTER
        dv.setPadding(0, 0, 0, 0)

        if (key.width > 0 && key.height > 0 && dv.width > 0 && dv.height > 0) {
            val label = klondikeTextView
            val contentTop = dv.top.toFloat()
            val contentBottom = if (
                label != null && label.visibility != View.GONE && label.height > 0
            ) {
                label.bottom.toFloat()
            } else {
                dv.bottom.toFloat()
            }
            val blockCenterOffsetY = circleCenterY - (contentTop + contentBottom) / 2f
            val maxHorizontalTextOffset = 8f * density
            val horizontalNudge = -0.5f * density
            val digitOpticalX = dv.leftAlignedTextOffset(maxHorizontalTextOffset) + horizontalNudge

            // 保持数字与副标题垂直对齐居中
            dv.setTranslationIfChanged(
                circleCenterX - (dv.left + dv.width / 2f) + digitOpticalX,
                blockCenterOffsetY,
            )

            if (label != null && label.visibility == View.VISIBLE && label.height > 0) {
                val labelOpticalX = label.leftAlignedTextOffset(maxHorizontalTextOffset)
                label.setTranslationIfChanged(
                    circleCenterX - (label.left + label.width / 2f) + labelOpticalX,
                    blockCenterOffsetY,
                )
            }
        }
    }
}

internal fun View.setTranslationIfChanged(x: Float, y: Float) {
    if (abs(translationX - x) >= CENTER_EPSILON) {
        translationX = x
    }
    if (abs(translationY - y) >= CENTER_EPSILON) {
        translationY = y
    }
}

/** 计算左对齐文本在 View 内部居中所需的正向水平偏移量。 */
internal fun TextView.leftAlignedTextOffset(maxOffset: Float): Float {
    val textLayout = layout ?: return 0f
    if (textLayout.lineCount == 0) return 0f
    val lineWidth = textLayout.getLineWidth(0)
    if (!lineWidth.isFinite() || lineWidth <= 0f) return 0f
    return ((width - lineWidth) / 2f).coerceIn(0f, maxOffset)
}

internal fun HyperSystemUiModule.applyLockscreenPinRowSpacing(root: View, spacingDp: Float) {
    val rows = LinkedHashMap<String, View>(4)
    fun visit(view: View) {
        view.idName()?.takeIf { it in LOCKSCREEN_PIN_ROW_IDS }?.let { rows[it] = view }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
    }
    visit(root)
    val spacingPx = (
        spacingDp.coerceIn(PIN_ROW_SPACING_MIN, PIN_ROW_SPACING_MAX) *
            root.resources.displayMetrics.density
        ).roundToInt().toFloat()
    LOCKSCREEN_PIN_ROW_IDS.forEachIndexed { index, id ->
        // 固定第 4 行，正间距向上展开避开指纹区域
        rows[id]?.translationY = -spacingPx * (LOCKSCREEN_PIN_ROW_IDS.lastIndex - index)
    }
}
