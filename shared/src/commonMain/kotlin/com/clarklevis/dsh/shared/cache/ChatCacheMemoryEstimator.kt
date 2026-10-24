package com.clarklevis.dsh.shared.cache

import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.SessionEvent

/**
 * 用于淘汰决策的保守估算，不是 heap 实测。原始事件计入 3 份保留/桥接/队列副本，投影计入 2 份。
 * 使用 String.length 和有界 Json 树遍历；不序列化、不生成完整 JSON，也不遍历字符串的所有字符。
 */
object ChatCacheMemoryEstimator {
    private const val SATURATION_BYTES = 64L * 1024 * 1024
    private const val RAW_RETAINED_COPIES = 3L
    private const val PROJECTION_RETAINED_COPIES = 2L
    private const val RECORD_OVERHEAD_BYTES = 768L
    private const val ITEM_OVERHEAD_BYTES = 512L
    private const val STRING_OVERHEAD_BYTES = 48L
    private const val NODE_OVERHEAD_BYTES = 64L
    private const val MAXIMUM_JSON_DEPTH = 128

    fun estimateHistory(events: List<SessionEvent>): Long {
        val counter = Counter(false, SATURATION_BYTES / RAW_RETAINED_COPIES)
        for (record in events) {
            counter.bytes += RECORD_OVERHEAD_BYTES
            visit(record, counter)
            if (counter.full) break
        }
        return (counter.bytes * RAW_RETAINED_COPIES).coerceAtMost(SATURATION_BYTES)
    }

    fun estimateProjection(items: List<ConversationItem>): Long {
        val counter = Counter(false, SATURATION_BYTES / PROJECTION_RETAINED_COPIES)
        for (item in items) {
            counter.bytes += ITEM_OVERHEAD_BYTES
            counter.text(item.id)
            counter.text(item.title)
            counter.text(item.text)
            item.images.forEach { image ->
                counter.text(image.attachmentId)
                counter.text(image.name)
                counter.text(image.mediaType)
            }
            (item.files + item.knownFiles).forEach { file ->
                counter.text(file.path)
                counter.text(file.description)
            }
            if (counter.full) break
        }
        return (counter.bytes * PROJECTION_RETAINED_COPIES).coerceAtMost(SATURATION_BYTES)
    }

    /** 编码前检查字符数下界；极深 Json 直接达到拒绝阈值，避免在缓存路径递归序列化。 */
    internal fun contentLowerBound(record: SessionEvent): Long {
        val counter = Counter(true, ChatCacheWindowPolicy.DEFAULT_MAXIMUM_BYTES + 1)
        visit(record, counter)
        return counter.bytes
    }

    @Suppress("LongMethod")
    private fun visit(record: SessionEvent, counter: Counter) {
        counter.text(record.sessionId)
        counter.entries(record.sourceEventSeqs?.size ?: 0)
        counter.json(record.surfaceOp)
        val event = record.event
        counter.text(event.type)
        counter.text(event.text)
        counter.text(event.source)
        counter.text(event.chunkType)
        counter.text(event.reasoning)
        counter.text(event.callId)
        counter.text(event.name)
        counter.text(event.preview)
        counter.text(event.reason)
        counter.text(event.rootCallId)
        counter.text(event.parentCallId)
        counter.text(event.subCallId)
        counter.text(event.commandId)
        counter.text(event.args)
        counter.text(event.outcome)
        counter.text(event.compactionId)
        counter.text(event.sourceCommandId)
        counter.text(event.error)
        counter.text(event.output)
        counter.text(event.retryId)
        counter.text(event.finish?.kind)
        counter.text(event.tool?.id)
        counter.text(event.tool?.name)
        counter.text(event.tool?.argumentsDelta)
        counter.json(event.usage)
        counter.json(event.arguments)
        counter.json(event.raw)
        counter.json(event.stream)
        counter.json(event.reasonDetails)
        counter.json(event.failure)
        counter.json(event.finish?.failure)
        event.toolCalls?.forEach { call ->
            counter.text(call.id)
            counter.text(call.name)
            counter.json(call.arguments)
        }
        event.images?.forEach { image ->
            counter.text(image.attachmentId)
            counter.text(image.name)
            counter.text(image.mediaType)
        }
        event.files?.forEach { file ->
            counter.text(file.path)
            counter.text(file.description)
        }
    }

    private class Counter(private val contentOnly: Boolean, private val maximum: Long) {
        var bytes = 0L
        val full: Boolean get() = bytes >= maximum

        fun text(value: String?) {
            if (value == null || full) return
            bytes += if (contentOnly) value.length.toLong() else STRING_OVERHEAD_BYTES + value.length.toLong() * 2
        }

        fun entries(count: Int) {
            bytes += if (contentOnly) count.toLong() else count.toLong() * NODE_OVERHEAD_BYTES
        }

        fun json(value: JsonValue?) {
            if (value != null) jsonNode(value, 0)
        }

        private fun jsonNode(node: JsonValue, depth: Int) {
            if (full) return
            if (depth > MAXIMUM_JSON_DEPTH) {
                // 极深 JSON 采取保守上界，避免主线程栈溢出；缓存导出会明确拒绝该条。
                bytes = maximum
                return
            }
            bytes += if (contentOnly) 1 else NODE_OVERHEAD_BYTES
            when (node) {
                is JsonValue.StringValue -> text(node.value)
                is JsonValue.ObjectValue -> for ((key, child) in node.value) {
                    text(key)
                    jsonNode(child, depth + 1)
                    if (full) break
                }
                is JsonValue.ArrayValue -> for (child in node.value) {
                    jsonNode(child, depth + 1)
                    if (full) break
                }
                else -> Unit
            }
        }
    }
}
