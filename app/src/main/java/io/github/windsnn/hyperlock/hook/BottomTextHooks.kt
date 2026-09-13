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
                        if (mask and LOCKSCREEN_TEXT_CHARGING != 0) hide.invoke(controller, CHARGING_INDICATION_TYPE)
                        // 本 ROM 的勿扰与通知计数不在 KeyguardIndicationController 的轮播集合里：
                        // 它们由 NotificationNumStateView（zenView / notificationCountView）渲染，
                        // 已由 installLockscreenBottomTextViewHook 处理，这里不再按文案猜测。
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
 * HyperOS renders the DND and notification-count indications in a dedicated view rather
 * than through KeyguardIndicationController. Hook its refresh methods and hide the concrete
 * TextViews after the vendor code has updated their state.
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
        refreshMethods.forEachIndexed { index, method ->
            attachHook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-bottom-text-view-$index")
                .intercept { chain ->
                    val result = chain.proceed()
                    applyLockscreenBottomTextVisibility(chain.thisObject, preferences, stateClass)
                    result
                }
        }
        // The binder posts an animation after each state update. Its completion callback
        // restores child visibility, so guard that shared animation helper as well.
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
                            val owner = generateSequence(view?.parent) { it.parent }
                                .firstOrNull { it.javaClass.name == NOTIFICATION_NUM_STATE_VIEW_CLASS }
                            val mask = preferences.getInt(KEY_LOCKSCREEN_BOTTOM_TEXT_MASK, 0)
                            val ownerView = owner as? ViewGroup
                            val countView = ownerView?.let { readViewField(it, "notificationCountView") }
                            val zenView = ownerView?.let { readViewField(it, "zenView") }
                            val divider = ownerView?.let { readViewField(it, "dividingLine") }
                            val hide = (mask and LOCKSCREEN_TEXT_NOTIFICATIONS != 0 && view === countView) ||
                                (mask and LOCKSCREEN_TEXT_DND != 0 && view === zenView) ||
                                (mask and (LOCKSCREEN_TEXT_DND or LOCKSCREEN_TEXT_NOTIFICATIONS) != 0 && view === divider)
                            if (hide && view != null) {
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
    target: Any,
    preferences: SharedPreferences,
    targetClass: Class<*>,
) {
    val mask = preferences.getInt(KEY_LOCKSCREEN_BOTTOM_TEXT_MASK, 0)
    if (mask == 0) return
    fun fieldValue(name: String): Any? {
        var type: Class<*>? = targetClass
        while (type != null) {
            val value = runCatching {
                type.getDeclaredField(name).apply { isAccessible = true }.get(target)
            }.getOrNull()
            if (value != null) return value
            type = type.superclass
        }
        return null
    }
    if (mask and LOCKSCREEN_TEXT_DND != 0) {
        (fieldValue("zenView") as? View)?.visibility = View.GONE
    }
    if (mask and LOCKSCREEN_TEXT_NOTIFICATIONS != 0) {
        (fieldValue("notificationCountView") as? TextView)?.let {
            it.text = ""
            it.contentDescription = ""
            it.visibility = View.GONE
            it.alpha = 0f
        }
    }
    if (mask and (LOCKSCREEN_TEXT_DND or LOCKSCREEN_TEXT_NOTIFICATIONS) != 0) {
        (fieldValue("dividingLine") as? View)?.visibility = View.GONE
    }
}

internal fun HyperSystemUiModule.readViewField(target: Any, name: String): View? {
    var type: Class<*>? = target.javaClass
    while (type != null) {
        val value = runCatching {
            type.getDeclaredField(name).apply { isAccessible = true }.get(target)
        }.getOrNull()
        if (value is View) return value
        type = type.superclass
    }
    return null
}
