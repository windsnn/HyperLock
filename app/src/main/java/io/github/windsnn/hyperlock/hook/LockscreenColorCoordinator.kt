package io.github.windsnn.hyperlock.hook

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.windsnn.hyperlock.HyperSystemUiModule
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Field

/**
 * 锁屏底部区域（快捷按钮与迷你播放器）壁纸明暗自适应协同中枢。
 *
 * 1. 监听系统 MiuiKeyguardWallPaperManager 的 updateColorAndDeep 计算结果；
 * 2. 提取 isBottomDeep 亮暗状态；
 * 3. 线程安全地在主线程向快捷按钮与迷你播放器广播明暗变化；
 * 4. 支持主动反射系统单例在初次绑定时即可获得当前有效状态，无需等待壁纸事件。
 */
internal object LockscreenColorCoordinator {
    private const val TAG = "HyperLock-ColorCoord"
    private const val WALLPAPER_MANAGER_CLASS = "com.android.keyguard.wallpaper.MiuiKeyguardWallPaperManager"
    private val INTERFACES_IMPL_MGR_CLASSES = listOf(
        "com.miui.systemui.interfacesmanager.InterfacesImplManager",
        "com.miui.systemui.interfaces.InterfacesImplManager",
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<BottomColorListener>()

    @Volatile
    var isBottomDeep: Boolean = true
        private set

    private var isHookInstalled = false
    private var hasDispatchedInitial = false
    private var isBottomDeepField: Field? = null

    private var cachedModule: HyperSystemUiModule? = null
    private var cachedClassLoader: ClassLoader? = null
    private var cachedWpManagerClass: Class<*>? = null

    interface BottomColorListener {
        fun onBottomDeepChanged(isBottomDeep: Boolean)
    }

    fun addListener(listener: BottomColorListener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
        if (!hasDispatchedInitial) {
            cachedModule?.let { mod ->
                cachedClassLoader?.let { cl ->
                    cachedWpManagerClass?.let { wpCls ->
                        tryFetchCurrentColors(mod, cl, wpCls)
                    }
                }
            }
        }
        val currentDeep = isBottomDeep
        mainHandler.post {
            listener.onBottomDeepChanged(currentDeep)
        }
    }

    fun install(
        module: HyperSystemUiModule,
        classLoader: ClassLoader,
        preferences: SharedPreferences,
    ) {
        if (isHookInstalled) return
        isHookInstalled = true

        cachedModule = module
        cachedClassLoader = classLoader

        runCatching {
            val wpManagerClass = classLoader.loadClass(WALLPAPER_MANAGER_CLASS)
            cachedWpManagerClass = wpManagerClass
            resolveFields(wpManagerClass)

            // 尝试主动获取当前单例，预热当前色彩状态
            tryFetchCurrentColors(module, classLoader, wpManagerClass)

            val updateMethod = wpManagerClass.declaredMethods.firstOrNull {
                it.name == "updateColorAndDeep" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }

            if (updateMethod != null) {
                module.attachHook(updateMethod)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("lockscreen-wallpaper-color-coordinator")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val managerInstance = chain.thisObject
                        if (managerInstance != null) {
                            extractAndDispatch(managerInstance, module)
                        }
                        result
                    }
                module.hookLog(Log.INFO, TAG, "Hooked MiuiKeyguardWallPaperManager.updateColorAndDeep")
            } else {
                module.hookLog(Log.WARN, TAG, "updateColorAndDeep method not found in MiuiKeyguardWallPaperManager")
            }
        }.onFailure { error ->
            module.hookLog(Log.ERROR, TAG, "Failed to install LockscreenColorCoordinator hook", error)
        }
    }

    fun tryRefreshIfMissing() {
        if (!hasDispatchedInitial) {
            cachedModule?.let { mod ->
                cachedClassLoader?.let { cl ->
                    cachedWpManagerClass?.let { wpCls ->
                        tryFetchCurrentColors(mod, cl, wpCls)
                    }
                }
            }
        }
    }

    private fun resolveFields(clazz: Class<*>) {
        isBottomDeepField = runCatching {
            clazz.getDeclaredField("mBottomIconIsDeep").apply { isAccessible = true }
        }.getOrNull()
    }

    private fun tryFetchCurrentColors(
        module: HyperSystemUiModule,
        classLoader: ClassLoader,
        wpManagerClass: Class<*>,
    ) {
        for (className in INTERFACES_IMPL_MGR_CLASSES) {
            val success = runCatching {
                val implMgrClass = classLoader.loadClass(className)
                val getImplMethod = implMgrClass.getMethod("getImpl", Class::class.java)
                val instance = getImplMethod.invoke(null, wpManagerClass)
                if (instance != null) {
                    extractAndDispatch(instance, module)
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (success) {
                module.hookLog(Log.INFO, TAG, "Successfully fetched wallpaper manager instance via " + className)
                return
            }
        }
    }

    private fun extractAndDispatch(managerInstance: Any, module: HyperSystemUiModule) {
        runCatching {
            val deep = isBottomDeepField?.get(managerInstance) as? Boolean ?: isBottomDeep
            val changed = !hasDispatchedInitial || deep != isBottomDeep
            hasDispatchedInitial = true
            isBottomDeep = deep

            if (changed) {
                val targets = synchronized(listeners) { ArrayList(listeners) }
                mainHandler.post {
                    for (listener in targets) {
                        runCatching {
                            listener.onBottomDeepChanged(deep)
                        }.onFailure { error ->
                            module.hookLog(Log.WARN, TAG, "Listener onBottomDeepChanged failed", error)
                        }
                    }
                }
            }
        }.onFailure { error ->
            module.hookLog(Log.WARN, TAG, "Failed to extract wallpaper bottom deep state", error)
        }
    }
}
