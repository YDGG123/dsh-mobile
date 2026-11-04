package com.clarklevis.dsh.shared.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot 插件能力三态（网关契约 `d174044`：`/info` 的 `capabilities.botPlugin`，
 * 取值 `'full' | 'legacy' | 'missing'`；文档 docs/mobile-v1-integration.md）。
 *
 * - FULL：装了带全部扩展的 Bot 插件（红字转写 + 稳定提交都在）。
 * - LEGACY：装了上游原版插件（既有行为，必须照旧能用——不许改）。
 * - MISSING：没装插件 ⇒ Bot 页给页面内常驻说明（不弹窗），团队端点全是 501。
 *
 * `parseBotPluginCapability` 对 null / 未知值返回 null：旧网关没有这个字段、
 * 或将来出现第四种取值时**按「无契约」处理**，调用方保持既有行为（不算 missing、
 * 不出横幅）——不猜。
 */
enum class BotPluginCapability { FULL, LEGACY, MISSING }

/** 解析 capabilities.botPlugin 原始字符串；null/未知值 ⇒ null（无契约，见上）。 */
fun parseBotPluginCapability(raw: String?): BotPluginCapability? = when (raw) {
    "full" -> BotPluginCapability.FULL
    "legacy" -> BotPluginCapability.LEGACY
    "missing" -> BotPluginCapability.MISSING
    else -> null
}

/**
 * 从 `/info` 响应体提取 `capabilities.botPlugin` 并解析。
 * **只碰这一个字段**——不把整个 capabilities 强类型化（Chief 2026-11-04 派单边界）。
 * JSON 不可解析 / capabilities 不是对象 / 字段缺失 ⇒ null（无契约）。
 */
fun botPluginCapabilityFromInfo(infoJson: String): BotPluginCapability? =
    runCatching {
        parseBotPluginCapability(
            Json.parseToJsonElement(infoJson).jsonObject["capabilities"]
                ?.jsonObject?.get("botPlugin")
                ?.jsonPrimitive?.contentOrNull
        )
    }.getOrNull()

/**
 * `MISSING` 态下把三类 501 的技术 code 映射成中文人话（网关源头：
 * `REDACTED_PROJECTION_REQUIRED`＝adapter.mjs:144「A DS Bot-owned redacted
 * transcript is required.」、`STABLE_SUBMIT_REQUIRED`＝提交链 501、
 * `TEAM_FEED_UNAVAILABLE`＝server.mjs:278/353 无团队 feed）。
 *
 * 按 **code** 映射，不按 message 字符串匹配、不上屏服务器英文（Chief 2026-11-04）。
 * 非 MISSING 态返回 null ⇒ 调用方回落原始 message：legacy/full 保持既有行为
 * （插件在位时这三类 501 不发生；真发生也按既有行为上屏原文，不扩大改动面）。
 */
fun botPluginMissingErrorText(code: String?, capability: BotPluginCapability?): String? {
    if (capability != BotPluginCapability.MISSING) return null
    return when (code) {
        "REDACTED_PROJECTION_REQUIRED" -> "未安装 Bot 插件，无法读取 Bot 与群聊消息"
        "STABLE_SUBMIT_REQUIRED" -> "未安装 Bot 插件，无法发送消息"
        "TEAM_FEED_UNAVAILABLE" -> "未安装 Bot 插件，团队动态不可用"
        else -> null
    }
}
