package io.github.windsnn.hyperlock.hook

import android.content.SharedPreferences
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.windsnn.hyperlock.hook.*
import io.github.windsnn.hyperlock.HyperSystemUiModule
import io.github.windsnn.hyperlock.settings.*
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_BOTTOM_TEXT_MASK
import io.github.windsnn.hyperlock.settings.LOCKSCREEN_TEXT_CHARGING
import io.github.windsnn.hyperlock.settings.LOCKSCREEN_TEXT_DND
import io.github.windsnn.hyperlock.settings.LOCKSCREEN_TEXT_NOTIFICATIONS
import io.github.libxposed.api.XposedInterface.ExceptionMode

internal fun HyperSystemUiModule.installLockscreenChargingTextHook(classLoader: ClassLoader, preferences: SharedPreferences) {
    runCatching {
        val controllerClass = classLoader.loadClass(KEYGUARD_INDICATION_CONTROLLER_CLASS)
        val rotateField = controllerClass.getDeclaredField("mRotateTextViewController")
            .apply { isAccessible = true }
        attachHook(controllerClass.getMethod("updateDeviceEntryIndication", Boolean::class.javaPrimitiveType))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("lockscreen-hide-charging-text")
            .intercept { chain ->
                val result = chain.proceed()
                val mask = preferences.getInt(KEY_LOCKSCREEN_BOTTOM_TEXT_MASK, 0)
                if (mask != 0) {
                    runCatching {
                        val controller = rotateField.get(chain.thisObject) ?: return@runCatching
                        val hide = controller.javaClass.getMethod("hideIndication", Int::class.javaPrimitiveType)
                        // HyperOS 的勿扰与通知计数由 NotificationNumStateView 独立渲染，不走此处轮播逻辑
                        if (mask and LOCKSCREEN_TEXT_CHARGING != 0) hide.invoke(controller, CHARGING_INDICATION_TYPE)
                    }.onFailure { error ->
                        hookLog(Log.ERROR, TAG, "Could not hide lockscreen charging text", error)
                    }
                }
                result
            }
    }.onFailure { error ->
        hookLog(Log.ERROR, TAG, "Could not install lockscreen charging-text hook", error)
    }
}

/**
 * 拦截 NotificationNumStateView 的更新方法，在状态刷新后按配置隐藏对应控件。
 */
internal fun HyperSystemUiModule.installLockscreenBottomTextViewHook(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    runCatching {
        val stateClass = classLoader.loadClass(NOTIFICATION_NUM_STATE_VIEW_CLASS)
        val refreshMethods = stateClass.declaredMethods.filter {
            it.name == "updateZenViewText" ||
                it.name == "updateNotificationCountView" ||
                it.name == "onFinishInflate"
        }
        if (refreshMethods.isEmpty()) {
            hookLog(Log.DEBUG, TAG, "NotificationNumStateView has no known refresh methods")
            return@runCatching
        }
        val zenViewField = runCatching {
            stateClass.getDeclaredField("zenView").apply { isAccessible = true }
        }.getOrNull()
        val countViewField = runCatching {
            stateClass.getDeclaredField("notificationCountView").apply { isAccessible = true }
        }.getOrNull()
        val dividerField = runCatching {
            stateClass.getDeclaredField("dividingLine").apply { isAccessible = true }
        }.getOrNull()

        refreshMethods.forEachIndexed { index, method ->
            attachHook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-bottom-text-view-$index")
                .intercept { chain ->
                    val result = chain.proceed()
                    applyLockscreenBottomTextVisibility(
                        target = chain.thisObject,
                        preferences = preferences,
                        zenViewField = zenViewField,
                        countViewField = countViewField,
                        dividerField = dividerField,
                    )
                    result
                }
        }
        // 当勿扰与通知计数同时开启隐藏时，若其中一方原本处于显示状态，将容器整体置为不可见并令 getLayoutHeight 返回 0，彻底消除底部残留占位
        runCatching {
            val getLayoutHeight = stateClass.getMethod("getLayoutHeight")
            attachHook(getLayoutHeight)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-bottom-text-layout-height")
                .intercept { chain ->
                    val owner = chain.thisObject ?: return@intercept chain.proceed()
                    val mask = preferences.getInt(KEY_LOCKSCREEN_BOTTOM_TEXT_MASK, 0)
                    val hideDnd = mask and LOCKSCREEN_TEXT_DND != 0
                    val hideNotif = mask and LOCKSCREEN_TEXT_NOTIFICATIONS != 0
                    if (hideDnd && hideNotif) {
                        0
                    } else {
                        val zenVisible = (zenViewField?.get(owner) as? View)?.visibility == View.VISIBLE && !hideDnd
                        val countVisible = (countViewField?.get(owner) as? View)?.visibility == View.VISIBLE && !hideNotif
                        if (!zenVisible && !countVisible && (hideDnd || hideNotif)) {
                            0
                        } else {
                            chain.proceed()
                        }
                    }
                }
        }
        // 拦截更新后的可见性动画，防止隐藏控件被动画完成回调重置为可见
        runCatching {
            val animateClass = classLoader.loadClass(NUM_STATE_VIEW_ANIMATE_EXT_CLASS)
            animateClass.declaredMethods
                .filter { it.name == "animateUpdateViewVisibility" && it.parameterCount >= 1 }
                .forEachIndexed { index, method ->
                    attachHook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-bottom-text-animation-$index")
                        .intercept { chain ->
                            val view = chain.getArg(0) as? View
                            val mask = preferences.getInt(KEY_LOCKSCREEN_BOTTOM_TEXT_MASK, 0)
                            if (view == null || mask == 0) return@intercept chain.proceed()
                            val owner = generateSequence(view.parent) { it.parent }
                                .firstOrNull(stateClass::isInstance)
                                ?: return@intercept chain.proceed()
                            val countView = countViewField?.get(owner) as? View
                            val zenView = zenViewField?.get(owner) as? View
                            val divider = dividerField?.get(owner) as? View
                            val hide = (mask and LOCKSCREEN_TEXT_NOTIFICATIONS != 0 && view === countView) ||
                                (mask and LOCKSCREEN_TEXT_DND != 0 && view === zenView) ||
                                (mask and (LOCKSCREEN_TEXT_DND or LOCKSCREEN_TEXT_NOTIFICATIONS) != 0 && view === divider)
                            if (hide) {
                                view.visibility = View.GONE
                                view.alpha = 0f
                                if (view === countView) {
                                    (view as? TextView)?.text = ""
                                    (view as? TextView)?.contentDescription = ""
                                }
                                null
                            } else {
                                chain.proceed()
                            }
                        }
                }
        }.onFailure { error ->
            hookLog(Log.DEBUG, TAG, "Optional lockscreen bottom-text animation hook unavailable", error)
        }
    }.onFailure { error ->
        hookLog(Log.DEBUG, TAG, "Optional NotificationNumStateView hook unavailable", error)
    }
}

internal fun HyperSystemUiModule.applyLockscreenBottomTextVisibility(
    target: Any?,
    preferences: SharedPreferences,
    zenViewField: java.lang.reflect.Field?,
    countViewField: java.lang.reflect.Field?,
    dividerField: java.lang.reflect.Field?,
) {
    val owner = target ?: return
    val mask = preferences.getInt(KEY_LOCKSCREEN_BOTTOM_TEXT_MASK, 0)
    if (mask == 0) return
    if (mask and LOCKSCREEN_TEXT_DND != 0) {
        (zenViewField?.get(owner) as? View)?.visibility = View.GONE
    }
    if (mask and LOCKSCREEN_TEXT_NOTIFICATIONS != 0) {
        (countViewField?.get(owner) as? TextView)?.let {
            it.text = ""
            it.contentDescription = ""
            it.visibility = View.GONE
            it.alpha = 0f
        }
    }
    if (mask and (LOCKSCREEN_TEXT_DND or LOCKSCREEN_TEXT_NOTIFICATIONS) != 0) {
        (dividerField?.get(owner) as? View)?.visibility = View.GONE
    }
}
