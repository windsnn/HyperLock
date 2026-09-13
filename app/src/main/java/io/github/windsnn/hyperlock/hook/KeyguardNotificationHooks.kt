package io.github.windsnn.hyperlock.hook

import android.content.SharedPreferences
import android.util.Log
import io.github.windsnn.hyperlock.HyperLog
import io.github.windsnn.hyperlock.hook.*
import io.github.windsnn.hyperlock.HyperSystemUiModule
import io.github.windsnn.hyperlock.player.LockscreenLyricsNotificationBridge
import io.github.windsnn.hyperlock.settings.*
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

private const val FLOW_COLLECTOR_CLASS = "kotlinx.coroutines.flow.FlowCollector"
private const val FLOW_COMBINE_CLASS =
    "kotlinx.coroutines.flow.FlowKt__ZipKt\$combine\$\$inlined\$unsafeFlow\$1"
private const val COMBINE_TRANSFORM_FIELD = "\$transform\$inlined"
private const val CONTINUATION_CLASS = "kotlin.coroutines.Continuation"
private const val EMPTY_COROUTINE_CONTEXT_CLASS = "kotlin.coroutines.EmptyCoroutineContext"

/** 同一条流可能被多处同时收集，只保留最近几次的 collector，避免补发时目标已经失效。 */
private const val MAX_REPLAY_TARGETS_PER_FLOW = 4

private val notificationReplayTargets = Collections.synchronizedMap(
    WeakHashMap<Any, MutableList<ReplayableCollector>>(),
)

internal fun HyperSystemUiModule.installLockscreenNotificationHook(classLoader: ClassLoader, preferences: SharedPreferences) {
    // 歌词卡片的「有通知时自动隐藏」依赖通知行状态：暴露一个主动校验入口，
    // 让卡片在隐藏自己之前复查真实状态，避免状态滞后导致歌词一直不出现。
    LockscreenLyricsNotificationBridge.verifyActiveNotifications = ::updateActiveLockscreenNotifications

    val shelfSpaceHookInstalled = runCatching {
        installShelfSpaceReplayHook(classLoader, preferences)
        true
    }.onFailure { error ->
        hookLog(Log.WARN, TAG, "Could not install lockscreen notification shelf-space hook", error)
    }.getOrDefault(false)

    val positionFlows = findFodNotificationPositionFlows(classLoader)
    var positionHookCount = 0
    positionFlows.forEachIndexed { index, positionFlowClass ->
        runCatching {
            val flowsField = positionFlowClass.getDeclaredField("\$flows\$inlined")
                .apply { isAccessible = true }
            val collect = positionFlowClass.declaredMethods.firstOrNull { method ->
                method.name == "collect" && method.parameterCount == 2
            } ?: error("nsslLockYPosition combine Flow.collect was not found")
            attachHook(collect)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("lockscreen-notification-fod-position-$index")
                .intercept { chain ->
                    runCatching {
                        installFodEnrollmentFlowOverride(
                            chain.thisObject,
                            flowsField,
                            positionFlowClass.classLoader,
                            preferences,
                        )
                    }.onFailure { error ->
                        hookLog(Log.ERROR, TAG, "Could not override lockscreen FOD position", error)
                    }
                    chain.proceed()
                }
            positionHookCount += 1
        }.onFailure { error ->
            hookLog(Log.WARN, TAG, "Could not install FOD-position hook for ${positionFlowClass.name}", error)
        }
    }
    if (!shelfSpaceHookInstalled && positionHookCount == 0) {
        hookLog(Log.ERROR, TAG, "Could not locate lockscreen notification FOD-position flow")
    }
    registerSettingsReloadSlot(LockscreenNotificationReloadSlot(this))
}

/**
 * 通知下沉的返回值由系统自己的 combine flow 缓存：模块既改不了上游 StateFlow，也没有「重算」入口，
 * 所以这里不拦截变换结果，而是抓住下游 collector —— 发射时按开关改写并记下系统原值，
 * 设置变化时由 [LockscreenNotificationReloadSlot] 往同一个 collector 补发一次，让下游立刻重算。
 *
 * 挂在库类 `FlowKt__ZipKt$combine...` 上的 hook 用 `$transform$inlined` 的类名过滤，
 * 只有这条 useExtraShelfSpace 流会走改写分支，其余 combine 直接原样放行。
 */
internal fun HyperSystemUiModule.installShelfSpaceReplayHook(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    val flowCollectorClass = classLoader.loadClass(FLOW_COLLECTOR_CLASS)
    val combineClass = classLoader.loadClass(FLOW_COMBINE_CLASS)
    attachHook(
        combineClass.getDeclaredMethod(
            "collect",
            flowCollectorClass,
            classLoader.loadClass(CONTINUATION_CLASS),
        ),
    )
        .setExceptionMode(ExceptionMode.PROTECTIVE)
        .setId("lockscreen-notification-shelf-space")
        .intercept { chain ->
            val owner = chain.thisObject
            val transform = readInstanceField(owner, COMBINE_TRANSFORM_FIELD)
            val collector = chain.getArg(0)
            if (transform?.javaClass?.name != FOD_SHELF_SPACE_FLOW_CLASS || collector == null) {
                chain.proceed()
            } else {
                val replayable = replayTargetFor(
                    owner = owner,
                    classLoader = classLoader,
                    collectorInterface = flowCollectorClass,
                    collector = collector,
                    preferences = preferences,
                )
                // 只换参数，必须用 proceed(args)：proceedWith 是「换 this」的重载，
                // 传数组进去会被当成 thisObject 校验并抛 IllegalArgumentException（该异常会穿透到协程）。
                chain.proceed(arrayOf(replayable.proxy, chain.getArg(1)))
            }
        }
}

private fun HyperSystemUiModule.replayTargetFor(
    owner: Any,
    classLoader: ClassLoader,
    collectorInterface: Class<*>,
    collector: Any,
    preferences: SharedPreferences,
): ReplayableCollector {
    val replayable = ReplayableCollector(classLoader, collectorInterface, collector) { value ->
        // useExtraShelfSpace = 第二个输入 || !第一个输入；改写为 false 表示"不额外占用货架空间"，
        // 即去掉通知被指纹区顶下去的下沉留白。
        if (isNotificationFodPositionLimitRemoved(preferences)) false else value
    }
    synchronized(notificationReplayTargets) {
        val targets = notificationReplayTargets.getOrPut(owner) { ArrayList(2) }
        targets += replayable
        while (targets.size > MAX_REPLAY_TARGETS_PER_FLOW) targets.removeAt(0)
    }
    return replayable
}

/**
 * 设置变化时向已登记的 collector 补发一次。开关没变就直接返回：
 * 其它设置连续变化（拖滑块）不应该反复触发通知重排。
 */
internal class LockscreenNotificationReloadSlot(private val module: HyperSystemUiModule) : SettingsReloadSlot {
    override val id = "lockscreen-notification"

    @Volatile
    private var applied: Boolean? = null

    override fun reload(preferences: SharedPreferences, classLoader: ClassLoader) {
        val enabled = module.isNotificationFodPositionLimitRemoved(preferences)
        if (applied == enabled) return
        applied = enabled
        val targets = synchronized(notificationReplayTargets) {
            notificationReplayTargets.values.flatten()
        }
        for (target in targets) {
            if (!target.replay()) {
                HyperLog.d("Notification", "Lockscreen notification replay target is stale")
            }
        }
    }
}

/**
 * 包住系统 flow 的下游 collector：转发时按 [rewrite] 改写并记住系统原值，
 * [replay] 用于设置变化时立刻补发一次，不需要重启作用域。
 */
internal class ReplayableCollector(
    classLoader: ClassLoader,
    collectorInterface: Class<*>,
    private val original: Any,
    private val rewrite: (Any?) -> Any?,
) {
    private val continuationClass = classLoader.loadClass(CONTINUATION_CLASS)
    private val emitMethod = collectorInterface.getMethod("emit", Any::class.java, continuationClass)
    private val proxyClassLoader = collectorInterface.classLoader ?: classLoader
    private val emptyContext = runCatching {
        classLoader.loadClass(EMPTY_COROUTINE_CONTEXT_CLASS).getField("INSTANCE").get(null)
    }.getOrNull()

    @Volatile
    private var lastValue: Any? = null

    @Volatile
    private var hasValue = false

    val proxy: Any = Proxy.newProxyInstance(
        proxyClassLoader,
        arrayOf(collectorInterface),
    ) { proxy, method, args ->
        when (method.name) {
            "emit" -> {
                val value = args?.getOrNull(0)
                lastValue = value
                hasValue = true
                // 只有改写本身失败才退回原值；下游 emit 的异常必须原样抛回给协程。
                emit(runCatching { rewrite(value) }.getOrElse { value }, args?.getOrNull(1))
            }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            "toString" -> "HyperLockReplayableCollector"
            else -> null
        }
    }

    /** 补发一次；返回 false 表示 collector 已失效（视图销毁、collection 取消）。 */
    fun replay(): Boolean {
        if (!hasValue || emptyContext == null) return false
        return runCatching { emit(rewrite(lastValue), newContinuation()) }.isSuccess
    }

    private fun emit(value: Any?, continuation: Any?): Any? = try {
        emitMethod.invoke(original, value, continuation)
    } catch (error: InvocationTargetException) {
        throw error.cause ?: error
    }

    private fun newContinuation(): Any = Proxy.newProxyInstance(
        proxyClassLoader,
        arrayOf(continuationClass),
    ) { proxy, method, args ->
        when (method.name) {
            "getContext" -> emptyContext
            "resumeWith" -> Unit
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            "toString" -> "HyperLockReplayContinuation"
            else -> null
        }
    }
}

internal fun HyperSystemUiModule.findFodNotificationPositionFlows(classLoader: ClassLoader): List<Class<*>> {
    val result = LinkedHashSet<Class<*>>()
    // Kotlin emits this combine Flow as a top-level synthetic class.  It is not reported by
    // Class.getDeclaredClasses(), and the lambda ordinal changes whenever Xiaomi edits the
    // controller.  Locate the stable Flow shape instead of depending on the ordinal or its
    // nested SuspendLambda implementation.
    // 17.03.260226.r 实测命中 `...$lambda$102$$inlined$combine$1`（即 lambda 序号为 102），
    // 区间保留是为了跨 ROM 版本继续可用。
    for (ordinal in FOD_NOTIFICATION_POSITION_FLOW_ORDINAL_RANGE) {
        val className = "$FOD_NOTIFICATION_POSITION_FLOW_PREFIX$ordinal$FOD_NOTIFICATION_POSITION_FLOW_SUFFIX"
        runCatching { classLoader.loadClass(className) }
            .getOrNull()
            ?.takeIf { type ->
                type.declaredFields.any { it.name == "\$flows\$inlined" } &&
                    type.declaredMethods.any { method ->
                        method.name == "collect" && method.parameterCount == 2
                    }
            }
            ?.let(result::add)
    }
    return result.toList()
}

/**
 * Makes the final combine input (hasEnrolledTemplatesFlow) report false while the feature is
 * enabled.  Decorating the source flow keeps preference changes live and leaves all other
 * FOD behavior untouched.
 */
internal fun HyperSystemUiModule.installFodEnrollmentFlowOverride(
    combineFlow: Any?,
    flowsField: java.lang.reflect.Field,
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) {
    val owner = combineFlow ?: return
    val flows = flowsField.get(owner) as? Array<Any?> ?: return
    if (flows.isEmpty()) return
    synchronized(fodEnrollmentFlowOverrides) {
        if (fodEnrollmentFlowOverrides.containsKey(owner)) return
        val sourceFlow = flows.lastOrNull() ?: return
        val flowClass = classLoader.loadClass("kotlinx.coroutines.flow.Flow")
        if (!flowClass.isInstance(sourceFlow)) return
        flows[flows.lastIndex] = createFodEnrollmentFlowOverride(
            owner,
            sourceFlow,
            flowClass,
            classLoader,
            preferences,
        )
        fodEnrollmentFlowOverrides[owner] = sourceFlow
    }
}

internal fun HyperSystemUiModule.createFodEnrollmentFlowOverride(
    owner: Any,
    sourceFlow: Any,
    flowClass: Class<*>,
    classLoader: ClassLoader,
    preferences: SharedPreferences,
): Any = java.lang.reflect.Proxy.newProxyInstance(
    classLoader,
    arrayOf(flowClass),
) { _, method, args ->
    val invocationArgs = args ?: emptyArray()
    if (method.name != "collect" || invocationArgs.isEmpty()) {
        return@newProxyInstance method.invoke(sourceFlow, *invocationArgs)
    }
    val originalCollector = invocationArgs[0] ?: return@newProxyInstance method.invoke(
        sourceFlow,
        *invocationArgs,
    )
    val collectorClass = method.parameterTypes.firstOrNull()
        ?: return@newProxyInstance method.invoke(sourceFlow, *invocationArgs)
    // 同样换成可补发的 collector：这条输入被改写时保留系统原值，
    // 设置变化后由 LockscreenNotificationReloadSlot 补发一次。
    val replayable = replayTargetFor(owner, classLoader, collectorClass, originalCollector, preferences)
    method.invoke(sourceFlow, replayable.proxy, *invocationArgs.drop(1).toTypedArray())
}
