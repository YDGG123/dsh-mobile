package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import com.clarklevis.dsh.shared.teamfeed.TeamFeedGlyph

/** vc61 ③：眼睛椭圆尺寸（px）——w/2=h/2 下界 0.5pt（iOS `eyeLayout` 同规），纯函数便于判据。 */
internal fun teamGlyphEyeSize(rx: Double, ry: Double, scale: Float): Pair<Float, Float> =
    Pair(
        maxOf(rx.toFloat() * 2f * scale, 0.5f),
        maxOf(ry.toFloat() * 2f * scale, 0.5f),
    )

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
            // vc61 ③：眼睛是**椭圆**——网关五元组 [cx, cy, w, h, tilt]，rx=w/2、ry=h/2，
            // 画法对齐 iOS `TeamAvatarView`（RoundedRectangle w×h + rotationEffect(tilt)）。
            // 之前 drawCircle(rx) 忽略 ry/tilt，把高瘦眼画成小圆点（用户 2026-11-04 真机反馈）。
            // 下界对齐 iOS eyeLayout（max(dim, 0.5)）防 0 尺寸图形不渲染。
            val cx = origin.x + eye.cx.toFloat() * scale
            val cy = origin.y + eye.cy.toFloat() * scale
            val size = teamGlyphEyeSize(eye.rx, eye.ry, scale)
            rotate(degrees = eye.tilt.toFloat(), pivot = Offset(cx, cy)) {
                drawOval(
                    color = backdropColor,
                    topLeft = Offset(cx - size.first / 2f, cy - size.second / 2f),
                    size = Size(size.first, size.second),
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
