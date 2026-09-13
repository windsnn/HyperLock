package io.github.windsnn.hyperlock.hook

import android.content.SharedPreferences

/**
 * 热重载槽位：设置变化后由模块主动让某个功能按新值重新生效。
 *
 * 统一的只是「何时重载、按什么顺序、失败如何隔离」，不是「怎么生效」：
 * - 视图型（快捷背景、迷你播放器、PIN 圆形）在 [reload] 里把设置重新应用到已登记的视图；
 * - 流型（通知下沉）在 [reload] 里向已登记的 collector 补发一次值，让系统的 flow 重算。
 *
 * [reload] 必须幂等、可重复调用。注册时机由各功能自己掌握：谁安装功能谁登记槽位，
 * 这样新增功能不会再出现「漏接热启动」的情况。同一 id 重复注册以最后一次为准。
 */
internal interface SettingsReloadSlot {
    /** 注册表键与失败日志用的稳定 id。 */
    val id: String

    fun reload(preferences: SharedPreferences, classLoader: ClassLoader)
}

private val settingsReloadSlots = LinkedHashMap<String, SettingsReloadSlot>()

/** 注册顺序即执行顺序（快捷几何等有先后依赖的功能靠这里保持顺序）。 */
internal fun registerSettingsReloadSlot(slot: SettingsReloadSlot) {
    synchronized(settingsReloadSlots) {
        settingsReloadSlots[slot.id] = slot
    }
}

internal fun snapshotSettingsReloadSlots(): List<SettingsReloadSlot> =
    synchronized(settingsReloadSlots) { settingsReloadSlots.values.toList() }
