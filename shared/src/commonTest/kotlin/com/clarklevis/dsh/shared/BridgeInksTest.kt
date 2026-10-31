package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.protocol.BridgeInks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 行级 `color` 的解析口径（2026-11-01 定位「头像颜色恒落中性兜底」后立的闸门）。
 *
 * 期望值与 ds-bot `src/client/inks.js` 的 `INKS` 表逐条相同：两端若各抄一份，
 * 漂移时这里先红。
 */
class BridgeInksTest {

    @Test
    fun coversEveryDesktopInk() {
        assertEquals(19, BridgeInks.names.size, "ds-bot INKS 是 19 个 ink")
        for (name in listOf("deepseek", "charcoal", "black", "aurora", "gray", "magenta")) {
            assertEquals(true, name in BridgeInks.names, "$name 应在表内")
        }
    }

    @Test
    fun resolvesInkNameToLightAndDark() {
        // 与 ds-bot inks.js 逐字一致：deepseek light #4D6BFE / dark #6A84FF。
        assertEquals("#4D6BFE", BridgeInks.resolve("deepseek", dark = false))
        assertEquals("#6A84FF", BridgeInks.resolve("deepseek", dark = true))
        // 深色档不是浅色档的照抄，抽查两组相差明显的。
        assertEquals("#FFFFFF", BridgeInks.resolve("black", dark = true))
        assertEquals("#000000", BridgeInks.resolve("black", dark = false))
        assertEquals("#2EE6E0", BridgeInks.resolve("aurora", dark = true))
    }

    @Test
    fun acceptsHexAndIsCaseInsensitiveForNames() {
        assertEquals("#123456", BridgeInks.resolve("#123456", dark = false))
        assertEquals("#aabbcc", BridgeInks.resolve("#AABBCC", dark = false), "hex 统一小写输出")
        assertEquals("#4D6BFE", BridgeInks.resolve("  DeepSeek  ", dark = false), "名字大小写/空白应容错")
        // 3 位缩写归一化到 6 位。
        assertEquals("#aabbcc", BridgeInks.resolve("#abc", dark = false))
    }

    @Test
    fun unknownOrMalformedFallsBackToNull() {
        // 验收反例：未知 ink 名与非法 hex 都必须落回 null（调用方给中性色），不崩不猜。
        assertNull(BridgeInks.resolve("nope", dark = false))
        assertNull(BridgeInks.resolve("#GGGGGG", dark = false))
        assertNull(BridgeInks.resolve("#12345", dark = false), "长度不对不截断")
        assertNull(BridgeInks.resolve("", dark = false))
        assertNull(BridgeInks.resolve(null, dark = false))
        assertNull(BridgeInks.pair("nope"))
    }
}