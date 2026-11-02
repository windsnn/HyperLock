package io.github.windsnn.hyperlock

import android.util.Log
import io.github.windsnn.hyperlock.hook.*
import io.github.windsnn.hyperlock.settings.*
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Constructor
import java.lang.reflect.Method

class HyperSystemUiModule : XposedModule() {
    /** 供各个 Hook 安装器委托调用的桥接方法 */
    internal fun attachHook(origin: Method) = hook(origin)

    internal fun attachHook(origin: Constructor<*>) = hook(origin)

    internal fun attachClassInitializer(origin: Class<*>) = hookClassInitializer(origin)

    internal fun hookLog(priority: Int, tag: String, message: String) = log(priority, tag, message)

    internal fun hookLog(priority: Int, tag: String, message: String, error: Throwable?) =
        log(priority, tag, message, error)

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName !in SYSTEM_UI_TARGETS) return
        runCatching {
            val preferences = getRemotePreferences(REMOTE_PREFERENCE_GROUP)
            HyperLog.isVerbose = preferences.getBoolean(KEY_VERBOSE_LOG, false)
            val classLoader = param.defaultClassLoader
            when (param.packageName) {
                SYSTEM_UI -> {
                    setupPreferenceChangeListener(classLoader, preferences)
                    setupBroadcastReceiver(classLoader, preferences)
                    if (!lockscreenNotificationHookInstalled) {
                        installLockscreenNotificationHook(classLoader, preferences)
                        installLockscreenMediaNotificationHook(classLoader, preferences)
                        lockscreenNotificationHookInstalled = true
                    }
                    if (!fingerprintIconHookInstalled) {
                        installFingerprintIconVisualHook(classLoader, preferences)
                        // 指纹图标动画 hook 在部分构建版本中类结构可能变化，失败时降级容错
                        runCatching {
                            installLockscreenFingerprintAnimationHook(classLoader, preferences)
                        }.onFailure { error ->
                            log(Log.WARN, TAG, "Skipped unsupported lockscreen fingerprint animation hook", error)
                        }
                        fingerprintIconHookInstalled = true
                    }
                    if (!systemUiDepthHookInstalled) {
                        installSystemUiDepthHooks(classLoader, preferences)
                        systemUiDepthHookInstalled = true
                    }
                    if (!lockscreenChargingHookInstalled) {
                        installLockscreenChargingTextHook(classLoader, preferences)
                        installLockscreenBottomTextViewHook(classLoader, preferences)
                        lockscreenChargingHookInstalled = true
                    }
                    if (!lockscreenShortcutGlassHookInstalled) {
                        LockscreenColorCoordinator.install(this, classLoader, preferences)
                        installLockscreenShortcutGlassHook(classLoader, preferences)
                        lockscreenShortcutGlassHookInstalled = true
                    }
                    if (!lockscreenPinCircleBackgroundHookInstalled) {
                        installLockscreenPinCircleBackgroundHook(classLoader, preferences)
                        lockscreenPinCircleBackgroundHookInstalled = true
                    }
                    if (!systemUiClockMaterialLimitHookInstalled) {
                        installClockMaterialLimitHook(classLoader, preferences)
                        systemUiClockMaterialLimitHookInstalled = true
                    }
                }
                AOD -> {
                    if (!depthEffectHookInstalled) {
                        installDepthEffectHook(classLoader, preferences)
                        installAodThirdPartyWallpaperDepthHook(classLoader, preferences)
                        depthEffectHookInstalled = true
                    }
                    if (!aodClockMaterialLimitHookInstalled) {
                        installClockMaterialLimitHook(classLoader, preferences)
                        aodClockMaterialLimitHookInstalled = true
                    }
                    installClockMaterialHierarchyCompatHooks(classLoader, preferences)
                }
            }
            log(Log.INFO, TAG, "Installed hooks for ${param.packageName}")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install hooks for ${param.packageName}", error)
        }
    }
}
