package com.clarklevis.dsh.android.ui

/**
 * Bot 头像「眨眼」的节奏与相位（用户 2026-11-02：iOS Bot 页头像会眨，Android Bot 页不会）。
 *
 * ## 与 iOS 同一套节奏，但画法不同（已登记的有意差异）
 * iOS `TeamAvatarView.swift` 压的是**角色眼睛**（glyph 里挖空的 eyes 层，`eyeSquash`）；
 * Android 的 Bot 页头像画的是 20 个**描边符号**（`shape_*.xml`），没有独立眼睛层
 * ——`avatar.glyph` 通道属 D1，尚未落地。所以这里压的是**符号本身**（轻压扁 + 轻微回弹），
 * 表达「眨一下」的同一意图，但视觉上不是眼睛闭合。
 *
 * ## 相位
 * 电脑端 `--bt-delay: -(fnv1a(botID) % 5000)/1000 s`，iOS 用同一个 fnv1a 做负延迟；
 * 这里取同一个窗口（[BLINK_PHASE_WINDOW_MILLIS]），所以同一只 Bot 在任何平台
 * 都从周期的同一处开始，不会出现「一排头像同时眨」的机械感。
 *
 * 纯函数抽出来是为了让用例直接验实现（相位是否稳定、范围是否合法），
 * 而不是在测试里重写一遍算法。
 */
internal const val BLINK_PHASE_WINDOW_MILLIS = 5_000L

/** 一档：(纵向压扁系数, 保持毫秒)。与 iOS `blinkSteps(working:)` 的离散档位同构。 */
internal data class BlinkStep(val squash: Float, val holdMillis: Long)

/**
 * vc63 ②（用户 2026-11-04：压到 0.80 几乎看不见）——**glyph 分支**（独立眼睛层，`TeamGlyphCanvas`
 * 的 `eyeSquash`）的节奏改为**逐值对齐 iOS `TeamAvatarView.blinkSteps`**：
 *
 * - 空闲（bt-blink-idle，5s 一周期眨**两次**，9 档）：iOS hold 单位是秒，这里 ×1000 换毫秒；
 * - 干活（bt-blink，2.4s 一周期，5 档）；
 * - 0.03 = 眼睛眯到近闭、1.06 = 回弹过冲（保留）；周期总和恰为 5000/2400ms。
 *
 * **Icon 分支**（描边符号 `shape_*.xml`，无独立眼睛层）**不用这套**：0.03 会把整只符号压成
 * 一条线——保留轻压 0.80，登记为**有意差异**（见 ICON_*_BLINK_STEPS 注释，Chief 2026-11-04 批）。
 */
internal val IDLE_GLYPH_BLINK_STEPS: List<BlinkStep> = listOf(
    BlinkStep(1.00f, 1_480),
    BlinkStep(0.03f, 130),
    BlinkStep(1.06f, 80),
    BlinkStep(1.00f, 60),
    BlinkStep(1.00f, 1_710),
    BlinkStep(0.03f, 130),
    BlinkStep(1.06f, 80),
    BlinkStep(1.00f, 60),
    BlinkStep(1.00f, 1_270)
)

internal val WORKING_GLYPH_BLINK_STEPS: List<BlinkStep> = listOf(
    BlinkStep(1.00f, 1_008),
    BlinkStep(0.03f, 96),
    BlinkStep(1.06f, 48),
    BlinkStep(1.00f, 48),
    BlinkStep(1.00f, 1_200)
)

/** Icon 分支（描边符号）轻压节奏：压的是**整只符号**，0.03 会把符号压成一条线——
 *  有意保持 0.80 轻压（vc62 前的行为），与 iOS 的差异**已登记**（Chief 2026-11-04 批）。 */
internal val IDLE_ICON_BLINK_STEPS: List<BlinkStep> = listOf(
    BlinkStep(0.80f, 90),
    BlinkStep(1.06f, 70),
    BlinkStep(1.00f, 80),
    BlinkStep(1.00f, 2_400)
)

internal val WORKING_ICON_BLINK_STEPS: List<BlinkStep> = listOf(
    BlinkStep(0.80f, 90),
    BlinkStep(1.06f, 70),
    BlinkStep(1.00f, 80),
    BlinkStep(1.00f, 1_500)
)

/** 相位偏移（毫秒）：`fnv1a(botId) % 5000`，与电脑端/iOS 同源同窗口。空 id ⇒ 0。 */
internal fun blinkPhaseMillis(botId: String?): Long {
    val id = botId?.takeIf { it.isNotBlank() } ?: return 0L
    return (RosterDerivedLook.fnv1a(id).toLong() and 0xFFFFFFFFL) % BLINK_PHASE_WINDOW_MILLIS
}

/** 要不要真的驱动动效：**入口开启**且**系统没关动画**（Android 的"减弱动态效果"）。 */
internal fun shouldBlink(entryEnabled: Boolean, systemAnimationsEnabled: Boolean): Boolean =
    entryEnabled && systemAnimationsEnabled

/** 选节奏表：glyph 分支 = iOS 逐值节奏（独立眼睛层，压到 0.03）；
 *  Icon 分支 = 整只符号轻压 0.80（有意差异，压到 0.03 会把符号压成一条线）。 */
internal fun blinkStepsFor(working: Boolean, glyphBranch: Boolean): List<BlinkStep> = when {
    glyphBranch && working -> WORKING_GLYPH_BLINK_STEPS
    glyphBranch -> IDLE_GLYPH_BLINK_STEPS
    working -> WORKING_ICON_BLINK_STEPS
    else -> IDLE_ICON_BLINK_STEPS
}

/** 周期总长（毫秒），用例用它对拍"相位不会越界"。 */
internal fun blinkPeriodMillis(working: Boolean, glyphBranch: Boolean): Long =
    blinkStepsFor(working, glyphBranch).sumOf { it.holdMillis }
