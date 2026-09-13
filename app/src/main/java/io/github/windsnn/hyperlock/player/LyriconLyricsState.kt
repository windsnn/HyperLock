package io.github.windsnn.hyperlock.player

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.extensions.TimingNavigator

/** 位置推进后的处理结果。 */
internal enum class LyricProgressUpdate {
    /** 位置没变，或未到节流间隔且当前行未变：无需刷新。 */
    NONE,

    /** 当前行发生变化：立即刷新（并记录行日志）。 */
    LINE,

    /** 位置推进但当前行未变：按节流间隔刷新。 */
    PROGRESS,
}

/**
 * 词幕订阅端的歌词状态机（纯逻辑，不依赖 Android 与词幕运行时）。
 *
 * 与词幕的推送顺序对齐（连接快照顺序为：提供端 → 播放状态 → 歌曲 → 显示开关 → 位置）：
 * 1. 只有提供端真的变化才清空歌词，同一提供端重复上报（重连/快照）保持现状；
 * 2. 换歌按歌曲身份判断：换歌重置播放位置，同一首歌歌词分批到达时保留位置；
 * 3. 位置按「当前行变化立即刷 / 否则节流刷 / seek 立即刷」节流；
 * 4. 断开或连接超时清空歌词，避免锁屏留下刷不动的旧文本。
 *
 * 所有方法都必须由同一个线程调用（本模块里是 SystemUI 主线程）。
 */
internal class LyriconLyricsState(
    private val progressRefreshIntervalMs: Long,
    private val clock: () -> Long,
) {
    private var providerKey: String? = null
    private var playerPackageName: String? = null
    private var songKey: String? = null
    private var lines: List<LockscreenLyricLine> = emptyList()
    private var navigator: TimingNavigator<LockscreenLyricLine>? = null
    private var position: Long = 0L
    private var displayTranslation: Boolean? = null
    private var displayRoma: Boolean? = null
    private var plainText: String = ""
    private var plainDetail: String = ""
    private var displayedLineIndex: Int = -1
    private var lastProgressAt: Long = 0L
    private var leadMs: Long = 0L

    /** 当前歌词所属播放器的真实 Android 应用包名（如 com.salt.music, com.tencent.qqmusic）。 */
    val currentPlayerPackageName: String?
        get() = playerPackageName

    /** 当前行索引（-1 表示没有可展示的行）。 */
    val currentLineIndex: Int
        get() = LyricTimeline.currentLineIndex(lines, navigator, position + leadMs)

    /** 当前提前量（毫秒），仅用于日志。 */
    val currentLeadMs: Long
        get() = leadMs

    /** 当前行文本，仅用于日志。 */
    val currentLineText: String
        get() = lines.getOrNull(currentLineIndex)?.text.orEmpty()

    /** 当前播放位置（毫秒）。 */
    val currentPosition: Long
        get() = position

    /** 可用（已通过时间轴兜底）的歌词行数，仅用于日志。 */
    val usableLineCount: Int
        get() = lines.size

    /** 提供端变化；返回 true 表示歌词被清空、需要刷新卡片。 */
    fun providerChanged(key: String?, packageName: String? = null): Boolean {
        if (providerKey == key && playerPackageName == packageName) return false
        providerKey = key
        playerPackageName = packageName
        resetLyric()
        return true
    }

    /** 歌曲变化（含歌词分批到达）；返回 true 表示需要刷新卡片。 */
    fun songChanged(key: String?, raw: List<RichLyricLine>): Boolean {
        if (key != songKey) {
            songKey = key
            position = 0L
            displayedLineIndex = -1
        }
        lines = LyricTimeline.buildLines(raw)
        navigator = lines.takeIf { it.isNotEmpty() }?.let { TimingNavigator(it.toTypedArray()) }
        plainText = ""
        plainDetail = ""
        return true
    }

    /** 纯文本歌词（无时间轴）；返回 true 表示需要刷新卡片。 */
    fun plainTextReceived(text: String?): Boolean {
        val textLines = text.orEmpty()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        plainText = textLines.firstOrNull().orEmpty()
        plainDetail = textLines.drop(1).joinToString(" / ")
        lines = emptyList()
        navigator = null
        songKey = null
        displayedLineIndex = -1
        return true
    }

    /** 播放位置更新；[seek] 表示主动跳转（跳过节的流）。 */
    fun positionChanged(newPosition: Long, seek: Boolean): LyricProgressUpdate {
        if (!seek && newPosition == position) return LyricProgressUpdate.NONE
        position = newPosition
        val index = currentLineIndex
        val now = clock()
        val lineChanged = index != displayedLineIndex
        if (!seek && !lineChanged && now - lastProgressAt < progressRefreshIntervalMs) {
            return LyricProgressUpdate.NONE
        }
        displayedLineIndex = index
        lastProgressAt = now
        return if (lineChanged) LyricProgressUpdate.LINE else LyricProgressUpdate.PROGRESS
    }

    /** 词幕下发的"是否显示翻译"开关；返回 true 表示副行可能需要重算。 */
    fun displayTranslationChanged(isDisplayTranslation: Boolean): Boolean {
        if (displayTranslation == isDisplayTranslation) return false
        displayTranslation = isDisplayTranslation
        return true
    }

    /** 词幕下发的"是否显示罗马音"开关；返回 true 表示副行可能需要重算。 */
    fun displayRomaChanged(isDisplayRoma: Boolean): Boolean {
        if (displayRoma == isDisplayRoma) return false
        displayRoma = isDisplayRoma
        return true
    }

    /**
     * 提前切行的时间（毫秒）。
     *
     * 行切换点由 `position + leadMs` 决定：下一行的 `begin` 到来之前就切过去，
     * 让用户在人声唱到第一个词时已经就位（苹果音乐的手感）。
     * 返回 true 表示提前量变化导致当前行需要重算。
     */
    fun leadChanged(newLeadMs: Long): Boolean {
        val normalized = newLeadMs.coerceIn(0L, MAX_LEAD_MS)
        if (leadMs == normalized) return false
        leadMs = normalized
        return true
    }

    /** 断开 / 连接超时：清空歌词，返回是否确实有内容被清掉。 */
    fun cleared(): Boolean {
        val hadContent = plainText.isNotBlank() || lines.isNotEmpty()
        resetLyric()
        return hadContent
    }

    /** 彻底清空所有缓存（包括提供端与包名状态），用于媒体会话切换或视频接管场景。 */
    fun clearCache() {
        resetLyric()
        providerKey = null
        playerPackageName = null
    }

    /** 当前应展示的歌词；null 表示此刻没有可展示内容。 */
    fun currentLyric(showTranslation: Boolean, showRoma: Boolean): LockscreenLyricText? {
        plainText.takeIf { it.isNotBlank() }?.let { return LockscreenLyricText(it, plainDetail) }
        val line = lines.getOrNull(currentLineIndex) ?: return null
        val text = line.text.takeIf { it.isNotBlank() } ?: return null
        return LockscreenLyricText(
            text = text,
            detail = LyricTimeline.resolveDetail(
                line = line,
                showTranslation = showTranslation,
                showRoma = showRoma,
                providerTranslation = displayTranslation,
                providerRoma = displayRoma,
            ),
            words = line.words,
            begin = line.begin,
            end = line.end,
        )
    }

    private fun resetLyric() {
        lines = emptyList()
        navigator = null
        plainText = ""
        plainDetail = ""
        songKey = null
        position = 0L
        displayedLineIndex = -1
    }

    private companion object {
        const val MAX_LEAD_MS = 800L
    }
}
