package com.clarklevis.dsh.android.ui

import android.text.method.ArrowKeyMovementMethod
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
import com.clarklevis.dsh.android.localization.appText

/** 全文快照放在独立窗口中选择，不改动聊天列表的分块、排版和滚动位置。 */
@Composable
internal fun DshMarkdownSelectionPage(markdown: String, onDismiss: () -> Unit) {
    val configuration = LocalConfiguration.current
    val dark = configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    val background = if (dark) Color.Black else Color.White
    val foreground = if (dark) Color.White else Color.Black
    val buttonBackground = Color(if (dark) 0xFF242426 else 0xFFF5F5F7)
    val accent = if (dark) DshSelectionStyle.DARK_ACCENT else DshSelectionStyle.LIGHT_ACCENT
    val codeBackground = if (dark) DshSelectionStyle.DARK_CODE else DshSelectionStyle.LIGHT_CODE
    val density = LocalDensity.current
    val fontSizePx = with(density) { DshSelectionStyle.BODY_SIZE.sp.toPx() }
    val padding = with(density) { DshSelectionStyle.HORIZONTAL_INSET.dp.roundToPx() }
    val lineSpacing = with(density) { 4.dp.toPx() }
    var selectionAnchor by remember { mutableStateOf<DshSelectionToolbarAnchor?>(null) }
    var selectionView by remember { mutableStateOf<DshMarkdownTextView?>(null) }
    val rendered = remember(markdown, fontSizePx, density.density, dark) {
        renderMarkdownSelectionText(markdown, fontSizePx, density.density, codeBackground, accent, 0xFF8E8E93.toInt())
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false
    )) {
        DshMenuHost {
            val dialogView = LocalView.current
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            SideEffect {
                window?.let {
                    WindowCompat.getInsetsController(it, dialogView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
            }
            DshMenuBackdropHost { backdrop ->
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().then(backdrop).background(background)
                        .statusBarsPadding().navigationBarsPadding().testTag("markdown-selection-page")) {
                        Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            Text(appText("选择文字"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = foreground)
                            TextButton(onClick = onDismiss, shape = CircleShape,
                                colors = ButtonDefaults.textButtonColors(contentColor = foreground, containerColor = buttonBackground),
                                modifier = Modifier.align(Alignment.CenterStart).sizeIn(minWidth = 64.dp, minHeight = 44.dp)
                                    .testTag("markdown-selection-close")) {
                                Text(appText("关闭"), fontSize = 17.sp, fontWeight = FontWeight.Normal)
                            }
                        }
                        AndroidView(factory = { viewContext ->
                            DshMarkdownTextView(android.view.ContextThemeWrapper(viewContext.createConfigurationContext(configuration),
                                com.clarklevis.dsh.android.R.style.ThemeOverlay_MarkdownSelection)).apply {
                                isSelectionPage = true
                                onSelectionToolbarChanged = { selectionAnchor = it }
                                selectionView = this
                                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontSizePx)
                                applySelectionColors(foreground.toArgb(), accent)
                                setPadding(padding, with(density) { DshSelectionStyle.TOP_INSET.dp.roundToPx() },
                                    padding, with(density) { DshSelectionStyle.BOTTOM_INSET.dp.roundToPx() })
                                includeFontPadding = false
                                setLineSpacing(lineSpacing, 1f)
                                gravity = Gravity.TOP or Gravity.START
                                isVerticalScrollBarEnabled = true
                                text = rendered
                                tag = rendered
                                // 选择页内链接作为正文选择，点击不跳离当前页面。
                                movementMethod = ArrowKeyMovementMethod.getInstance()
                                contentDescription = appText("选择文字")
                                doOnLayout { beginTextSelection() }
                            }
                        }, update = { view ->
                            view.applySelectionColors(foreground.toArgb(), accent)
                            if (view.tag !== rendered) {
                                val start = view.selectionStart
                                val end = view.selectionEnd
                                val scroll = view.scrollY
                                val selecting = view.hasSelectionActionMode
                                view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontSizePx)
                                view.text = rendered
                                view.tag = rendered
                                if (selecting && start >= 0 && end > start && end <= view.text.length) {
                                    view.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION,
                                        android.os.Bundle().apply {
                                            putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                                            putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
                                        })
                                }
                                view.doOnLayout { it.scrollTo(0, scroll) }
                            }
                        }, onRelease = {
                            it.onSelectionToolbarChanged = null
                            it.resetTextInteraction()
                            selectionView = null
                            selectionAnchor = null
                        },
                            modifier = Modifier.fillMaxWidth().weight(1f))
                    }
                    selectionAnchor?.let { anchor ->
                        DshSelectionToolbar(anchor,
                            onCopy = { selectionView?.onTextContextMenuItem(android.R.id.copy) },
                            onSelectAll = { selectionView?.onTextContextMenuItem(android.R.id.selectAll) })
                    }
                }
            }
        }
    }
}

private fun DshMarkdownTextView.applySelectionColors(foreground: Int, accent: Int) {
    setTextColor(foreground)
    setLinkTextColor(accent)
    highlightColor = (accent and 0x00FFFFFF) or 0x33000000
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        textSelectHandleLeft?.mutate()?.let { it.setTint(accent); setTextSelectHandleLeft(it) }
        textSelectHandleRight?.mutate()?.let { it.setTint(accent); setTextSelectHandleRight(it) }
        textSelectHandle?.mutate()?.let { it.setTint(accent); setTextSelectHandle(it) }
    }
}
