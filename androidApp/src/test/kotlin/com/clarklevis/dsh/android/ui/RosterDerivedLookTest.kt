package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.android.R
import org.junit.Assert.assertEquals
import java.io.File
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 派生形象（B 批）对拍用例 —— **真值表来自 AndroidTester 的 golden**，不是我复述的:
 * `/Volumes/应用/dsh-workspace/android-verify/golden/avatar-look-v1.tsv`
 * （22 行, sha256[0:24] `1698dcab0a213f58e63be64f`, 输入 `golden/ids-all.txt`
 *  sha256[0:24] `d5ba529123172f1fe2ae58c3`），由 `oracle-avatar-look.mjs` 从
 * ds-bot `src/client/characters.js` 直接 import 算出。
 *
 * **喂法必须"显式字段 + id"一起给**：Chief 那行 `avatar.shape=whale`、`color=deepseek`，
 * 只喂 id 会算成 `gumdrop/orange`（错）。[RosterDerivedLook.effectiveLook] 的顺序就是
 * `显式 → 派生 → 字面量兜底`。
 *
 * 合成 id 里有中文、emoji 代理对（`🤖` = 两个 UTF-16 码元）、全 f、`80000000`、90 字符长串——
 * 专门用来抓"换成 codePointAt / Long / 按字节迭代"这类改写：那几种写法在这几行必然不等。
 */
class RosterDerivedLookTest {

    /** id, 显式 shape, 显式 color, 期望 shape, 期望 color —— 与 golden 逐行一致。 */
    private val golden = listOf(
        // 线上 11 行（只有 Chief 带显式 avatar/color，其余靠派生）
        Row("session-a5000f0e-e571-41f5-9c02-8cc30cbc7fef", "whale", "deepseek", "whale", "deepseek"),
        Row("session-9710897c-810e-4e84-b07b-cc27e0cb5698", null, null, "moon", "blue"),
        Row("session-a7a898a5-cc3a-468c-8e0a-3cba0fce80f7", null, null, "bunny", "violet"),
        Row("session-48a9b54f-0e1e-4e32-9823-3fc4e8b454c8", null, null, "acorn", "magenta"),
        Row("session-ffad66de-fe15-4807-b09a-5d49773ec2da", null, null, "gumdrop", "magenta"),
        Row("session-44f35ae5-888e-46e5-b1e9-20ec2ea25fb8", null, null, "moon", "yellow"),
        Row("session-000df163-a18d-416b-97d4-d42869d92238", null, null, "peanut", "violet"),
        Row("session-ff4dda8b-04de-46bd-8659-62c9050aa66d", null, null, "bunny", "yellow"),
        Row("session-4745edd2-ec90-4168-b2d9-76467a2f152e", null, null, "moon", "magenta"),
        Row("session-4d8741a8-4c26-4f23-94a4-89774b1d31ff", null, null, "bunny", "blue"),
        Row("session-48799833-b205-487f-b40c-ad96ccfe7e9e", null, null, "kitty", "green"),
        // 合成对抗 id（只喂 id）
        Row("a", null, null, "gumdrop", "cyan"),
        Row("b", null, null, "peanut", "yellow"),
        Row("0", null, null, "moon", "green"),
        Row("ab", null, null, "bunny", "violet"),
        Row("session-00000000-0000-0000-0000-000000000001", null, null, "acorn", "cyan"),
        Row("session-ffffffff-ffff-ffff-ffff-ffffffffffff", null, null, "kitty", "yellow"),
        Row("session-80000000-0000-4000-8000-000000000000", null, null, "moon", "cyan"),
        Row("AndroidTester", null, null, "acorn", "orange"),
        Row("测试机器人-甲", null, null, "acorn", "green"),
        Row("表情符号-🤖-乙", null, null, "star", "violet"),
        Row(
            "Z-超长-0123456789012345678901234567890123456789012345678901234567890123456789",
            null, null, "acorn", "yellow"
        )
    )

    private data class Row(
        val id: String,
        val explicitShape: String?,
        val explicitColor: String?,
        val shape: String,
        val color: String
    )

    /**
     * 逐行等于电脑端：22 行全绿才算过。失败信息带行号，方便与 golden 的 TSV 第 N 行对位。
     */
    @Test
    fun effectiveLookEqualsDesktopGoldenLineByLine() {
        assertEquals("golden 行数不对（应为 22）", 22, golden.size)
        golden.forEachIndexed { index, row ->
            val actual = RosterDerivedLook.effectiveLook(row.explicitShape, row.explicitColor, row.id)
            assertEquals("第 ${index + 1} 行 shape（id=${row.id}）", row.shape, actual.first)
            assertEquals("第 ${index + 1} 行 color（id=${row.id}）", row.color, actual.second)
        }
        // 交给 AndroidTester 的 Android 侧 TSV（id⇥shape⇥color），与 golden 同序可直接 diff。
        // 落盘一份**可直接重跑**的对照产物：`androidApp/build/derived-look-android.tsv`
        // （`./gradlew :androidApp:testDebugUnitTest --tests "*RosterDerivedLookTest*"` 即刷新）。
        val tsv = buildString {
            golden.forEach { row ->
                val actual = RosterDerivedLook.effectiveLook(row.explicitShape, row.explicitColor, row.id)
                append(row.id).append('\t').append(actual.first).append('\t').append(actual.second).append('\n')
            }
        }
        val moduleDir = File(System.getProperty("user.dir") ?: ".").let { dir ->
            generateSequence(dir.absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "androidApp/build.gradle.kts").isFile || File(it, "gradle/libs.versions.toml").isFile }
                ?: dir.absoluteFile
        }
        val out = File(moduleDir, "androidApp/build/derived-look-android.tsv")
        out.parentFile?.mkdirs()
        out.writeText(tsv, Charsets.UTF_8)
        println("### derived-look-android.tsv (${golden.size} rows) -> ${out.absolutePath}")
        print(tsv)
        println("### end")
    }

    /**
     * **顺序守卫**：显式字段优先。只按 id 派生会算成 gumdrop/orange —— 这条正是真值表
     * 第 1 行的坑（AndroidTester 第一版就踩到了）。
     */
    @Test
    fun explicitFieldsWinOverDerivedLook() {
        val id = "session-a5000f0e-e571-41f5-9c02-8cc30cbc7fef"
        assertEquals("gumdrop" to "orange", RosterDerivedLook.look(id))
        assertEquals("whale" to "deepseek", RosterDerivedLook.effectiveLook("whale", "deepseek", id))
    }

    /** 非法显式值（不在 20 个角色名里 / 不是 ink 名也不是 hex）⇒ 退回派生，不画错也不留空。 */
    @Test
    fun invalidExplicitFieldsFallBackToDerived() {
        val id = "session-a7a898a5-cc3a-468c-8e0a-3cba0fce80f7"
        assertEquals("bunny" to "violet", RosterDerivedLook.effectiveLook("dragon", "neon", id))
        // 合法 hex 也认（`avatar.color` 走这条），不合法则派生。
        assertEquals("bunny" to "#12ab34", RosterDerivedLook.effectiveLook(null, "#12ab34", id))
    }

    /** 空 id + 无显式字段 ⇒ 电脑端同一批字面量兜底（`gumdrop` / `black`），不抛异常。 */
    @Test
    fun missingIdFallsBackToDesktopLiterals() {
        assertEquals("gumdrop" to "black", RosterDerivedLook.effectiveLook(null, null, null))
        assertEquals("gumdrop" to "black", RosterDerivedLook.effectiveLook(null, null, "   "))
        assertEquals("whale" to "black", RosterDerivedLook.effectiveLook("whale", null, ""))
    }

    /** 默认表**顺序即索引**（JS :590/:599），被重排 ⇒ 派生结果整体错位。 */
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
     * 20/20：ds-bot `characters` 表的 20 个名字都必须有**同名** drawable，
     * 且 `characterNames` 与它完全一致（多一个少一个都会让 `resolveLook` 判"显式 shape 合法"出错）。
     */
    @Test
    fun allTwentyDesktopShapesHaveAnAndroidDrawable() {
        val desktopShapes = listOf(
            "acorn", "beacon", "bunny", "cushion", "gem", "gumdrop", "hex", "kitty", "magnet", "moon",
            "peanut", "pebbles", "plus", "ring", "saucer", "squircle", "star", "wave", "whale", "zed"
        )
        assertEquals(20, desktopShapes.size)
        assertEquals(desktopShapes.toSet(), RosterDerivedLook.characterNames)
        val missing = desktopShapes.filter { shapeIconRes(it) == null }
        assertTrue("以下形状没有同名 drawable: $missing", missing.isEmpty())
        // 抽查具体资源，证明映射真接到了 R（不是恒返回某个常量）。
        assertEquals(R.drawable.shape_whale, shapeIconRes("whale"))
        assertEquals(R.drawable.shape_kitty, shapeIconRes("kitty"))
    }

    /** 名字当 id 会算出别的形状 —— 钉住"键必须是 `bots[].id`"（电脑端同理）。 */
    @Test
    fun derivedLookIsKeyedByBotIdNotByName() {
        val byRealId = RosterDerivedLook.look("session-48a9b54f-0e1e-4e32-9823-3fc4e8b454c8")
        val byName = RosterDerivedLook.look("iOSCoder")
        assertEquals("acorn" to "magenta", byRealId)
        assertEquals("star" to "magenta", byName)
        assertNotEquals(byRealId!!.first, byName!!.first)
    }
}