package com.clarklevis.dsh.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.localization.appText
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay

/** 两页常驻：页头「两者都要」（用户 2026-11-05 拍板）——
 *  **流内**：顶栏占位 → 副行（subtitle 插槽）→ 分段「对话|轨迹」（我方 vc38 形态，正文从分段下方开始）；
 *  **overlay**：上游 `ConversationPageIndicator` 瞬态圆点（TopCenter，不占布局高度）与顶栏玻璃。 */
@Suppress("LongParameterList") // Compose 容器显式接收状态、修饰符及两个内容插槽。
@Composable
internal fun ConversationPagerChrome(
    pagerState: PagerState,
    sessionId: String?,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit,
    subtitle: (@Composable () -> Unit)? = null,
    page: @Composable (Int, Dp) -> Unit
) {
    val density = LocalDensity.current
    val initialTopInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() } + 64.dp
    var topInset by remember(density) { mutableStateOf(initialTopInset) }
    val backdrop = rememberLayerBackdrop()
    val currentPage = pagerState.currentPage
    val pageName = appText(if (currentPage == 0) "对话" else "轨迹")
    val switchLabel = appText(if (currentPage == 0) "切换到轨迹" else "切换到对话")
    val pagerLabel = appText("会话页面")
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(
            modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        ) {
            Column(Modifier.fillMaxSize()) {
                // 顶栏占位：与页面同底色（我方 vc38 裁决：正文不穿到栏下）。
                Spacer(Modifier.fillMaxWidth().height(topInset))
                subtitle?.invoke()
                ConversationPageSwitch(
                    selectedPage = currentPage,
                    onSelectPage = onSelectPage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxSize().weight(1f).layerBackdrop(backdrop)
                        .testTag("conversation-pager")
                        .semantics {
                            contentDescription = pagerLabel
                            stateDescription = pageName
                            customActions = listOf(
                                CustomAccessibilityAction(switchLabel) {
                                    onSelectPage(1 - currentPage)
                                    true
                                }
                            )
                        }
                ) { index ->
                    Box(
                        Modifier.fillMaxSize().semantics {
                            if (index != currentPage) hideFromAccessibility()
                        }
                    ) {
                        // 正文不再为顶栏让位：分段已在流内（vc38）。
                        page(index, 0.dp)
                    }
                }
            }
            val glassProtectedHeight = topInset
            // 消息从顶栏下方 8dp 开始，渐隐必须在这段留白内结束，避免覆盖首条气泡。
            val glassFadeHeight = 8.dp
            Box(
                Modifier.fillMaxWidth().height(glassProtectedHeight + glassFadeHeight)
                    .clipToBounds()
                    .conversationTopGlass(backdrop, glassProtectedHeight, glassFadeHeight)
                    .semantics { hideFromAccessibility() }
                    .testTag("conversation-top-glass")
            )
            Box(Modifier.fillMaxWidth().onSizeChanged { topInset = with(density) { it.height.toDp() } }) {
                topBar()
            }
            // 上游指示条保留（overlay，不占布局）：页头「两者都要」的另一半。
            ConversationPageIndicator(
                sessionId,
                currentPage,
                Modifier.align(Alignment.TopCenter).padding(top = topInset + 4.dp)
            )
        }
    }
}

@Composable
internal fun ConversationPageTitle(title: String, selectedPage: Int, status: (@Composable () -> Unit)? = null) {
    val pageName = appText(if (selectedPage == 0) "对话" else "轨迹")
    Column(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .semantics { stateDescription = pageName }
            .testTag("conversation-page-title"),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
    ) {
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = if (status == null) 17.sp else 16.sp,
            lineHeight = if (status == null) TextUnit.Unspecified else 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("conversation-title-text")
        )
        status?.invoke()
    }
}

@Composable
private fun ConversationPageIndicator(sessionId: String?, selectedPage: Int, modifier: Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(sessionId, selectedPage) {
        alpha.snapTo(1f)
        delay(2_000)
        alpha.animateTo(0f, tween(300))
    }
    if (alpha.value > 0f) {
        Row(
            modifier.graphicsLayer { this.alpha = alpha.value }
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("conversation-page-indicator")
                .semantics { hideFromAccessibility() },
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            repeat(2) { index ->
                Box(
                    Modifier.size(5.dp).background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = if (index == selectedPage) 0.65f else 0.2f),
                        CircleShape
                    )
                )
            }
        }
    }
}

/** iOS `DSHPalette.cardFill`（Theme.swift:425）：浅色白 0.55 / 深色白 0.06。 */
@Composable
private fun segmentedSelectedFill(): Color =
    if (LocalDshIsDark.current) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.55f)

/**
 * 会话页分段「对话 | 轨迹」——**逐值对齐 iOS `DSHSegmented`（Theme.swift:1276-1312）**：
 *
 * ```
 * 外层 Capsule 底 = veil(0.06) + padding 3 ｜ 项间距 xxs = 2 ｜ 每项 minHeight 30
 * 文字 footnote 13：选中 semibold + ink(0.92) ｜ 未选 regular + ink(0.55)
 * 选中底 = RoundedRectangle(12) 填 cardFill + 阴影（浅色 0.08 / 半径 3 / y 1）
 * ```
 *
 * vc62 合并漏项（Chief 2026-11-06 派回）：上游解冲突时把我方实现解没了、只剩注释，
 * 而我方设备测试还在断言它存在。自 2007de4 原样取回；与上游指示条并存（页头「两者都要」）。
 */
@Composable
private fun ConversationPageSwitch(selectedPage: Int, onSelectPage: (Int) -> Unit, modifier: Modifier = Modifier) {
    val isDark = LocalDshIsDark.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .testTag("conversation-page-switch")
            .background(homeVeil(0.06f), CircleShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(0 to appText("对话"), 1 to appText("轨迹")).forEach { (index, label) ->
            val selected = index == selectedPage
            Box(
                Modifier.weight(1f).heightIn(min = 30.dp)
                    .then(
                        if (selected) {
                            Modifier.dropShadow(
                                shape = shape,
                                shadow = Shadow(
                                    radius = 3.dp,
                                    color = Color.Black.copy(alpha = if (isDark) 0f else 0.08f),
                                    offset = DpOffset(x = 0.dp, y = 1.dp)
                                )
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clip(shape)
                    .background(if (selected) segmentedSelectedFill() else Color.Transparent)
                    .clickable { onSelectPage(index) }
                    .testTag("conversation-page-switch-$index"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = homeInk(if (selected) 0.92f else 0.55f),
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}
