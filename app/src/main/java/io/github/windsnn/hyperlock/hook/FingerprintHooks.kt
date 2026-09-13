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
                // A locked-again keyguard reuses the existing FOD window and only makes
                // its animation surface opaque through this method.
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
                        // The platform method only clears the animation/icon surface. The
                        // FOD view remains attached and continues receiving touch events.
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
 * HyperOS 4's animation manager is shared by lockscreen and in-app biometric prompts. The
 * target HyperOS 4 smali checks mKeyguardAuthen with if-eqz and applies the empty
 * resource/animation branch when it is true. Software FOD prompts keep the normal path.
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
        // Some HyperOS builds move these methods to a superclass or add an unused argument.
        // Include the complete hierarchy and hook every matching overload.
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
