package io.github.windsnn.hyperlock.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.util.Log
import io.github.windsnn.hyperlock.ACTION_RELOAD_SETTINGS
import io.github.windsnn.hyperlock.HyperLog
import io.github.windsnn.hyperlock.HyperSystemUiModule
import io.github.windsnn.hyperlock.KEY_VERBOSE_LOG
import io.github.windsnn.hyperlock.PERMISSION_RELOAD_SETTINGS
import io.github.libxposed.api.XposedInterface.ExceptionMode

internal fun HyperSystemUiModule.setupPreferenceChangeListener(classLoader: ClassLoader, preferences: SharedPreferences) {
    if (preferenceChangeListener == null) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            scheduleDebouncedReload(classLoader, preferences)
        }
        preferenceChangeListener = listener
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }
}

internal fun HyperSystemUiModule.setupBroadcastReceiver(classLoader: ClassLoader, preferences: SharedPreferences) {
    getSystemUiContext()?.let { context ->
        registerReloadReceiver(context, classLoader, preferences)
    }
    hookApplicationOnCreate(classLoader, preferences)
}

internal fun HyperSystemUiModule.getSystemUiContext(): Context? = runCatching {
    val activityThreadClass = Class.forName("android.app.ActivityThread")
    val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
    currentApplicationMethod.invoke(null) as? Context
}.getOrNull()

internal fun HyperSystemUiModule.hookApplicationOnCreate(classLoader: ClassLoader, preferences: SharedPreferences) {
    runCatching {
        val appClass = classLoader.loadClass("android.app.Application")
        val onCreateMethod = appClass.getDeclaredMethod("onCreate")
        attachHook(onCreateMethod)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("systemui-application-on-create-reload")
            .intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? Context)?.let { context ->
                    registerReloadReceiver(context, classLoader, preferences)
                }
                result
            }
    }.onFailure { error ->
        hookLog(Log.WARN, TAG, "Could not hook Application.onCreate for reload receiver", error)
    }
}

internal fun HyperSystemUiModule.registerReloadReceiver(
    context: Context,
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    if (reloadReceiverRegistered) return
    synchronized(shortcutRoots) {
        if (reloadReceiverRegistered) return
        val appContext = context.applicationContext ?: context
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_RELOAD_SETTINGS) {
                        scheduleDebouncedReload(classLoader, preferences)
                    }
                }
            }
            val filter = IntentFilter(ACTION_RELOAD_SETTINGS)
            // 动态注册在 SystemUI 进程，跨应用接收必须导出；用 signature 级权限限制发送方，
            // 防止任意第三方应用反复触发重载（见模块 AndroidManifest）。
            appContext.registerReceiver(
                receiver,
                filter,
                PERMISSION_RELOAD_SETTINGS,
                null,
                Context.RECEIVER_EXPORTED,
            )
            reloadReceiverRegistered = true
        }.onFailure { error ->
            hookLog(Log.ERROR, TAG, "Failed to register ACTION_RELOAD_SETTINGS BroadcastReceiver", error)
        }
    }
}

internal fun HyperSystemUiModule.scheduleDebouncedReload(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
    delayMs: Long = 25L,
) {
    val action = Runnable {
        pendingReloadRunnable = null
        reloadLockscreenSettings(classLoader, preferences)
    }
    pendingReloadRunnable?.let { mainHandler.removeCallbacks(it) }
    pendingReloadRunnable = action
    mainHandler.postDelayed(action, delayMs)
}

internal fun HyperSystemUiModule.reloadLockscreenSettings(classLoader: ClassLoader, preferences: SharedPreferences) {
    HyperLog.isVerbose = preferences.getBoolean(KEY_VERBOSE_LOG, false)
    // 各功能在安装时把槽位登记进来（见 SettingsReloadSlots.kt），这里只负责顺序与失败隔离：
    // 一个槽位抛异常不能阻断后面的槽位。
    for (slot in snapshotSettingsReloadSlots()) {
        runCatching {
            slot.reload(preferences, classLoader)
        }.onFailure { error ->
            hookLog(Log.ERROR, TAG, "Could not reload ${slot.id}", error)
        }
    }
}
