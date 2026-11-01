package com.clarklevis.dsh.android.ui

import androidx.annotation.DrawableRes
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.AndroidBridgeTeamAdapter
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.domain.TeamPhase
import com.clarklevis.dsh.shared.protocol.BridgeInks
import com.clarklevis.dsh.shared.protocol.BridgeConversationRow
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bot 团队页：Mobile Bridge 的 `/info` + `/state` + `/conversations`（+ `/cards`）
 * 合流后渲染真实名册。行语义逐项对照 iOS `TeamView.swift`：
 * 主 Bot 卡（:431）/ botCell（:466，Main Bot + Hidden 徽标）/ roomCell（:482，成员串）/
 * sessionCell（:375，工作区 + 相对时间）/ avatar（:353，数据色圆 + 白首字母，
 * 颜色链路：行级 `color`（ink 名）→ `BridgeInks.resolve` → 回落 `avatar.color` hex → 中性；
 * 大部分行为中性是数据没给行级 color，不是解析缺位。）
 *
 * 形态说明（Chief 裁决「差异有意」）：本页内容对应 iOS 的 **TeamView sheet**
 * （WorkspaceView :189 .sheet(isPresented:$showsTeam)，bridge 名册数据源），
 * 不是 iOS tab 上的 feed 段（WorkspaceView :580 gatewayTeamSection，dsBotTeam
 * WS = D 组实时流）——Android 无 sheet 入口，用独立页形态承接。
 *
 * 文字档口径（Chief 2026-11-01 裁决「忠实 iOS」）：行上文字走 SwiftUI 系统档
 * 的实际值 —— .body 17 / .caption 12 / .headline 17 semibold / .subheadline 15 /
 * .caption2 11 —— 不套 DshType 梯子（TeamView 行内全是系统档，iOSCoder 复核实证）。
 *
 * 尚未对齐（登记，不假装做了）：
 * - hostGlyph 矢量头像路径（C 组大件，iOS 此处也是首字母圆，先按 iOS 的样子对齐）；
 * - 点行进聊天 / 写操作 / 设备管理（C3、D 组）；
 * - pending 转圈：state 层已有 isPending（TeamReducer :82）但 C2 无写路径，随 C3 接线；
 * - 「置顶」角标是本页有意的少量超出（iOS 只在详情页展示 pin，详情页归 C3）；
 * - waiting 不渲染：真实抓包 0 行带 waiting（05:56 /conversations，AndroidTester 实测）；
 *   busy 只在 session 行渲染「运行中」徽标（iOS sessionCell :414-416），
 *   bot/room 行不渲染 busy 是 iOS 本来的行为。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BotTeamScreen(
    adapter: AndroidBridgeTeamAdapter?,
    onBack: () -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    // iOS Bot tab 无返回键顶栏（WorkspaceView:321 primaryTab 内容分段）；抽屉/导航入口进来的保留顶栏。
    showTopBar: Boolean = true
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
            if (showTopBar) CenterAlignedTopAppBar(
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + bottomInset),
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
            // Bots 节（iOS :343 Section("Bots")，zh-Hans 值同为 "Bots"——iOSDev 裁决）：
            // 标题常显、空时 emptyRow「暂无 Bot」（iOS 空节也显示，Android 原来整节隐藏）。
            item { SectionTitle(appText("Bots")) }
            if (bots.isEmpty()) {
                item { EmptyRosterRow(appText("暂无 Bot")) }
            } else {
                items(bots, key = { it.id }) { row ->
                    BotRow(row, isMain = mainBotIds.contains(row.id))
                }
            }
            // 群聊节：标题常显，空时「暂无群聊」（iOS :420-422）。
            item { SectionTitle(appText("群聊")) }
            if (rooms.isEmpty()) {
                item { EmptyRosterRow(appText("暂无群聊")) }
            } else {
                items(rooms, key = { it.id }) { row -> RoomRow(row) }
            }
            // 会话节：标题常显，空时「暂无其他会话」（iOS :356-359）。
            item { SectionTitle(appText("会话")) }
            if (sessions.isEmpty()) {
                item { EmptyRosterRow(appText("暂无其他会话")) }
            } else {
                items(sessions, key = { it.id }) { row -> SessionRow(row) }
            }
            // 「卡片」节 = Android 有意超出（iOSDev 裁决，规格 §15）：BridgeCardRow 全仓唯一渲染点，
            // 是 Android 侧 pending view/question/secret 卡唯一可见入口；iOS 对应可见性走 Bot tab 行
            // 状态文案（GatewayTeamFeed:1179 statusText，D 组 feed 层，Android 未接）。
            // D 组落地、tab 切 feed 后重评，预期移除。
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

/** iOS mainBotCard（TeamView :431-455）：头像 46 + 名字 + 角色 + 「主」胶囊（accent 15% 底）。 */
@Composable
private fun PrimaryBotCard(row: BridgeConversationRow, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RosterAvatar(row.name, row.color, row.avatar, size = 46)
        Column(Modifier.weight(1f)) {
            // iOS .headline = 17 semibold（TeamView :435）。裁决原文写「主 Bot 18」，但源码
            // .headline 实际值是 17 —— 按「忠实 iOS 系统档」原则取 17，如需 18 一行改回。
            Text(row.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (!row.role.isNullOrBlank()) {
                Text(
                    row.role.orEmpty(),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    fontSize = 15.sp // iOS .subheadline（:437）
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
        RosterAvatar(row.name, row.color, row.avatar, size = 38) // iOS botCell :477 avatar 38（Chief 2026-11-01 裁决；34 无规格出处）
        Column(Modifier.weight(1f)) {
            Text(row.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp) // iOS .body（:479）
            if (!row.role.isNullOrBlank()) {
                Text(
                    row.role.orEmpty(),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    fontSize = 12.sp // iOS .caption（:481）
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
            .testTag("room-row-${row.name}"), // 独立 tag：原与 BotRow 同 tag，验收脚本无法区分（AndroidTester 指出）
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RoomGroupGlyph()
        Column(Modifier.weight(1f)) {
            Text(row.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp) // iOS .body（:492）
            if (!row.members.isNullOrEmpty()) {
                Text(
                    row.members.joinToString(appText("、")),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    fontSize = 12.sp, // iOS .caption（:494）
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** iOS roomCell（:482-486）：person.3.fill 图标 + 灰底圆 38×38，无首字母 —— 与 bot 行（色圆+首字母）两种形态并存。 */
@Composable
private fun RoomGroupGlyph() {
    GlyphCircle(R.drawable.ic_room_group)
}

/** iOS sessionCell（:377-381）：bubble.left.and.text.bubble.right.fill + 灰底圆 38×38。 */
@Composable
private fun SessionBubbleGlyph() {
    GlyphCircle(R.drawable.ic_session_bubble)
}

@Composable
private fun GlyphCircle(@DrawableRes iconRes: Int) {
    Box(
        Modifier.size(38.dp)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f) // iOS .foregroundStyle(.secondary)
        )
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
        SessionBubbleGlyph()
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 17.sp, // iOS .body（:388）
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!row.workspace.isNullOrBlank()) {
                    Text(
                        row.workspace.orEmpty(),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        fontSize = 12.sp, // iOS .caption（:390）
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                row.lastActiveAt?.let { activeMillis ->
                    Text(
                        relativeTime(activeMillis / 1000.0),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f), // iOS .tertiary（:393）≈30%，取 0.35 保底对比
                        fontSize = 12.sp
                    )
                }
            }
        }
        // busy 徽标：iOS sessionCell :414-416 明确渲染（真实抓包 4 行 busy 全在 session，AndroidTester 05:56 实测）。
        if (row.busy == true) {
            CapsuleBadge(appText("运行中"), container = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f) // iOS chevron.right .caption .tertiary（:401）
        )
    }
}

/**
 * iOS avatar(for:)（TeamView :353）+ avatarColor（:535）+ dynamicInk 同链路：
 * **行级 `row.color`（ink 名或 #hex）→ `BridgeInks.resolve`（shared 单一来源，
 * 与 iOS `dynamicInk` 同口径）→ 回落 `avatar.color` 十六进制（`Color(bridgeHex:)`
 * :1059：RRGGBB / RRGGBBAA，alpha 在末）→ 中性色**。
 *
 * ⚠️ 改完大部分头像仍是灰的——这是**数据没给**（2026-11-01 实测 14 行里只有
 * Chief 一行带行级 color，其余 13 行就是 None），不是解析没接对。
 * hostGlyph 矢量头像归 C 组头像件。
 */
@Composable
private fun RosterAvatar(
    name: String,
    rowColor: String?,
    avatar: kotlinx.serialization.json.JsonObject?,
    size: Int
) {
    // fallback 色：规格 §14 裁决值（iOS TeamView:539 Color.secondary.opacity(0.55)
    // → secondaryLabel 两档 × 0.55 = 组合 alpha 0.33）：
    //   浅 #3C3C43@0.33 / 深 #EBEBF5@0.33
    // （Chief 2026-11-01 #10：原 onBackground×0.33 单式在浅色与规格差 15 个色阶；13/14 行走此路径）
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val fallback = (if (dark) Color(0xEBEBF5) else Color(0x3C3C43)).copy(alpha = 0.33f)
    val avatarHex = (avatar?.get("color") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val color = remember(rowColor, avatarHex, dark, fallback) {
        rosterAvatarColor(rowColor, avatarHex, dark, fallback)
    }
    Box(
        Modifier.size(size.dp)
            .background(color, CircleShape),
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

/** 行级 color（ink 名/#hex）→ avatar.color hex → fallback；两头都 null 才轮到中性色。 */
private fun rosterAvatarColor(rowColor: String?, avatarHex: String?, dark: Boolean, fallback: Color): Color {
    BridgeInks.resolve(rowColor, dark)?.let { return parseBridgeHexColor(it, fallback) }
    avatarHex?.let { return parseBridgeHexColor(it, fallback) }
    return fallback
}

/** iOS `Color(bridgeHex:)`（TeamView :1059）：#RGB/#RRGGBB/#RRGGBBAA（alpha 在末），非法落 fallback。 */
private fun parseBridgeHexColor(hex: String?, fallback: Color): Color {
    if (hex == null) return fallback
    val digits = hex.trim().removePrefix("#")
    if (digits.length != 6 && digits.length != 8) return fallback
    val value = digits.toLongOrNull(16) ?: return fallback
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

/** iOS emptyRow（TeamView :343/:356/:420 空态）：节标题常显、内容位单行说明。样式待真机校准（iOS 字号未逐值核）。 */
@Composable
private fun EmptyRosterRow(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
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
