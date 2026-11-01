package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clarklevis.dsh.android.R

/**
 * 品牌字标（**一张图 + 按主题反相**），对齐 iOS `HarnessMark`（Components/Glass.swift:186-203）。
 *
 * iOS 的形态是 `Image("OrcarnessHarnessWordmark").colorInvertIfNeeded(onDarkBackground)`
 * —— 三个调用点全部传 `colorScheme == .dark`。**不是两张图选 variant**：
 * 选 variant 正是首页当初硬编码 `dsh_orcarness_wordmark_dark`、浅色下白字压白底的根因
 * （iOS `WorkspaceView.swift:934-936` 注释 B6 记着同一型错：「以前默认 onDarkBackground=true…浅色下白底白字整块看不见」）。
 *
 * 高度恒 29（iOS 三处都一样），宽度由调用方给：首页 216 / 抽屉 min(抽屉宽−48, 216) / 会话侧栏 116。
 */
@Composable
internal fun OrcarnessWordmark(width: Dp, modifier: Modifier = Modifier, height: Dp = 29.dp) {
    val dark = LocalDshIsDark.current
    Image(
        painter = painterResource(R.drawable.dsh_orcarness_wordmark),
        contentDescription = null,
        colorFilter = if (dark) ColorFilter.colorMatrix(OrcarnessWordmarkInvertMatrix) else null,
        modifier = modifier.size(width = width, height = height)
    )
}

/** iOS `colorInvertIfNeeded` 的 Android 等价物：RGB 取负 + 255 偏移，alpha 原样保留。
 *  Compose ColorMatrix 的偏移列是 0–255 量纲（不是 0–1），所以写 255。 */
internal val OrcarnessWordmarkInvertMatrix = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    )
)