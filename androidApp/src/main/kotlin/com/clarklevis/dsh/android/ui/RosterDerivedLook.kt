package com.clarklevis.dsh.android.ui

/**
 * 名册行「派生形象」——**电脑端 `lookOf(bot)` 的同源移植**（B 批，2026-11-02）。
 *
 * ## 为什么需要它
 * bridge 名册（`/state` + `/conversations`）里**只有用户手动设过的 bot 才有 `avatar` 字段**：
 * 线上实测 11 行中只有 Chief 一行带 `avatar={shape:"whale"}`，其余 10 行 `avatar=null`、`color=null`
 * ⇒ Android 只能落到「色圆 + 首字母」。而 iOS 08 屏吃的是 D 组 `dsBotTeam` feed，那里每行都带
 * 发布端按 `lookOf(id)` 派生出来的 `avatar.glyph`，所以线上 iOS 每组行都有图形。
 *
 * 这里把派生算法搬过来，让 Android 在**没有 glyph 通道**（D1 未落地）时也能与电脑端同源：
 * 同一个 id ⇒ 同一个 shape + 同一个 ink 名。**D1 落地后 glyph 会先接管，本文件自动退化为兜底**，
 * 兜底顺序（与 iOS 一致）：`image > glyph > shape > 派生 > 首字母`。
 *
 * ## 同源出处（JS 权威实现）
 * `dsh-plugin-mobile-gateway/lib/mobile-v1/project-avatar.mjs`：
 * `fnv1a` :643、`defaultShape` :648、`defaultColor` :654、`resolveLook` :660。
 * 两张表的**顺序即索引，不得重排**：`defaultShapes` :590（7 项）、`defaultColors` :599（8 项）。
 *
 * ## 一个必须守住的坑
 * 哈希入参必须是**电脑端同一个 id 字段**（`bots[].id`，本仓线上即 `session-…`）；用别的键
 * （名字、roomId）算出来的形状与电脑端逐行对不上。逐行对拍见 `RosterDerivedLookTest`。
 *
 * ## 整数语义
 * JS 侧全程 int32（`Math.imul` / `|0` / `>>>`），这里用 Kotlin `Int` 的溢出回绕 + `ushr` 复刻；
 * 只有取无符号模/除法时提升到 `Long` 并 `and 0xFFFFFFFF`。**不要**把 `Int` 换成 `Long` 中间态，
 * 那会得到不同结果（无符号回绕是算法的一部分）。
 */
internal object RosterDerivedLook {

    /** `characterData.defaultShapes`（JS :590）——**顺序即索引**。 */
    private val DEFAULT_SHAPES = listOf("star", "moon", "gumdrop", "peanut", "bunny", "kitty", "acorn")

    /** `characterData.defaultColors`（JS :599）——**顺序即索引**。 */
    private val DEFAULT_COLORS = listOf("red", "orange", "yellow", "green", "cyan", "blue", "violet", "magenta")

    /** 电脑端缺字段时用的形状/颜色名（`resolveLook` :663-664 的回落字面量）。 */
    const val FALLBACK_SHAPE = "gumdrop"
    const val FALLBACK_COLOR = "black"

    /**
     * 派生形象：返回 `shape 名（20 个角色名之一）` 与 `ink 名`。
     * `botId` 为空 ⇒ null（调用方保持原兜底，不猜）。
     */
    fun look(botId: String?): Pair<String, String>? {
        val id = botId?.takeIf { it.isNotBlank() } ?: return null
        return derivedShape(id) to derivedColorInk(id)
    }

    /** `defaultShape(id)`（JS :648）。 */
    internal fun derivedShape(botId: String): String {
        var value = fnv1a(botId)
        value = (value xor (value ushr 16)) * 73244475
        value = (value xor (value ushr 13)) * 3266489909L.toInt()
        val index = ((value xor (value ushr 16)).toLong() and 0xFFFFFFFFL) % DEFAULT_SHAPES.size
        return DEFAULT_SHAPES[index.toInt()]
    }

    /** `defaultColor(id)`（JS :654）。 */
    internal fun derivedColorInk(botId: String): String {
        val t = (fnv1a(botId).toLong() + 1831565813L).toInt()
        var n = (t xor (t ushr 15)) * (1 or t)
        n = (n + ((n xor (n ushr 7)) * (61 or n))) xor n
        val unsigned = (n xor (n ushr 14)).toLong() and 0xFFFFFFFFL
        val index = unsigned * DEFAULT_COLORS.size / 4294967296L
        return DEFAULT_COLORS[index.toInt()]
    }

    /** `fnv1a`（JS :643）：UTF-16 码元逐字符，int32 回绕；JS 返回 `>>> 0`，这里保留同一批比特。 */
    internal fun fnv1a(text: String): Int {
        var value = 2166136261L.toInt()
        for (character in text) {
            value = value xor character.code
            value *= 16777619
        }
        return value
    }

    /** 派生表本身（供对拍用例引用，避免用例里抄一份字面量）。 */
    internal fun shapeTable(): List<String> = DEFAULT_SHAPES

    internal fun colorTable(): List<String> = DEFAULT_COLORS
}