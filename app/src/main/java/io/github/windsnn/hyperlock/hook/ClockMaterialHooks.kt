package io.github.windsnn.hyperlock.hook

import android.content.SharedPreferences
import android.util.Log
import io.github.windsnn.hyperlock.HyperSystemUiModule
import io.github.windsnn.hyperlock.settings.KEY_REMOVE_CLOCK_MATERIAL_LIMIT
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
        // ClockBean 的位置随 ROM 而变：SystemUI 是 applyOtaClockParams(int, Context, ClockBean)，
        // com.miui.aod 是 applyOtaClockParams(ClockBean, Context, int)。按参数类型定位，
        // 否则 AOD 侧会拿到 int，getClockEffect/setClockEffect 抛异常被吞掉，hook 一直空转。
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
                // 只在"系统把玻璃降级成模糊混合"时还原：0 表示设备不支持背景模糊，
                // 此时强行写回 5 会让时钟落到不受支持的材质路径；2（叠加）不会被改写，无需处理。
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
