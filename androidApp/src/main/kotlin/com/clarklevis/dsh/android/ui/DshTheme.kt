package com.clarklevis.dsh.android.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

internal object DshColors {
    val Navy = Color(0xFF06172B)
    val NavyRaised = Color(0xFF0D2440)
    val Ocean = Color(0xFF2E6BE6)

    /** 品牌选中底·深色档 #4D8CFF（iOS Theme.swift:462-464 p.brand 深 `Color(0.30,0.55,1.0)`）。别拿 DarkColors.primary(0xFF7EA8FF) 顶替。 */
    val OceanDark = Color(0xFF4D8CFF)
    val Mist = Color(0xFFBFD6FF)
    val Ink = Color(0xFF0E131A)
    val Paper = Color(0xFFF9FAFC)
    val Purple = Color(0xFF7A54C7)
    val Orange = Color(0xFFF07D14)
    val Amber = Color(0xFFFFAD1F)
    val Success = Color(0xFF2EB85C)
    /** danger 深色档：iOS `#FF0000 @ 90%`。 */
    val Danger = Color(0xE6FF0000)
    // 语义色浅色档（iOS Theme.swift:150-167，浅色 AA≥3:1/4.5:1 压暗值）：
    val SuccessLight = Color(0xFF126B2E)
    val AmberLight = Color(0xFF8C5200)
    val OrangeLight = Color(0xFF9E4D00)
    val DangerLight = Color(0xFFB81F1A)
}

/** 语义色四态按深浅色选档（iOS DSHStatusKind：warning=Amber、caution=Orange）。 */
internal class DshSemanticColors(
    val success: Color,
    val warning: Color,
    val caution: Color,
    val danger: Color,
)

internal val LocalDshSemanticColors = staticCompositionLocalOf {
    DshSemanticColors(DshColors.Success, DshColors.Amber, DshColors.Orange, DshColors.Danger)
}

/** 全 App 统一深浅色有效值（含 AppearanceSettings 覆写）：双色值场景（如 tab bar 阴影 0.24/0.10）用。 */
internal val LocalDshIsDark = staticCompositionLocalOf { true }

/** 组件内取语义色：`DshSemantic.success/warning/caution/danger`，自动随深浅色换档（对齐 iOS 语义色双档）。 */
internal object DshSemantic {
    val success: Color @Composable get() = LocalDshSemanticColors.current.success
    val warning: Color @Composable get() = LocalDshSemanticColors.current.warning
    val caution: Color @Composable get() = LocalDshSemanticColors.current.caution
    val danger: Color @Composable get() = LocalDshSemanticColors.current.danger
}


/**
 * 首页前景/容器/描边/填充色 —— **逐值对齐 iOS `Theme.swift:181-220` 的 DSHPalette**。
 *
 * iOS 原注释（Theme.swift:174-190）：浅色下 ink 按 `0.35 + o*0.65` 重算 —— 起点 0.55→0.35
 * 让次要文字与元信息重新拉开层次；正文（0.92 档）几乎不变（15.44→14.82 对比度）；
 * 最淡档仍有 3.88:1（WCAG 大字标准），0.40 往上全部过 AA。**0.35 地板不是随手写的，别省。**
 *
 * 对照（iOS/Android 同公式 → 对比度应逐档一致）：正文 ~14.8:1 / 次要 ~7:1 / 最淡 3.88:1。
 */
@Composable
internal fun homeInk(alpha: Float = 1f): Color =
    if (LocalDshIsDark.current) {
        Color.White.copy(alpha = alpha)
    } else {
        DshColors.Ink.copy(alpha = (0.35f + alpha * 0.65f).coerceAtMost(1f))
    }

/** 容器填充（iOS surface）：浅色不用低透明白（白底上等于没有），用高不透明白衬底。 */
/**
 * iOS **系统语义色档**（`label` / `secondaryLabel` / `tertiaryLabel`）的 Android 等价物。
 *
 * **基底是纯黑（深色纯白），不是品牌墨 `#0E131A`**：iOSDev 2026-11-01 实测，白底上
 * `alpha 1.0/0.60/0.30` 用纯黑是 **21.00 / 5.74 / 2.11**，用品牌墨只有 **18.64 / 4.87 / 1.98**
 * —— 后者会**系统性偏淡一档**（0.60 档差 0.87）。
 *
 * 适用范围：**对话页的消息正文/次要/最淡**（SwiftUI `.primary/.secondary/.tertiary`）。
 * **组件**（卡片/行/设置项）走 `homeInk/...` 那四支 palette 公式，别混用：
 * 两张网格的正文差 2.36 个对比度（21.00 vs 18.64），混用会一边改对一边改错。
 */
@Composable
internal fun semanticLabel(alpha: Float = 1f): Color =
    if (LocalDshIsDark.current) {
        // iOS 深色 `secondaryLabel` = (235,235,245)，不是纯白；tertiary 同基底 @0.30
        Color(0xFFEBEBF5).copy(alpha = alpha)
    } else {
        // iOS 浅色：`label` = 纯黑 @1.00（21.00）；
        // `secondaryLabel`/`tertiaryLabel` = **#3C3C43 (60,60,67)** 基底 @0.60/@0.30 → 3.44/1.73。
        // **不要用纯黑@0.60（5.74）或品牌墨@0.60（4.87）**：两者都比真机实测（次要簇 3.3）偏深一档 ——
        // Chief 2026-11-01 按系统语义色实算 + iOSTester 真机簇双向对账后的裁定。
        if (alpha >= 1f) Color.Black else Color(0xFF3C3C43).copy(alpha = alpha)
    }

@Composable
internal fun homeSurface(alpha: Float): Color =
    if (LocalDshIsDark.current) {
        Color.White.copy(alpha = alpha)
    } else {
        Color.White.copy(alpha = (0.72f + alpha * 1.6f).coerceAtMost(1f))
    }

/** 描边/分隔线（iOS stroke）：浅色需要真正可见的细线（≥0.06），否则边界在浅底上消失。 */
@Composable
internal fun homeStroke(alpha: Float): Color =
    if (LocalDshIsDark.current) {
        Color.White.copy(alpha = alpha)
    } else {
        DshColors.Ink.copy(alpha = (0.06f + alpha * 0.6f).coerceAtMost(0.3f))
    }

/** 填充/描边专用档（iOS veil）：**不要拿 ink 当底色** —— 浅色 ink 有 0.35 地板，
 *  `ink(0.07)` 当填充会变成 58% 近黑（iOS 注释记录的「分段控件灰底比白卡片重」就是这个坑）。
 *  veil 浅色最多 0.16；深色沿用白色叠加。 */
@Composable
internal fun homeVeil(alpha: Float): Color =
    if (LocalDshIsDark.current) {
        Color.White.copy(alpha = alpha)
    } else {
        DshColors.Ink.copy(alpha = (alpha * 0.9f).coerceAtMost(0.16f))
    }

@Composable
internal fun StatusIndicatorDot(
    color: Color,
    modifier: Modifier = Modifier,
    glowing: Boolean = false
) {
    val decoratedModifier = if (glowing) {
        modifier.dropShadow(
            shape = CircleShape,
            shadow = Shadow(
                radius = 5.dp,
                spread = 1.dp,
                color = color.copy(alpha = 0.28f),
                offset = DpOffset(0.dp, 0.dp)
            )
        )
    } else {
        modifier
    }
    Box(decoratedModifier.background(color, CircleShape))
}

private val LightColors = lightColorScheme(
    primary = DshColors.Ocean,
    secondary = DshColors.Purple,
    background = DshColors.Paper,
    surface = Color.White,
    onBackground = DshColors.Ink,
    onSurface = DshColors.Ink
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7EA8FF),
    secondary = Color(0xFFB69BFF),
    // 深色底 = iOS navy（Theme.swift:132），抬升面 = navyRaised（:133）—— 视觉规格 §1。
    background = DshColors.Navy,
    surface = DshColors.NavyRaised,
    onBackground = Color(0xFFF1F4FA),
    onSurface = Color(0xFFF1F4FA)
)

@Composable
internal fun DshTheme(
    appearance: AppearanceSettings = rememberAppearanceSettings(),
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    val systemDensity = LocalDensity.current
    val fontScale = appearance.effectiveFontScale
    val appDensity = remember(systemDensity, fontScale) { AppFontDensity(systemDensity, fontScale) }
    val dark = appearance.interfaceStyle.isDark(isSystemInDarkTheme())
    val configuration = Configuration(LocalConfiguration.current).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }
    // 所有页面的深浅色判断使用同一份有效配置，避免只更新 MaterialTheme 而留下浅色背景。
    val semantic = if (dark) {
        DshSemanticColors(DshColors.Success, DshColors.Amber, DshColors.Orange, DshColors.Danger)
    } else {
        DshSemanticColors(DshColors.SuccessLight, DshColors.AmberLight, DshColors.OrangeLight, DshColors.DangerLight)
    }
    CompositionLocalProvider(
        LocalAppearanceSettings provides appearance,
        LocalDensity provides appDensity,
        LocalConfiguration provides configuration
    ) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
            CompositionLocalProvider(
                LocalDshSemanticColors provides semantic,
                LocalDshIsDark provides dark,
                LocalRippleConfiguration provides null
            ) { DshMenuHost(content) }
        }
    }
}

// 保留 Android 的系统字号转换（包括非线性缩放），再应用用户选择的倍率。
internal class AppFontDensity(
    private val systemDensity: Density,
    private val appScale: Float
) : Density {
    override val density: Float = systemDensity.density
    override val fontScale: Float = systemDensity.fontScale * appScale

    override fun TextUnit.toDp() = with(systemDensity) { this@toDp.toDp() } * appScale

    override fun androidx.compose.ui.unit.Dp.toSp(): TextUnit =
        with(systemDensity) { (this@toSp / appScale).toSp() }
}

@Composable
internal fun dshSwitchColors(preserveDisabledColors: Boolean = false): SwitchColors {
    val blue = if (isSystemInDarkTheme()) Color(0xFF1D447F) else Color(0xFF245AC4)
    if (!preserveDisabledColors) return SwitchDefaults.colors(
        checkedTrackColor = blue,
        checkedBorderColor = blue,
        checkedThumbColor = Color.White
    )
    val uncheckedTrack = MaterialTheme.colorScheme.surfaceContainerHighest
    val uncheckedBorder = MaterialTheme.colorScheme.outline
    val uncheckedThumb = MaterialTheme.colorScheme.outline
    return SwitchDefaults.colors(
        checkedTrackColor = blue,
        checkedBorderColor = blue,
        checkedThumbColor = Color.White,
        uncheckedTrackColor = uncheckedTrack,
        uncheckedBorderColor = uncheckedBorder,
        uncheckedThumbColor = uncheckedThumb,
        disabledCheckedTrackColor = blue,
        disabledCheckedBorderColor = blue,
        disabledCheckedThumbColor = Color.White,
        disabledUncheckedTrackColor = uncheckedTrack,
        disabledUncheckedBorderColor = uncheckedBorder,
        disabledUncheckedThumbColor = uncheckedThumb
    )
}
