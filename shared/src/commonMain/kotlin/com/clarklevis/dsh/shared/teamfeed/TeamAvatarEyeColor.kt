package com.clarklevis.dsh.shared.teamfeed

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * vc63：眼洞填色的对比度校正——iOS `TeamGlyph.swift:411-475` 的 **KMP 逐行移植**
 * （`RGBColor.luminance/contrast/mixed` + `TeamAvatarEyeColor.resolve`），公式一字不差：
 *
 * - 电脑端眼睛是 mask 挖空（洞透容器底色）；手机端等效做法是「洞填成底色」。
 *   但透底不保证看得见：浅底+亮车身 yellow 1.75:1、深底+暗车身 charcoal 1.02:1（iOS 实测）。
 * - 所以加 3:1 下限（图形可辨识），方向按**车身亮度**选：暗车身往白推、亮车身往黑压，
 *   0.1 步进混色直到达标；步尽仍不达 → 返回目标色（iOS 同款兜底）。
 *
 * 平台接线要求：进 `resolve` 的两个色必须是**解析后的实色 sRGB 分量**（0..1）——
 * iOS 用 `rgb(_:colorScheme:)`（UITraitCollection resolvedColor）解析动态色；
 * Android 的 Compose `Color` 在主题组合后已是实色，直接取 red/green/blue 分量即可。
 */
data class RgbColor(val red: Double, val green: Double, val blue: Double) {

    /** WCAG 相对亮度（iOS `TeamGlyph.swift:429-434` 同式）。 */
    val luminance: Double
        get() {
            fun channel(value: Double): Double =
                if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
        }

    /** WCAG 对比度（iOS `:426-429` 同式：(max+0.05)/(min+0.05)）。 */
    fun contrast(other: RgbColor): Double {
        val a = luminance
        val b = other.luminance
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /** 朝 [other] 线性混色（amount 0 = 自己，1 = other；iOS `:436-441` 同式）。 */
    fun mixed(other: RgbColor, amount: Double): RgbColor {
        val t = min(max(amount, 0.0), 1.0)
        return RgbColor(
            red = red + (other.red - red) * t,
            green = green + (other.green - green) * t,
            blue = blue + (other.blue - blue) * t,
        )
    }

    companion object {
        val BLACK = RgbColor(red = 0.0, green = 0.0, blue = 0.0)
        val WHITE = RgbColor(red = 1.0, green = 1.0, blue = 1.0)
    }
}

object TeamAvatarEyeColor {

    /** 目标对比度下限：3:1（图形元素可辨识下限，iOS `:452` 同值）。 */
    const val MINIMUM_CONTRAST: Double = 3.0

    /** 车身亮度低于此值视为「暗车身」，洞色往白推而不是往黑压（iOS `:454` 同值）。 */
    const val DARK_BODY_LUMINANCE: Double = 0.18

    fun resolve(backdrop: RgbColor, body: RgbColor, minimum: Double = MINIMUM_CONTRAST): RgbColor {
        if (backdrop.contrast(body) >= minimum) return backdrop
        // 暗车身 → 往白；亮车身 → 往黑。两个方向各自单调提升对比度（iOS `:461-471` 同序同步长）。
        val target = if (body.luminance < DARK_BODY_LUMINANCE) RgbColor.WHITE else RgbColor.BLACK
        var amount = 0.1
        while (amount <= 1.0) {
            val candidate = backdrop.mixed(target, amount)
            if (candidate.contrast(body) >= minimum) return candidate
            amount += 0.1
        }
        return target
    }
}
