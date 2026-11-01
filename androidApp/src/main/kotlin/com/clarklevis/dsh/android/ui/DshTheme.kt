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
 * 压在 HarnessAnimatedBackground 上的前景墨色（首页专用）。
 * 深色档 = 白×alpha（原样）；浅色档 = Ink×（×0.9 压暗，夹取 [0.06,0.92]）。
 * 对应 Chief 2026-11-01 映射表：1.0→0.92 / 0.70→0.65 / 0.65→0.60 / 0.58→0.50 / 0.55→0.50 / 0.50→0.45 / 0.10→0.08
 * （统一公式 ×0.9 与表内采样误差 ≤0.02；对照浅底 #E4EAF4~#F9FAFC，Ink@0.9 对比度 ≈ 13:1 ≥ 4.5）。
 */
@Composable
internal fun homeInk(alpha: Float = 1f): Color =
    if (LocalDshIsDark.current) {
        Color.White.copy(alpha = alpha)
    } else {
        DshColors.Ink.copy(alpha = (alpha * 0.92f).coerceIn(0.06f, 0.92f))
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
