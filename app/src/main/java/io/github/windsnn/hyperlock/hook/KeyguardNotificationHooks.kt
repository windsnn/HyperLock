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
 * 拦截通知容器的下沉 Flow。
 *
 * 通过包装下游 FlowCollector，在发射时按配置改写 useExtraShelfSpace，
 * 并在配置变更时由 [LockscreenNotificationReloadSlot] 主动重发，无需重启作用域。
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
                // 替换 Collector 参数必须使用 proceed(args)，避免触发参数类型校验异常
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
        // useExtraShelfSpace 为 false 时去除通知由于指纹避让产生的下沉空隙
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
 * 配置变更时触发通知 Flow 重新计算。配置未变化时跳过重算。
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
 * 拦截并改写 Flow 数据，支持在配置变更时通过 [replay] 主动重放最新值。
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
    // 扫描 KeyguardPanelViewController 中由编译器生成的 combine Flow 合成类（带序号区间保护）
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
 * 将指纹注册状态 Flow 改写为 false，以解除锁屏通知位置避让限制。
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
    // 装饰为可重放的 Collector，以便配置热更新时主动触发重算
    val replayable = replayTargetFor(owner, classLoader, collectorClass, originalCollector, preferences)
    method.invoke(sourceFlow, replayable.proxy, *invocationArgs.drop(1).toTypedArray())
}
