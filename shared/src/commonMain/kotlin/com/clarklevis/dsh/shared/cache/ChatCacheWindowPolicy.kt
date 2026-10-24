package com.clarklevis.dsh.shared.cache

import com.clarklevis.dsh.shared.facade.CachedHistoryWindow
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.encodeToString

/** 两端使用同一窗口预算；仅裁旧事件，不修改正在显示/同步的原始历史。 */
object ChatCacheWindowPolicy {
    const val DEFAULT_MAXIMUM_BYTES: Long = 8L * 1024 * 1024
    const val DEFAULT_MAXIMUM_EVENTS: Int = 400
    private const val HEADER_MARGIN_BYTES = 256L
    private const val EMPTY_EVENTS_SUFFIX = "[]}"
    private data class EncodedEvent(val json: String, val bytes: Long)

    internal fun exportTail(
        metadata: CachedHistoryWindow,
        records: List<SessionEvent>,
        maximumBytes: Long = DEFAULT_MAXIMUM_BYTES,
        maximumEvents: Int = DEFAULT_MAXIMUM_EVENTS
    ): String? {
        val encodedHeader = wireJson.encodeToString(metadata.copy(events = emptyList()))
        var remaining = maximumBytes - encodedHeader.utf8Size() - HEADER_MARGIN_BYTES
        val encoded = mutableListOf<String>()
        var firstSequence: Int? = null
        var cropped = false
        val durableTail = records.asReversed().asSequence().filter { it.event.type != "assistant/chunk" }
        for (record in durableTail) {
            val candidate = if (encoded.size < maximumEvents) encodeWithinBudget(record, remaining) else null
            if (candidate == null) {
                cropped = true
                break
            }
            encoded += candidate.json
            remaining -= candidate.bytes
            firstSequence = record.seq
        }
        if (cropped && encoded.isEmpty()) {
            // 最新单条本身放不下时拒绝该快照；不能截断正文或用更旧事件假装覆盖最新水位。
            chatCacheLog("snapshot-skipped", "reason=oversized-latest-event")
            return null
        }
        val header = metadata.copy(
            events = emptyList(),
            hasMore = metadata.hasMore || cropped,
            nextBeforeSequence = if (cropped) firstSequence else metadata.nextBeforeSequence
        )
        // events 是版本化 envelope 的最后一个字段，拼接的片段全部由同一个 serializer 生成。
        // 无需再次编码整个选中窗口，更不会先编码全部历史。
        val prefix = wireJson.encodeToString(header)
        check(prefix.endsWith(EMPTY_EVENTS_SUFFIX))
        if (cropped) chatCacheLog("snapshot-tail-cropped", "events=${encoded.size}")
        return buildString {
            append(prefix.dropLast(EMPTY_EVENTS_SUFFIX.length))
            append('[')
            encoded.asReversed().forEachIndexed { index, event ->
                if (index > 0) append(',')
                append(event)
            }
            append("]}")
        }
    }
    private fun encodeWithinBudget(record: SessionEvent, remaining: Long): EncodedEvent? {
        if (ChatCacheMemoryEstimator.contentLowerBound(record) > remaining) return null
        val json = wireJson.encodeToString(record)
        val bytes = json.utf8Size() + 1 // 预留逗号，比精确数组占用多一字节。
        return if (bytes <= remaining) EncodedEvent(json, bytes) else null
    }
}

/** 不分配 UTF-8 ByteArray；坏代理字符按替换字符的三字节估算。 */
// UTF-8 的标准字节边界和位宽。
@Suppress("MagicNumber")
internal fun String.utf8Size(): Long {
    var bytes = 0L
    var index = 0
    while (index < length) {
        val value = this[index]
        bytes += when {
            value.code <= 0x7f -> 1
            value.code <= 0x7ff -> 2
            value.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                index += 1
                4
            }
            else -> 3
        }
        index += 1
    }
    return bytes
}
