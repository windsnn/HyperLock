package io.github.windsnn.hyperlock.hook

import android.content.SharedPreferences
import android.util.Log
import io.github.windsnn.hyperlock.HyperSystemUiModule
import io.github.windsnn.hyperlock.settings.KEY_REMOVE_CLOCK_MATERIAL_LIMIT
import io.github.windsnn.hyperlock.settings.KEY_REMOVE_DEPTH_IMAGE_LIMIT
import io.github.libxposed.api.XposedInterface.ExceptionMode

internal fun HyperSystemUiModule.installClockMaterialLimitHook(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    runCatching {
        val utilityClass = classLoader.loadClass(CLOCK_UTILITY_CLASS)
        val applyMethod = utilityClass.declaredMethods.firstOrNull {
            it.name == CLOCK_UTILITY_METHOD && it.parameterCount == 3
        } ?: error("$CLOCK_UTILITY_METHOD was not found")
        val beanIndex = applyMethod.parameterTypes.indexOfFirst { it.name == CLOCK_BEAN_CLASS }
        check(beanIndex >= 0) {
            "$CLOCK_UTILITY_METHOD has no $CLOCK_BEAN_CLASS parameter: " +
                applyMethod.parameterTypes.joinToString { it.name }
        }
        applyMethod.isAccessible = true
        attachHook(applyMethod)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("clock-material-limit")
            .intercept { chain ->
                val bean = chain.getArg(beanIndex)
                if (!preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false)) {
                    return@intercept chain.proceed()
                }
                val originalEffect = runCatching {
                    bean?.javaClass?.getMethod("getClockEffect")?.invoke(bean) as? Int
                }.getOrNull()
                val result = chain.proceed()
                val appliedEffect = runCatching {
                    bean?.javaClass?.getMethod("getClockEffect")?.invoke(bean) as? Int
                }.getOrNull()
                if (originalEffect == CLOCK_EFFECT_GLASS && appliedEffect == CLOCK_EFFECT_BLUR_MIX) {
                    runCatching {
                        bean?.javaClass?.getMethod("setClockEffect", Int::class.javaPrimitiveType)
                            ?.invoke(bean, originalEffect)
                    }.onFailure { error ->
                        hookLog(Log.WARN, TAG, "Could not restore clock material effect", error)
                    }
                }
                result
            }
    }.onFailure { error ->
        hookLog(Log.WARN, TAG, "Could not install clock material-limit bypass", error)
    }
}

internal fun HyperSystemUiModule.installClockMaterialHierarchyCompatHooks(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    if (!preferences.getBoolean(KEY_REMOVE_CLOCK_MATERIAL_LIMIT, false) &&
        !preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)
    ) return

    runCatching {
        val filterClass = classLoader.loadClass("com.miui.keyguard.editor.data.bean.WallpaperFilter")
        attachHook(filterClass.getMethod("getSupportHierarchy"))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("aod-wallpaper-filter-support-hierarchy")
            .intercept { true }
    }.onFailure { error ->
        hookLog(Log.WARN, TAG, "Could not hook WallpaperFilter.getSupportHierarchy", error)
    }

    val clockViewClasses = listOf(
        "com.miui.keyguard.editor.edit.clock.BaseClockView",
        "com.miui.keyguard.editor.edit.clock.ClassicClockView",
        "com.miui.keyguard.editor.edit.clock.RhombusClockView",
        "com.miui.keyguard.editor.edit.clock.MagazineClockView",
    )
    for (clsName in clockViewClasses) {
        runCatching {
            val cls = classLoader.loadClass(clsName)
            cls.declaredMethods.filter { it.name == "supportHierarchy" && it.parameterCount == 0 }.forEach { method ->
                attachHook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("aod-clock-support-hierarchy-${cls.simpleName}")
                    .intercept { true }
            }
        }
    }

    runCatching {
        val templateViewClass = classLoader.loadClass("com.miui.keyguard.editor.edit.base.EffectsTemplateView")
        templateViewClass.declaredMethods.filter { it.name == "shouldEnableHierarchy" && it.parameterCount == 0 }.forEach { method ->
            attachHook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("aod-effects-should-enable-hierarchy")
                .intercept { true }
        }
    }

    runCatching {
        val magicTypeClass = classLoader.loadClass("com.miui.keyguard.editor.data.bean.MagicType")
        magicTypeClass.declaredMethods.filter { it.name == "isShader" && it.parameterCount == 1 }.forEach { method ->
            attachHook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("aod-magic-type-is-shader")
                .intercept { false }
        }
    }
}
