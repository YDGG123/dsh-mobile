package com.clarklevis.dsh.shared.projection

import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 78：宿主注入上下文家族的白名单判据（每种 kind 一例）+ 三条必须保留的反向对照。
 *
 * 直接喂 [ConversationProjection.fold]（线上同一条投影），不过 store 中转——
 * 要判的是「投影层认不认这些 kind」，不是「store 的 MVI 管道」。
 */
class InternalSourceKindsProjectionTest {
    private fun project(vararg events: GatewayEvent): List<ConversationItem> {
        val projection = ConversationProjector()
        projection.fold(events.mapIndexed { index, event ->
            SessionEvent("session-fix-$index", index + 1, 1000.0 + index, event)
        })
        return projection.items
    }

    /** 与网关 `normalizedEvent()` 同构的 user/message 事件（正文在 content 数组，raw 带完整 source）。 */
    private fun userMessage(kind: String?, text: String, senderName: String? = null, botPost: Boolean = false): GatewayEvent {
        val sourceJson = buildString {
            append("{\"kind\":")
            append("\"\"".let { if (kind == null) "null" else "\"$kind\"" })
            if (botPost) {
                append(",\"role\":\"post\",\"form\":\"relay\"")
                if (senderName != null) append(",\"senderName\":\"$senderName\"")
            }
            append("}")
        }
        // raw 用协议自己的 JsonValue 树（fold 只读 raw["source"]）。
        val sourceTree: com.clarklevis.dsh.shared.protocol.JsonValue =
            if (kind == null && !botPost) com.clarklevis.dsh.shared.protocol.JsonValue.NullValue
            else com.clarklevis.dsh.shared.protocol.JsonValue.ObjectValue(buildMap {
                if (kind != null) put("kind", com.clarklevis.dsh.shared.protocol.JsonValue.StringValue(kind))
                if (botPost) {
                    put("role", com.clarklevis.dsh.shared.protocol.JsonValue.StringValue("post"))
                    put("form", com.clarklevis.dsh.shared.protocol.JsonValue.StringValue("relay"))
                    if (senderName != null) put("senderName", com.clarklevis.dsh.shared.protocol.JsonValue.StringValue(senderName))
                }
            })
        return GatewayEvent(
            type = "user/message",
            text = text,
            source = kind,
            raw = com.clarklevis.dsh.shared.protocol.JsonValue.ObjectValue(
                mapOf("id" to com.clarklevis.dsh.shared.protocol.JsonValue.StringValue("m-${text.hashCode()}"), "source" to sourceTree),
            ),
        )
    }

    /** 白名单内每种 kind 都不出行（含用户截图实体的 MNEMON 快照）。 */
    @Test
    fun everyInternalKindIsSuppressed() {
        val kinds = listOf(
            "repeat-tool-reminder", "runtime-context", "time-context",
            "dsh-mnemon", "ds-bot-time-context", "plugin:hindsight", "skill-catalog",
            "tool-jobs", "model-selection", "agent-instructions", "session-reference",
            "compact-checkpoint",
        )
        for (kind in kinds) {
            val text = if (kind == "dsh-mnemon") "MNEMON RUNTIME MEMORY SNAPSHOT … USER.md …" else "注入上下文-$kind"
            val items = project(userMessage(kind, text))
            assertTrue(
                !items.any { candidate -> candidate.text == text },
                "kind=$kind 不该出行（实际 items=${items.map { it.text }}）",
            )
        }
    }

    /** 反向对照①：kind="user" 的普通发言必须照常出行（防白名单写成「什么都过滤」）。 */
    @Test
    fun ordinaryUserKindStillRenders() {
        val items = project(userMessage("user", "正常发言"))
        assertTrue(items.any { it.text == "正常发言" })
    }

    /** 反向对照②：source=null 的普通消息必须照常出行。 */
    @Test
    fun nullSourceStillRenders() {
        val items = project(userMessage(null, "无来源发言"))
        assertTrue(items.any { it.text == "无来源发言" })
    }

    /** 反向对照③：kind="bot" 的群聊发言照常出行且带作者名（真发言不是注入）。 */
    @Test
    fun botPostStillRendersWithSenderName() {
        val items = project(userMessage("bot", "[Group post from iOSDev] 大家好", senderName = "iOSDev", botPost = true))
        val item = items.single()
        assertEquals(com.clarklevis.dsh.shared.projection.ConversationItemKind.ASSISTANT, item.kind)
        assertEquals("iOSDev", item.senderName)
    }
}