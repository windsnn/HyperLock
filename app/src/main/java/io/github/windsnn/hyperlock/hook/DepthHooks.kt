package io.github.windsnn.hyperlock.hook

import android.content.SharedPreferences
import android.util.Log
import io.github.windsnn.hyperlock.hook.*
import io.github.windsnn.hyperlock.HyperSystemUiModule
import io.github.windsnn.hyperlock.settings.*
import io.github.windsnn.hyperlock.settings.KEY_REMOVE_DEPTH_IMAGE_LIMIT
import io.github.libxposed.api.XposedInterface.ExceptionMode

internal fun HyperSystemUiModule.installDepthEffectHook(classLoader: ClassLoader, preferences: SharedPreferences) {
    runCatching {
        // 阈值处理与 SystemUI 侧完全同构，统一走共享实现，避免两侧慢慢分叉。
        installDepthThresholdHooks(
            classLoader = classLoader,
            preferences = preferences,
            idStem = "depth-image-threshold",
        )
        installDepthAvoidanceBypass(classLoader, preferences)
    }.onFailure { error ->
        hookLog(Log.ERROR, TAG, "Could not install depth image threshold hook", error)
    }
}

/**
 * DepthAvoidEvaluator 阈值的三段处理（AOD 与 SystemUI 同构，仅 id 前缀不同）：
 * 1. 在 `<clinit>` 创建 IMAGE_THRESHOLD 之前改写构造函数参数；
 * 2. `<clinit>` 结束后兜底再写一次 rate（类初始化可能晚于安装）；
 * 3. 安装时立即写一次，覆盖"hook 晚于类初始化"的情况。
 */
internal fun HyperSystemUiModule.installDepthThresholdHooks(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
    idStem: String,
) {
    // Loading a class does not run its static initializer. Hook the constructor before
    // DepthAvoidEvaluator creates IMAGE_THRESHOLD in <clinit>.
    val thresholdClass = classLoader.loadClass(DEPTH_THRESHOLD_CLASS)
    val evaluatorClass = classLoader.loadClass(DEPTH_EVALUATOR_CLASS)
    attachHook(thresholdClass.getDeclaredConstructor(Double::class.javaPrimitiveType))
        .setExceptionMode(ExceptionMode.PROTECTIVE)
        .setId(idStem)
        .intercept { chain ->
            val original = (chain.getArg(0) as? Number)?.toDouble()
            if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false) &&
                original != null && original < 0.9
            ) {
                chain.proceed(arrayOf(UNLIMITED_DEPTH_IMAGE_THRESHOLD))
            } else {
                chain.proceed()
            }
        }
    attachClassInitializer(evaluatorClass)
        .setExceptionMode(ExceptionMode.PROTECTIVE)
        .setId("$idStem-verification")
        .intercept { chain ->
            val result = chain.proceed()
            // Threshold.rate 是 final 字段：写回时必须 isAccessible（forceDepthImageThreshold 内已处理）。
            forceDepthImageThreshold(evaluatorClass, thresholdClass, preferences)
            result
        }
    forceDepthImageThreshold(evaluatorClass, thresholdClass, preferences)
}

internal fun HyperSystemUiModule.installDepthAvoidanceBypass(classLoader: ClassLoader, preferences: SharedPreferences) {
    val controllerClass = classLoader.loadClass(HIERARCHY_AVOID_CONTROLLER_CLASS)
    attachHook(controllerClass.getMethod("isHierarchyEnable"))
        .setExceptionMode(ExceptionMode.PROTECTIVE)
        .setId("depth-time-overlap-result")
        .intercept { chain ->
            val result = chain.proceed()
            if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false) &&
                isUserHierarchyEnabled(chain.thisObject, controllerClass)
            ) true else result
        }

    attachHook(
        controllerClass.getMethod(
            "onHierarchyEnableChange",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ),
    )
        .setExceptionMode(ExceptionMode.PROTECTIVE)
        .setId("depth-time-overlap-state")
        .intercept { chain ->
            if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false) &&
                isUserHierarchyEnabled(chain.thisObject, controllerClass)
            ) {
                chain.proceedWith(chain.thisObject, arrayOf(true, chain.getArg(1)))
            } else {
                chain.proceed()
            }
        }
}

internal fun HyperSystemUiModule.isUserHierarchyEnabled(instance: Any?, controllerClass: Class<*>): Boolean = runCatching {
    controllerClass.getDeclaredField(USER_OPEN_HIERARCHY_FIELD)
        .apply { isAccessible = true }
        .getBoolean(instance)
}.getOrDefault(false)


internal fun HyperSystemUiModule.installSystemUiDepthHooks(classLoader: ClassLoader, preferences: SharedPreferences) {
    runCatching {
        installDepthThresholdHooks(
            classLoader = classLoader,
            preferences = preferences,
            idStem = "systemui-depth-image-threshold",
        )

        val interactorClass = classLoader.loadClass(KEYGUARD_DEPTH_INTERACTOR_CLASS)
        attachHook(interactorClass.getMethod("updateAvoidStatus"))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("systemui-depth-time-overlap")
            .intercept { chain ->
                if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) {
                    clearDepthAvoidState(chain.thisObject, interactorClass)
                    null
                } else {
                    chain.proceed()
                }
            }
        val alphaMethod = interactorClass.declaredMethods.firstOrNull {
            it.name == "setDepthTransitionAlpha" && it.parameterCount == 3
        } ?: error("setDepthTransitionAlpha not found")
        attachHook(alphaMethod)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("systemui-depth-alpha")
            .intercept { chain ->
                if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) {
                    clearDepthAvoidState(chain.thisObject, interactorClass)
                }
                chain.proceed()
            }
        installDepthDisplayStateHook(classLoader, preferences)
        installThirdPartyWallpaperDepthHook(classLoader, preferences)
    }.onFailure { error ->
        hookLog(Log.ERROR, TAG, "Could not install SystemUI lockscreen depth hooks", error)
    }
}

internal fun HyperSystemUiModule.forceDepthImageThreshold(
    evaluatorClass: Class<*>,
    thresholdClass: Class<*>,
    preferences: SharedPreferences,
) {
    if (!preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) return
    runCatching {
        val threshold = evaluatorClass.getDeclaredField(IMAGE_THRESHOLD_FIELD).get(null)
        thresholdClass.getDeclaredField(THRESHOLD_RATE_FIELD)
            .apply { isAccessible = true }
            .setDouble(threshold, UNLIMITED_DEPTH_IMAGE_THRESHOLD)
    }.onFailure { error ->
        hookLog(Log.ERROR, TAG, "Could not set SystemUI depth image threshold", error)
    }
}

internal fun HyperSystemUiModule.installDepthDisplayStateHook(classLoader: ClassLoader, preferences: SharedPreferences) {
    val panelClass = classLoader.loadClass(KEYGUARD_PANEL_VIEW_CONTROLLER_CLASS)
    val depthEnabled = panelClass.getDeclaredField("depthEffectEnable").apply { isAccessible = true }
    val interactor = panelClass.getDeclaredField("keyguardDepthInteractor").apply { isAccessible = true }
    val actualDisplayDepth = interactor.type.getDeclaredField("isActualDisplayDepth")
        .apply { isAccessible = true }
    val depthEnabledInner = interactor.type.getDeclaredField("depthEffectEnableInner")
        .apply { isAccessible = true }
    val updateElements = panelClass.getMethod("updateKeyguardElementsVisibility")
    attachHook(panelClass.getMethod("updateShowDepthState"))
        .setExceptionMode(ExceptionMode.PROTECTIVE)
        .setId("systemui-depth-display-state")
        .intercept { chain ->
            if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) {
                depthEnabled.setBoolean(chain.thisObject, true)
                depthEnabledInner.setBoolean(interactor.get(chain.thisObject), true)
            }
            val result = chain.proceed()
            if (preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) {
                val depthInteractor = interactor.get(chain.thisObject)
                if (!actualDisplayDepth.getBoolean(depthInteractor)) {
                    actualDisplayDepth.setBoolean(depthInteractor, true)
                    updateElements.invoke(chain.thisObject)
                }
            }
            result
        }
}

internal fun HyperSystemUiModule.clearDepthAvoidState(instance: Any?, interactorClass: Class<*>) {
    runCatching {
        val state = interactorClass.getDeclaredField("_avoidState")
            .apply { isAccessible = true }
            .get(instance)
        val update = state.javaClass.methods.firstOrNull {
            it.name == "updateState\$1" && it.parameterCount == 2
        } ?: error("StateFlow update method not found")
        update.invoke(state, null, false)
    }.onFailure { error ->
        hookLog(Log.ERROR, TAG, "Could not clear lockscreen depth avoid state", error)
    }
}


internal fun HyperSystemUiModule.createForcedLargeScreenHierarchy(hierarchyClass: Class<*>): Any? = runCatching {
    val constructor = hierarchyClass.getDeclaredConstructor(
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
    ).apply { isAccessible = true }
    constructor.newInstance(
        true, true, true,
        true, true, true,
        true, true, true,
    )
}.getOrElse {
    runCatching {
        val ctor = hierarchyClass.declaredConstructors.first().apply { isAccessible = true }
        val types = ctor.parameterTypes
        val args = arrayOfNulls<Any>(types.size)
        for (idx in types.indices) {
            args[idx] = when (types[idx]) {
                Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> true
                Int::class.javaPrimitiveType, java.lang.Integer::class.java -> 0
                else -> null
            }
        }
        ctor.newInstance(*args)
    }.getOrNull()
}

internal fun HyperSystemUiModule.installThirdPartyWallpaperDepthHook(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    if (!preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) return
    runCatching {
        installWallpaperCapabilityHooks(
            idPrefix = "systemui",
            wallpaperInfoClass = { classLoader.loadClass(WALLPAPER_INFO_CLASS) },
            hierarchyClass = { classLoader.loadClass(LARGE_SCREEN_HIERARCHY_ENABLE_CLASS) },
        )
    }.onFailure { error ->
        hookLog(Log.WARN, TAG, "Could not install third-party wallpaper depth hooks", error)
    }
}

/**
 * 第三方壁纸的两道闸门（支持主体、大屏层次）在 AOD 与 SystemUI 两侧同构，只有类名与 id 前缀
 * 不同。类名用 lambda 延迟加载，保持"开关关闭时不加载目标类"的原有行为。
 */
internal fun HyperSystemUiModule.installWallpaperCapabilityHooks(
    idPrefix: String,
    wallpaperInfoClass: () -> Class<*>,
    hierarchyClass: () -> Class<*>,
) {
    attachHook(wallpaperInfoClass().getMethod("getSupportSubject"))
        .setExceptionMode(ExceptionMode.PROTECTIVE)
        .setId("$idPrefix-depth-wallpaper-subject")
        .intercept { chain -> true }

    val forcedHierarchy = createForcedLargeScreenHierarchy(hierarchyClass())
    if (forcedHierarchy != null) {
        attachHook(wallpaperInfoClass().getMethod("getLargeScreenHierarchyEnable"))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("$idPrefix-depth-wallpaper-hierarchy")
            .intercept { chain -> forcedHierarchy }
    }
}

/**
 * The AOD editor has its own wallpaper model and applies the same third-party checks again.
 * Keep this separate from TemplateApiImpl.isDefaultTheme(), which belongs to the global
 * theme soft-glass path and must retain its original semantics.
 */
internal fun HyperSystemUiModule.installAodThirdPartyWallpaperDepthHook(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    if (!preferences.getBoolean(KEY_REMOVE_DEPTH_IMAGE_LIMIT, false)) return
    runCatching {
        installWallpaperCapabilityHooks(
            idPrefix = "aod",
            wallpaperInfoClass = { classLoader.loadClass(AOD_WALLPAPER_INFO_CLASS) },
            hierarchyClass = { classLoader.loadClass(AOD_LARGE_SCREEN_HIERARCHY_ENABLE_CLASS) },
        )

        val companionClass = classLoader.loadClass(WALLPAPER_CONTROLLER_COMPANION_CLASS)
        val pickColorMethod = companionClass.getMethod(
            "getPickWallpaperColorInfo",
            String::class.java,
            Integer::class.java,
            Integer::class.java,
        )
        attachHook(pickColorMethod)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("aod-depth-wallpaper-clock-style")
            .intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    result?.javaClass?.getMethod("setClockInfoStyle", Integer::class.java)
                        ?.invoke(result, chain.getArg(1))
                    result?.javaClass?.getMethod("setSignatureAlignment", Integer::class.java)
                        ?.invoke(result, chain.getArg(2))
                }
                result
            }

        val controllerClass = classLoader.loadClass(WALLPAPER_CONTROLLER_CLASS)
        attachHook(controllerClass.getMethod("needResetMagicType"))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("aod-depth-wallpaper-reset")
            .intercept { chain -> false }

    }.onFailure { error ->
        hookLog(Log.WARN, TAG, "Could not install AOD third-party wallpaper depth hooks", error)
    }
}
