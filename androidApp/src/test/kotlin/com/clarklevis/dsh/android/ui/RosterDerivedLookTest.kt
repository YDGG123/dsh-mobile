package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 派生形象（B 批）的对拍用例——**逐行等于电脑端 `lookOf(id)`**。
 *
 * 期望值不是手抄的：由电脑端 JS 权威实现算出
 * （`dsh-plugin-mobile-gateway/lib/mobile-v1/project-avatar.mjs` 的 `resolveLook`，
 * 2026-11-02 23:1x 跑 `node`，11 行全绿后才写进这里），id 取 `~/.dsh/bot/state.json`
 * 里 bot 的 `id` 字段（即 `session-…`）。
 *
 * 若哪天有人改错了哈希入参（例如传名字、传 roomId）或改乱了默认表顺序，这组断言会当场变红。
 */
class RosterDerivedLookTest {

    /** 线上 11 个 bot（23:1x 读 `~/.dsh/bot/state.json`）：name → id → 电脑端派生结果。 */
    private val desktopLook = listOf(
        Triple("Chief", "session-a5000f0e-e571-41f5-9c02-8cc30cbc7fef", "gumdrop" to "orange"),
        Triple("Coder", "session-9710897c-810e-4e84-b07b-cc27e0cb5698", "moon" to "blue"),
        Triple("Tester", "session-a7a898a5-cc3a-468c-8e0a-3cba0fce80f7", "bunny" to "violet"),
        Triple("iOSCoder", "session-48a9b54f-0e1e-4e32-9823-3fc4e8b454c8", "acorn" to "magenta"),
        Triple("iOSTester", "session-ffad66de-fe15-4807-b09a-5d49773ec2da", "gumdrop" to "magenta"),
        Triple("DSBotDev", "session-44f35ae5-888e-46e5-b1e9-20ec2ea25fb8", "moon" to "yellow"),
        Triple("DSBotQA", "session-000df163-a18d-416b-97d4-d42869d92238", "peanut" to "violet"),
        Triple("iOSDev", "session-ff4dda8b-04de-46bd-8659-62c9050aa66d", "bunny" to "yellow"),
        Triple("ReleaseBot", "session-4745edd2-ec90-4168-b2d9-76467a2f152e", "moon" to "magenta"),
        Triple("AndroidCoder", "session-4d8741a8-4c26-4f23-94a4-89774b1d31ff", "bunny" to "blue"),
        Triple("AndroidTester", "session-48799833-b205-487f-b40c-ad96ccfe7e9e", "kitty" to "green")
    )

    @Test
    fun derivedLookEqualsDesktopLookOfLineByLine() {
        for ((name, id, expected) in desktopLook) {
            assertEquals("$name 派生形状与电脑端不一致", expected.first, RosterDerivedLook.derivedShape(id))
            assertEquals("$name 派生颜色与电脑端不一致", expected.second, RosterDerivedLook.derivedColorInk(id))
        }
    }

    /** 空/空白 id ⇒ 不猜（调用方保持原兜底）。 */
    @Test
    fun blankIdDerivesNothing() {
        assertEquals(null, RosterDerivedLook.look(null))
        assertEquals(null, RosterDerivedLook.look(""))
        assertEquals(null, RosterDerivedLook.look("   "))
    }

    /**
     * **坑的守卫**：哈希入参必须是 `bots[].id`。传名字会算出别的形状/颜色
     * （电脑端同一函数即如此：`lookOf({id:'iOSCoder'})` = star/magenta ≠ 真 id 的 acorn/magenta）；
     * 这里把它钉住，免得后人"顺手"改成按名字做键。
     */
    @Test
    fun derivedLookIsKeyedByBotIdNotByName() {
        val byRealId = RosterDerivedLook.look("session-48a9b54f-0e1e-4e32-9823-3fc4e8b454c8")
        val byName = RosterDerivedLook.look("iOSCoder")
        assertEquals("acorn" to "magenta", byRealId)
        assertEquals("star" to "magenta", byName)
        assertNotEquals(byRealId!!.first, byName!!.first)
    }

    /** 默认表**顺序即索引**（JS :590/:599），顺序被重排 ⇒ 派生结果会整体错位。 */
    @Test
    fun defaultTablesKeepDesktopOrder() {
        assertEquals(
            listOf("star", "moon", "gumdrop", "peanut", "bunny", "kitty", "acorn"),
            RosterDerivedLook.shapeTable()
        )
        assertEquals(
            listOf("red", "orange", "yellow", "green", "cyan", "blue", "violet", "magenta"),
            RosterDerivedLook.colorTable()
        )
    }

    /**
     * 20/20：ds-bot `characters` 表（JS :2 起，2026-11-02 23:0x 用正则取出 20 键）里的每个名字
     * 都必须在 Android 的 `shape_*` drawable 里有**同名**对应——缺一个，那一行就会退回中性圆点。
     */
    @Test
    fun allTwentyDesktopShapesHaveAnAndroidDrawable() {
        val desktopShapes = listOf(
            "acorn", "beacon", "bunny", "cushion", "gem", "gumdrop", "hex", "kitty", "magnet", "moon",
            "peanut", "pebbles", "plus", "ring", "saucer", "squircle", "star", "wave", "whale", "zed"
        )
        assertEquals(20, desktopShapes.size)
        val missing = desktopShapes.filter { shapeIconRes(it) == null }
        assertTrue("以下形状没有同名 drawable: $missing", missing.isEmpty())
        // 抽查一个具体资源，证明映射真接到了 R（不是恒返回某个常量）。
        assertEquals(R.drawable.shape_whale, shapeIconRes("whale"))
        assertEquals(R.drawable.shape_kitty, shapeIconRes("kitty"))
        assertNotNull(shapeIconRes("gumdrop"))
    }
}