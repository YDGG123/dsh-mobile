package com.clarklevis.dsh.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText

/**
 * B1 只读原型：首页底部 tab bar（Android 原本整条缺失，对齐清单 1.1）。
 * Bot tab 的指向：**对应 iOS 的 sheet 内容（bridge 名册），而非 iOS tab 内容** ——
 * iOS 的 Bot 有两个入口（WorkspaceView:321/326 tab 是 feed 驱动；:189/660 sheet 才是
 * bridge 名册），本条 bar 的 Bot tab 打开的是 sheet 那一份（即本仓 BotTeamScreen）。
 * 数值全量来自 iOS `DSHTabBar`（Core/Theme.swift:1003-1091），验收表 =
 * android-visual-spec.md §4 + AndroidTester 逐值核对：
 * 总高 62 = 54 + 2×4（xs）、圆角 31 = totalHeight/2、水平外边距 16（lg）、
 * 项间距 4、图标 15（DSHType.body 字号位）、文字 11 medium（DSHType.meta）、
 * 图标↔文字竖距 3、单项 min 高 54、选中 = Capsule 品牌底白字、未选中 ink(0.55)、
 * 阴影深 0.24 / 浅 0.10。
 *
 * 描边裁决：iOS 16–25 材质分支补 cardStroke 描边（:1070-1074）、iOS 26 glassEffect
 * 自带边缘不叠线 —— Android 的 `dshLiquidGlass` 在 S+ 是真背景模糊（自带高光边缘），
 * **跟 iOS 26 支不加描边**；SDK < S 回退路径自带 0.8dp 白边（等价旧支的边界需求）。
 *
 * 只读原型范围（Chief 定）：仅选中态，内容切换随 C/D 组接线；角标三来源
 * （审批+提问+bridge，WorkspaceView :366-370）Android 只有 bridge 且暂无
 * pending 数据 → 现阶段传空，完整角标等 D 组。
 */
internal enum class DshHomeTab(@DrawableRes val iconRes: Int, val label: String) {
    WORKSPACE(R.drawable.ic_tab_workspace, "工作区"),
    BOTS(R.drawable.ic_tab_bots, "Bot"),
    SETTINGS(R.drawable.ic_tab_tune, "设置")
}

@Composable
internal fun DshTabBar(
    selected: DshHomeTab,
    badges: Map<DshHomeTab, Int>,
    onSelect: (DshHomeTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = LocalDshIsDark.current
    Row(
        modifier
            .testTag("home-tab-bar")
            // 水平外边距 16（iOS :1077 .padding(.horizontal, DSHSpacing.lg)）——玻璃面外侧，整条不贴边。
            .padding(horizontal = 16.dp)
            // 阴影：iOS glassSurface shadowOpacity 深 0.24 / 浅 0.10（:1075）。半径验收清单未给值，取 16dp 待真机校准。
            .dropShadow(
                shape = RoundedCornerShape(percent = 50),
                shadow = Shadow(
                    radius = 16.dp,
                    color = Color.Black.copy(alpha = if (dark) 0.24f else 0.10f),
                    offset = DpOffset(0.dp, 6.dp)
                )
            )
            .dshLiquidGlass(RoundedCornerShape(percent = 50))
            // 内层 padding(xs)=4：总高 62 = 4 + 54 + 4（iOS :1073/:1015）
            .padding(4.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp) // 项间距 4 = DSHSpacing.xs（iOS :1029）
    ) {
        DshHomeTab.entries.forEach { tab ->
            DshTabItem(
                tab = tab,
                active = tab == selected,
                badge = badges[tab] ?: 0,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f) // 三项等宽（iOS :1046 maxWidth infinity）
            )
        }
    }
}

@Composable
private fun DshTabItem(
    tab: DshHomeTab,
    active: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = LocalDshIsDark.current
    // 未选中色分档（iOS :181-184）：深 Color.white.opacity(0.55)；浅 ink×0.7075（=0.35+0.55×0.65）。
    val tint = when {
        active -> Color.White
        dark -> Color.White.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7075f)
    }
    Column(
        modifier
            .testTag("home-tab-${tab.name.lowercase()}")
            .height(54.dp) // 单项最小高 54（iOS :1046）
            .clip(RoundedCornerShape(percent = 50)) // Capsule（iOS :1047）
            .background( // 选中品牌底分档：浅 ocean #2E6BE6 / 深 #4D8CFF（iOS :462-464）
                if (active) (if (dark) DshColors.OceanDark else DshColors.Ocean) else Color.Transparent
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically) // 图标↔文字竖距 3（iOS :1034）
    ) {
        Box {
            // 图标两维度（iOS :1036 .appFont(DSHType.body.weight(.medium))）：
            // ① 字重 —— iOS 由 SF Symbol（字体符号）承担 medium；Android 矢量 drawable 无字重维度，
            //    接受差异（追平需整套图标按 medium 重导，成本不成比例）——已知平台差异。
            // ② 缩放 —— iOS appFont 让图标随用户字号档 0.8–1.5 缩放（Theme.swift:85-87）；
            //    Android dp 不随 AppFontDensity 缩放，此处按 fontScale 乘出尺寸跟随（否则调字号后图文比例失配）。
            val fontScale = LocalDensity.current.fontScale
            Icon(
                painterResource(tab.iconRes),
                contentDescription = appText(tab.label),
                modifier = Modifier.size((15 * fontScale).dp), // 基准 15 = DSHType.body 字号位（:1036）
                tint = tint
            )
            if (badge > 0 && !active) {
                // 角标挂图标右上（用户第五轮反馈，不挂整个按钮），与图标留固定间隙
                //（用户拍板 7pt = DSHTabBadge.iconGap）。B1 原型先落位置，
                // DSHTabBadge 完整样式（两位数收窄防顶进胶囊圆角）待真机校准批次。
                Text(
                    badge.toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // 固定 7pt 间隙（iOS :1085+ 定稿 iconGap=7：按宽度算会让两位数顶进胶囊圆角被裁）。
                        // 轴向假设：徽标整体移出图标右上角各 7dp，两位数再往右自然延伸，待真机校准。
                        .offset(x = 7.dp, y = (-7).dp)
                        .background(DshSemantic.danger, RoundedCornerShape(percent = 50))
                        .padding(horizontal = 3.dp)
                )
            }
        }
        Text(
            appText(tab.label),
            color = tint,
            style = DshType.meta // 11sp medium（iOS DSHType.meta :1042）
        )
    }
}
