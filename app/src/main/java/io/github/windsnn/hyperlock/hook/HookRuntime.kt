package io.github.windsnn.hyperlock.hook

import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import io.github.windsnn.hyperlock.HyperSystemUiModule
import io.github.windsnn.hyperlock.settings.KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_MODE
import io.github.windsnn.hyperlock.settings.KEY_NOTIFICATION_FOD_POSITION_LIMIT_REMOVED
import io.github.windsnn.hyperlock.settings.KEY_SHORTCUT_ICON_COLOR_MODE
import io.github.windsnn.hyperlock.settings.SHORTCUT_BACKGROUND_NONE
import io.github.windsnn.hyperlock.settings.SHORTCUT_BACKGROUND_SOFT_GLASS
import io.github.windsnn.hyperlock.settings.SHORTCUT_ICON_COLOR_AUTO
import io.github.windsnn.hyperlock.settings.SHORTCUT_ICON_COLOR_DARK
import java.util.Collections
import java.util.IdentityHashMap

internal fun HyperSystemUiModule.commonShortcutParent(first: View, second: View): ViewGroup? {
    val ancestors = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
    var current: View? = first
    while (current != null) {
        ancestors += current
        current = current.parent as? View
    }
    current = second.parent as? View
    while (current != null) {
        if (current in ancestors && current is ViewGroup) return current
        current = current.parent as? View
    }
    return null
}

internal fun View.idName(): String? = runCatching {
    resources.getResourceEntryName(id)
}.getOrNull()

internal fun HyperSystemUiModule.shortcutBackgroundMode(preferences: SharedPreferences): Int = preferences.getInt(
    KEY_LOCKSCREEN_SHORTCUT_BACKGROUND_MODE,
    SHORTCUT_BACKGROUND_NONE,
).coerceIn(SHORTCUT_BACKGROUND_NONE, SHORTCUT_BACKGROUND_SOFT_GLASS)

internal fun HyperSystemUiModule.shortcutIconColorMode(preferences: SharedPreferences): Int = preferences.getInt(
    KEY_SHORTCUT_ICON_COLOR_MODE,
    SHORTCUT_ICON_COLOR_AUTO,
).coerceIn(SHORTCUT_ICON_COLOR_AUTO, SHORTCUT_ICON_COLOR_DARK)

internal fun HyperSystemUiModule.isNotificationFodPositionLimitRemoved(preferences: SharedPreferences): Boolean =
    preferences.getBoolean(KEY_NOTIFICATION_FOD_POSITION_LIMIT_REMOVED, false)

internal fun HyperSystemUiModule.readInstanceField(instance: Any, name: String): Any? {
    var type: Class<*>? = instance.javaClass
    while (type != null) {
        val field = runCatching { type.getDeclaredField(name) }.getOrNull()
        if (field != null) {
            return runCatching {
                field.isAccessible = true
                field.get(instance)
            }.getOrNull()
        }
        type = type.superclass
    }
    return null
}
