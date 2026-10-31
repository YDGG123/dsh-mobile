package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.AndroidBridgeTeamAdapter
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.domain.TeamPhase
import com.clarklevis.dsh.shared.protocol.BridgeConversationRow
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bot 团队页：Mobile Bridge 的 `/info` + `/state` + `/conversations`（+ `/cards`）
 * 合流后渲染真实名册。行语义逐项对照 iOS `TeamView.swift`：
 * 主 Bot 卡（:431）/ botCell（:466，Main Bot + Hidden 徽标）/ roomCell（:482，成员串）/
 * sessionCell（:375，工作区 + 相对时间）/ avatar（:353，数据色圆 + 白首字母，
 * 颜色来自 `avatar.color` 十六进制，`Color(bridgeHex:)` :1059 —— RRGGBB 或 RRGGBBAA）。
 *
 * 尚未对齐（登记，不假装做了）：
 * - hostGlyph 矢量头像路径（C 组大件，iOS 此处也是首字母圆，先按 iOS 的样子对齐）；
 * - 点行进聊天 / 写操作 / 设备管理（C3、D 组）；
 * - iOS botCell 的 pending 转圈依赖写操作 in-flight 态，随 C3 一起；
 * - 「置顶」角标是本页有意的少量超出（iOS 只在详情页展示 pin，详情页归 C3）；
 * - busy/waiting 文案已从行上移除 —— iOS bridge 名册行不渲染它们（那是 D 组实时流状态层）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BotTeamScreen(
    adapter: AndroidBridgeTeamAdapter?,
    onBack: () -> Unit
) {
    // 走主题（与 ScheduledTasksScreen/PluginsScreen 一致）：A1 换品牌海军蓝底时本页自动跟随，不许硬编码。
    val background = MaterialTheme.colorScheme.background
    val state = adapter?.state
    val phase = state?.phase ?: TeamPhase.IDLE
    val bots = state?.bots.orEmpty()
    val rooms = state?.rooms.orEmpty()
    val sessions = state?.sessions.orEmpty()
    val mainBotIds = state?.mainBotIds.orEmpty()
    val cards = adapter?.pendingCards.orEmpty()
    val cardOwnerNames = remember(bots, rooms) {
        (bots + rooms).associate { it.id to it.name }
    }
    LaunchedEffect(adapter) {
        adapter?.start()
        adapter?.refreshNow()
    }
    Scaffold(
        modifier = Modifier.testTag("bot-team-page"),
        containerColor = background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(appText("Bot 团队"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TopBarCircleButton(
                        R.drawable.ic_back_chevron,
                        appText("返回"),
                        onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                actions = {
                    if (adapter?.isRefreshing == true) {
                        CircularProgressIndicator(Modifier.padding(16.dp).size(20.dp), strokeWidth = 2.dp)
                    } else {
                        TopBarCircleButton(
                            R.drawable.ic_refresh,
                            appText("刷新团队"),
                            { adapter?.refreshNow() },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background)
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("bot-team-list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                StatusCard(
                    phase = phase,
                    serverName = adapter?.serverInfo?.name,
                    origin = adapter?.origin,
                    error = adapter?.lastError,
                    hasSession = adapter?.hasSession == true
                )
            }
            // iOS botsSection（TeamView :337-351）：主 Bot 单独一节，行本身仍在 Bots 列表里（不去重）。
            val primaryBot = bots.firstOrNull { mainBotIds.contains(it.id) }
            if (primaryBot != null) {
                item { SectionTitle(appText("主 Bot")) }
                item {
                    PrimaryBotCard(primaryBot, modifier = Modifier.testTag("bot-row-primary-${primaryBot.name}"))
                }
            }
            if (bots.isNotEmpty()) {
                item { SectionTitle(appText("Bot（${bots.size}）")) }
                items(bots, key = { it.id }) { row ->
                    BotRow(row, isMain = mainBotIds.contains(row.id))
                }
            }
            if (rooms.isNotEmpty()) {
                item { SectionTitle(appText("群聊（${rooms.size}）")) }
                items(rooms, key = { it.id }) { row -> RoomRow(row) }
            }
            if (sessions.isNotEmpty()) {
                item { SectionTitle(appText("会话（${sessions.size}）")) }
                items(sessions, key = { it.id }) { row -> SessionRow(row) }
            }
            if (cards.isNotEmpty()) {
                item { SectionTitle(appText("卡片（${cards.size}）")) }
                items(cards, key = { it.id }) { card ->
                    BridgeCardRow(card, ownerName = cardOwnerNames[card.owner])
                }
            }
            if (bots.isEmpty() && rooms.isEmpty() && sessions.isEmpty()) {
                item {
                    Text(
                        appText("还没有收到团队名册数据"),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        style = DshType.body,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * 桥接卡片行（E1）：`kind:"view"` 出「方案对比」这类正文卡，question/secret 出
 * 「等你回答／等你填密钥」行 —— 文案与 iOS `statusText`（GatewayTeamFeed :1179）一致。
 * 纪律同 iOS：bridge 没有 question-answer action，**卡片只能看**，必须标明在电脑端处理。
 */
@Composable
private fun BridgeCardRow(card: com.clarklevis.dsh.shared.protocol.BridgeCardQuestion, ownerName: String?) {
    Column(
        Modifier.fillMaxWidth()
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
            Text(card.statusText, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), fontSize = 12.sp)
        }
        if (card.prompt.isNotBlank()) {
            Text(
                card.prompt,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (card.options.isNotEmpty()) {
            Text(
                card.options.joinToString(" / "),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            appText("在电脑端处理"),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** iOS mainBotCard（TeamView :431）：名字 + 角色 + 「主」胶囊（accent 15% 底）。头像位留待 C 组头像件。 */
@Composable
private fun PrimaryBotCard(row: BridgeConversationRow, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.name, color = MaterialTheme.colorScheme.onBackground, style = DshType.title)
            if (!row.role.isNullOrBlank()) {
                Text(
                    row.role.orEmpty(),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = DshType.footnote
                )
            }
        }
        CapsuleBadge(
            text = appText("主"),
            container = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            content = MaterialTheme.colorScheme.primary
        )
    }
}

/** iOS botCell（TeamView :466）：名字 + 角色 + Main Bot / Hidden 徽标。 */
@Composable
private fun BotRow(row: BridgeConversationRow, isMain: Boolean) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("bot-row-${row.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RosterAvatar(row.name, row.avatar, size = 34)
        Column(Modifier.weight(1f)) {
            Text(row.name, color = MaterialTheme.colorScheme.onBackground, style = DshType.body)
            if (!row.role.isNullOrBlank()) {
                Text(
                    row.role.orEmpty(),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = DshType.footnote
                )
            }
        }
        if (isMain) {
            CapsuleBadge(appText("Main Bot"), container = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
        }
        if (row.hidden == true) {
            CapsuleBadge(appText("Hidden"), container = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
        }
        // 「置顶」为有意的少量超出：iOS 只在详情页显示 pin（详情页归 C3），此处先保留可见性。
        if (row.pinned == true) {
            Text(appText("置顶"), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f), fontSize = 11.sp)
        }
    }
}

/** iOS roomCell（TeamView :482）：名字 + 成员串（、分隔，单行截断）。 */
@Composable
private fun RoomRow(row: BridgeConversationRow) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("bot-row-${row.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RosterAvatar(row.name, row.avatar, size = 34)
        Column(Modifier.weight(1f)) {
            Text(row.name, color = MaterialTheme.colorScheme.onBackground, style = DshType.body)
            if (!row.members.isNullOrEmpty()) {
                Text(
                    row.members.joinToString(appText("、")),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = DshType.footnote,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** iOS sessionCell（TeamView :375）：名字 + 工作区 + 相对时间。 */
@Composable
private fun SessionRow(row: BridgeConversationRow) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("session-row-${row.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                color = MaterialTheme.colorScheme.onBackground,
                style = DshType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!row.workspace.isNullOrBlank()) {
                    Text(
                        row.workspace.orEmpty(),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        style = DshType.footnote,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                row.lastActiveAt?.let { activeMillis ->
                    Text(
                        relativeTime(activeMillis / 1000.0),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                        style = DshType.footnote
                    )
                }
            }
        }
    }
}

/**
 * iOS avatar(for:)（TeamView :353）：数据色圆 + 白色首字母（size*0.42 semibold）。
 * 颜色来自 `avatar.color` 十六进制（`Color(bridgeHex:)` :1059：RRGGBB 或 RRGGBBAA，
 * alpha 在末尾），解析失败回退中性色。hostGlyph 矢量头像归 C 组头像件。
 */
@Composable
private fun RosterAvatar(name: String, avatar: kotlinx.serialization.json.JsonObject?, size: Int) {
    Box(
        Modifier.size(size.dp)
            .background(bridgeAvatarColor(avatar), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.take(1),
            color = Color.White,
            fontSize = (size * 0.42f).sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun bridgeAvatarColor(avatar: kotlinx.serialization.json.JsonObject?): Color {
    val hex = (avatar?.get("color") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return fallbackAvatarColor
    val digits = hex.trim().removePrefix("#")
    if (digits.length != 6 && digits.length != 8) return fallbackAvatarColor
    val value = digits.toLongOrNull(16) ?: return fallbackAvatarColor
    return if (digits.length == 8) {
        Color(
            red = ((value shr 24) and 0xFF).toInt() / 255f,
            green = ((value shr 16) and 0xFF).toInt() / 255f,
            blue = ((value shr 8) and 0xFF).toInt() / 255f,
            alpha = (value and 0xFF).toInt() / 255f
        )
    } else {
        Color(
            red = ((value shr 16) and 0xFF).toInt() / 255f,
            green = ((value shr 8) and 0xFF).toInt() / 255f,
            blue = (value and 0xFF).toInt() / 255f
        )
    }
}

private val fallbackAvatarColor = Color(0x8C8C8C99) // 中性灰 ≈ iOS Color.secondary.opacity(0.55)

/** iOS badge(_:)（TeamView :507）：胶囊、caption2、次要色。 */
@Composable
private fun CapsuleBadge(
    text: String,
    container: Color,
    content: Color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
) {
    Text(
        text,
        color = content,
        fontSize = 11.sp,
        modifier = Modifier
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        style = DshType.footnote,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun StatusCard(
    phase: TeamPhase,
    serverName: String?,
    origin: String?,
    error: String?,
    hasSession: Boolean
) {
    val label = when (phase) {
        TeamPhase.READY -> appText("已就绪")
        TeamPhase.LOADING -> appText("正在同步")
        TeamPhase.OFFLINE -> appText("离线")
        TeamPhase.NEEDS_PAIRING -> appText("未配对")
        TeamPhase.IDLE -> appText("未开始")
    }
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(14.dp)
            .testTag("bot-team-status"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(appText("桥接状态"), color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(label, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), style = DshType.footnote)
        }
        Text(
            appText("服务器：") + (serverName ?: "—"),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            style = DshType.footnote
        )
        Text(
            appText("地址：") + (origin ?: "—"),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            style = DshType.footnote
        )
        if (!hasSession) {
            Text(
                appText("尚未配对桥接设备：请先与网关配对（配对成功会自动下发团队会话）"),
                color = DshSemantic.warning,
                style = DshType.footnote
            )
        }
        if (error != null) {
            Text(error, color = DshSemantic.danger, style = DshType.footnote)
        }
    }
}
