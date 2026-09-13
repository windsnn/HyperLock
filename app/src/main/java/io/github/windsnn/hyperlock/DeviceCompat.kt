package io.github.windsnn.hyperlock

import android.content.Context
import android.os.Build

/**
 * 设备与系统环境检测。
 *
 * 模块的 hook 依赖澎湃 OS 系统界面（SystemUI）与息屏（AOD）的内部实现，
 * 所以判定只看「是不是适配所用的澎湃 OS 4 这一代」；
 * 具体版本号只用于展示，版本不一致时给一句备注，不因此改变结论——
 * 系统界面版本会随 OTA 变动，拿它当门槛只会把能用的机型挡在外面。
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

    val title: String
        get() = when (level) {
            Level.SUPPORTED -> "已适配"
            Level.UNVERIFIED -> "未验证的系统"
            Level.UNSUPPORTED -> "不支持的系统"
        }
}

/** 适配与测试所用的系统界面版本，仅用于提示「未在测试版本上验证」。 */
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
        DeviceCompat.Level.SUPPORTED -> "澎湃 OS 4，处于适配范围内"
        DeviceCompat.Level.UNVERIFIED ->
            "系统版本 $rom 不是适配所用的澎湃 OS 4，部分功能可能失效"
        DeviceCompat.Level.UNSUPPORTED ->
            if (Build.VERSION.SDK_INT < 33) {
                "需要 Android 13 及以上"
            } else {
                "未识别到澎湃 OS（当前 $rom），本模块仅适配澎湃 OS 4"
            }
    }

    val notes = buildList {
        if (systemUi == null) {
            add("未找到系统界面（System UI）")
        } else if (level == DeviceCompat.Level.SUPPORTED && systemUi != BASELINE_SYSTEM_UI_VERSION) {
            add("系统界面 $systemUi 未在测试版本上验证")
        }
        if (aod == null) {
            add("未安装「息屏与锁屏编辑」，息屏相关功能不可用")
        }
    }

    return DeviceCompat(
        level = level,
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        romVersion = rom.ifEmpty { "未知" },
        androidVersion = "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
        systemUiVersion = systemUi,
        aodVersion = aod,
        reason = reason,
        notes = notes,
    )
}

private fun packageVersion(context: Context, packageName: String): String? = runCatching {
    context.packageManager.getPackageInfo(packageName, 0).versionName
}.getOrNull()
