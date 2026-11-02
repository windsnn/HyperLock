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
 * 锁屏媒体卡片（`MediaViewBlurOnKeyguardEffect`）通过读取系统资源动态装配参数，
 * 优先从当前系统资源中读取，失败时降级至内置兜底值。
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
 * 记录已应用轮廓高光的 View 弱引用集合。
 *
 * 轮廓高光由底层 Native 绘制在 View 上，仅需对已启用过高光的 View 显式下发全零参数注销。
 */
private val bloomStrokedViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

/** 下发参数布局与系统 `HyperBloomStrokeUtils` 一致，清空用的就是它的 `EMPTY`（全零）。 */
private fun setBloomStroke(view: View, parameters: FloatArray) {
    View::class.java.getMethod("setMiBloomStroke", FloatArray::class.java).invoke(view, parameters)
}

/**
 * 清空承载层上的轮廓高光。
 *
 * 避免 View 复用时遗留上一次渲染的高光状态。
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
 * 高级材质：对齐系统锁屏媒体卡片元素混合实现
 * (`NotificationUtil.applyElementViewBlend` + `applyContainerViewBlur`)。
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
 * 读取当前系统实际使用的玻璃参数向量。
 *
 * 优先按 SYSTEM_GLASS_PARAM_ARRAY_NAMES 中定义的数组名顺序解析资源。
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
    // 通道布局对齐 GlassToken：下标 4 为 luminanceAmount，11..14 为染色通道 (RGBA)
    params[4] = luminance.coerceIn(0f, MAX_SHORTCUT_GLASS_LUMINANCE)
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

    // MiBlurCompat 静态入口签名：(int mode, View view)；若缺失则退回 View 原生实例方法
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

    // 柔光玻璃自身 Shader 已包含模糊逻辑，仅需激活窗口模糊通道，背景模糊半径置 0
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

    // 优先通过 MiGlassCompat 下发材质类型以联动 SDF 边界尺寸；失败则回退至原生 View 反射
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
        val actualClass = view.javaClass
        actualClass.getMethod("setMiViewMaterialType", Int::class.javaPrimitiveType)
            .invoke(view, SHORTCUT_GLASS_MATERIAL_TYPE)
        actualClass.getMethod("setMiGlass", FloatArray::class.java)
            .invoke(view, glassParameters)
    }
    // 材质已由 Shader 绘制，清除原有背景及图像
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
 * 清除 View 上的柔光玻璃 Shader。
 *
 * 注销已注册的底层 Shader，避免切换背景模式或复用 View 时残留玻璃效果。
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
        // 参数向量全零重置以注销 Shader
        glassCompat.getMethod("setMiGlassCompat", View::class.java, FloatArray::class.java)
            .invoke(null, view, FloatArray(GLASS_PARAM_COUNT))
    }
    view.invalidate()
}

/**
 * 释放承载层上的所有平台材质效果（柔光玻璃 Shader、元素混合层、背景模糊与轮廓高光）。
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
