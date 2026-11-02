package io.github.windsnn.hyperlock.hook

import android.content.SharedPreferences
import android.util.Log
import io.github.windsnn.hyperlock.hook.*
import io.github.windsnn.hyperlock.HyperSystemUiModule
import io.github.windsnn.hyperlock.settings.*
import io.github.windsnn.hyperlock.settings.FINGERPRINT_HIDE_GLOBAL
import io.github.windsnn.hyperlock.settings.FINGERPRINT_HIDE_LOCKSCREEN
import io.github.windsnn.hyperlock.settings.FINGERPRINT_HIDE_NONE
import io.github.windsnn.hyperlock.settings.KEY_FINGERPRINT_HIDE_MODE
import io.github.libxposed.api.XposedInterface.ExceptionMode

internal fun HyperSystemUiModule.installFingerprintIconVisualHook(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    runCatching {
        val iconClass = classLoader.loadClass(MIUI_GXZW_ICON_VIEW_CLASS)
        val dismissIcon = iconClass.getMethod(FOD_DISMISS_ICON_METHOD)
        val displayMethods = iconClass.declaredMethods.filter { method ->
            (method.name == "show" && method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))) ||
                (method.name == "showFingerprintIcon" && method.parameterCount == 0) ||
                // 重新锁屏时系统复用已有 FOD 窗口，仅通过 setGxzwIconOpaque 恢复不透明度
                (method.name == "setGxzwIconOpaque" && method.parameterCount == 0)
        }
        check(displayMethods.isNotEmpty()) { "MiuiGxzwIconView display methods were not found" }
        displayMethods.forEachIndexed { index, method ->
            attachHook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-fod-icon-transparent-$index")
                .intercept { chain ->
                    val result = chain.proceed()
                    if (fingerprintHideMode(preferences) == FINGERPRINT_HIDE_GLOBAL) {
                        // 清除图标绘制 Surface，保留 View 附着以维持触控响应
                        dismissIcon.invoke(chain.thisObject)
                    }
                    result
                }
        }
    }.onFailure { error ->
        hookLog(Log.ERROR, TAG, "Could not install lockscreen FOD icon hook", error)
    }
}


internal fun HyperSystemUiModule.fingerprintHideMode(preferences: SharedPreferences): Int =
    preferences.getInt(KEY_FINGERPRINT_HIDE_MODE, FINGERPRINT_HIDE_NONE)
        .coerceIn(FINGERPRINT_HIDE_NONE, FINGERPRINT_HIDE_GLOBAL)

/**
 * 拦截 HyperOS 指纹动画管理器。
 *
 * 仅在锁屏场景（mKeyguardAuthen == true）下返回空图标/空动画，应用内生物识别弹窗保持原有逻辑。
 */
internal fun HyperSystemUiModule.installLockscreenFingerprintAnimationHook(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    runCatching {
        val managerClass = classLoader.loadClass(MIUI_GXZW_ANIM_MANAGER_CLASS)
        val keyguardAuthen = managerClass.getDeclaredField(MIUI_GXZW_KEYGUARD_AUTHEN_FIELD)
            .apply { isAccessible = true }
        val animItemMap = managerClass.getDeclaredField(MIUI_GXZW_ANIMATION_ITEMS_FIELD)
            .apply { isAccessible = true }
        // 兼容处理不同构建版本中可能存在的父类继承与重载变体
        val methods = buildList {
            var current: Class<*>? = managerClass
            while (current != null) {
                addAll(current.declaredMethods)
                current = current.superclass
            }
        }.distinctBy { method ->
            method.name to method.parameterTypes.toList()
        }
        val iconResources = methods.filter { it.name == MIUI_GXZW_FINGER_ICON_RESOURCE_METHOD }
        val recognizingItems = methods.filter { it.name == MIUI_GXZW_RECOGNIZING_ANIM_ITEM_METHOD }
        check(iconResources.isNotEmpty()) {
            "getFingerIconResource was not found; methods=${methods.map { it.name }.filter { name ->
                name.contains("Finger", ignoreCase = true) || name.contains("Icon", ignoreCase = true)
            }}"
        }
        check(recognizingItems.isNotEmpty()) {
            "getRecognizingAnimItem was not found; methods=${methods.map { it.name }.filter { name ->
                name.contains("Recogn", ignoreCase = true) || name.contains("Anim", ignoreCase = true)
            }}"
        }

        iconResources.forEachIndexed { index, method ->
            attachHook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-fod-animation-icon-resource-$index")
                .intercept { chain ->
                    if (fingerprintHideMode(preferences) == FINGERPRINT_HIDE_LOCKSCREEN &&
                        keyguardAuthen.getBoolean(chain.thisObject)
                    ) {
                        LOCKSCREEN_HIDDEN_FINGERPRINT_ICON_RESOURCE
                    } else {
                        chain.proceed()
                    }
                }
        }
        recognizingItems.forEachIndexed { index, method ->
            attachHook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-fod-animation-recognizing-item-$index")
                .intercept { chain ->
                    if (fingerprintHideMode(preferences) == FINGERPRINT_HIDE_LOCKSCREEN &&
                        keyguardAuthen.getBoolean(chain.thisObject)
                    ) {
                        val map = animItemMap.get(chain.thisObject) as? Map<*, *>
                        map?.get(0)
                    } else {
                        chain.proceed()
                    }
                }
        }
    }.onFailure { error ->
        hookLog(Log.ERROR, TAG, "Could not install HyperOS 4 lockscreen FOD animation hooks", error)
    }
}
