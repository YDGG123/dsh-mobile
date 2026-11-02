package com.clarklevis.dsh.shared.teamfeed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * chunk 感知的收帧入口（D1 缺口 2 闭环，2026-11-03）—— transport 层委托它分发，
 * 与 iOS 同层（`FrameReassembler` 就在 transport 收帧处）。
 *
 * **为什么不能靠字符串嗅探**：用户消息里可能出现字面量 `"kind":"chunk"`，
 * 按 contains 判会误吞正常帧——所以走 JSON 解析判定（解析失败 = 不是 chunk，原样放行）。
 *
 * 分发规则：
 * - 不是 chunk 帧 ⇒ [Result.Deliver]（原样交处理链，文本保持调用方传入的原样）；
 * - chunk 且流未收齐 ⇒ [Result.Pending]（吞掉，不交处理链）；
 * - chunk 且流收齐 ⇒ [Result.Complete]（重组后的**原始 JSON 字符串**交处理链，走原解析路径）；
 * - chunk 且出错（超体积/错乱/越界…）⇒ [Result.Failed]：**流已在重组器里丢弃**，
 *   调用方记日志；快照重拉归段 5 的会话层（现在只上报，不假装恢复）。
 */
class TeamFeedChunkInbox(
    private val reassembler: TeamFeedChunkReassembler = TeamFeedChunkReassembler(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        /** 原样交处理链（[text] = 要交给 onFrameText 的内容）。 */
        data class Deliver(val text: String) : Result

        /** chunk 流收齐：重组出的原始 JSON（应交 onFrameText 走原解析路径）。 */
        data class Complete(val json: String) : Result

        /** chunk 流还差块：吞掉。 */
        object Pending : Result

        /** chunk 流出错：流已被丢弃，调用方记日志并安排重取快照。 */
        data class Failed(val error: TeamFeedReassemblyError) : Result
    }

    fun onText(text: String): Result {
        val chunk = runCatching {
            val obj = json.parseToJsonElement(text) as? JsonObject ?: return Result.Deliver(text)
            if (obj.str("kind") != "chunk") return Result.Deliver(text)
            parseTeamFeedChunk(obj.jsonObject)
        }.getOrNull()
        if (chunk == null) return Result.Deliver(text)
        return when (val outcome = reassembler.accept(chunk)) {
            is TeamFeedChunkOutcome.Success ->
                if (outcome.payload != null) Result.Complete(outcome.payload) else Result.Pending
            is TeamFeedChunkOutcome.Failure -> Result.Failed(outcome.error)
        }
    }

    /** 新连接 ⇒ 流状态作废（chunk 流按连接计，iOS reassembler 也是每连接一份）。 */
    fun reset() {
        reassembler.clear()
    }
}
