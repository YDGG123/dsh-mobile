package com.clarklevis.dsh.shared.teamfeed

import kotlinx.serialization.json.longOrNull
/**
 * D1 状态机：把 [TeamFeedFrame] 归并进 [TeamFeedState]。
 *
 * 不变式（每条都有对应判据用例，见 `TeamFeedReducerTest`）：
 * - `delta.base != 本地 seq` ⇒ **丢弃整帧**并置 `needSnapshot = true`（契约 §6：把重同步局部化，
 *   不把错位增量继续往下传；网关侧 `team-feed.mjs:497-509` 同规）。
 * - `instanceId` 变更 ⇒ 丢弃增量 + 重取快照（host 重启；`team-feed.mjs:460-470`）。
 * - `order` 帧**不推进**消费水位（`seq` 是 head 回显，:232；排序键在 `sessions`）。
 * - `patch` 的 `set` 裁掉静态字段（契约 §4 `STATIC_ONLY`；网关已裁，客户端**再裁一次**不信任上游）。
 * - 卡片：`card.shown` 带正文 upsert；`card.answered` / `card.cleared` **不带正文**只有 id ⇒
 *   必须靠活卡集合退役，否则「等你回答」永远消不掉（iOS `:139-147` 真机坑）。
 * - `degraded == true`：易变字段不可信，状态里显式置位，UI 必须标注、不许渲染成「全部空闲」。
 */
class TeamFeedReducer(initial: TeamFeedState = TeamFeedState()) {

    data class TeamFeedState(
        val instanceId: String? = null,
        val seq: Long = 0,
        val degraded: Boolean = false,
        val mainBotIds: List<String> = emptyList(),
        /** `order` 帧带来的会话排序键（只影响会话列表排序，不影响名册行）。 */
        val orderSessions: List<String> = emptyList(),
        /** id → 行（bot 与 room 同池，`kind` 区分）。 */
        val rows: Map<String, TeamFeedRow> = emptyMap(),
        /** 活着的卡片：id → 卡正文（`card.shown` upsert / `answered|cleared` 退役）。 */
        val cards: Map<String, TeamFeedCard> = emptyMap(),
    )

    /** 归并结果：`Applied`（状态已更新）或 `NeedSnapshot`（必须重取快照，状态不动）。 */
    sealed interface Outcome {
        data class Applied(val state: TeamFeedState, val changed: Boolean) : Outcome
        data class NeedSnapshot(val reason: String) : Outcome
    }

    var state: TeamFeedState = initial
        private set

    /** 上一帧之后是否需要重取快照（消费方读到 true 就发 snapshot 请求，然后自行清零）。 */
    var needSnapshot: Boolean = false
        private set

    fun apply(frame: TeamFeedFrame): Outcome {
        return when (frame) {
            is TeamFeedFrame.Ready -> Outcome.Applied(state, changed = false)
            is TeamFeedFrame.Error -> Outcome.Applied(state, changed = false)
            is TeamFeedFrame.Snapshot -> applySnapshot(frame)
            is TeamFeedFrame.Delta -> applyDelta(frame)
            is TeamFeedFrame.Order -> applyOrder(frame)
            is TeamFeedFrame.AvatarBatch -> applyAvatarBatch(frame)
            is TeamFeedFrame.Cards -> applyCards(frame)
        }
    }

    private fun applySnapshot(frame: TeamFeedFrame.Snapshot): Outcome {
        val rows = frame.rows.associateBy { it.id }
        state = state.copy(
            instanceId = frame.instanceId ?: state.instanceId,
            seq = frame.seq,
            degraded = frame.degraded,
            mainBotIds = frame.mainBotIds,
            rows = rows,
        )
        return Outcome.Applied(state, changed = true)
    }

    private fun applyDelta(frame: TeamFeedFrame.Delta): Outcome {
        if (frame.instanceId != null && state.instanceId != null && frame.instanceId != state.instanceId) {
            needSnapshot = true
            return Outcome.NeedSnapshot("instanceId changed: ${state.instanceId} -> ${frame.instanceId}")
        }
        if (frame.base != state.seq) {
            needSnapshot = true
            return Outcome.NeedSnapshot("delta base ${frame.base} != local seq ${state.seq}")
        }
        var rows = state.rows
        var mainBotIds = state.mainBotIds
        var changed = false
        for (op in frame.ops) {
            when (op) {
                is TeamFeedOp.Upsert -> {
                    // 静态字段边界：add/update 才允许带 avatar 等静态字段（契约 §4），这里不裁。
                    rows = rows + (op.row.id to op.row)
                    changed = true
                }
                is TeamFeedOp.Patch -> {
                    val current = rows[op.id] ?: continue
                    rows = rows + (op.id to current.patchedWith(op.set, op.kind))
                    changed = true
                }
                is TeamFeedOp.Remove -> {
                    rows = rows - op.id
                    changed = true
                }
                is TeamFeedOp.MainSet -> {
                    mainBotIds = op.mainBotIds
                    changed = true
                }
            }
        }
        state = state.copy(
            seq = frame.seq,
            degraded = frame.degraded,
            rows = rows,
            mainBotIds = mainBotIds,
        )
        return Outcome.Applied(state, changed)
    }

    private fun applyOrder(frame: TeamFeedFrame.Order): Outcome {
        // seq 是 head 回显：不推进消费水位，只更新排序键（否则 base 校验会立即失配）。
        state = state.copy(orderSessions = frame.sessions, degraded = frame.degraded)
        return Outcome.Applied(state, changed = true)
    }

    private fun applyAvatarBatch(frame: TeamFeedFrame.AvatarBatch): Outcome {
        if (frame.items.isEmpty()) return Outcome.Applied(state, changed = false)
        val rows = state.rows.toMutableMap()
        for ((id, avatar) in frame.items) {
            val current = rows[id] ?: continue
            rows[id] = current.copy(avatar = current.avatar.copy(shape = avatar.shape ?: current.avatar.shape, image = avatar.image ?: current.avatar.image, hash = avatar.hash ?: current.avatar.hash))
        }
        state = state.copy(rows = rows)
        return Outcome.Applied(state, changed = true)
    }

    private fun applyCards(frame: TeamFeedFrame.Cards): Outcome {
        val cards = if (frame.isSnapshot) {
            frame.ops.mapNotNull { op -> (op as? TeamFeedCardOp.Shown)?.card }.associateBy { it.id }
        } else {
            val next = state.cards.toMutableMap()
            for (op in frame.ops) {
                when (op) {
                    is TeamFeedCardOp.Shown -> next[op.card.id] = op.card
                    is TeamFeedCardOp.Retire -> next.remove(op.id)
                }
            }
            next
        }
        state = state.copy(cards = cards)
        return Outcome.Applied(state, changed = true)
    }

    fun requestSnapshotHandled() {
        needSnapshot = false
    }
}

/** patch 只允许易变字段（契约 §4 `STATIC_ONLY`）；静态字段来了就丢，不信任上游裁剪。 */
private val STATIC_ONLY = setOf(
    "avatar", "color", "name", "role", "model", "effort", "workDir", "instructions",
    "main", "pinned", "hidden", "createdAt", "members", "admin", "mode", "notice", "sessionId", "parts", "glyph",
)

private fun TeamFeedRow.patchedWith(set: Map<String, kotlinx.serialization.json.JsonElement>, kind: String): TeamFeedRow {
    val dynamic = set.filterKeys { it !in STATIC_ONLY }
    // 动态字段只有 activity/work/approvals/pending/waiting/preview/updatedAt；其余（未知键）丢弃。
    return copy(
        activity = (dynamic["activity"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: activity,
        work = (dynamic["work"] as? kotlinx.serialization.json.JsonObject)?.let {
            TeamFeedWork(
                roomId = it.str("roomId"), tool = it.str("tool"), target = it.str("target"),
                note = it.str("note"), said = it.str("said"), since = it.long("since"),
            )
        } ?: work,
        approvals = dynamic["approvals"].let { arr ->
            (arr as? kotlinx.serialization.json.JsonArray)?.mapNotNull(::parseApprovalElement) ?: approvals
        },
        pending = dynamic["pending"].let { arr ->
            (arr as? kotlinx.serialization.json.JsonArray)?.mapNotNull(::parsePendingElement) ?: pending
        },
        waiting = (dynamic["waiting"] as? kotlinx.serialization.json.JsonObject)?.let {
            TeamFeedWaiting(
                bots = it.arrayOfStrings("bots"),
                rooms = it.arrayOfStrings("rooms"),
                forWhom = it.arrayOfStrings("for"),
            )
        } ?: waiting,
        preview = (dynamic["preview"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: preview,
        updatedAt = dynamic["updatedAt"].let { (it as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull } ?: updatedAt,
    )
}

private fun parseApprovalElement(element: kotlinx.serialization.json.JsonElement): TeamFeedApproval? {
    val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
    val sessionId = obj.str("sessionId") ?: return null
    return TeamFeedApproval(sessionId, obj.str("tool"), obj.str("reason"), obj.long("at"))
}

private fun parsePendingElement(element: kotlinx.serialization.json.JsonElement): TeamFeedPending? {
    val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
    val kind = obj.str("kind") ?: return null
    val sessionId = obj.str("sessionId") ?: return null
    return TeamFeedPending(kind, sessionId, obj.str("roomId"), obj.long("at"))
}
