package com.clarklevis.dsh.android.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字号 token（A3 / 视觉规格 §8）—— 忠实 iOS `DSHType`（`Core/Theme.swift:386-398`）。
 *
 * 与规格文本的一个刻意偏离：iOS 的 DSHType **不是裸字号**，每档带 weight，
 * mono/monoValue 还带 monospaced design —— 本 object 按 TextStyle 工厂照抄，
 * 不做成 `.sp` 常量（否则 12.sp 的普通说明文字会被套上 mono 的名，
 * 将来 mono 补等宽 design 时静默破坏这批文字）。
 *
 * 替换纪律（AndroidCoder 2026-11-01 与 Chief/iOSDev 报备的方案 1+2）：
 * 只把「size+weight+design 三元组与本表精确相等」的内联位点换成 token，
 * 保证零像素变化；14/16sp 等非 token 值的重映射（改像素）等视觉侧映射表，不在本步。
 *
 * 缩放沿用 `AppFontDensity`（sp 天然随用户字号档缩放，与 iOS AppFont 同机制）。
 */
internal object DshType {
    /** 11pt medium · 元信息：时间、计数、状态字、标签 */
    val meta = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)

    /** 13pt regular · 次要文字：说明、副标题、行内辅助 */
    val footnote = TextStyle(fontSize = 13.sp)

    /** 15pt regular · 正文与行标题 */
    val body = TextStyle(fontSize = 15.sp)

    /** 18pt semibold · 区块标题、卡片主数字 */
    val title = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

    /** 28pt semibold · 摘要页的大数字 */
    val display = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold)

    /** 12pt 等宽 regular · 地址、路径、cron、代码 */
    val mono = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)

    /** 17pt 等宽 semibold · 指标数字 */
    val monoValue = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
}
