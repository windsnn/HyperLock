package io.github.windsnn.hyperlock.player

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import io.github.windsnn.hyperlock.HyperLog
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.ProviderInfo

private const val TAG = "MiniPlayerLyrics"

/** UI 进度刷新最小间隔（毫秒）：词幕订阅端按 16ms 轮询播放位置，设为 33ms（约 30fps）既丝滑又省电。 */
private const val PROGRESS_REFRESH_INTERVAL_MS = 33L

private fun findApplicationContext(context: Context): Context {
    context.applicationContext?.let { return it }
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Application) return current
        current.applicationContext?.let { return it }
        current = current.baseContext
    }
    val app = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
        currentApplicationMethod.invoke(null) as? Context
    }.getOrNull()
    return app ?: context
}

/**
 * 词幕（Lyricon）活跃播放器订阅端。
 *
 * 本类只做"Android 适配"：订阅生命周期、主线程派发、日志。
 * 歌词状态迁移集中在纯逻辑的 [LyriconLyricsState]（可离线验证），时间轴计算在 [LyricTimeline]。
 */
internal class LyriconLockscreenSubscriber(
    context: Context,
    private val onLineChanged: () -> Unit,
    private val onProgressTick: (position: Long) -> Unit,
    private val isAwake: () -> Boolean = { true },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val state = LyriconLyricsState(
        progressRefreshIntervalMs = PROGRESS_REFRESH_INTERVAL_MS,
        clock = { SystemClock.uptimeMillis() },
    )
    private val subscriberContext = object : ContextWrapper(findApplicationContext(context)) {
        override fun getPackageName(): String = "io.github.windsnn.hyperlock.miniplayer"
        override fun getApplicationContext(): Context = this
    }
    private val subscriber: LyriconSubscriber = LyriconFactory.createSubscriber(subscriberContext)

    /** 当前歌词所属播放器的真实 Android 应用包名（如 com.salt.music, com.tencent.qqmusic）。 */
    val currentPlayerPackageName: String?
        get() = state.currentPlayerPackageName

    /** 彻底清空所有缓存（切歌、关后台或视频应用接管时调用）。 */
    fun clearCache() {
        state.clearCache()
    }

    private val connectionListener = object : ConnectionListener {
        override fun onConnected(subscriber: LyriconSubscriber) {
            HyperLog.i(TAG, "Lyricon subscriber connected ${subscriberInfoLabel(subscriber)}")
            handler.post { onLineChanged() }
        }

        override fun onReconnected(subscriber: LyriconSubscriber) {
            HyperLog.i(TAG, "Lyricon subscriber reconnected ${subscriberInfoLabel(subscriber)}")
            handler.post { onLineChanged() }
        }

        override fun onDisconnected(subscriber: LyriconSubscriber) {
            HyperLog.i(TAG, "Lyricon subscriber disconnected")
            handler.post {
                // 中心服务断开后不会再有推送，继续留着上一首歌的文本只会让锁屏显示"卡住的歌词"。
                if (state.cleared()) onLineChanged()
            }
        }

        override fun onConnectTimeout(subscriber: LyriconSubscriber) {
            // 用 warn 级别：这是"歌词不显示"最常见的原因，未开详细日志时也该能看到。
            HyperLog.w(
                TAG,
                "Lyricon subscriber connection timed out：" +
                    "请确认已安装词幕核心服务，并在 LSPosed 中勾选「系统界面」作用域后重启 SystemUI",
            )
            handler.post {
                if (state.cleared()) onLineChanged()
            }
        }
    }

    private val listener = object : ActivePlayerListener {
        override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
            val key = providerInfo?.let {
                "${it.providerPackageName}/${it.playerPackageName}/${it.processName}"
            }
            val playerPkg = providerInfo?.playerPackageName?.takeIf { it.isNotBlank() }
                ?: providerInfo?.providerPackageName?.takeIf { it.isNotBlank() }
            val metadataKeys = providerInfo?.metadata?.keys?.joinToString(",").orEmpty()
            handler.post {
                // 重连与快照会重复上报同一提供端，此时保留已有歌词，避免整块闪空。
                if (!state.providerChanged(key, playerPkg)) return@post
                val metadataSuffix = if (metadataKeys.isEmpty()) "" else " metadata=[$metadataKeys]"
                HyperLog.i(TAG, "Active provider -> ${key ?: "<none>"} (player=$playerPkg)$metadataSuffix")
                onLineChanged()
            }
        }

        override fun onSongChanged(song: Song?) {
            val raw = song?.lyrics.orEmpty()
            val key = LyricTimeline.songKeyOf(song, raw)
            val name = song?.name
            handler.post {
                state.songChanged(key, raw)
                HyperLog.i(TAG, "Song -> ${name ?: "<none>"} raw=${raw.size} usable=${state.usableLineCount}")
                onLineChanged()
            }
        }

        override fun onReceiveText(text: String?) {
            val preview = text.orEmpty()
                .lineSequence()
                .map(String::trim)
                .firstOrNull(String::isNotBlank)
                .orEmpty()
            handler.post {
                state.plainTextReceived(text)
                HyperLog.i(TAG, "Plain text lyric -> ${preview.ifBlank { "<clear>" }}")
                onLineChanged()
            }
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            // 暂停时保留当前行（与锁屏阅读习惯一致），这里只记录状态便于排查。
            HyperLog.i(TAG, "Playback state -> ${if (isPlaying) "playing" else "paused"}")
        }

        override fun onPositionChanged(position: Long) = applyPosition(position, seek = false)

        override fun onSeekTo(position: Long) = applyPosition(position, seek = true)

        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
            handler.post {
                if (state.displayTranslationChanged(isDisplayTranslation)) onLineChanged()
            }
        }

        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) {
            handler.post {
                if (state.displayRomaChanged(isDisplayRoma)) onLineChanged()
            }
        }
    }

    init {
        runCatching {
            subscriber.addConnectionListener(connectionListener)
            val subscribed = subscriber.subscribeActivePlayer(listener)
            subscriber.register()
            HyperLog.i(
                TAG,
                "Initialized Lyricon subscriber ${subscriberInfoLabel(subscriber)} subscribed=$subscribed",
            )
        }.onFailure { error ->
            HyperLog.e(TAG, "Failed to initialize Lyricon subscriber", error)
        }
    }

    /** 当前应展示的歌词；null 表示此刻没有可展示内容。 */
    fun currentLyric(appearance: LockscreenLyricsAppearance): LockscreenLyricText? {
        // 提前量属于取行时机，必须在读取当前行之前同步进状态机。
        state.leadChanged(appearance.leadMs.toLong())
        return state.currentLyric(appearance.showTranslation, appearance.showRoma)
    }

    /** 当前播放位置（毫秒）。 */
    val currentPosition: Long
        get() = state.currentPosition

    fun destroy() {
        runCatching { subscriber.removeConnectionListener(connectionListener) }
        runCatching { subscriber.unsubscribeActivePlayer(listener) }
        runCatching { subscriber.unregister() }
        runCatching { subscriber.destroy() }
        handler.removeCallbacksAndMessages(null)
    }

    private fun subscriberInfoLabel(subscriber: LyriconSubscriber): String =
        "(${subscriber.subscriberInfo.packageName}/${subscriber.subscriberInfo.processName})"

    /** 位置回调来自 16ms 轮询线程，状态机与 UI 一律回到主线程。 */
    private fun applyPosition(position: Long, seek: Boolean) {
        // 1. 息屏阻断：直接在 16ms 轮询线程判断，灭屏时直接 return，0 次 handler.post，主线程 0 唤醒
        val isInteractive = powerManager?.isInteractive ?: true
        if (!isInteractive && !seek) return

        // 2. 锁屏/卡片不可见阻断：直接在 16ms 轮询线程检查 volatile isAwake 标记，开屏解锁/未在锁屏展现时直接 return，0 次 handler.post，主线程 0 唤醒
        if (!isAwake() && !seek) return

        handler.post {
            // 3. 进入主线程后二次防抖校验（防止消息排队期间锁屏刚好退场）
            if (!isAwake() && !seek) return@post

            when (state.positionChanged(position, seek)) {
                LyricProgressUpdate.NONE -> Unit
                LyricProgressUpdate.LINE -> {
                    HyperLog.i(
                        TAG,
                        "Line -> #${state.currentLineIndex} ${state.currentLineText} " +
                            "(lead=${state.currentLeadMs}ms)",
                    )
                    onLineChanged()
                }
                LyricProgressUpdate.PROGRESS -> onProgressTick(position)
            }
        }
    }
}
