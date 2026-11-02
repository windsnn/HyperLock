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
            // 动态注册在 SystemUI 进程，使用 signature 级别自定义权限校验发送方以防止滥用
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
    // 顺序执行重载槽位，单项异常不阻断后续槽位执行
    for (slot in snapshotSettingsReloadSlots()) {
        runCatching {
            slot.reload(preferences, classLoader)
        }.onFailure { error ->
            hookLog(Log.ERROR, TAG, "Could not reload ${slot.id}", error)
        }
    }
}
