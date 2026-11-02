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

/** 空闲节奏：压一下 → 轻微过冲 → 回稳 → 静置（对应电脑端 `bt-blink-idle`）。 */
internal val IDLE_BLINK_STEPS: List<BlinkStep> = listOf(
    BlinkStep(0.80f, 90),
    BlinkStep(1.06f, 70),
    BlinkStep(1.00f, 80),
    BlinkStep(1.00f, 2_400)
)

/** 正在干活：眨得更勤（间隔缩短），与 iOS `blinkSteps(working:)` 同一意图。 */
internal val WORKING_BLINK_STEPS: List<BlinkStep> = listOf(
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

internal fun blinkStepsFor(working: Boolean): List<BlinkStep> =
    if (working) WORKING_BLINK_STEPS else IDLE_BLINK_STEPS

/** 周期总长（毫秒），用例用它对拍"相位不会越界"。 */
internal fun blinkPeriodMillis(working: Boolean): Long = blinkStepsFor(working).sumOf { it.holdMillis }
