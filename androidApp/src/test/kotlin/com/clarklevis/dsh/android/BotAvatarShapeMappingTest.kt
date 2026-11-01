package com.clarklevis.dsh.android

import androidx.compose.ui.graphics.Color
import com.clarklevis.dsh.android.ui.shapeIconRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bot 头像 shape 符号映射（vc44 D4）——**抓"表看着齐、实际只有一个分支生效"**（Chief 2026-11-02 加的判据）。
 *
 * 纯逻辑可测：20 个键必须映射到 **20 个互不相同**的 drawable；未知键必须返回 null（落中性圆点）。
 */
class BotAvatarShapeMappingTest {

    private val shapes = listOf(
        "whale", "star", "moon", "wave", "squircle", "zed", "hex", "gumdrop", "peanut", "saucer",
        "bunny", "kitty", "beacon", "plus", "magnet", "acorn", "pebbles", "cushion", "ring", "gem"
    )

    /** ① 20 个键全有映射（不漏项）。 */
    @Test
    fun everyKnownShapeMapsToSomeIcon() {
        shapes.forEach { shape ->
            assertTrue("shape=$shape 应有映射", shapeIconRes(shape) != null)
        }
    }

    /** ② 20 个映射互不相同（防"全部落到 default、只生效一项"）。 */
    @Test
    fun knownShapesMapToDistinctIcons() {
        val ids = shapes.map { shapeIconRes(it) }
        assertEquals("20 个 shape 应映射到 20 个不同 drawable", 20, ids.toSet().size)
        assertTrue("不应出现 0（未解析）", ids.none { it == 0 })
    }

    /** ③ 未知形状 ⇒ null（调用方据此落中性圆点，照 iOS circle.fill）。 */
    @Test
    fun unknownShapeMapsToNull() {
        assertNull(shapeIconRes("unknown-shape"))
        assertNull(shapeIconRes(""))
        assertNull(shapeIconRes(null))
    }

    /** ④ 大小写不敏感（服务端键名小写，但别依赖它）。 */
    @Test
    fun isCaseInsensitive() {
        assertEquals(shapeIconRes("whale"), shapeIconRes("Whale"))
        assertEquals(shapeIconRes("WHALE"), shapeIconRes("whale"))
    }
}
