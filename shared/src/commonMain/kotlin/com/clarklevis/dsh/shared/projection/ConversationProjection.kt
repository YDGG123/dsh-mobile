package com.clarklevis.dsh.shared.projection

import com.clarklevis.dsh.shared.protocol.PresentedFile
import com.clarklevis.dsh.shared.protocol.FinishInfo
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.failureText
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.sync.AssistantChunk
import com.clarklevis.dsh.shared.sync.expandAssistantStream
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.Serializable

@Serializable
enum class ConversationItemKind { USER, CONTEXT, ASSISTANT, REASONING, TOOL, JSON_TOOL, TOOL_RESULT, STATUS, SYSTEM }

@Serializable
data class ConversationProjectionLabels(
    val userMessage: String = "You",
    /** 已包含末尾分隔符，例如 `Context · `；事件 source 会在 KMP 内追加。 */
    val context: String = "Context · ",
    val streamingAssistant: String = "DeepSeek",
    val streamingReasoning: String = "Think",
    val finalAssistant: String = "DeepSeek",
    val finalReasoning: String = "Think",
    val assemblingTool: String = "Tool",
    val toolResultDone: String = "Tool result",
    val toolResultFailed: String = "Tool failed",
    val commandRunning: String = "Running…",
    val commandCompacting: String = "Compacting…",
    val commandCompleted: String = "Completed",
    val commandFailed: String = "Failed",
    val compactedHistory: String = "Compacted {items} history items (~{tokens} tokens)",
    val interrupted: String = "Generation interrupted",
    val modelAttemptFailed: String = "Model request failed",
    val modelRetrying: String = "Retrying model request",
    val modelRetried: String = "Model request retried",
    val generationFailed: String = "Generation failed",
    val generationCancelled: String = "Generation cancelled",
    val outputTruncated: String = "Output truncated; showing the beginning and end"
)

@Serializable
data class ConversationItem(
    val id: String,
    val kind: ConversationItemKind,
    val title: String,
    val text: String,
    val images: List<GatewayImageAttachment> = emptyList(),
    val isError: Boolean = false,
    val epochSeconds: Double,
    val turn: Int? = null,
    val files: List<PresentedFile> = emptyList(),
    val knownFiles: List<PresentedFile> = emptyList(),
    /**
     * 源事件的 seq（`eventId` 里那一段，`sessionId-seq` 形态的条目可直接取用）。
     * **跨源精确去重键的一半**：`(sessionId, seq)` 与 bridge 消息的 seq 同源（宿主事件 seq），
     * 加它是为了覆盖「用户自己发的消息」——那类条目 id 是 `sessionId-user-<messageId>`，拆不出 seq
     * （iOSCoder 2026-11-02 查出，Chief 定为不许退化成启发式去重）。
     *
     * ⚠️ **已知边界（Chief 2026-11-02 裁定不做）**：**helper 产物本条为 `null`**——
     * `appendStream` / `upsertStatus` / `insert` 等 helper 里构造的条目（工具/流式/状态类，共 9 处）
     * 作用域里没有 `record`，填 seq 需要改函数签名并一路透传。
     *
     * ⚠️ **修正（AndroidTester 2026-11-02 量化）**：原先注释说「这些条目 id 本就是 `sessionId-seq`，
     * 拆比透传免费」——**对不上代码**。`appendStream` 的 4 个调用点 id 全是**合成 key**
     * （`stream-text-$key` / `stream-reason-$key` / `stream-tool-$toolKey` / `appendStream(key, key, …)`），
     * **末段不是 seq**；steering 的 id 末段是 rawId，也不是 seq（但那条已填 seq，不受影响）。
     * 所以这些条目**两样都拿不到 ⇒ 必然落到第三档**。**消费方（合并层）的取值优先级因此写死**：
     *
     * ```
     * item.seq          → 有则用（用户消息等已填）
     * 否则从 id 末段拆   → 仅当末段是纯数字（"sessionId-seq" 形态）；**非数字一律视为拿不到**
     *                      （不得抛异常、不得默认 0 —— 那会变成另一个静默错值）
     * 两样都拿不到      → **该条目不参与去重，但必须留日志**（不许静默跳过）
     * ```
     *
     * ⇒ **第三档是常态，不是异常路径**：装机 logcat 里应能看到**多条**该类日志；一条都没有才判红
     * （说明第三档压根没实现，而页面上看不出任何异常）。
     *
     * 用户消息两条路径（steering / user-message）**已填 seq**，③「自己发的只出现一次」不缺字段。
     */
    val seq: Int? = null,
    /**
     * 群聊里这条发言人是谁（**只来自服务端 `author.name`**，Chief 2026-11-02 定为方案 B）。
     *
     * 三条渲染规则（**同样定死，勿各自发挥**）：
     * ```
     * senderName == null 且 role == user        ⇒ 显示「我」
     * senderName != null                        ⇒ 显示该名字（气泡上方小号灰字）
     * senderName == null 且 role == assistant   ⇒ **不显示任何名字**（绝不回落成「我」）
     * ```
     * 纪律：**禁止本地推断**（不拿会话标题凑、不写「未知/成员」）——假身份比空更糟；
     * `role == user` 一律不显示名字，否则会与网关侧那条用户条目重复显示自己的名字。
     */
    val senderName: String? = null
)

/** 高频 token 流只发送有序操作，不重复发送已经累计的完整消息文本。 */
@Serializable
data class ConversationProjectionOperation(
    val kind: String,
    val item: ConversationItem? = null,
    val itemId: String? = null,
    val delta: String? = null,
    val epochSeconds: Double? = null
)

class ConversationProjector(
    private val labels: ConversationProjectionLabels = ConversationProjectionLabels()
) {
    private val mutableItems = mutableListOf<ConversationItem>()
    private val filesByTurn = mutableMapOf<Int, LinkedHashMap<String, PresentedFile>>()
    private val presentedSequences = mutableSetOf<Int>()
    private val streamIndexes = mutableMapOf<String, Int>()
    private val finalizedKeys = mutableSetOf<String>()
    private val commandItemIds = mutableMapOf<String, String>()
    private val compactionCommandIds = mutableMapOf<String, String>()
    private val attemptKeys = mutableMapOf<String, String>()
    private val retryKeys = mutableMapOf<Int, String>()
    private val transientKeys = mutableSetOf<String>()
    private val steeringMessages = linkedMapOf<String, SessionEvent>()
    private val committedUserMessageIds = mutableSetOf<String>()
    // 与网关 `projection.mjs:4` 的 INTERNAL_TOOLS **同源**（单一真源口径）：
    // 这些工具的 call/result 都不生成对话行（2026-11-03 用户报「群聊出现 {} / {"replies":[]} 裸 JSON」）。
    private val internalTools = setOf("group_relay")
    // 内部提醒的 source.kind：宿主插件注入的 user/message（发给模型的上下文），**不该画成对话正文**。
    // "repeat-tool-reminder"：宿主插件 dsh-repeat-tool-reminder 的同参重调警告。真数据 fixture
    // room-2a10aef5.jsonl 里就有 2 条（form:"notice"、正文 "Repeated tool call detected…"），
    // 原先落 CONTEXT 行上屏（2026-11-03 用户报）。
    // "runtime-context"：宿主对所有注入上下文的统一 kind（dsh-agent-loop runtime-context 模块
    // SOURCE="runtime-context"、form:"snapshot"）——mnemon 记忆快照、working-directory 等全走它。
    // "time-context"：dsh-time-context 插件每回合注入的时间戳提示（"Time sampled while
    // preparing turn …"；真会话实测 14 条、16:3x zstandard 解压），与 runtime-context 同族
    // （source.kind="time-context"、form:"snapshot"，16:3x 真数据确认）。
    // 实测：真会话 39 条 runtime-context + 14 条 time-context，每条原先都落 CONTEXT 行上屏
    // （用户 2026-11-03 报「MNEMON RUNTIME MEMORY SNAPSHOT」/「Time sampled…」两大段）。
    // 轨迹页**不受影响**：TrajectoryProjection:95-96 独立消费原始
    // 事件，注入仍以 CONTEXT 节点保留（对话页不显示、轨迹页照常，Chief 判据）。
    // 与 internalTools 同一机制——按 kind 判，不按文本判（"Repeated tool call detected" 这串字
    // 可能出现在正常对话里）。黑名单制：不在名单里的 kind 照旧走原分支（fail-open 同向）。
    // 78（2026-11-03）：宿主注入给模型的上下文家族一次补全——每个都真实出现过/必然出现，
    // 只修一个下一个又会冒出来（dsh-mnemon 84 条实证：iOSCoder 20:15 真会话统计）。
    // 按 source.kind 判，不按文本判；轨迹页不受影响（消费原始事件）。
    private val internalSourceKinds = setOf(
        "repeat-tool-reminder",
        "runtime-context",
        "time-context",
        "dsh-mnemon",
        "ds-bot-time-context",
        "plugin:hindsight",
        "skill-catalog",
        "tool-jobs",
        "model-selection",
        "agent-instructions",
        "session-reference",
        "compact-checkpoint",
    )
    // callId → 工具名：tool/result 不带 name，判定「是不是内部工具」必须靠 call 帧登记。
    // 有上限防爆表：超过就把较早的一半丢掉（call 帧与其 result 几乎总是相邻到达）。
    private val toolCallNames = linkedMapOf<String, String>()
    // 配对：callId → 已插入工具卡的行下标。result 到达时把输出**替换**进同一张卡
    // （而不是再插一行）——否则正常工具会拆成上下两条（2026-11-03 用户报的形态之一）。
    // iOSTester 的判据：insert 一次 + 替换正文，绝不允许变成两条。
    private val pendingToolCards = mutableMapOf<String, Int>()

    val items: List<ConversationItem> get() = mutableItems.toList()
    val hasAssistantChunks: Boolean get() = transientKeys.isNotEmpty()
    var lastSequence: Int = -1
        private set

    fun reset() {
        mutableItems.clear()
        filesByTurn.clear()
        presentedSequences.clear()
        streamIndexes.clear()
        finalizedKeys.clear()
        commandItemIds.clear()
        compactionCommandIds.clear()
        transientKeys.clear()
        attemptKeys.clear()
        retryKeys.clear()
        steeringMessages.clear()
        committedUserMessageIds.clear()
        toolCallNames.clear()
        lastSequence = -1
    }

    fun rebuild(events: List<SessionEvent>) {
        reset()
        fold(events)
    }

    fun fold(events: List<SessionEvent>) {
        foldWithOperations(events)
    }

    fun foldWithOperations(events: List<SessionEvent>): List<ConversationProjectionOperation> {
        val operations = mutableListOf<ConversationProjectionOperation>()
        events.forEach { record ->
            val start = operations.size
            fold(record, operations)
            val turn = record.event.turn
            if (turn != null) {
                val affectedIds = operations.drop(start).mapNotNull { it.item?.takeIf { item ->
                    item.kind == ConversationItemKind.ASSISTANT
                }?.id }.toSet()
                mutableItems.indices.filter { mutableItems[it].id in affectedIds }.forEach { index ->
                    val old = mutableItems[index]
                    if (old.turn != turn) {
                        val updated = old.copy(turn = turn)
                        mutableItems[index] = updated
                        for (operationIndex in start until operations.size) {
                            val operation = operations[operationIndex]
                            if (operation.item?.id == updated.id) operations[operationIndex] = operation.copy(item = updated)
                        }
                    }
                }
                attachFiles(turn, operations)
            }
            lastSequence = maxOf(lastSequence, record.seq)
        }
        return operations
    }

    /** 独立流只改变展示行，绝不推进 lastSequence。 */
    fun foldAssistantChunks(attemptId: String, chunks: List<AssistantChunk>): List<ConversationProjectionOperation> {
        val operations = mutableListOf<ConversationProjectionOperation>()
        chunks.forEach { entry ->
            fun field(name: String) = entry.chunk[name]?.jsonPrimitive?.contentOrNull
            val type = field("type")
            val kind = when (type) {
                "text-delta" -> ConversationItemKind.ASSISTANT
                "reasoning-delta" -> ConversationItemKind.REASONING
                "tool-call-delta" -> ConversationItemKind.TOOL
                else -> return@forEach
            }
            val key = "live-$attemptId-$type-${field("index") ?: field("id") ?: "0"}"
            val title = when (kind) {
                ConversationItemKind.ASSISTANT -> labels.streamingAssistant
                ConversationItemKind.REASONING -> labels.streamingReasoning
                else -> field("name") ?: streamIndexes[key]?.let { mutableItems[it].title } ?: labels.assemblingTool
            }
            transientKeys += key
            appendStream(key, key, kind, title,
                field(if (type == "tool-call-delta") "argumentsDelta" else "text").orEmpty(),
                normalizeEpoch(entry.time), operations)
        }
        return operations
    }

    fun clearAssistantChunks(): List<ConversationProjectionOperation> {
        val operations = mutableListOf<ConversationProjectionOperation>()
        transientKeys.forEach { removeStream(it, operations) }
        transientKeys.clear()
        return operations
    }

    /** 控制流中的 steering 是已提交的用户消息，但尚未进入持久事件流。 */
    fun replaceSteeringMessages(records: List<SessionEvent>): List<ConversationProjectionOperation> {
        val operations = mutableListOf<ConversationProjectionOperation>()
        val next = records.associateBy { requireNotNull(it.event.raw?.get("id")?.stringValue) }
            .filterKeys { it !in committedUserMessageIds }
        (steeringMessages.keys - next.keys).forEach { id ->
            removeStream("steering-$id", operations)
        }
        next.forEach { (id, record) ->
            val key = "steering-$id"
            val item = ConversationItem(
                id = "${record.sessionId}-user-$id", kind = ConversationItemKind.USER,
                title = labels.userMessage, text = record.event.text.orEmpty(),
                images = record.event.images.orEmpty(), files = record.event.files.orEmpty(), epochSeconds = normalizeEpoch(record.time),
                seq = record.seq
            )
            val index = streamIndexes[key]
            if (index == null) {
                streamIndexes[key] = mutableItems.size
                insert(item, operations)
            } else if (mutableItems[index].copy(epochSeconds = item.epochSeconds) != item) {
                // 重复快照保留原显示时间，不让同一条气泡反复更新。
                val updated = item.copy(epochSeconds = mutableItems[index].epochSeconds)
                mutableItems[index] = updated
                operations += ConversationProjectionOperation("replace", item = updated, itemId = updated.id)
            }
        }
        steeringMessages.clear()
        steeringMessages.putAll(next)
        return operations
    }

    private fun fold(
        record: SessionEvent,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val event = record.event
        val key = "${event.turn ?: -1}-${event.step ?: -1}"
        val date = normalizeEpoch(record.time)
        when {
            event.type == "user/message" && (event.source == null || event.source == "user") -> {
                if (!event.text.isNullOrEmpty() || !event.images.isNullOrEmpty() || !event.files.isNullOrEmpty()) {
                    val messageId = event.raw?.get("id")?.stringValue
                    // 旧网关未转发消息 ID 时，只按到达顺序匹配一条临时消息；不能按正文全局去重。
                    val pendingId = if (messageId != null) messageId.takeIf { it in steeringMessages }
                    else steeringMessages.entries.firstOrNull { (_, pending) ->
                        pending.event.text.orEmpty() == event.text.orEmpty() &&
                            pending.event.images.orEmpty() == event.images.orEmpty() &&
                            pending.event.files.orEmpty() == event.files.orEmpty()
                    }?.key
                    val item = ConversationItem(
                        id = messageId?.let { "${record.sessionId}-user-$it" } ?: eventId(record),
                        kind = ConversationItemKind.USER,
                        title = labels.userMessage,
                        text = event.text.orEmpty(),
                        images = event.images.orEmpty(),
                        files = event.files.orEmpty(),
                        epochSeconds = date,
                        seq = record.seq,
                        // 群聊里「用户自己发的」⇒ senderName 保持 null（渲染层显示「我」）。
                        // 这一层统一产出：两端渲染层只消费，不再各自去 raw 里挖 author。
                        senderName = null
                    )
                    if (pendingId != null) {
                        finalizeStream("steering-$pendingId", item, operations)
                        steeringMessages.remove(pendingId)
                        committedUserMessageIds += pendingId
                    } else insert(item, operations)
                    if (messageId != null) committedUserMessageIds += messageId
                }
            }
            event.type == "user/message" && !event.text.isNullOrEmpty() -> {
                // 内部提醒不出行：它是注入给模型的上下文（internalSourceKinds 注释），按 kind 判不按文本判。
                // 轨迹页不受影响——那里消费的是原始事件，不是这条投影。
                if (event.source in internalSourceKinds) return
                // 群聊里 Bot 以 user/message 形态转发发言（`source.kind == "bot" && source.role == "post"`，
                // 网关 group-projection.mjs:34 已确认形态）：它是**别人 Bot 的发言**，要渲染成 assistant 气泡
                // 并带作者名（`source.senderName`）；**系统事件（failed/resumed/stopped/done/again）不能混进来**
                // ——它们继续走下面的 CONTEXT 分支，senderName 必须 null（iOSDev 定的判据：不该有人名不能出现）。
                val source = event.raw?.get("source")
                val botPost = source?.get("kind")?.stringValue == "bot" && source?.get("role")?.stringValue == "post"
                if (botPost && source?.get("form")?.stringValue == "relay"
                    // ⚠️ 判空必须用 `!= null`（JsonObject.get 对**缺失键**返回 null）：
                // 写成 `!= JsonNull` 时缺失键也判 true → none 恒 false → 全部落 CONTEXT（修复无效）。
                // 显式 null（`"done": null`）按网关语义 `!== undefined` 也算已投递 → skip，与此一致。
                && setOf("failed", "resumed", "stopped", "done", "again").none { source?.get(it) != null }
                ) {
                    val rawText = event.text
                        .replace(Regex("^\\[Group post from [^\\]]*\\]\\n?"), "")   // content 已并成单串（textBlocks joinToString("")）
                    // **防御性死分支**（AndroidTester 2026-11-02 grep 网关原文后定性，勿写成"与网关一致"）：
                    // 网关 `group-projection.mjs:33` 先剥前缀、`:34` 才 `if (text)` ⇒ **产出层就不会发这种行**，
                    // 所以这条分支在真实链路上不可达；保留只为防上游形态变更。丢弃本身是**消费层**行为，
                    // 与网关的**产出层**丢弃不是同一件事——两层"看起来一样"不等于语义一致。
                    if (rawText.isNotBlank()) {
                        // 名字**沿用网关已脱敏的 source.senderName**（网关 group-projection.mjs:34 已过 redact 并截 100 字），
                        // 客户端**不自行脱敏也不本地推断**——与「senderName 只来自服务端 author.name」同一口径。
                        val senderName = source?.get("senderName")?.stringValue?.takeIf { it.isNotBlank() }
                        insert(ConversationItem(
                            id = eventId(record),
                            kind = ConversationItemKind.ASSISTANT,
                            title = "",
                            text = rawText,
                            epochSeconds = date,
                            seq = record.seq,
                            // KMP 统一产出（Chief 裁定）：两端渲染层只消费 item.senderName 显示，
                            // 不再各自从 raw 里挖；名字缺失 ⇒ null ⇒ 渲染层不显示任何名字。
                            senderName = senderName
                        ), operations)
                    }
                } else {
                    insert(ConversationItem(
                        id = eventId(record),
                        kind = ConversationItemKind.CONTEXT,
                        title = labels.context + contextSourceName(event),
                        text = event.text,
                        images = event.images.orEmpty(),
                        epochSeconds = date,
                        seq = record.seq,
                        // 系统事件/上下文注入 ⇒ 一律不署名（不是 Bot 发言）。
                        senderName = null
                    ), operations)
                }
            }
            event.type == "assistant/chunk" && event.chunkType == "text-delta" && key !in finalizedKeys ->
                appendStream("stream-text-$key", "text-$key", ConversationItemKind.ASSISTANT, labels.streamingAssistant, event.text.orEmpty(), date, operations)
            event.type == "assistant/chunk" && event.chunkType == "reasoning-delta" && key !in finalizedKeys ->
                appendStream("stream-reason-$key", "reason-$key", ConversationItemKind.REASONING, labels.streamingReasoning, event.text.orEmpty(), date, operations)
            event.type == "assistant/chunk" && event.chunkType == "tool-call-delta" && key !in finalizedKeys -> {
                val toolKey = event.tool?.id ?: key
                val name = event.tool?.name ?: labels.assemblingTool
                val kind = if (name.equals("run_code", ignoreCase = true)) ConversationItemKind.JSON_TOOL else ConversationItemKind.TOOL
                appendStream("stream-tool-$toolKey", "tool-$toolKey", kind, name, event.tool?.argumentsDelta.orEmpty(), date, operations)
            }
            event.type == "assistant/attempt" -> {
                val content = (event.stream ?: event.raw?.get("stream"))?.toJsonElement()?.let(::expandAssistantStream).orEmpty()
                val finish = event.finish ?: content.lastOrNull { it.chunk["type"]?.jsonPrimitive?.contentOrNull == "finish" }
                    ?.chunk?.get("reason")?.let { JsonValue.fromJsonElement(it) }
                    ?.let { FinishInfo(it["kind"]?.stringValue, it["failure"] ?: it["error"]) }
                val cancelled = finish?.kind in setOf("aborted", "cancelled", "canceled")
                val title = if (cancelled) labels.generationCancelled else labels.modelAttemptFailed
                val partial = content.mapNotNull { chunk ->
                    if (chunk.chunk["type"]?.jsonPrimitive?.contentOrNull in setOf("text-delta", "reasoning-delta")) {
                        chunk.chunk["text"]?.jsonPrimitive?.contentOrNull
                    } else null
                }.joinToString("")
                val text = listOfNotNull(partial.takeIf(String::isNotEmpty), failureText(finish?.failure)).joinToString("\n\n")
                val itemKey = "attempt-${eventId(record)}"
                attemptKeys[key] = itemKey
                streamIndexes[itemKey] = mutableItems.size
                insert(ConversationItem(eventId(record), ConversationItemKind.SYSTEM, title,
                    text, isError = !cancelled, epochSeconds = date, seq = record.seq), operations)
            }
            event.type == "llm/retry" || event.type == "llm/retry-started" -> {
                // A retry settles the attempt notice; keep one status row per provider retry chain.
                attemptKeys.remove(key)?.let { removeStream(it, operations) }
                val itemKey = "retry-${record.sessionId}-${event.retryId ?: key}"
                val old = streamIndexes[itemKey]?.let { mutableItems[it] }
                val count = event.retry?.let { " ($it${event.maxRetries?.let { max -> "/$max" }.orEmpty()})" }.orEmpty()
                val delay = event.delayMs?.let { " · ${it / 1000} s" }.orEmpty()
                val started = event.type == "llm/retry-started"
                val text = failureText(event.failure) ?: old?.text.orEmpty()
                upsertStatus(itemKey, ConversationItem(old?.id ?: eventId(record), ConversationItemKind.SYSTEM,
                    if (started && old != null) old.title.replace(labels.modelRetrying, labels.modelRetried)
                    else (if (started) labels.modelRetried else labels.modelRetrying) + count + delay,
                    text, epochSeconds = date, seq = record.seq), operations)
                event.turn?.let { retryKeys[it] = itemKey }
            }
            event.type == "turn/end" -> {
                val reason = event.reason ?: event.reasonDetails?.get("kind")?.stringValue
                val failed = reason in setOf("error", "failed")
                val cancelled = reason in setOf("aborted", "cancelled", "canceled")
                val interrupted = reason == "interrupted"
                if (failed || cancelled || interrupted) {
                    attemptKeys.keys.filter { it.startsWith("${event.turn}-") }.toList().forEach { attempt ->
                        attemptKeys.remove(attempt)?.let { removeStream(it, operations) }
                    }
                    retryKeys.remove(event.turn)?.let { retryKey ->
                        val old = streamIndexes[retryKey]?.let { mutableItems[it] }
                        if (old != null && old.title.startsWith(labels.modelRetrying)) {
                            if (cancelled) removeStream(retryKey, operations)
                            else upsertStatus(retryKey, old.copy(
                                title = old.title.replace(labels.modelRetrying, labels.modelRetried)), operations)
                        }
                    }
                    val title = when {
                        cancelled -> labels.generationCancelled
                        failed -> labels.generationFailed
                        else -> labels.interrupted
                    }
                    val details = event.reasonDetails ?: event.raw?.get("reason")
                    insert(ConversationItem(eventId(record), ConversationItemKind.SYSTEM, title,
                        failureText(details?.get("error") ?: details?.get("failure")) ?: event.error.orEmpty(),
                        isError = failed || interrupted, epochSeconds = date), operations)
                } else {
                    retryKeys.remove(event.turn)
                }
                attemptKeys.keys.filter { it.startsWith("${event.turn}-") }.toList().forEach { attemptKeys.remove(it) }
            }
            event.type == "assistant/message" -> {
                finalizedKeys += key
                val finalReasoning = event.reasoning?.takeIf(String::isNotEmpty)?.let { reasoning ->
                    ConversationItem(
                        "${eventId(record)}-reason",
                        ConversationItemKind.REASONING,
                        labels.finalReasoning,
                        reasoning,
                        isError = event.interrupted == true,
                        epochSeconds = date
                    )
                }
                val finalAssistant = if (!event.text.isNullOrEmpty() || !event.images.isNullOrEmpty() || !filesByTurn[event.turn].isNullOrEmpty()) {
                    ConversationItem(
                        eventId(record),
                        ConversationItemKind.ASSISTANT,
                        if (event.interrupted == true) labels.interrupted else labels.finalAssistant,
                        event.text.orEmpty(),
                        event.images.orEmpty(),
                        isError = event.interrupted == true,
                        epochSeconds = date
                    )
                } else {
                    null
                }

                if (finalReasoning != null && streamIndexes["reason-$key"] == null) {
                    // A final-only reasoning payload must still precede the
                    // answer. Since insert operations append, rebuild this
                    // uncommon pair in order instead of leaving reasoning
                    // below an already-streamed answer.
                    removeStream("text-$key", operations)
                    insert(finalReasoning, operations)
                    finalAssistant?.let { insert(it, operations) }
                } else {
                    // Keep a streamed response's identity through finalization.
                    // Platform renderers own their parser/source by item id; a
                    // remove+insert here tears down that source before its final
                    // buffered snapshot can be rendered. Replacing in place also
                    // avoids a structural list update at the end of every reply.
                    finalizeStream("reason-$key", finalReasoning, operations)
                    finalizeStream("text-$key", finalAssistant, operations)
                }
            }
            event.type == "deliverables/presented" && event.turn != null && presentedSequences.add(record.seq) -> {
                val files = filesByTurn.getOrPut(event.turn) { linkedMapOf() }
                event.files.orEmpty().filter { it.path.isNotBlank() }.forEach { file ->
                    files[file.path] = file
                }
            }
            event.type == "tool/call" -> {
                val name = event.name ?: "Tool Call"
                if (event.callId != null) {
                    toolCallNames[event.callId] = name
                    if (toolCallNames.size > 1024) toolCallNames.keys.take(toolCallNames.size / 2).forEach { toolCallNames.remove(it) }
                }
                // 内部工具（如群聊 relay）不出行：网关 projection.mjs:104 同口径。
                if (name.lowercase() !in internalTools) {
                    val kind = if (name.equals("run_code", ignoreCase = true)) ConversationItemKind.JSON_TOOL else ConversationItemKind.TOOL
                    insert(ConversationItem(eventId(record), kind, name, event.arguments?.jsonDisplayText().orEmpty(), epochSeconds = date), operations)
                }
            }
            event.type == "tool/result" -> {
                // result 不带 name：用 callId 查 call 帧登记的工具名再判内部名单。
                // callId 未知（旧网关 / 缺 call 帧）⇒ fail-open 放行——误伤正常工具比多显示一行更糟。
                val callName = event.callId?.let { toolCallNames[it] }
                val internal = callName != null && callName.lowercase() in internalTools
                val cardIndex = event.callId?.let { pendingToolCards.remove(it) }
                    ?.takeIf { it in mutableItems.indices }
                    // 内部工具（如群聊 relay）：call 已经没出过行，result 也一行不生成。
                    // **配对成功**：把输出替换进 call 那张卡（title 保持工具名），
                    // 绝不另插一行 TOOL_RESULT——那是「正常工具拆成上下两条」的根因。
                    // fail-open：callId 未登记（旧网关 / 缺 call 帧）⇒ 按老样子出 TOOL_RESULT 一行。
                    if (!internal && cardIndex != null) {
                        val base = mutableItems[cardIndex]
                        val output = toolOutput(event)
                        mutableItems[cardIndex] = base.copy(
                            text = if (output.isEmpty()) base.text else base.text + "\n\n" + output,
                            isError = base.isError || toolFailed(event)
                        )
                    } else if (!internal) {
                        insert(ConversationItem(
                            id = eventId(record),
                            kind = ConversationItemKind.TOOL_RESULT,
                            title = if (toolFailed(event)) labels.toolResultFailed else labels.toolResultDone,
                            text = toolOutput(event),
                            isError = toolFailed(event),
                            epochSeconds = date
                        ), operations)
                    }
            }
            event.type == "command/run" -> commandStarted(record, date, operations)
            event.type == "compaction/start" -> compactionStarted(record, date, operations)
            event.type == "compaction/summary" -> compactionSummarized(record, date, operations)
            event.type == "compaction/end" -> compactionEnded(record, date, operations)
            event.type == "command/done" -> commandFinished(record, date, operations)
        }
    }

    private fun commandStarted(
        record: SessionEvent,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val event = record.event
        val commandId = event.commandId ?: event.raw?.get("commandId")?.stringValue ?: return
        val itemId = "${record.sessionId}-command-$commandId"
        commandItemIds[commandId] = itemId
        insert(
            ConversationItem(
                id = itemId,
                kind = ConversationItemKind.STATUS,
                title = event.name ?: event.raw?.get("name")?.stringValue ?: "command",
                text = labels.commandRunning,
                epochSeconds = date
            ),
            operations
        )
    }

    private fun compactionStarted(
        record: SessionEvent,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val event = record.event
        val compactionId = event.compactionId ?: event.raw?.get("compactionId")?.stringValue
        val commandId = event.sourceCommandId ?: event.raw?.get("sourceCommandId")?.stringValue
        if (compactionId != null && commandId != null) compactionCommandIds[compactionId] = commandId
        updateCommand(record, commandId, date, operations) { old ->
            old.copy(title = old.title.ifBlank { "compact" }, text = labels.commandCompacting, epochSeconds = date)
        }
    }

    private fun compactionSummarized(
        record: SessionEvent,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val event = record.event
        val compactionId = event.compactionId ?: event.raw?.get("compactionId")?.stringValue
        val commandId = event.sourceCommandId
            ?: event.raw?.get("sourceCommandId")?.stringValue
            ?: compactionId?.let(compactionCommandIds::get)
        val itemCount = event.shadowedItemCount
            ?: event.raw?.get("shadowedItemCount")?.doubleValue?.toInt()
        val tokenCount = event.shadowedTokenCount
            ?: event.raw?.get("shadowedTokenCount")?.doubleValue?.toInt()
        val summary = if (itemCount != null && tokenCount != null) {
            labels.compactedHistory
                .replace("{items}", itemCount.toString())
                .replace("{tokens}", tokenCount.toString())
        } else {
            labels.commandCompleted
        }
        updateCommand(record, commandId, date, operations) { old ->
            old.copy(title = old.title.ifBlank { "compact" }, text = summary, epochSeconds = date)
        }
    }

    private fun compactionEnded(
        record: SessionEvent,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val event = record.event
        val error = event.error ?: event.raw?.get("error")?.stringValue
        if (error == null) return
        val compactionId = event.compactionId ?: event.raw?.get("compactionId")?.stringValue
        val commandId = event.sourceCommandId
            ?: event.raw?.get("sourceCommandId")?.stringValue
            ?: compactionId?.let(compactionCommandIds::get)
        updateCommand(record, commandId, date, operations) { old ->
            old.copy(text = error, isError = true, epochSeconds = date)
        }
    }

    private fun commandFinished(
        record: SessionEvent,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val event = record.event
        val commandId = event.commandId ?: event.raw?.get("commandId")?.stringValue
        val outcome = event.outcome ?: event.raw?.get("outcome")?.stringValue
        val failed = event.isError == true || outcome == "error" || outcome == "failed" || outcome == "failure"
        updateCommand(record, commandId, date, operations) { old ->
            val hasCompactionSummary = old.title == "compact" &&
                old.text != labels.commandRunning && old.text != labels.commandCompacting
            old.copy(
                text = if (hasCompactionSummary && !failed) old.text
                    else event.text?.takeIf(String::isNotBlank)
                        ?: if (failed) labels.commandFailed else labels.commandCompleted,
                isError = failed,
                epochSeconds = date
            )
        }
    }

    private fun updateCommand(
        record: SessionEvent,
        commandId: String?,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>,
        transform: (ConversationItem) -> ConversationItem
    ) {
        val resolvedCommandId = commandId ?: return
        val itemId = commandItemIds[resolvedCommandId] ?: "${record.sessionId}-command-$resolvedCommandId"
        val index = mutableItems.indexOfFirst { it.id == itemId }
        if (index < 0) {
            commandItemIds[resolvedCommandId] = itemId
            val fallback = ConversationItem(
                id = itemId,
                kind = ConversationItemKind.STATUS,
                title = "compact",
                text = labels.commandRunning,
                epochSeconds = date
            )
            insert(transform(fallback), operations)
            return
        }
        val updated = transform(mutableItems[index])
        mutableItems[index] = updated
        operations += ConversationProjectionOperation(kind = "replace", item = updated, itemId = itemId)
    }

    private fun toolFailed(event: GatewayEvent): Boolean {
        if (event.isError == true) return true
        val output = event.output ?: event.preview.orEmpty()
        // Shell tools encode a nonzero process exit in the final line while Host isError may be false.
        val exit = Regex("\\n\\[exit code: (-?\\d+)\\]$").find(output)?.groupValues?.get(1)?.toIntOrNull()
        return (exit != null && exit != 0) || Regex("\\n\\[killed by signal: [^\\]\\n]+\\]$").containsMatchIn(output)
    }

    private fun toolOutput(event: GatewayEvent): String {
        val output = event.output ?: event.preview.orEmpty()
        val detail = event.error?.takeIf { it.isNotBlank() && !output.contains(it) }
        return listOfNotNull(output.takeIf(String::isNotEmpty), detail,
            labels.outputTruncated.takeIf { event.outputTruncated == true }).joinToString("\n\n")
    }

    private fun upsertStatus(key: String, item: ConversationItem, operations: MutableList<ConversationProjectionOperation>) {
        val index = streamIndexes[key]
        if (index == null) {
            streamIndexes[key] = mutableItems.size
            insert(item, operations)
        } else {
            mutableItems[index] = item
            operations += ConversationProjectionOperation("replace", item = item, itemId = item.id)
        }
    }

    private fun attachFiles(turn: Int, operations: MutableList<ConversationProjectionOperation>) {
        val files = filesByTurn[turn]?.values?.toList().orEmpty()
        if (files.isEmpty()) return
        val indexes = mutableItems.indices.filter {
            mutableItems[it].kind == ConversationItemKind.ASSISTANT && mutableItems[it].turn == turn
        }
        indexes.forEach { index ->
            val old = mutableItems[index]
            val cards = if (index == indexes.last()) files else emptyList()
            if (old.files != cards || old.knownFiles != files) {
                val updated = old.copy(files = cards, knownFiles = files)
                mutableItems[index] = updated
                operations += ConversationProjectionOperation("replace", item = updated, itemId = old.id)
            }
        }
    }

    private fun insert(
        item: ConversationItem,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        mutableItems += item
        operations += ConversationProjectionOperation(kind = "insert", item = item)
    }

    private fun appendStream(
        id: String,
        key: String,
        kind: ConversationItemKind,
        title: String,
        delta: String,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        if (delta.isEmpty()) return
        val index = streamIndexes[key]
        if (index != null) {
            val old = mutableItems[index]
            mutableItems[index] = old.copy(text = old.text + delta, epochSeconds = date)
            operations += ConversationProjectionOperation(
                kind = "append-text",
                itemId = old.id,
                delta = delta,
                epochSeconds = date
            )
        } else {
            streamIndexes[key] = mutableItems.size
            insert(ConversationItem(id, kind, title, delta, epochSeconds = date), operations)
        }
    }

    private fun removeStream(
        key: String,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val index = streamIndexes.remove(key) ?: return
        val removed = mutableItems.removeAt(index)
        operations += ConversationProjectionOperation(kind = "remove", itemId = removed.id)
        streamIndexes.entries.forEach { entry -> if (entry.value > index) entry.setValue(entry.value - 1) }
    }

    private fun finalizeStream(
        key: String,
        finalItem: ConversationItem?,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val index = streamIndexes.remove(key)
        if (index == null) {
            finalItem?.let { insert(it, operations) }
            return
        }
        if (finalItem == null) {
            val removed = mutableItems.removeAt(index)
            operations += ConversationProjectionOperation(kind = "remove", itemId = removed.id)
            streamIndexes.entries.forEach { entry ->
                if (entry.value > index) entry.setValue(entry.value - 1)
            }
            return
        }

        val stableFinalItem = finalItem.copy(id = mutableItems[index].id)
        mutableItems[index] = stableFinalItem
        operations += ConversationProjectionOperation(
            kind = "replace",
            item = stableFinalItem,
            itemId = stableFinalItem.id
        )
    }

    companion object {
        fun firstIndexAfter(sequence: Int, events: List<SessionEvent>): Int {
            var low = 0
            var high = events.size
            while (low < high) {
                val middle = (low + high) / 2
                if (events[middle].seq <= sequence) low = middle + 1 else high = middle
            }
            return low
        }

        fun make(events: List<SessionEvent>, labels: ConversationProjectionLabels = ConversationProjectionLabels()): List<ConversationItem> =
            ConversationProjector(labels).apply { rebuild(events) }.items
    }
}

data class ConversationHistoryRebase(
    val events: List<SessionEvent>,
    val projector: ConversationProjector
) {
    fun appendingLiveTail(latest: List<SessionEvent>): ConversationHistoryRebase {
        val start = ConversationProjector.firstIndexAfter(projector.lastSequence, latest)
        if (start >= latest.size) return this
        val tail = latest.subList(start, latest.size)
        projector.fold(tail)
        return copy(events = events + tail)
    }

    companion object {
        fun build(history: List<SessionEvent>, current: List<SessionEvent>): ConversationHistoryRebase {
            val records = history.associateBy(SessionEvent::seq).toMutableMap().apply {
                current.forEach { put(it.seq, it) }
            }
            val merged = records.values.sortedBy(SessionEvent::seq)
            return ConversationHistoryRebase(merged, ConversationProjector().apply { rebuild(merged) })
        }
    }
}

private fun contextSourceName(event: GatewayEvent): String =
    event.raw?.get("source")?.get("plugin")?.stringValue?.takeIf(String::isNotEmpty)
        ?: event.source
        ?: "Context"

private fun eventId(record: SessionEvent): String = "${record.sessionId}-${record.seq}"
private fun normalizeEpoch(value: Double): Double = if (value > 10_000_000_000) value / 1_000 else value
