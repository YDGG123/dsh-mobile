package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

/** 模式与统计弹窗共用阴影、毛玻璃、箭头，以及从胶囊中心展开的动画。 */
@Composable
internal fun ComposerGlassPopover(
    expanded: Boolean,
    onDismiss: () -> Unit,
    testTag: String,
    width: Dp = 300.dp,
    alignToEnd: Boolean = true,
    maximumContentHeight: Dp = Dp.Infinity,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
    arrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val shadowPadding = with(density) { 16.dp.roundToPx() }
    val gap = with(density) { 6.dp.roundToPx() }
    var placement by remember { mutableStateOf<SessionStatsPopoverPlacement?>(null) }
    val positionProvider = remember(shadowPadding, gap, alignToEnd) {
        SessionStatsPopoverPositionProvider(shadowPadding, gap, alignToEnd) { placement = it }
    }
    val transformOrigin = placement?.transformOrigin ?: TransformOrigin(0.5f, 1f)
    val pointsUp = placement?.pointsUp == true
    val surfaceWidth = minOf(width, with(density) { windowSize.width.toDp() - 32.dp }).coerceAtLeast(1.dp)
    val availableHeight = with(density) {
        (placement?.surfaceHeightLimit ?: (windowSize.height - shadowPadding * 2)).coerceAtLeast(1).toDp()
    }
    val contentHeightLimit = minOf(maximumContentHeight, (availableHeight - 8.dp).coerceAtLeast(1.dp))
    val shape = SessionStatsPopoverShape(
        arrowX = placement?.arrowX ?: with(density) { surfaceWidth.toPx() / 2 }, pointsUp = pointsUp)
    DshMenuPopup(expanded, onDismiss, positionProvider,
        modifier = Modifier.width(surfaceWidth).testTag(testTag),
        transformOrigin = transformOrigin, shape = shape, shadowPadding = 16.dp,
        properties = PopupProperties(focusable = expanded, clippingEnabled = false)) {
        Column(Modifier.padding(top = if (pointsUp) 8.dp else 0.dp, bottom = if (pointsUp) 0.dp else 8.dp)
            .heightIn(max = contentHeightLimit).verticalScroll(rememberScrollState()).padding(contentPadding),
            verticalArrangement = arrangement, content = content)
    }
}
