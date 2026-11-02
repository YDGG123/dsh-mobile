package com.clarklevis.dsh.shared.teamfeed

/**
 * 大响应分块重组（D1 缺口 2 补齐，2026-11-03）—— port iOS `FrameReassembler.swift`，语义逐条对齐：
 *
 * - 网关契约（`chunked.mjs:1-26`）：客户端在握手（auth）帧声明 `chunked-frames-v1` 之后才可能收到
 *   chunk 帧；没声明的老客户端永远收到整帧。chunk 形状：`{kind:'chunk', streamId, index, total,
 *   finalKind, part, at}`；`part` 按 **UTF-8 字符边界**切开（emoji/中文不会被劈半），客户端拼回原文。
 * - 设计红线（iOS `:6-12`）：**任何异常都不静默**——丢块、乱序、超体积、并发超限、超时，全部返回
 *   明确错误，由调用方记日志并重拉快照；宁可重来，也不拼半个 JSON 交给上层。
 * - 乱序/重复块安全：按 `index` 归位；重复块幂等覆盖、不重复计数。
 * - 体积先算后存：累计 UTF-8 字节数超上限立即丢流，不无限吃内存。
 * - 任何构造路径都过 [TeamFeedChunkLimits.clamped]：8MiB 硬帽不许被生产路径绕过（iOS P2 根因）。
 */
const val CHUNK_CAPABILITY = "chunked-frames-v1"

data class TeamFeedChunkLimits(
    /** 单块上限（UTF-8 字节）。收到超限块直接判错——说明网关和宣传值不一致。 */
    val maxChunkBytes: Int = 512 * 1024,
    /** 单条流重组后的上限（UTF-8 字节）。 */
    val maxReassembledBytes: Int = 4 * 1024 * 1024,
    /** 同一条连接上允许同时存在的流数。 */
    val maxStreams: Int = 2,
    /** 首块到达后多久还没收齐就丢弃（客户端内部资源保护，网关不公布）。 */
    val streamTimeoutSeconds: Long = 30,
) {
    companion object {
        const val ABSOLUTE_CHUNK_CAP = 2 * 1024 * 1024
        const val ABSOLUTE_REASSEMBLED_CAP = 8 * 1024 * 1024
        const val MINIMUM_REASSEMBLED = 64 * 1024
    }

    /** **唯一的夹取入口**。任何来自外部的 Limits（/info、测试、新字段）都必须走这里。 */
    val clamped: TeamFeedChunkLimits
        get() = copy(
            maxChunkBytes = maxChunkBytes.coerceIn(1024, TeamFeedChunkLimits.ABSOLUTE_CHUNK_CAP),
            maxReassembledBytes = maxReassembledBytes.coerceIn(
                TeamFeedChunkLimits.MINIMUM_REASSEMBLED,
                TeamFeedChunkLimits.ABSOLUTE_REASSEMBLED_CAP,
            ),
            maxStreams = maxStreams.coerceIn(1, 4),
            streamTimeoutSeconds = streamTimeoutSeconds.coerceIn(1, 300),
        )

    /** 从网关 `/info` 的 capabilities 读三个上限（缺字段 = 不更新该项）。 */
    fun mergedWith(capabilities: Map<String, Double?>): TeamFeedChunkLimits {
        var result = this
        capabilities["maxFrameBytes"]?.takeIf { it > 0 }?.let { result = result.copy(maxChunkBytes = it.toInt()) }
        capabilities["maxReassembledBytes"]?.takeIf { it > 0 }?.let { result = result.copy(maxReassembledBytes = it.toInt()) }
        capabilities["maxStreamsPerConnection"]?.takeIf { it > 0 }?.let { result = result.copy(maxStreams = it.toInt()) }
        return result.clamped
    }
}

/** 一条 chunk 帧解出来的内容（iOS `FrameReassembler.Chunk` 同构）。 */
data class TeamFeedChunk(
    val streamId: String,
    val index: Int,
    val total: Int,
    /** 重组后要走回的原处理链（snapshot / delta / cards / order …）。 */
    val finalKind: String,
    /** 已按 UTF-8 字符边界切好的原始 JSON 片段。 */
    val part: String,
)

/** 从 JSON 解 chunk；形状不对返回 null（调用方按普通坏帧丢弃，不进重组器）。 */
fun parseTeamFeedChunk(element: kotlinx.serialization.json.JsonElement): TeamFeedChunk? {
    val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
    if (obj.str("kind") != "chunk") return null
    val streamId = obj.str("streamId")?.takeIf { it.isNotEmpty() } ?: return null
    val index = obj.int("index") ?: return null
    val total = obj.int("total") ?: return null
    val finalKind = obj.str("finalKind")?.takeIf { it.isNotEmpty() } ?: return null
    val part = obj.str("part") ?: return null
    return TeamFeedChunk(streamId, index, total, finalKind, part)
}

sealed interface TeamFeedReassemblyError {
    data class ChunkTooLarge(val bytes: Int, val limit: Int) : TeamFeedReassemblyError
    data class IndexOutOfRange(val index: Int, val total: Int) : TeamFeedReassemblyError
    data class Malformed(val reason: String) : TeamFeedReassemblyError
    data class StreamTooLarge(val bytes: Int, val limit: Int) : TeamFeedReassemblyError
    data class TooManyStreams(val limit: Int) : TeamFeedReassemblyError
    data class StreamExpired(val streamId: String) : TeamFeedReassemblyError
}

/** accept 的结果：`payload != null` = 流已收齐（原始 JSON 字符串）；null = 还差块。 */
sealed interface TeamFeedChunkOutcome {
    data class Success(val payload: String?) : TeamFeedChunkOutcome
    data class Failure(val error: TeamFeedReassemblyError) : TeamFeedChunkOutcome
}

class TeamFeedChunkReassembler(
    limits: TeamFeedChunkLimits = TeamFeedChunkLimits(),
    private val nowMillis: () -> Long = { 0L },
) {
    private var limitsValue: TeamFeedChunkLimits = limits.clamped
    val limits: TeamFeedChunkLimits get() = limitsValue

    private class Stream(
        val total: Int,
        val finalKind: String,
        val parts: Array<String?>,
        var received: Int,
        var bytes: Int,
        val startedAtMillis: Long,
    )

    private val streams = mutableMapOf<String, Stream>()

    /** 更新上限（来自网关 /info）。必须过 clamped，别在别处再写一遍夹取。 */
    fun applyLimits(new: TeamFeedChunkLimits) {
        limitsValue = new.clamped
    }

    /** 显式丢一条流（错误路径都先丢再报）。 */
    fun discard(streamId: String) {
        streams.remove(streamId)
    }

    /** 清空全部流（新连接 ⇒ 旧连接的 chunk 流全部作废）。 */
    fun clear() {
        streams.clear()
    }

    /**
     * 喂一块。返回 [TeamFeedChunkOutcome.Success] 且 payload 非空表示这条流已收齐；
     * payload 为 null 表示还差块；[TeamFeedChunkOutcome.Failure] 一律意味着该流已被丢弃，
     * 调用方应记日志并重拉快照。
     */
    fun accept(chunk: TeamFeedChunk): TeamFeedChunkOutcome {
        val partBytes = chunk.part.encodeToByteArray().size
        val chunkLimit = limitsValue.maxChunkBytes
        if (partBytes > chunkLimit) {
            discard(chunk.streamId)
            return TeamFeedChunkOutcome.Failure(TeamFeedReassemblyError.ChunkTooLarge(partBytes, chunkLimit))
        }
        if (chunk.total <= 0 || chunk.index < 0 || chunk.index >= chunk.total || chunk.finalKind.isEmpty()) {
            discard(chunk.streamId)
            return TeamFeedChunkOutcome.Failure(TeamFeedReassemblyError.IndexOutOfRange(chunk.index, chunk.total))
        }
        val existing = streams[chunk.streamId]
        if (existing != null) {
            if (existing.total != chunk.total || existing.finalKind != chunk.finalKind) {
                // 同一 streamId 换了 total/类型：协议错乱，丢弃重来。
                discard(chunk.streamId)
                return TeamFeedChunkOutcome.Failure(
                    TeamFeedReassemblyError.Malformed("total/finalKind changed mid-stream: ${existing.total}→${chunk.total}"),
                )
            }
            if (existing.parts[chunk.index] == null) {
                existing.parts[chunk.index] = chunk.part
                existing.received += 1
                existing.bytes += partBytes
                val reassemblyLimit = limitsValue.maxReassembledBytes
                if (existing.bytes > reassemblyLimit) {
                    discard(chunk.streamId)
                    return TeamFeedChunkOutcome.Failure(
                        TeamFeedReassemblyError.StreamTooLarge(existing.bytes, reassemblyLimit),
                    )
                }
            } else {
                // 重复块：幂等覆盖（网关重发/网络重放都可能出现），不重复计入 received。
                existing.parts[chunk.index] = chunk.part
            }
        } else {
            if (streams.size >= limitsValue.maxStreams) {
                return TeamFeedChunkOutcome.Failure(TeamFeedReassemblyError.TooManyStreams(limitsValue.maxStreams))
            }
            val parts = arrayOfNulls<String?>(chunk.total)
            parts[chunk.index] = chunk.part
            streams[chunk.streamId] = Stream(chunk.total, chunk.finalKind, parts, 1, partBytes, nowMillis())
        }
        val stream = streams.getValue(chunk.streamId)
        if (stream.received != stream.total) return TeamFeedChunkOutcome.Success(null)
        val payload = stream.parts.filterNotNull().joinToString("")
        discard(chunk.streamId)
        if (payload.isEmpty()) {
            return TeamFeedChunkOutcome.Failure(TeamFeedReassemblyError.Malformed("reassembled payload is empty"))
        }
        return TeamFeedChunkOutcome.Success(payload)
    }

    /** 清掉超时的流，返回被丢弃的 streamId（调用方据此记日志/重拉快照）。 */
    fun expire(nowMillisOverride: Long? = null): List<String> {
        val instant = nowMillisOverride ?: nowMillis()
        val dropped = streams.filter { (_, stream) ->
            instant - stream.startedAtMillis > limitsValue.streamTimeoutSeconds * 1000
        }.keys.toList()
        dropped.forEach { streams.remove(it) }
        return dropped
    }
}
