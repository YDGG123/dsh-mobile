package com.clarklevis.dsh.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 凭证主动刷新节奏（对齐 iOS `BridgeTokenTTL`，`BridgeTeamAdapter.swift:1116-1141`）。
 *
 * 网关可在 `/info` 的 `capabilities.accessTokenTtlSeconds` 里公布访问凭证有效期，
 * 手机按 **TTL × 90%** 排主动刷新（留 10% 余量吸收网络抖动与请求耗时），
 * 这样网关把 15 分钟改成 30/5 分钟都不用发版。
 *
 * 前向兼容红线：**字段缺失、类型不对、非正数、非有限一律当作「没给」**，
 * 调用方回落到 14 分钟兜底——老网关不发这个字段也必须能连。
 */
internal object BridgeTokenTtl {
    /** 占 TTL 的比例。 */
    const val REFRESH_RATIO: Double = 0.9

    /** 间隔下限/上限（秒）：太小会频繁换证（网关侧也是负担），太大就逼近过期。 */
    const val MIN_INTERVAL_SECONDS: Double = 60.0
    const val MAX_INTERVAL_SECONDS: Double = 60.0 * 60.0

    /** 老网关不公布 TTL 时的兜底（与 iOS 同值：14 分钟）。 */
    const val DEFAULT_INTERVAL_SECONDS: Double = 14.0 * 60.0

    /** 纯换算：非有限/非正 → null；否则 90% 并夹在 [60s, 60min]。 */
    fun proactiveIntervalSeconds(ttlSeconds: Double?): Double? =
        ttlSeconds
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it * REFRESH_RATIO).coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS) }

    /** 从 `/info` 原始 JSON 里取 TTL 并换算；任何异常形态一律返回 null（不抛）。 */
    fun proactiveIntervalSecondsFromInfo(infoJson: String?): Double? {
        if (infoJson.isNullOrBlank()) return null
        val raw = runCatching {
            Json.parseToJsonElement(infoJson)
                .jsonObject["capabilities"]
                ?.jsonObject
                ?.get("accessTokenTtlSeconds")
                ?.jsonPrimitive
                // 与 iOS 对齐：只认**数字**。字符串 "900" 视为"类型不对"（iOS 的 `as? NSNumber` 同样返回 nil），
                // 否则网关一旦把 TTL 发成字符串，两端就会一个换证一个不换。
                ?.takeIf { !it.isString }
                ?.content
                ?.toDoubleOrNull()
        }.getOrNull() ?: return null
        return proactiveIntervalSeconds(raw)
    }
}
