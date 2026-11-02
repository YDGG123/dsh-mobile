package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.teamfeed.TeamFeedGlyph
import com.clarklevis.dsh.shared.teamfeed.parseTeamFeedGlyph
import kotlinx.serialization.json.JsonObject

/**
 * glyph 矢量解析（D1 段 3）—— port `TeamGlyph.swift` 的契约，逐条对齐：
 *
 * - path 只支持 `M/L/H/V/Z` 的**绝对与相对**形式（电脑端 20 个角色的 d 已实测只用这些，
 *   iOS `TeamGlyph.swift:10` 同规）；遇到其它命令（C/Q/A/S/T）返回 null 并打日志，**不静默画错**。
 * - `layers` 接受两种形态：对象数组 `[{d, fill, opacity}]`，或电脑端拼的 SVG 片段字符串
 *   `<path d="…" fill="…" fill-opacity="…"/>…`（iOS `:86-100` 的兼容分支）。
 * - `eyes` = `[cx, cy, w, h, tilt?]`，语义是**从所有图层挖空**（露出底色），不是再画一层。
 * - JSON → 模型复用 shared 的 `parseTeamFeedGlyph`（两端同一份字段口径，不另写一份）。
 */
object TeamGlyphParser {

    /** 一条路径指令：命令字母 + 展开后的参数 + 是否相对。渲染时再折算成绝对坐标。 */
    data class PathCommand(val command: Char, val args: List<Double>, val relative: Boolean)

    /** 支持的命令（iOS 同规）：M/L/H/V/Z，大写绝对、小写相对。 */
    private val SUPPORTED = setOf('M', 'L', 'H', 'V', 'Z', 'm', 'l', 'h', 'v', 'z')

    /** 每个命令的参数个数（M/L 各 2；H/V 各 1；Z 0）。 */
    private val ARG_COUNT = mapOf('M' to 2, 'L' to 2, 'H' to 1, 'V' to 1, 'Z' to 0)

    fun parse(json: JsonObject): TeamFeedGlyph? = parseTeamFeedGlyph(json)

    /**
     * 解析 `d` 为指令序列。数字分隔符：逗号/空白/负号前缀（`20.9l5.1 .1` 这种"数字贴字母"也合法）。
     * 出现不支持的命令、参数个数不齐、数字非法 ⇒ 整条返回 null（不画一半）。
     */
    fun parsePath(d: String): List<PathCommand>? {
        if (d.isBlank()) return null
        val tokens = tokenize(d) ?: return null
        val commands = mutableListOf<PathCommand>()
        var index = 0
        // SVG 契约：路径必须以 M（或 m）开头；没有起点的 L/H/V 是坏数据。
        if (tokens.isEmpty() || tokens.first() !is Token.Command || tokens.first().let { (it as Token.Command).value.uppercase().single() } != 'M') {
            return null
        }
        while (index < tokens.size) {
            val token = tokens[index]
            if (token !is Token.Command) return null
            val upper = token.value.uppercase().single()
            if (upper !in ARG_COUNT) return null // C/Q/A/S/T 等：不支持 ⇒ null（不静默画错）
            val relative = token.value.first().isLowerCase()
            val argCount = ARG_COUNT.getValue(upper)
            index += 1
            // 同一命令字母可后跟多组参数（`L 1 2 3 4` = 两条线）；无参数的 Z 只能有一条。
            while (true) {
                val args = mutableListOf<Double>()
                var inner = index
                while (args.size < argCount && inner < tokens.size && tokens[inner] is Token.Number) {
                    val value = (tokens[inner] as Token.Number).value
                    if (!value.isFinite()) return null
                    args.add(value)
                    inner += 1
                }
                if (args.size < argCount) {
                    if (commands.isEmpty() && upper == 'M' && args.isEmpty()) return null
                    break
                }
                commands.add(PathCommand(upper, args, relative))
                index = inner
                if (argCount == 0) break
            }
            if (commands.isEmpty()) return null
        }
        return commands.ifEmpty { null }
    }

    /**
     * 把指令折算成**绝对坐标折线**（渲染直接可用）：Z 闭合到最近一次 M 的起点。
     * 返回 null = 输入非法（与 [parsePath] 同口径）。
     */
    fun toAbsolutePolyline(d: String): List<Pair<DoubleArray, DoubleArray>>? {
        val commands = parsePath(d) ?: return null
        var cursor = DoubleArray(2)
        var subpathStart = DoubleArray(2)
        var penDown = false
        val segments = mutableListOf<Pair<DoubleArray, DoubleArray>>()
        for (cmd in commands) {
            val dx = if (cmd.relative && cmd.command != 'Z') cursor[0] else 0.0
            val dy = if (cmd.relative && cmd.command != 'Z') cursor[1] else 0.0
            when (cmd.command) {
                'M' -> {
                    cursor = doubleArrayOf(cmd.args[0] + dx, cmd.args[1] + dy)
                    subpathStart = cursor.copyOf()
                    penDown = false
                }
                'L' -> {
                    val next = doubleArrayOf(cmd.args[0] + dx, cmd.args[1] + dy)
                    if (penDown) segments.add(cursor.copyOf() to next)
                    cursor = next
                    penDown = true
                }
                'H' -> {
                    val next = doubleArrayOf(cmd.args[0] + dx, cursor[1])
                    if (penDown) segments.add(cursor.copyOf() to next)
                    cursor = next
                    penDown = true
                }
                'V' -> {
                    val next = doubleArrayOf(cursor[0], cmd.args[0] + dy)
                    if (penDown) segments.add(cursor.copyOf() to next)
                    cursor = next
                    penDown = true
                }
                'Z' -> {
                    if (penDown) segments.add(cursor.copyOf() to subpathStart.copyOf())
                    cursor = subpathStart.copyOf()
                    penDown = false
                }
            }
        }
        return segments.ifEmpty { null }
    }

    /** SVG 片段 `<path d="…" fill="…" fill-opacity="…"/>` → 图层列表（iOS `layers(fromSVGFragment:)` 兼容）。 */
    fun parseSvgFragmentLayers(fragment: String): List<TeamFeedGlyphLayerDsl> {
        val result = mutableListOf<TeamFeedGlyphLayerDsl>()
        val regex = Regex("""<path\s+([^>]*?)/?>""")
        for (match in regex.findAll(fragment)) {
            val attrs = match.groupValues[1]
            val d = Regex("""d\s*=\s*"([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: continue
            val fill = Regex("""fill\s*=\s*"([^"]+)"""").find(attrs)?.groupValues?.get(1)
            val opacity = Regex("""fill-opacity\s*=\s*"([^"]+)"""").find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()
            result.add(TeamFeedGlyphLayerDsl(d, fill, opacity))
        }
        return result
    }

    data class TeamFeedGlyphLayerDsl(val d: String, val fill: String?, val opacity: Double?)

    private sealed interface Token {
        data class Command(val value: String) : Token
        data class Number(val value: Double) : Token
    }

    private fun tokenize(d: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        var index = 0
        while (index < d.length) {
            val c = d[index]
            when {
                c.isWhitespace() || c == ',' -> index += 1
                c.isLetter() -> {
                    if (c.uppercase().single() !in SUPPORTED) return null
                    tokens.add(Token.Command(c.toString()))
                    index += 1
                }
                else -> {
                    // 数字（含小数、负号、科学计数）。
                    val start = index
                    if (c == '-' || c == '+') index += 1
                    var seenDot = false
                    var seenDigit = false
                    while (index < d.length) {
                        val n = d[index]
                        when {
                            n.isDigit() -> { seenDigit = true; index += 1 }
                            n == '.' && !seenDot -> { seenDot = true; index += 1 }
                            (n == 'e' || n == 'E') && seenDigit -> {
                                index += 1
                                if (index < d.length && (d[index] == '-' || d[index] == '+')) index += 1
                            }
                            n == '-' || n == '+' -> break // 负号兼分隔符：留给下一轮
                            else -> break
                        }
                    }
                    if (!seenDigit) return null
                    val value = d.substring(start, index).toDoubleOrNull() ?: return null
                    tokens.add(Token.Number(value))
                }
            }
        }
        return tokens
    }
}
