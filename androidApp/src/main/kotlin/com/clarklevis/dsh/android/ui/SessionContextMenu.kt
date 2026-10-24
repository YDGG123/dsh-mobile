package com.clarklevis.dsh.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.R

/** 与 iOS 会话菜单对齐：浅色圆角面板、前置图标及红色归档操作。 */
@Composable
internal fun SessionContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit
) {
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    val positionProvider = remember(margin) { SessionMenuPositionProvider(margin) }
    DshMenuPopup(expanded, onDismissRequest, positionProvider,
        Modifier.width(260.dp).testTag("session-context-menu"), shadowPadding = 16.dp) {
        Column(Modifier.padding(vertical = 8.dp)) {
            SessionMenuAction(appText("重命名"), R.drawable.ic_session_rename,
                androidx.compose.material3.MaterialTheme.colorScheme.onSurface, onRename)
            SessionMenuAction(appText("删除（归档）"), R.drawable.ic_session_archive, Color(0xFFFF3B30), onArchive)
        }
    }
}

@Composable
private fun SessionMenuAction(title: String, @DrawableRes icon: Int, tint: Color, onClick: () -> Unit) {
    DshMenuItem(text = { Text(title, color = tint, fontSize = 17.sp, lineHeight = 22.sp) },
        leadingIcon = { Icon(painterResource(icon), null, Modifier.size(20.dp), tint = tint) },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 28.dp, vertical = 10.dp), onClick = onClick)
}

private class SessionMenuPositionProvider(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(0)
        val above = anchorBounds.top - popupContentSize.height
        val preferredY = if (above >= margin) above else anchorBounds.bottom
        return IntOffset(
            ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                .coerceIn(margin.coerceAtMost(maxX), maxX),
            preferredY.coerceIn(margin.coerceAtMost(maxY), maxY)
        )
    }
}
