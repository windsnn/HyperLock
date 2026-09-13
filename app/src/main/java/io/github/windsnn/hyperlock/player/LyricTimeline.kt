package io.github.windsnn.hyperlock.player

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.extensions.TimingNavigator
import io.github.proify.lyricon.lyric.model.interfaces.ILyricTiming

/** 行结束时间不可信时使用的兜底行时长（毫秒）。 */
private const val FALLBACK_LINE_DURATION_MS = 5_000L

/**
 * 单行歌词。
 *
 * 实现 [ILyricTiming] 以便直接复用词幕官方的 [TimingNavigator] 检索当前行；
 * `begin/end/duration` 在 [LyricTimeline.buildLines] 中已做兜底，保证 `end > begin`。
 */
internal data class LockscreenLyricLine(
    override var begin: Long,
    override var end: Long,
    override var duration: Long,
    val text: String,
    val translation: String = "",
    val secondary: String = "",
    val roma: String = "",
    val words: List<LyricWord> = emptyList(),
) : ILyricTiming

/** 锁屏卡片当前要展示的文本：主行 + 副行（翻译 / 罗马音 / 次要文本）+ 逐字时间轴。 */
internal data class LockscreenLyricText(
    val text: String,
    val detail: String = "",
    val words: List<LyricWord> = emptyList(),
    val begin: Long = 0L,
    val end: Long = 0L,
)

/**
 * 歌词时间轴纯逻辑：行构建、当前行检索、副行选择、歌曲身份。
 *
 * 刻意不依赖 Android 与词幕订阅端，方便离线/单测验证这部分的边界行为。
 */
internal object LyricTimeline {

    /**
     * 把词幕歌词行转成时间轴可信的行。
     *
     * 与上游 `Song.normalize()` 的差异：`end` 缺失（`end <= begin`）时用下一行的 `begin`
     * 补齐，而不是丢弃该行——部分提供端只给 `begin`，直接丢弃会让整首歌没有歌词。
     */
    fun buildLines(raw: List<RichLyricLine>): List<LockscreenLyricLine> {
        if (raw.isEmpty()) return emptyList()
        val sorted = raw.sortedBy { it.begin }
        val result = ArrayList<LockscreenLyricLine>(sorted.size)
        sorted.forEachIndexed { index, line ->
            val text = lineText(line)
            if (text.isBlank()) return@forEachIndexed
            val begin = line.begin.coerceAtLeast(0L)
            var end = line.end
            if (end <= begin) {
                val nextBegin = sorted.getOrNull(index + 1)?.begin ?: 0L
                end = when {
                    nextBegin > begin -> nextBegin
                    line.duration > 0 -> begin + line.duration
                    else -> begin + FALLBACK_LINE_DURATION_MS
                }
            }
            result += LockscreenLyricLine(
                begin = begin,
                end = end,
                duration = end - begin,
                text = text,
                translation = textOf(line.translation) { line.translationWords },
                secondary = textOf(line.secondary) { line.secondaryWords },
                roma = line.roma.orEmpty().trim(),
                words = line.words.orEmpty(),
            )
        }
        return result
    }

    /**
     * 当前行索引：取最后一条 `begin <= position` 的行；间奏（该行已结束）保持上一句，
     * 第一行尚未开始时显示第一行，避免前奏阶段卡片空着。
     */
    fun currentLineIndex(
        lines: List<LockscreenLyricLine>,
        navigator: TimingNavigator<LockscreenLyricLine>?,
        position: Long,
    ): Int {
        if (lines.isEmpty()) return -1
        val index = navigator?.findTargetIndex(position) ?: -1
        return if (index < 0) 0 else index
    }

    /** 副行优先级：翻译（本地开关 + 词幕开关）→ 罗马音 → 次要文本。 */
    fun resolveDetail(
        line: LockscreenLyricLine,
        showTranslation: Boolean,
        showRoma: Boolean,
        providerTranslation: Boolean?,
        providerRoma: Boolean?,
    ): String = when {
        showTranslation && providerTranslation != false && line.translation.isNotBlank() ->
            line.translation
        showRoma && providerRoma != false && line.roma.isNotBlank() -> line.roma
        line.secondary.isNotBlank() && line.secondary != line.text -> line.secondary
        else -> ""
    }

    /** 歌曲身份：用于区分「换歌」与「同一首歌歌词分批到达」。 */
    fun songKeyOf(song: Song?, raw: List<RichLyricLine>): String? {
        if (song == null) return null
        return listOf(
            song.id.orEmpty(),
            song.name.orEmpty(),
            song.artist.orEmpty(),
            song.duration.toString(),
            (raw.firstOrNull()?.begin ?: -1L).toString(),
        ).joinToString("|")
    }

    fun lineText(line: RichLyricLine): String = textOf(line.text) { line.words }

    /** 文本优先，缺失时用逐词数据拼接，保证只带 `words` 的提供端也能显示。 */
    fun textOf(value: String?, words: () -> List<LyricWord>?): String =
        value?.takeIf(String::isNotBlank)
            ?: words().orEmpty().joinToString("") { it.text.orEmpty() }.trim()
}
