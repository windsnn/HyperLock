package io.github.windsnn.hyperlock

import android.util.Log
import io.github.windsnn.hyperlock.hook.*
import io.github.windsnn.hyperlock.settings.*
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Constructor
import java.lang.reflect.Method

class HyperSystemUiModule : XposedModule() {
    /** Bridges so the extracted hook files can install through this module. */
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
                        // This optional visual hook varies between HyperOS builds.
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
                }
            }
            log(Log.INFO, TAG, "Installed hooks for ${param.packageName}")
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install hooks for ${param.packageName}", error)
        }
    }
}
