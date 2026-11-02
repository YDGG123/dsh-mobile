package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.teamfeed.parseTeamFeedGlyph
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1 段 3 判据用例：path 解析对齐 iOS `TeamGlyph.swift` 契约
 * （M/L/H/V/Z 绝对+相对；不支持的命令 ⇒ null 并记日志，不静默画错）。
 *
 * 真实 20 角色的 path 数据在 ds-bot `src/client/characters.js`（本仓没有这份文件），
 * 「20/20 解析非空」那条真值验证按计划文档在接入 feed 真数据时做——
 * 这里先把**解析器本身**的判据钉死：真样本能过、坏样本必 null。
 */
class TeamGlyphParserTest {

    /** 真实样本（ds-bot whale 的 d 开头，含"数字贴字母"与相对 l）：必须解析成功。 */
    @Test
    fun realWhaleHeadParsesWithRelativeCommands() {
        val d = "M65.3 20.9l5.1 .1 4.8 .9 4.8 1.7 4.3 2.3z"
        val commands = TeamGlyphParser.parsePath(d)
        assertNotNull(commands)
        assertTrue(commands!!.first().command == 'M')
        assertTrue(commands.first().args[0] == 65.3)
        // drop(1) 里含收尾的 z（相对、无参），线段段全是相对 L。
        assertTrue(commands.drop(1).filter { it.command != 'Z' }.all { it.relative && it.command == 'L' })
        // 折线能算出来（渲染直接可用）。
        assertNotNull(TeamGlyphParser.toAbsolutePolyline(d))
    }

    /** 负例：不支持的命令（C 曲线）⇒ 整条 null，不画一半（iOS 同规）。 */
    @Test
    fun unsupportedCurveCommandIsNull() {
        assertNull(TeamGlyphParser.parsePath("M4,13c0,-3 3,-5 6,-5"))
        assertNull(TeamGlyphParser.parsePath("M1 1Q2 2 3 3"))
        assertNull(TeamGlyphParser.parsePath("M1 1A3 3 0 1 0 4 4"))
    }

    /** 负例：参数个数不齐 / 数字非法 / 空串。 */
    @Test
    fun malformedPathIsNull() {
        assertNull(TeamGlyphParser.parsePath(""))
        assertNull(TeamGlyphParser.parsePath("L1 2")) // 没有起点
        assertNull(TeamGlyphParser.parsePath("M1"))
        assertNull(TeamGlyphParser.parsePath("M1 x2"))
    }

    /** H/V 绝对与相对：折线坐标逐点比对（渲染层的正确性靠这一条兜底）。 */
    @Test
    fun absoluteAndRelativeAxesProduceSameGeometry() {
        val absolute = TeamGlyphParser.toAbsolutePolyline("M10 10H30V30H10Z")!!.map { it.first.toList() to it.second.toList() }
        val relative = TeamGlyphParser.toAbsolutePolyline("M10 10h20v20h-20Z")!!.map { it.first.toList() to it.second.toList() }
        assertEquals(absolute, relative)
        // M 本身不产生线段；第一条是 H30：(30,10)→(30,30)。
        assertEquals(30.0, absolute.first().first[0], 1e-9)
        assertEquals(30.0, absolute.first().second[0], 1e-9)
    }

    /** Z 闭合回子路径起点。 */
    @Test
    fun closeReturnsToSubpathStart() {
        val segments = TeamGlyphParser.toAbsolutePolyline("M10 10L20 10Z")!!
        val last = segments.last()
        assertEquals(10.0, last.second[0], 1e-9)
        assertEquals(10.0, last.second[1], 1e-9)
    }

    /** JSON → 模型：glyph 在 bot 顶层（iOS :329-333），layers 数组形态 + eyes 四元组。 */
    @Test
    fun glyphJsonParsesLayersAndEyes() {
        val glyph = parseTeamFeedGlyph(
            Json.parseToJsonElement(
                """{"size":100,"path":"M1 1L2 2Z",
                   "layers":[{"d":"M1 1L2 2","fill":"#fff","opacity":0.16}],
                   "eyes":[[72.5,48.4,9.3,5.2,0.2]],
                   "color":"deepseek"}""",
            ).jsonObject,
        )
        assertNotNull(glyph)
        assertEquals(100.0, glyph!!.size, 1e-9)
        assertEquals(1, glyph.layers.size)
        assertEquals(0.16, glyph.layers.first().opacity!!, 1e-9)
        assertEquals(1, glyph.eyes.size)
        assertEquals(4.65, glyph.eyes.first().rx, 1e-9)
        assertEquals("deepseek", glyph.color)
    }

    /** layers 的 SVG 片段兼容形态（电脑端直接拼的 `<path …/>`）。 */
    @Test
    fun svgFragmentLayersParse() {
        val layers = TeamGlyphParser.parseSvgFragmentLayers(
            """<path d="M93.3 55.8l1-.3 .1 1.2" fill="#fff" fill-opacity=".16"/><path d="M1 1L2 2" fill="#000"/>""",
        )
        assertEquals(2, layers.size)
        assertEquals("#fff", layers.first().fill)
        assertEquals(0.16, layers.first().opacity!!, 1e-9)
    }

    /** 缺 path / size 非法 ⇒ null（parseTeamFeedGlyph 的守卫）。 */
    @Test
    fun glyphWithoutPathOrNullSizeIsRejected() {
        assertNull(parseTeamFeedGlyph(Json.parseToJsonElement("""{"size":100}""").jsonObject))
        assertNull(parseTeamFeedGlyph(Json.parseToJsonElement("""{"size":0,"path":"M1 1"}""").jsonObject))
    }
}
