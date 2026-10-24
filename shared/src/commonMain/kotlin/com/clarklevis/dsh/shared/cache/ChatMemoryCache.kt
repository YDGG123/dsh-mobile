package com.clarklevis.dsh.shared.cache

import com.clarklevis.dsh.shared.facade.CachedHistoryWindow

internal data class ChatCacheKey(val gatewayId: String, val sessionId: String)

private const val MEBIBYTE = 1024L * 1024
private const val MEMORY_MAXIMUM_BYTES = 24 * MEBIBYTE
private const val MEMORY_MAXIMUM_SESSIONS = 6
private const val ESTIMATED_EVENT_OVERHEAD_BYTES = 512L
private const val MAXIMUM_GRAMS_PER_MESSAGE = 2048
private const val ESTIMATED_INDEX_ROW_OVERHEAD_BYTES = 64L

/** 只由 metadata 锁保护；这里不执行序列化、SQLite 或文件 I/O。bytes 是保守估算，不是 heap 实测值。 */
internal class ChatMemoryCache(
    private val maximumBytes: Long = MEMORY_MAXIMUM_BYTES,
    private val maximumSessions: Int = MEMORY_MAXIMUM_SESSIONS
) {
    private data class Entry(val window: CachedHistoryWindow, val bytes: Long)
    private val entries = linkedMapOf<ChatCacheKey, Entry>()
    var bytes: Long = 0
        private set
    val size: Int get() = entries.size

    fun get(key: ChatCacheKey): CachedHistoryWindow? {
        val entry = entries.remove(key) ?: return null
        entries[key] = entry
        return entry.window
    }

    fun put(key: ChatCacheKey, window: CachedHistoryWindow, encodedBytes: Long) {
        remove(key)
        val estimate = encodedBytes * 2 + window.events.size * ESTIMATED_EVENT_OVERHEAD_BYTES
        if (estimate > maximumBytes) return
        entries[key] = Entry(window, estimate)
        bytes += estimate
        while (bytes > maximumBytes || entries.size > maximumSessions) remove(entries.keys.first())
    }

    fun remove(key: ChatCacheKey) {
        entries.remove(key)?.let { bytes -= it.bytes }
    }

    fun clear(gatewayId: String? = null) {
        entries.keys.filter { gatewayId == null || it.gatewayId == gatewayId }.forEach(::remove)
    }
}

internal data class SearchIndex(val texts: List<CachedSearchText>, val grams: List<CachedSearchGram>, val bytes: Long)

internal fun searchIndex(gatewayId: String, window: CachedHistoryWindow): SearchIndex {
    val texts = mutableListOf<CachedSearchText>()
    val grams = mutableListOf<CachedSearchGram>()
    for (record in window.events) {
        val text = record.event.text?.takeIf {
            record.event.type in setOf("user/message", "assistant/message") && it.isNotBlank()
        } ?: continue
        val normalized = text.lowercase()
        // 超长随机文本不建立无界 ngram；保留正文并按参数化包含查询降级，不能漏搜。
        val candidates = linkedSetOf<String>()
        var first = 0
        var second = nextUnicodeOffset(normalized, first)
        var third = nextUnicodeOffset(normalized, second)
        var end = nextUnicodeOffset(normalized, third)
        while (third < normalized.length && candidates.size <= MAXIMUM_GRAMS_PER_MESSAGE) {
            candidates += normalized.substring(first, end)
            first = second
            second = third
            third = end
            end = nextUnicodeOffset(normalized, end)
        }
        val indexed = candidates.size <= MAXIMUM_GRAMS_PER_MESSAGE
        texts += CachedSearchText(gatewayId, window.sessionId, record.seq, record.time, text, normalized, indexed)
        if (indexed) grams += candidates.map { CachedSearchGram(gatewayId, it, window.sessionId, record.seq) }
    }
    // 同时计入冗余正文和索引键；SQLite 页/事务开销另受物理 max_page_count 约束。
    val bytes =
        texts.sumOf {
            it.text.encodeToByteArray().size.toLong() + it.normalizedText.encodeToByteArray().size +
                ESTIMATED_INDEX_ROW_OVERHEAD_BYTES
        } +
            grams.sumOf {
                it.gram.encodeToByteArray().size.toLong() + gatewayId.encodeToByteArray().size + it.sessionId.encodeToByteArray().size +
                    ESTIMATED_INDEX_ROW_OVERHEAD_BYTES
            }
    return SearchIndex(texts, grams, bytes)
}

/** 按 Unicode scalar 切分，不能把 emoji 的 UTF-16 代理对拆开建索引。 */
internal fun unicodeUnits(value: String): List<String> {
    val result = mutableListOf<String>()
    var index = 0
    while (index < value.length) {
        val length = if (value[index].isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) 2 else 1
        result += value.substring(index, index + length)
        index += length
    }
    return result
}

private fun nextUnicodeOffset(value: String, index: Int): Int = when {
    index >= value.length -> value.length
    value[index].isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> index + 2
    else -> index + 1
}
