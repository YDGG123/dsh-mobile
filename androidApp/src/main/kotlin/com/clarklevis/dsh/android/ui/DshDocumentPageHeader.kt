package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.localization.appText

internal data class DshDocumentColors(val background: Color, val foreground: Color, val close: Color,
    val header: Color, val alternate: Color, val border: Color, val toolbar: Color, val icon: Color)

@Composable
internal fun dshDocumentColors(): DshDocumentColors = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
    DshDocumentColors(Color.Black, Color.White, Color(0xFF252528), Color(0xFF252527),
        Color(0xFF111113), Color(0xFF353538), Color(0xFF1C1C1E), Color(0xFFAAAAB0))
} else {
    DshDocumentColors(Color.White, Color.Black, Color(0xFFF5F5F7), Color(0xFFEFEFF0),
        Color(0xFFF8F8FA), Color(0xFFDADADD), Color(0xFFF5F5F7), Color(0xFF65656B))
}

/** 横屏挖孔仅占一侧时，两侧使用相同安全边距，页头仍相对屏幕居中。 */
@Composable
internal fun Modifier.documentPageInsets(): Modifier {
    val insets = WindowInsets.safeDrawing
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val horizontal = with(density) {
        maxOf(insets.getLeft(density, direction), insets.getRight(density, direction)).toDp()
    }
    return windowInsetsPadding(insets.only(WindowInsetsSides.Vertical)).padding(horizontal = horizontal)
}

/** 与 iOS DocumentPageHeader 使用同一套 60/20/64/44 尺寸及居中标题。 */
@Composable
internal fun DshDocumentPageHeader(title: String, closeTag: String, onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}) {
    val colors = dshDocumentColors()
    Box(Modifier.fillMaxWidth().height(60.dp).background(colors.background).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground,
            maxLines = 1, modifier = Modifier.padding(horizontal = 90.dp).testTag("document-page-title"))
        TextButton(onClick = onClose, shape = CircleShape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.foreground, containerColor = colors.close),
            modifier = Modifier.align(Alignment.CenterStart).size(64.dp, 44.dp).testTag(closeTag)) {
            Text(appText("关闭"), fontSize = 17.sp, fontWeight = FontWeight.Normal, maxLines = 1, softWrap = false)
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}


/** 独立窗口会重建平台配置；显式保留应用外观与字号，避免正文和页头出现不同深浅模式。 */
@Composable
internal fun DshDocumentDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalConfiguration provides configuration,
            androidx.compose.ui.platform.LocalDensity provides density
        ) {
            DshMenuHost {
                val view = androidx.compose.ui.platform.LocalView.current
                val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                androidx.compose.runtime.SideEffect {
                    window?.let {
                        it.statusBarColor = android.graphics.Color.TRANSPARENT
                        it.navigationBarColor = android.graphics.Color.TRANSPARENT
                        if (android.os.Build.VERSION.SDK_INT >= 29) it.isNavigationBarContrastEnforced = false
                        androidx.core.view.WindowCompat.getInsetsController(it, view).apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                    }
                }
                content()
            }
        }
    }
}
