package com.clarklevis.dsh.shared.protocol

/**
 * ds-bot 的 ink 调色板（`src/client/inks.js` 的 `INKS[k] = [light, dark]`）。
 *
 * 为什么放在 shared：桥接名册的行级 `color` 下发的是 **ink 名**（如 `"deepseek"`），
 * 不是十六进制；两个平台各自抄一份 19 对深浅值，迟早漂移（「手机端不许另造一份角色数据」
 * 是同一条纪律）。iOS 侧原有 `GatewayTeamFeed.inkPairs` 表值与此处逐条一致
 * （2026-11-01 对拍），后续以本表为单一来源。
 *
 * 深浅两档**都要**：电脑端 `styles.js` 深色走 `INKS[k][1]`，只抄浅色会让深色下
 * 19/19 与电脑端不同（QA 实测指出过）。
 */
object BridgeInks {
    private val pairs: Map<String, Pair<String, String>> = mapOf(
        "deepseek" to ("#4D6BFE" to "#6A84FF"),
        "charcoal" to ("#141416" to "#F1F2F4"),
        "graphite" to ("#2D2D2D" to "#D6D6DA"),
        "rose" to ("#EC2F6B" to "#FF5A7A"),
        "sky" to ("#0AA8D6" to "#2EE6E0"),
        "tangerine" to ("#FF6900" to "#FF8A2E"),
        "cobalt" to ("#082DFF" to "#5C78FF"),
        "black" to ("#000000" to "#FFFFFF"),
        "brown" to ("#A27952" to "#855C36"),
        "red" to ("#FF3E51" to "#E02135"),
        "orange" to ("#FF781C" to "#FF6700"),
        "yellow" to ("#FFAF38" to "#FF9800"),
        "green" to ("#00C972" to "#009957"),
        "cyan" to ("#1CC3B0" to "#00A592"),
        "blue" to ("#2A92FE" to "#0E74E0"),
        "violet" to ("#A97EFE" to "#804EE0"),
        "magenta" to ("#FF5EB1" to "#E02A88"),
        "gray" to ("#959595" to "#777777"),
        "aurora" to ("#00B8D4" to "#2EE6E0"),
    )

    /** 电脑端那 19 个 ink 名（顺序无关，仅供对拍与校验）。 */
    val names: Set<String> get() = pairs.keys

    /** ink 名的深浅两档；未知名字返回 null（调用方回落中性色，不猜）。 */
    fun pair(ink: String?): Pair<String, String>? {
        val key = ink?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return null
        return pairs[key]
    }

    /**
     * 行级 `color` 的取值解析：**ink 名**（`"deepseek"`）与 **`#rrggbb`** 都接受，
     * 返回该用的十六进制串；非法/未知一律 null。
     *
     * 与 iOS `GatewayTeamFeed.dynamicInk` 同一口径：先认 ink 名，再认 `#` 前缀 hex。
     */
    fun resolve(value: String?, dark: Boolean): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.startsWith("#")) return normalizeHex(raw)
        val entry = pair(raw) ?: return null
        return if (dark) entry.second else entry.first
    }

    /** `#RGB` / `#RRGGBB` 归一化为 `#RRGGBB`；其它长度视为非法（不猜、不截断）。 */
    private fun normalizeHex(value: String): String? {
        val digits = value.removePrefix("#")
        if (!digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return when (digits.length) {
            3 -> "#" + digits.map { "$it$it" }.joinToString("")
            6 -> "#" + digits.lowercase()
            else -> null
        }
    }
}