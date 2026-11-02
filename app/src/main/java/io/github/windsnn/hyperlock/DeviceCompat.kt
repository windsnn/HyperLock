package io.github.windsnn.hyperlock

import android.content.Context
import android.os.Build

/**
 * 设备与系统环境兼容性判定。
 *
 * 仅以系统代际（HyperOS 4）作为兼容性分级依据；
 * 系统界面与息屏的具体构建版本仅作展示与基线参考。
 */
internal data class DeviceCompat(
    val level: Level,
    val deviceName: String,
    val romVersion: String,
    val androidVersion: String,
    val systemUiVersion: String?,
    val aodVersion: String?,
    val reason: String,
    val notes: List<String>,
) {
    internal enum class Level { SUPPORTED, UNVERIFIED, UNSUPPORTED }

    val titleRes: Int
        get() = when (level) {
            Level.SUPPORTED -> R.string.device_compat_supported
            Level.UNVERIFIED -> R.string.device_compat_unverified
            Level.UNSUPPORTED -> R.string.device_compat_unsupported
        }
}

/** 模块适配基准版本，用于比对并提示未经充分测试的系统界面版本。 */
internal const val BASELINE_SYSTEM_UI_VERSION = "17.03.260226.r"
private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
private const val AOD_PACKAGE = "com.miui.aod"

internal fun checkDeviceCompat(context: Context): DeviceCompat {
    val rom = Build.VERSION.INCREMENTAL.orEmpty()
    val systemUi = packageVersion(context, SYSTEM_UI_PACKAGE)
    val aod = packageVersion(context, AOD_PACKAGE)

    val isHyperOs4 = rom.startsWith("OS4")
    val looksLikeHyperOs = rom.startsWith("OS") || rom.startsWith("V8")

    val level = when {
        Build.VERSION.SDK_INT < 33 -> DeviceCompat.Level.UNSUPPORTED
        !looksLikeHyperOs -> DeviceCompat.Level.UNSUPPORTED
        !isHyperOs4 -> DeviceCompat.Level.UNVERIFIED
        else -> DeviceCompat.Level.SUPPORTED
    }

    val reason = when (level) {
        DeviceCompat.Level.SUPPORTED -> context.getString(R.string.device_compat_reason_supported)
        DeviceCompat.Level.UNVERIFIED ->
            context.getString(R.string.device_compat_reason_unverified, rom)
        DeviceCompat.Level.UNSUPPORTED ->
            if (Build.VERSION.SDK_INT < 33) {
                context.getString(R.string.device_compat_reason_android_req)
            } else {
                context.getString(R.string.device_compat_reason_not_hyperos, rom)
            }
    }

    val notes = buildList {
        if (systemUi == null) {
            add(context.getString(R.string.device_compat_note_no_systemui))
        } else if (level == DeviceCompat.Level.SUPPORTED && systemUi != BASELINE_SYSTEM_UI_VERSION) {
            add(context.getString(R.string.device_compat_note_systemui_unverified, systemUi))
        }
        if (aod == null) {
            add(context.getString(R.string.device_compat_note_no_aod))
        }
    }

    return DeviceCompat(
        level = level,
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        romVersion = rom.ifEmpty { context.getString(R.string.device_compat_unknown) },
        androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        systemUiVersion = systemUi,
        aodVersion = aod,
        reason = reason,
        notes = notes,
    )
}

private fun packageVersion(context: Context, packageName: String): String? = runCatching {
    context.packageManager.getPackageInfo(packageName, 0).versionName
}.getOrNull()
