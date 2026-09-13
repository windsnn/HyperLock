package io.github.windsnn.hyperlock.material

import android.graphics.Color
import android.content.res.Resources
import android.view.View
import android.widget.ImageView
import io.github.windsnn.hyperlock.HyperLog
import io.github.windsnn.hyperlock.hook.*
import io.github.windsnn.hyperlock.hook.MAX_SHORTCUT_BACKDROP_BLUR_RADIUS
import io.github.windsnn.hyperlock.hook.MAX_SHORTCUT_GLASS_BLUR_RADIUS
import io.github.windsnn.hyperlock.hook.MAX_SHORTCUT_GLASS_LARGE_BLUR_RADIUS
import io.github.windsnn.hyperlock.hook.MAX_SHORTCUT_GLASS_LUMINANCE
import io.github.windsnn.hyperlock.hook.MAX_SHORTCUT_OPACITY
import io.github.windsnn.hyperlock.hook.MI_GLASS_COMPAT_CLASS
import io.github.windsnn.hyperlock.hook.FALLBACK_GLASS_BASE_PARAMETERS
import io.github.windsnn.hyperlock.settings.*
import io.github.windsnn.hyperlock.settings.shortcutBloomStrokeParameters
import io.github.windsnn.hyperlock.settings.SHORTCUT_GLASS_BLEND_MODE
import io.github.windsnn.hyperlock.settings.SHORTCUT_GLASS_BLUR_MODE
import io.github.windsnn.hyperlock.settings.SHORTCUT_GLASS_MATERIAL_TYPE
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * 系统元素混合的一层：颜色 + 混合模式，逐层交给 `View.addMiBackgroundBlendColor`。
 */
private data class ElementBlendLayer(val color: Int, val mode: Int)

private val fallbackElementBlendLayers: List<ElementBlendLayer> =
    FALLBACK_ELEMENT_BLEND_COLORS.indices.map { index ->
        ElementBlendLayer(FALLBACK_ELEMENT_BLEND_COLORS[index], FALLBACK_ELEMENT_BLEND_MODES[index])
    }

/**
 * 读取系统元素混合的三层参数。
 *
 * 与玻璃参数一样，平台把这三层写在资源里：`MediaViewBlurOnKeyguardEffect` 读出
 * `media_notification_element_blend_keyguard_color_*` 与 `media_notification_blend_mode_*`，
 * 再交给 `NotificationUtil.applyElementViewBlend`，所以资源就是当前 ROM 的唯一真值。
 */
private fun resolveSystemElementBlendLayers(resources: Resources?): List<ElementBlendLayer> {
    if (resources == null) return fallbackElementBlendLayers
    val layers = ArrayList<ElementBlendLayer>(fallbackElementBlendLayers.size)
    for (index in SYSTEM_ELEMENT_BLEND_COLOR_RESOURCES.indices) {
        val colorId = resources.resourceId(SYSTEM_ELEMENT_BLEND_COLOR_RESOURCES[index], "color")
        val modeId = resources.resourceId(SYSTEM_ELEMENT_BLEND_MODE_RESOURCES[index], "integer")
        if (colorId == 0 || modeId == 0) continue
        val color = runCatching { resources.getColor(colorId, null) }.getOrNull() ?: continue
        val mode = runCatching { resources.getInteger(modeId) }.getOrNull() ?: continue
        layers += ElementBlendLayer(color, mode)
    }
    return layers.ifEmpty { fallbackElementBlendLayers }
}

/** 容器背景模糊半径：未覆盖时直接用系统 `notification_container_blur_radius` 的像素值。 */
private fun resolveElementBlendBlurPx(resources: Resources?, blurRadiusDp: Int): Int {
    val density = resources?.displayMetrics?.density ?: 1f
    if (blurRadiusDp >= 0) {
        return (blurRadiusDp.coerceIn(0, 100) * density).roundToInt()
    }
    val fromResources = resources?.let { res ->
        runCatching {
            val id = res.resourceId(SYSTEM_ELEMENT_BLEND_BLUR_RESOURCE, "dimen")
            if (id == 0) 0 else res.getDimensionPixelSize(id)
        }.getOrDefault(0)
    } ?: 0
    return if (fromResources > 0) fromResources else (FALLBACK_ELEMENT_BLEND_BLUR_DP * density).roundToInt()
}

/** 资源查询：先按 SystemUI 包名找，找不到再退回目标进程自身的资源表。 */
private fun Resources.resourceId(name: String, type: String): Int =
    getIdentifier(name, type, SYSTEM_UI).takeIf { it != 0 } ?: getIdentifier(name, type, null)

/**
 * 已经下发过高光的承载层。轮廓高光写在 View 上，只能靠再下发一份空参数注销；为了不去碰
 * 从未开过高光的视图（平台接口是 vendor 私有的，无谓调用没有收益），这里登记「谁开过高光」，
 * 只有登记过的视图才会走注销路径。承载层被回收后条目由 WeakHashMap 自动清除。
 */
private val bloomStrokedViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

/** 下发参数布局与系统 `HyperBloomStrokeUtils` 一致，清空用的就是它的 `EMPTY`（全零）。 */
private fun setBloomStroke(view: View, parameters: FloatArray) {
    View::class.java.getMethod("setMiBloomStroke", FloatArray::class.java).invoke(view, parameters)
}

/**
 * 清空承载层上的轮廓高光，只有确实下发过、且尚未注销的视图才会真的调用平台接口。
 *
 * 高光是写在 View 上的一次性渲染状态，不会随底色/模糊变化自动回收：承载层在「高级材质」档位里
 * 被反复复用（迷你播放器切档、滑滑块、切开关都走同一块 materialLayer），所以关闭开关时必须显式
 * 下发全零参数注销它，否则上一次的高光会一直画着，直到视图被重建（重启作用域）。
 */
internal fun clearBloomStroke(view: View) {
    if (!bloomStrokedViews.containsKey(view)) return
    setBloomStroke(view, clearedBloomStrokeParameters())
    bloomStrokedViews.remove(view)
}

/** 开关打开时下发高光并登记，关闭时注销上一次的高光。 */
private fun applyBloomStroke(view: View, showHighlight: Boolean) {
    if (!showHighlight) {
        clearBloomStroke(view)
        return
    }
    setBloomStroke(view, shortcutBloomStrokeParameters(view.resources.displayMetrics.density))
    bloomStrokedViews[view] = true
}

/**
 * 高级材质：对齐系统自己的元素混合做法
 * （`NotificationUtil.applyElementViewBlend` + `applyContainerViewBlur`）。
 *
 * - 三项滑块的默认值就是系统值（基底色 #A0A0A0、不做 alpha 缩放、100dp 容器模糊），
 *   所以不动滑块时下发结果与系统锁屏媒体卡片完全一致；
 * - 颜色只管基底（第一层），系统的高光/折射层保持原样；
 * - 不透明度等价于系统 `MiBlurCompat.setMiBackgroundBlendColors(view, pairs, ratio)` 的 alpha 缩放。
 */
internal fun applySystemElementBlendMaterial(
    view: View,
    color: Int,
    opacity: Int,
    blurRadiusDp: Int,
    showHighlight: Boolean,
) {
    val viewClass = View::class.java
    viewClass.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType)
        .invoke(view, ELEMENT_BLEND_VIEW_BLUR_MODE)
    viewClass.getMethod("clearMiBackgroundBlendColor").invoke(view)

    val alphaScale = if (opacity >= 0) opacity.coerceIn(0, 100) / 100f else 1f
    val addBlendColor = viewClass.getMethod(
        "addMiBackgroundBlendColor",
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
    )
    resolveSystemElementBlendLayers(view.resources).forEachIndexed { index, layer ->
        var argb = layer.color
        if (index == 0) {
            argb = Color.argb(
                Color.alpha(argb),
                Color.red(color),
                Color.green(color),
                Color.blue(color),
            )
        }
        if (alphaScale != 1f) {
            argb = Color.argb(
                (Color.alpha(argb) * alphaScale).roundToInt().coerceIn(0, 255),
                Color.red(argb),
                Color.green(argb),
                Color.blue(argb),
            )
        }
        addBlendColor.invoke(view, argb, layer.mode)
    }

    viewClass.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType)
        .invoke(view, ELEMENT_BLEND_BACKGROUND_BLUR_MODE)
    viewClass.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType)
        .invoke(view, resolveElementBlendBlurPx(view.resources, blurRadiusDp))
    viewClass.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType)
        .invoke(view, true)
    applyBloomStroke(view, showHighlight)
}

/**
 * 旧版单通道混合路径（blend mode 101）。高级材质已改用
 * [applySystemElementBlendMaterial]，这里保留给锁屏数字键圆形等固定材质使用。
 */
internal fun applyLegacyBackdropMaterial(
    view: View,
    opacity: Int,
    blurRadius: Int,
    color: Int,
    showHighlight: Boolean,
) {
    val viewClass = View::class.java
    viewClass.getMethod("clearMiBackgroundBlendColor").invoke(view)
    viewClass.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType)
        .invoke(view, true)
    viewClass.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType)
        .invoke(view, SHORTCUT_GLASS_BLUR_MODE)
    viewClass.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType)
        .invoke(view, SHORTCUT_GLASS_BLUR_MODE)
    viewClass.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType)
        .invoke(view, blurRadius.coerceIn(0, MAX_SHORTCUT_BACKDROP_BLUR_RADIUS))
    viewClass.getMethod(
        "addMiBackgroundBlendColor",
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
    ).invoke(
        view,
        Color.argb(
            opacity.coerceIn(0, MAX_SHORTCUT_OPACITY) * 255 / MAX_SHORTCUT_OPACITY,
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        ),
        SHORTCUT_GLASS_BLEND_MODE,
    )
    applyBloomStroke(view, showHighlight)
}

/** 资源解析结果按「资源名」缓存，系统资源在进程生命周期内不会变，无需失效。 */
private val systemGlassParamsCache = ConcurrentHashMap<String, FloatArray>()

/**
 * 读取系统当前真正使用的玻璃参数向量。
 *
 * 平台不把这组数值写在代码里：`NotifiFullAodController.parseFloatArrayRes` 与
 * `MediaViewGlassOnKeyguardEffect` 等都是解析 string-array 资源得到它，因此资源就是本版本 ROM
 * 的唯一真值。读资源还能让材质自动跟随 ROM 更新，而不是停留在某一个版本的快照上。
 *
 * 参数在 [SYSTEM_GLASS_PARAM_ARRAY_NAMES] 里按「锁屏静止态 → 锁屏焦点态 → 普通态」尝试，
 * 与系统给锁屏卡片选参数的顺序一致。
 */
internal fun resolveSystemGlassParams(resources: Resources?): FloatArray? {
    if (resources == null) return null
    for (name in SYSTEM_GLASS_PARAM_ARRAY_NAMES) {
        systemGlassParamsCache[name]?.let { return it.clone() }
        val params = runCatching {
            val id = resources.getIdentifier(name, "array", SYSTEM_UI).takeIf { it != 0 }
                ?: resources.getIdentifier(name, "array", null)
            if (id == 0) return@runCatching null
            resources.getStringArray(id)
                .mapNotNull { it?.trim() }
                .filter { it.isNotEmpty() }
                .map { it.toFloat() }
                .toFloatArray()
        }.getOrNull()
        if (params != null && params.size >= GLASS_PARAM_COUNT) {
            systemGlassParamsCache[name] = params
            return params.clone()
        }
    }
    return null
}

internal fun buildNativeSoftGlassParameters(
    resources: Resources?,
    luminance: Float,
    color: Int,
    opacity: Int,
): FloatArray {
    val base = resolveSystemGlassParams(resources) ?: FALLBACK_GLASS_BASE_PARAMETERS
    val params = base.clone()
    // 通道布局来自 miuix.theme.token.GlassToken：下标 4 是 blend.luminanceAmount。
    params[4] = luminance.coerceIn(0f, MAX_SHORTCUT_GLASS_LUMINANCE)
    // 15 = inner.colorWhite、16 = inner.colorMix 负责玻璃的通透白色内漫射，保持系统基线不动。
    // 11..14 = inner.color（RGBA）是染色通道，默认值（白 / 15%）与系统锁屏组的
    // 1,1,1 @ 0.15 完全一致，因此不动滑块时下发结果等于系统原始参数。
    params[11] = Color.red(color) / 255f
    params[12] = Color.green(color) / 255f
    params[13] = Color.blue(color) / 255f
    params[14] = opacity.coerceIn(0, 100) / 100f
    return params
}

internal fun applyNativeSoftGlassMaterial(
    view: View,
    classLoader: ClassLoader,
    blurRadius: Int,
    backdropBlurRadius: Int,
    luminance: Float,
    color: Int,
    opacity: Int,
) {
    val viewClass = View::class.java
    val glassCompat = Class.forName(MI_GLASS_COMPAT_CLASS, false, classLoader)
    // 模糊模式与混合色的 Compat 入口在 MiBlurCompat，不在 MiGlassCompat。
    val blurCompat = runCatching { Class.forName(MI_BLUR_COMPAT_CLASS, false, classLoader) }.getOrNull()

    // MiBlurCompat 的静态签名是 (int, View)：先传模式再传控件。
    // 类名缺失时退回实例方法，行为与系统 glass 路径一致。
    runCatching {
        (blurCompat ?: error("$MI_BLUR_COMPAT_CLASS is unavailable")).getMethod(
            "setMiViewBlurModeCompat",
            Int::class.javaPrimitiveType,
            View::class.java,
        ).invoke(null, SHORTCUT_GLASS_BLUR_MODE, view)
    }.onFailure {
        viewClass.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType)
            .invoke(view, SHORTCUT_GLASS_BLUR_MODE)
    }
    runCatching {
        (blurCompat ?: error("$MI_BLUR_COMPAT_CLASS is unavailable"))
            .getMethod("clearMiBackgroundBlendColorCompat", View::class.java)
            .invoke(null, view)
    }.onFailure {
        runCatching { viewClass.getMethod("clearMiBackgroundBlendColor").invoke(view) }
    }

    // Only the compositor registration matters here: the visible blur comes from the Bionics
    // shader, so the background blur radius stays 0.
    viewClass.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType)
        .invoke(view, true)
    viewClass.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType)
        .invoke(view, SHORTCUT_GLASS_BLUR_MODE)
    viewClass.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType)
        .invoke(view, 0)

    // 默认值 50 / 50 对应系统玻璃面的 50 / 500（ElementSurfaceModel），与系统音乐卡片一致。
    val smallBlur = blurRadius.coerceIn(0, MAX_SHORTCUT_GLASS_BLUR_RADIUS)
    val backdrop = backdropBlurRadius.coerceIn(0, MAX_SHORTCUT_GLASS_BLUR_RADIUS)
    val largeBlur = (backdrop * 10).coerceIn(0, MAX_SHORTCUT_GLASS_LARGE_BLUR_RADIUS)
    glassCompat.getMethod(
        "setMiGlassBlurRadius",
        View::class.java,
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
    ).invoke(null, view, smallBlur, largeBlur)

    val glassParameters = buildNativeSoftGlassParameters(
        resources = view.resources,
        luminance = luminance,
        color = color,
        opacity = opacity,
    )

    // Compat 入口优先：反编译显示 `setMiViewMaterialTypeCompat` 是唯一会在材质类型为 1 时
    // 同时下发「SDF 最大尺寸（即控件边界）」的路径（未布局时它自己延后到 onLayout）。
    // 直接调实例方法会跳过这一步，折射边缘会按旧尺寸计算，出现边缘错位。
    val applied = runCatching {
        glassCompat.getMethod(
            "setMiViewMaterialTypeCompat",
            Int::class.javaPrimitiveType,
            View::class.java,
        ).invoke(null, SHORTCUT_GLASS_MATERIAL_TYPE, view)
        glassCompat.getMethod("setMiGlassCompat", View::class.java, FloatArray::class.java)
            .invoke(null, view, glassParameters)
        true
    }.getOrDefault(false)

    if (!applied) {
        // 老版本 ROM 没有 MiGlassCompat 时退回实例方法。
        val actualClass = view.javaClass
        actualClass.getMethod("setMiViewMaterialType", Int::class.javaPrimitiveType)
            .invoke(view, SHORTCUT_GLASS_MATERIAL_TYPE)
        actualClass.getMethod("setMiGlass", FloatArray::class.java)
            .invoke(view, glassParameters)
    }
    // The shader now paints this view, so the fallback drawable is dropped.
    view.background = null
    if (view is ImageView) {
        view.setImageDrawable(null)
    }
    view.invalidate()
}

internal fun applySystemGlassMaterial(
    view: View,
    classLoader: ClassLoader,
    blurRadius: Int,
    luminance: Float,
) {
    val glassCompat = Class.forName(MI_GLASS_COMPAT_CLASS, false, classLoader)
    val smallBlur = blurRadius.coerceIn(0, MAX_SHORTCUT_GLASS_BLUR_RADIUS)
    val glassParameters = buildNativeSoftGlassParameters(
        resources = view.resources,
        luminance = luminance,
        color = Color.WHITE,
        opacity = 0,
    )
    glassCompat.getMethod(
        "setMiGlassBlurRadius",
        View::class.java,
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
    ).invoke(null, view, smallBlur, (smallBlur * 2).coerceAtMost(MAX_SHORTCUT_GLASS_LARGE_BLUR_RADIUS))
    glassCompat.getMethod(
        "setMiViewMaterialTypeCompat",
        Int::class.javaPrimitiveType,
        View::class.java,
    ).invoke(null, SHORTCUT_GLASS_MATERIAL_TYPE, view)
    glassCompat.getMethod("setMiGlassCompat", View::class.java, FloatArray::class.java)
        .invoke(null, view, glassParameters)
}

/**
 * Releases the OS4 glass shader from a view that is leaving the soft-glass mode. The shader is
 * registered on the view that received it and keeps painting through later calls, so without this
 * a reused carrier still looks like soft glass after switching to another background mode.
 */
internal fun clearSoftGlassShader(view: View, classLoader: ClassLoader) {
    runCatching {
        val glassCompat = Class.forName(MI_GLASS_COMPAT_CLASS, false, classLoader)
        glassCompat.getMethod(
            "setMiGlassBlurRadius",
            View::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(null, view, 0, 0)
        glassCompat.getMethod(
            "setMiViewMaterialTypeCompat",
            Int::class.javaPrimitiveType,
            View::class.java,
        ).invoke(null, 0, view)
        // 系统自己的清理路径（MediaViewGlassOnKeyguardEffect.clear）还会把参数向量整体清零，
        // 否则已注册的 shader 会继续按上一次的参数绘制。
        glassCompat.getMethod("setMiGlassCompat", View::class.java, FloatArray::class.java)
            .invoke(null, view, FloatArray(GLASS_PARAM_COUNT))
    }
    view.invalidate()
}

/**
 * 释放承载层上的**全部**平台材质状态。
 *
 * 柔光玻璃的 shader 由 MiGlassCompat 释放，而高级材质写入的是视图级 blend 色层、
 * 背景模糊、pass-window blur 与轮廓高光；三套状态互相独立，只清一套会让"高级材质 → 纯色/跟随系统"
 * 留下旧色层、模糊或高光。每一步单独隔离，失败也能在日志里定位是哪一项没清掉。
 */
internal fun clearPlatformMaterial(view: View, classLoader: ClassLoader) {
    runCatching { clearSoftGlassShader(view, classLoader) }
    val viewClass = View::class.java
    val blurCompat = runCatching { Class.forName(MI_BLUR_COMPAT_CLASS, false, classLoader) }.getOrNull()
    val steps = listOf<Pair<String, () -> Unit>>(
        "clearMiBackgroundBlendColor" to {
            runCatching {
                (blurCompat ?: error("$MI_BLUR_COMPAT_CLASS is unavailable"))
                    .getMethod("clearMiBackgroundBlendColorCompat", View::class.java)
                    .invoke(null, view)
            }.onFailure { viewClass.getMethod("clearMiBackgroundBlendColor").invoke(view) }
        },
        "setMiViewBlurMode" to {
            viewClass.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType).invoke(view, 0)
        },
        "setMiBackgroundBlurMode" to {
            viewClass.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType).invoke(view, 0)
        },
        "setMiBackgroundBlurRadius" to {
            viewClass.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType).invoke(view, 0)
        },
        "setPassWindowBlurEnabled" to {
            viewClass.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType)
                .invoke(view, false)
        },
        "setMiBloomStroke" to { clearBloomStroke(view) },
    )
    steps.forEach { (name, step) ->
        runCatching(step).onFailure { error ->
            HyperLog.w(TAG, "Could not reset platform material ($name)", error)
        }
    }
    view.invalidate()
}
