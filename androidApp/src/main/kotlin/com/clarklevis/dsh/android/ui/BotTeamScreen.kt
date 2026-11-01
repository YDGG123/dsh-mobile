package com.clarklevis.dsh.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.AndroidBridgeTeamAdapter
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.platform.BotAvatarFileCache
import android.widget.Toast
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
    /** tab 根分段形态（iOSDev 裁定 2026-11-01，对齐 iOS TeamView:55-73）：
     *  标题「团队」、**有顶栏但无返回箭头**、右侧刷新；抽屉/独立路由（非 embedded）标题
     *  「Bot 团队」+ 返回箭头。左侧「完成」是 iOS sheet 场景残留，不复制。 */
    embedded: Boolean = false,
    /** tab 根分段注入的共享页头（字标 + 认证圆钮 + ＋）：iOS 三个 tab 共用同一条页头，
     *  Android 侧由 `DshProductApp.WorkspaceHeader` 提供，避免 Bot tab 自绘第二条。 */
    header: (@Composable () -> Unit)? = null,
    /** 有待审批/待回答的会话 id：用于状态点橙档（iOS `!approvals.isEmpty || !pending.isEmpty` ⇒ caution）。 */
    pendingSessionIds: Set<String> = emptySet(),
    /** 点行进会话（iOS WorkspaceView:801-813 三级回落；Android 先做 bridge sessionId → 开会话）。
     *  **必填**（AndroidTester 2026-11-02）：默认空实现会让「点了没反应」在编译期静默通过。 */
    onOpenSession: (String) -> Unit
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
    LaunchedEffect(bots) {
        com.clarklevis.dsh.android.platform.BotAvatarFileCache.prune()
    }
    Scaffold(
        modifier = Modifier.testTag("bot-team-page"),
        // 材质批：iOS 真机量到 **Bot 页背后是动效背景**，tab 内嵌时透明、露出 host 的动效背景；
        // 独立路由（非 embedded，无 host）保持原实底，否则会露窗口底。
        containerColor = if (embedded) Color.Transparent else background,
        topBar = {
            // tab 根分段（embedded）：iOS 的共享页头由 host 提供（字标 + 认证圆钮 + ＋），
            // 页内不再自绘顶栏——vc39 那个居中「团队」标题就是多余的那一层（用户 11-02 指出）。
            if (!embedded) {
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
        }
    ) { padding ->
        var query by rememberSaveable { mutableStateOf("") }
        val filteredBots = remember(bots, query) {
            if (query.isBlank()) bots else bots.filter { it.name.contains(query, ignoreCase = true) }
        }
        val filteredRooms = remember(rooms, query) {
            if (query.isBlank()) rooms else rooms.filter { it.name.contains(query, ignoreCase = true) }
        }
        // 顶栏净空：共享页头直接铺在 LazyColumn 顶上，必须自己吃状态栏 inset——
        // 工作区 tab 的 contentPadding 是 WindowInsets.safeDrawing（DshProductApp:405），
        // vc40 的 Bot tab 只给了 top=8dp，字标大（52dp 盒）还看得见，
        // 右侧两枚 34dp 圆钮整个被压在状态栏下 ⇒ 用户看到「＋没了」（11-02 截图）。
        val topInset = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("bot-team-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset + 8.dp, bottom = 16.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // ① 共享页头（字标 + 认证圆钮 + 品牌蓝＋）：由 host 注入，Bot tab 与工作区 tab 同一份。
            header?.let { item { it() } }
            // ② 区标题「Bot」：iOS WorkspaceView:653-655，左对齐 18 semibold（不是居中大标题）。
            item {
                Text(
                    appText("Bot"),
                    color = homeInk(0.92f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).testTag("bot-roster-title")
                )
            }
            // ③ 搜索框：复用会话 tab 的同一个 SessionSearch（iOS Bot tab 也挂同一个 sessionSearch）。
            item {
                SessionSearch(query, { query = it })
            }
            // ④ 单一分组「Agent」（iOS gatewayTeamSection 按 kind 分组：bot → Agent，room → 群聊）。
            item { SectionTitle(appText("Agent")) }
            if (filteredBots.isEmpty()) {
                item { EmptyRosterRow(appText("暂无 Bot")) }
            } else {
                items(filteredBots, key = { it.id }) { row ->
                    BotRow(
                        row = row,
                        isMain = mainBotIds.contains(row.id),
                        pending = row.sessionId in pendingSessionIds ||
                            cards.any { it.owner == row.id },
                        onOpenSession = onOpenSession
                    )
                }
            }
            // 群聊节：标题常显，空时「暂无群聊」。
            item { SectionTitle(appText("群聊")) }
            if (filteredRooms.isEmpty()) {
                item { EmptyRosterRow(appText("暂无群聊")) }
            } else {
                items(filteredRooms, key = { it.id }) { row -> RoomRow(row, bots, onOpenSession) }
            }
            // ⑤「卡片（N）」节**保留**：BridgeCardRow 全仓唯一渲染点，承载 A4 密钥卡 / E1 view 卡
            // 的可见入口（iOS 走 Bot 行状态文案，D 组 feed 未接）。删它 = 删掉卡片显示，不许顺手删。
            if (cards.isNotEmpty()) {
                item { SectionTitle(appText("卡片（${cards.size}）")) }
                items(cards, key = { it.id }) { card ->
                    BridgeCardRow(card, ownerName = cardOwnerNames[card.owner])
                }
            }
            // ⑥ 桥接状态卡：iOS 正常态什么都不画，只有离线横幅 / 未就绪诊断卡。
            // 这里从"常驻整卡"改成**只在异常/未就绪时出现**（用户 11-02：常驻那张是 Android 多出来的）。
            item {
                when (phase) {
                    TeamPhase.IDLE, TeamPhase.LOADING, TeamPhase.READY -> Unit
                    else -> StatusCard(
                        phase = phase,
                        serverName = adapter?.serverInfo?.name,
                        origin = adapter?.origin,
                        error = adapter?.lastError,
                        hasSession = adapter?.hasSession == true
                    )
                }
            }
            if (bots.isEmpty() && rooms.isEmpty()) {
                item {
                    Text(
                        appText("还没有收到团队名册数据"),
                        color = homeInk(0.6f),
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
                    color = homeInk(0.6f),
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

/**
 * Bot 名册行——对齐 iOS Bot tab 的 `gatewayTeamRow`（WorkspaceView:690-730）：
 * **纯列表**（无卡片底、无圆角框，靠留白分隔；iOS 注释：「08 设计稿：Bot 行是纯列表」）、
 * minHeight 64、头像 38、名字 + 「主」小字 + **状态副行**（空闲／正在干活）+ 头像右下状态点。
 *
 * 状态文案的边界（B 批未接）：动作文本（「正在干活：Read root layout…」）来自 D 组 team-feed
 * `row.activity`，bridge 名册只有 `busy`，所以这里只渲染「正在干活」/「空闲」，**不编造动作**。
 */
@Composable
private fun BotRow(row: BridgeConversationRow, isMain: Boolean, pending: Boolean, onOpenSession: (String) -> Unit) {
    val busy = row.busy == true
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable {
                // iOS WorkspaceView:801-813：命中就开会话，**落空必须报错**，不许静默没反应
                //（用户 2026-11-02：「bot 列表的对话点不进去」= vc40 从未实现行点击）。
                if (row.sessionId.isNotBlank()) onOpenSession(row.sessionId)
                else Toast.makeText(
                    context,
                    appText("这条对话还没同步到会话列表，稍后重试。"),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .testTag("bot-row-${row.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box {
            RosterAvatar(row.name, row.color, row.avatar, size = 38)
            RosterStatusDot(busy = busy, caution = pending, modifier = Modifier.align(Alignment.BottomEnd))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.name, color = homeInk(0.92f), fontSize = 17.sp)
                if (isMain) {
                    Text(appText("主"), color = homeInk(0.45f), fontSize = 11.sp)
                }
            }
            Text(
                if (busy) appText("正在干活") else appText("空闲"),
                color = if (busy) DshSemantic.success else homeInk(0.55f),
                fontSize = 13.sp
            )
        }
        if (row.hidden == true) {
            CapsuleBadge(appText("Hidden"), container = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
        }
    }
}

/**
 * 头像右下 9×9 状态点（iOS WorkspaceView:788-792 + GatewayTeamFeed:1417 statusTint）：
 * **有待办/占位卡/审批 → caution 橙** ｜ activity∈{working,thinking} → success 绿 ｜ 其余 veil 灰。
 * Android 的绿档是降级（bridge 只有 `busy`，正式判据要 feed 的 `activity`）；橙档数据 bridge 已有。
 */
@Composable
private fun RosterStatusDot(busy: Boolean, caution: Boolean, modifier: Modifier = Modifier) {
    val tint = when {
        caution -> DshSemantic.caution
        busy -> DshSemantic.success
        else -> homeVeil(0.35f)
    }
    Box(modifier.size(9.dp).background(tint, CircleShape).testTag("roster-status-dot"))
}

/** iOS roomCell（TeamView :482）：名字 + 成员串（、分隔，单行截断）。 */
@Composable
private fun RoomRow(row: BridgeConversationRow, bots: List<BridgeConversationRow>, onOpenSession: (String) -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable {
                if (row.sessionId.isNotBlank()) onOpenSession(row.sessionId)
                else Toast.makeText(
                    context,
                    appText("这条对话还没同步到会话列表，稍后重试。"),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .testTag("room-row-${row.name}"), // 独立 tag：原与 BotRow 同 tag，验收脚本无法区分（AndroidTester 指出）
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // iOS roomCell 成员头像堆叠（WorkspaceView:746-768，AndroidTester 2026-11-02 逐行核对）：
        // room 没有 avatar 字段，用 members 的 id 去 join 各 bot 现拼。
        //   成员头像块 = size × 0.72 = 27.4 ｜ 步进 = index × size × 0.42 = 16
        //   描边 = surface@0.9 1.5dp ｜ 绘制序 zIndex(3−index) ⇒ index 0 在最上 ｜ 向右 +x 展开
        //   （0.42 是**步进系数**，不是"重叠量"——之前按 (1−0.42)×38=22 算反了）
        val stack = remember(row.members, bots) {
            row.members.mapNotNull { id -> bots.firstOrNull { it.id == id } }.take(3)
        }
        if (stack.isEmpty()) {
            RoomGroupGlyph()
        } else {
            Row(Modifier.width(38.dp + (stack.size - 1).dp * 16f).testTag("bot-member-stack")) {
                stack.forEachIndexed { index, member ->
                    RosterAvatar(
                        name = member.name,
                        rowColor = member.color,
                        avatar = member.avatar,
                        size = 27, // 38 × 0.72
                        stroke = 1.5.dp,
                        modifier = Modifier
                            .offset(x = (16 * index).dp)
                            .zIndex((3 - index).toFloat())
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(row.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp) // iOS .body（:492）
            if (!row.members.isNullOrEmpty()) {
                Text(
                    row.members.joinToString(appText("、")),
                    color = homeInk(0.6f),
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
            tint = homeInk(0.6f) // iOS .foregroundStyle(.secondary)
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
                        color = homeInk(0.6f),
                        fontSize = 12.sp, // iOS .caption（:390）
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                row.lastActiveAt?.let { activeMillis ->
                    Text(
                        relativeTime(activeMillis / 1000.0),
                        color = homeInk(0.35f), // iOS .tertiary（:393）≈30%，取 0.35 保底对比
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
            tint = homeInk(0.35f) // iOS chevron.right .caption .tertiary（:401）
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
    size: Int,
    modifier: Modifier = Modifier,
    /** 成员堆叠的分离环（iOS surface@0.9 1.5pt）；普通行传 0。 */
    stroke: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f)
) {
    // fallback 色：规格 §14 裁决值（iOS TeamView:539 Color.secondary.opacity(0.55)
    // → secondaryLabel 两档 × 0.55 = 组合 alpha 0.33）：
    //   浅 #3C3C43@0.33 / 深 #EBEBF5@0.33
    // （Chief 2026-11-01 #10：原 onBackground×0.33 单式在浅色与规格差 15 个色阶；13/14 行走此路径）
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val fallback = (if (dark) Color(0xEBEBF5) else Color(0x3C3C43)).copy(alpha = 0.33f)
    // 第一层：照片头像（avatar.image data URL）→ 文件缓存（键=内容 sha256 前 16 位），
    // 命中直接读文件、不重解码（用户 2026-11-02：「保存到本地、只渲染一次，电脑端变了才更新」）。
    // bridge 名册线上暂只有 shape（image 随 D 组 feed 落地后生效），此层现在处于休眠、不报错。
    val imageDataUrl = (avatar?.get("image") as? JsonPrimitive)?.takeIf { it.isString }?.content
    var imageHash by remember(imageDataUrl) { mutableStateOf<String?>(null) }
    LaunchedEffect(imageDataUrl) {
        imageHash = imageDataUrl?.let { BotAvatarFileCache.store(it) }
    }
    val cachedBitmap = remember(imageHash) {
        imageHash?.let { hash ->
            BotAvatarFileCache.remember(hash)
            BotAvatarFileCache.fileFor(hash)?.path
                ?.let { path -> android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }
        }
    }
    val avatarHex = (avatar?.get("color") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val color = remember(rowColor, avatarHex, dark, fallback) {
        rosterAvatarColor(rowColor, avatarHex, dark, fallback)
    }
    Box(
        modifier.size(size.dp)
            .background(color, CircleShape)
            .then(if (stroke > androidx.compose.ui.unit.Dp(0f)) Modifier.border(stroke, homeSurface(0.9f), CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (cachedBitmap != null) {
            Image(
                bitmap = cachedBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else Text(
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
        color = homeInk(0.5f),
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = homeInk(0.7f),
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
            Text(label, color = homeInk(0.7f), style = DshType.footnote)
        }
        Text(
            appText("服务器：") + (serverName ?: "—"),
            color = homeInk(0.6f),
            style = DshType.footnote
        )
        Text(
            appText("地址：") + (origin ?: "—"),
            color = homeInk(0.6f),
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
