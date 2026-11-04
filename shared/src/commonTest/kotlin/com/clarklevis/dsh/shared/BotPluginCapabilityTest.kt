package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.domain.BotPluginCapability
import com.clarklevis.dsh.shared.domain.botPluginCapabilityFromInfo
import com.clarklevis.dsh.shared.domain.botPluginMissingErrorText
import com.clarklevis.dsh.shared.domain.parseBotPluginCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * botPlugin 三态解析与 missing 态错误码中文化的反向测试（Chief 2026-11-04 派单：
 * 三态各一条；网关契约 d174044：/info 的 capabilities.botPlugin = full | legacy | missing）。
 * 纯 JVM 断言，不依赖模拟器。
 */
class BotPluginCapabilityTest {

    // ── 三态各一条（parseBotPluginCapability）────────────────────────────

    @Test
    fun `full - 已装完整插件`() {
        assertEquals(BotPluginCapability.FULL, parseBotPluginCapability("full"))
    }

    @Test
    fun `legacy - 上游原版插件照旧能用`() {
        assertEquals(BotPluginCapability.LEGACY, parseBotPluginCapability("legacy"))
    }

    @Test
    fun `missing - 未装插件`() {
        assertEquals(BotPluginCapability.MISSING, parseBotPluginCapability("missing"))
    }

    // ── 无契约输入不猜（旧网关 / 第四态）────────────────────────────────

    @Test
    fun `字段缺失或未知值 - 返回 null 保持既有行为`() {
        assertNull(parseBotPluginCapability(null))
        assertNull(parseBotPluginCapability(""))
        // 将来网关出现第四种取值：按「无契约」处理，不出横幅（不猜）。
        assertNull(parseBotPluginCapability("full-plus"))
    }

    // ── 从 /info 响应体提取（botPluginCapabilityFromInfo）────────────────

    @Test
    fun `info 带 botPlugin 字段 - 三态正确提取`() {
        assertEquals(
            BotPluginCapability.MISSING,
            botPluginCapabilityFromInfo("""{"name":"gw","capabilities":{"botPlugin":"missing","team":true}}""")
        )
        assertEquals(
            BotPluginCapability.FULL,
            botPluginCapabilityFromInfo("""{"capabilities":{"discovery":true,"botPlugin":"full"}}""")
        )
        assertEquals(
            BotPluginCapability.LEGACY,
            botPluginCapabilityFromInfo("""{"capabilities":{"botPlugin":"legacy"}}""")
        )
    }

    @Test
    fun `info 缺字段或畸形 JSON - 返回 null 不抛`() {
        assertNull(botPluginCapabilityFromInfo("""{"capabilities":{"team":true}}"""))
        assertNull(botPluginCapabilityFromInfo("""{"capabilities":123}"""))
        assertNull(botPluginCapabilityFromInfo("""not-json"""))
        assertNull(botPluginCapabilityFromInfo(""))
    }

    // ── missing 态错误码中文化（按 code，不按 message 匹配）──────────────

    @Test
    fun `missing 态三类 501 映射中文`() {
        val chinese = botPluginMissingErrorText("REDACTED_PROJECTION_REQUIRED", BotPluginCapability.MISSING)
        assertTrue(chinese?.contains("未安装 Bot 插件") == true, "实际：$chinese")
        assertEquals("未安装 Bot 插件，无法发送消息", botPluginMissingErrorText("STABLE_SUBMIT_REQUIRED", BotPluginCapability.MISSING))
        assertEquals("未安装 Bot 插件，团队动态不可用", botPluginMissingErrorText("TEAM_FEED_UNAVAILABLE", BotPluginCapability.MISSING))
    }

    @Test
    fun `非 missing 态不映射 - legacy_full 保持既有行为`() {
        // legacy/full 下这三类 501 不发生；真发生也按既有行为上屏原文（返回 null ⇒ 调用方回落）。
        assertNull(botPluginMissingErrorText("REDACTED_PROJECTION_REQUIRED", BotPluginCapability.LEGACY))
        assertNull(botPluginMissingErrorText("STABLE_SUBMIT_REQUIRED", BotPluginCapability.FULL))
        assertNull(botPluginMissingErrorText("REDACTED_PROJECTION_REQUIRED", null))
    }

    @Test
    fun `未知 code 不映射 - 交给调用方回落原始文案`() {
        assertNull(botPluginMissingErrorText("SOMETHING_ELSE", BotPluginCapability.MISSING))
        assertNull(botPluginMissingErrorText(null, BotPluginCapability.MISSING))
    }
}
