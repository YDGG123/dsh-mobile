package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.protocol.BridgeCardQuestion

/**
 * 桥接卡片（E1）：`kind:"view"` 出「方案对比」这类正文卡，question/secret 出
 * 「等你回答／等你填密钥」行 —— 文案与 iOS `statusText`（`GatewayTeamFeed.swift:1179`）一致。
 * 纪律同 iOS：**卡片只能看**（bridge 无答复通道），必须标明在电脑端处理。
 *
 * ## 页面归属（2026-11-02，Chief 定 A 方案）
 * 整张卡**只在会话页**渲染（iOS `ConversationView:543` 的 `questionCard`，钉在输入区上方）；
 * Bot 页只保留行内只读预览一句（`WorkspaceView:719-726`）。
 * 判据：Bot 页 0 整张卡（C5）｜会话页真看得见（C4）｜同一张卡归属唯一（C6）。
 *
 * ⚠️ 这个文件原先的实现写在 `BotTeamScreen.kt` 里（`BotTeamScreen.kt:304`，私有），
 * 那不是"能复用"，是**放错页**。别再把它挂回 Bot 页来"省事"。
 */
@Composable
internal fun BridgeCardRow(
    card: BridgeCardQuestion,
    ownerName: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("bridge-card-${card.kind}")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ownerName ?: card.owner,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(card.statusText, color = homeInk(0.7f), fontSize = 12.sp)
        }
        if (card.prompt.isNotBlank()) {
            Text(
                card.prompt,
                color = homeInk(0.85f),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (card.options.isNotEmpty()) {
            Text(
                card.options.joinToString(" / "),
                color = homeInk(0.55f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            appText("在电脑端处理"),
            color = homeInk(0.5f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * 某会话该显示哪几张卡（C6 的机器判据，纯函数、可单测）。
 *
 * 归属按 `card.sessionId`（宿主会话 id，与 `snapshot.selectedSessionId` 同一命名空间）：
 * - 会话 id 为空（新建会话/尚无会话）⇒ 一张都不显示（**不许凭空出卡**）；
 * - 卡没有 `sessionId`（无主卡，`bridgeCardOwner` 只解出 owner）⇒ 不属于任何会话，不渲染；
 * - 两张卡分属不同会话 ⇒ 各在自己的会话里出现，不会串。
 */
internal fun bridgeCardsForSession(
    cards: List<BridgeCardQuestion>,
    sessionId: String?
): List<BridgeCardQuestion> {
    val target = sessionId?.takeIf { it.isNotBlank() } ?: return emptyList()
    return cards.filter { it.sessionId == target }
}