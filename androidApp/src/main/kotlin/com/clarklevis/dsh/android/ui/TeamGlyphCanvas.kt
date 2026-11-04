package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import com.clarklevis.dsh.shared.teamfeed.TeamFeedGlyph

/** vc62 ③：眼睛胶囊几何（JVM 可钉）——逐项对齐 iOS **视图路径** `eyeLayout`（TeamAvatarView.swift:159-165）：
 *  团队头像实际渲染走视图不走 path（TeamGlyph.swift:170 明写 Canvas 画眼已关），下界以视图为准：
 *  width/height 下界 **0.5**（max(dim×scale, 0.5)，squash **之前**做——iOS 先夹 frame 再 scaleEffect）；
 *  cornerRadius 下界 **0.25**（max(eye.radius×scale, 0.25)，eye.radius = min(w,h)/2）；
 *  squash 夹 **[0.01,1]**（eyePath:230，眨眼眯到近闭但不消失）；squash 乘在未旋转的 h 上（变换序 :229-233）。 */
internal data class TeamGlyphEyeRect(val width: Float, val height: Float, val cornerRadius: Float)

internal fun teamGlyphEyeRect(rx: Double, ry: Double, scale: Float, eyeSquash: Float = 1f): TeamGlyphEyeRect {
    val w = maxOf((rx * 2.0 * scale).toFloat(), 0.5f)
    val hFull = maxOf((ry * 2.0 * scale).toFloat(), 0.5f)
    val squash = eyeSquash.coerceIn(0.01f, 1f)
    return TeamGlyphEyeRect(
        width = w,
        height = hFull * squash,
        cornerRadius = maxOf(minOf(w, hFull) / 2f, 0.25f),
    )
}

/**
 * D1 段 3/5：glyph 矢量渲染 —— 电脑端同一份 `path` 数据直接画（方案 C，不做光栅化）。
 *
 * 契约（iOS `TeamGlyph.swift` / ds-bot characters）：
 * - body：主剪影用 **ink 色填充**（色 = roster `color` 的 ink，与电脑端 `lookOf` 同源）
 * - layers：叠加层按顺序、按各自的 fill/opacity 绘制
 * - eyes：**挖空露出底色**——渲染为「用 backdrop 色填充眼睛形状」
 *   （iOS TeamAvatarView 传 `backdrop:` 参数是同一手法）
 * - `glyph.size`（电脑端固定 100）是坐标系边长，按目标尺寸等比缩放
 */
@Composable
fun TeamGlyphCanvas(
    glyph: TeamFeedGlyph,
    inkColor: Color,
    backdropColor: Color,
    modifier: Modifier = Modifier,
    /** vc62 ③：眨眼压缩系数（iOS `scaleEffect(y: squash)` 作用于未旋转的胶囊高度，再 rotate）。 */
    eyeSquash: Float = 1f,
) {
    val body = remember(glyph.body) { TeamGlyphParser.toSubpaths(glyph.body) }
    val layers = remember(glyph.layers) {
        glyph.layers.map { layer -> Triple(layer, TeamGlyphParser.toSubpaths(layer.d), parseHexRgba(layer.fill)) }
    }
    Canvas(modifier) {
        val scale = this.size.minDimension / glyph.size.toFloat()
        val origin = Offset(
            (this.size.width - glyph.size.toFloat() * scale) / 2f,
            (this.size.height - glyph.size.toFloat() * scale) / 2f,
        )
        drawSubpaths(body, origin, scale, inkColor, stroke = false)
        for ((layer, paths, explicitFill) in layers) {
            val fill = explicitFill ?: inkColor
            drawSubpaths(paths, origin, scale, fill, stroke = false, alpha = (layer.opacity ?: 1.0).toFloat())
        }
        for (eye in glyph.eyes) {
            // vc62 ③：眼睛 = **胶囊**（圆角矩形，cornerRadius=min(w,h)/2）+ tilt 旋转——
            // 逐项对齐 iOS TeamGlyph.swift:216-218；vc61 的 drawOval 是误读（iOS 不是纯椭圆）。
            // 之前 drawCircle(rx) 更早一代的错（忽略 ry/tilt，高瘦眼画成圆点，用户 2026-11-04 真机反馈）。
            // 变换序对齐 iOS eyePath（:229-235）：未旋转尺寸 × squash → rotate → translate。
            val cx = origin.x + eye.cx.toFloat() * scale
            val cy = origin.y + eye.cy.toFloat() * scale
            val rect = teamGlyphEyeRect(eye.rx, eye.ry, scale, eyeSquash)
            rotate(degrees = eye.tilt.toFloat(), pivot = Offset(cx, cy)) {
                drawRoundRect(
                    color = backdropColor,
                    topLeft = Offset(cx - rect.width / 2f, cy - rect.height / 2f),
                    size = Size(rect.width, rect.height),
                    cornerRadius = CornerRadius(rect.cornerRadius),
                )
            }
        }
    }
}

private fun DrawScope.drawSubpaths(
    subpaths: List<List<DoubleArray>>?,
    origin: Offset,
    scale: Float,
    color: Color,
    stroke: Boolean,
    alpha: Float = 1f,
) {
    if (subpaths == null) return
    val path = Path()
    for (polyline in subpaths) {
        if (polyline.isEmpty()) continue
        path.moveTo(
            origin.x + polyline.first()[0].toFloat() * scale,
            origin.y + polyline.first()[1].toFloat() * scale,
        )
        for (point in polyline.drop(1)) {
            path.lineTo(origin.x + point[0].toFloat() * scale, origin.y + point[1].toFloat() * scale)
        }
        path.close()
    }
    if (stroke) {
        drawPath(path, color = color, alpha = alpha, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
    } else {
        drawPath(path, color = color, alpha = alpha)
    }
}

internal fun parseHexRgba(hex: String?): Color? {
    val digits = hex?.trim()?.removePrefix("#") ?: return null
    if (digits.length != 6 && digits.length != 8) return null
    val value = digits.toLongOrNull(16) ?: return null
    return if (digits.length == 8) {
        Color(
            red = ((value shr 24) and 0xFF).toInt() / 255f,
            green = ((value shr 16) and 0xFF).toInt() / 255f,
            blue = ((value shr 8) and 0xFF).toInt() / 255f,
            alpha = (value and 0xFF).toInt() / 255f,
        )
    } else {
        Color(
            red = ((value shr 16) and 0xFF).toInt() / 255f,
            green = ((value shr 8) and 0xFF).toInt() / 255f,
            blue = (value and 0xFF).toInt() / 255f,
        )
    }
}
