package io.github.windsnn.hyperlock.hook

import android.content.SharedPreferences

/**
 * 热重载槽位：配置发生变更时触发对应功能重新应用新配置。
 *
 * - 视图型：将最新配置重新应用于已登记的 View；
 * - 数据流型：向下游 Flow/Collector 发送新配置促使其重算。
 *
 * 实现必须具备幂等性。
 */
internal interface SettingsReloadSlot {
    /** 槽位唯一标识，用于注册表管理与异常日志。 */
    val id: String

    fun reload(preferences: SharedPreferences, classLoader: ClassLoader)
}

private val settingsReloadSlots = LinkedHashMap<String, SettingsReloadSlot>()

/** 按注册顺序维护重载槽位。 */
internal fun registerSettingsReloadSlot(slot: SettingsReloadSlot) {
    synchronized(settingsReloadSlots) {
        settingsReloadSlots[slot.id] = slot
    }
}

internal fun snapshotSettingsReloadSlots(): List<SettingsReloadSlot> =
    synchronized(settingsReloadSlots) { settingsReloadSlots.values.toList() }
