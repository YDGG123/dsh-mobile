package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.clarklevis.dsh.android.localization.appText

@Composable
internal fun RenameSessionDialog(
    title: String,
    onTitleChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: () -> Unit
) {
    val canSave = title.isNotBlank()
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0.18f)
        }
        // 先采样页面，再建立弹窗内的文字菜单宿主，避免玻璃采样自身。
        Box(
            Modifier.padding(24.dp)
                .adaptiveContentWidth(320.dp)
                .dshLightDialogGlass(RoundedCornerShape(38.dp))
                .testTag("rename-session-dialog")
        ) {
            // 必须在 Dialog 内建立独立宿主；继承页面宿主会在长按选区坐标转换时崩溃。
            DshMenuHost {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = (adaptiveWindowSize().height - 48.dp).coerceAtLeast(48.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, top = 22.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    Text(
                        appText("重命名会话"),
                        modifier = Modifier.fillMaxWidth().semantics { heading() },
                        color = DshColors.Ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    val fieldLabel = appText("会话名称")
                    BasicTextField(
                        value = title,
                        onValueChange = onTitleChange,
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(CircleShape)
                            .background(DshColors.Ink.copy(alpha = 0.16f))
                            .semantics { contentDescription = fieldLabel }
                            .testTag("rename-session-title")
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        textStyle = TextStyle(color = DshColors.Ink, fontSize = 17.sp),
                        cursorBrush = SolidColor(DshColors.Ocean),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (canSave) onSave() }),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (title.isEmpty()) {
                                    Text(fieldLabel, color = DshColors.Ink.copy(alpha = 0.5f), fontSize = 17.sp)
                                }
                                innerTextField()
                            }
                        }
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RenameDialogButton(appText("取消"), Modifier.weight(1f), onClick = onDismissRequest)
                        RenameDialogButton(appText("保存"), Modifier.weight(1f), enabled = canSave, onClick = onSave)
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialogButton(
    label: String,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier.heightIn(min = 48.dp).clip(CircleShape)
            .background(DshColors.Ink.copy(alpha = if (enabled) 0.13f else 0.06f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = DshColors.Ink.copy(alpha = if (enabled) 1f else 0.38f),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}
