package com.clarklevis.dsh.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    onOpenSession: (String) -> Unit,
    /** 凭证不可读时打开扫码配对（必填：没有它，用户面对的是一片空白，iOSCoder 2026-11-02 的根因链）。 */
    onRequestPairing: () -> Unit,
    /** true = 设备身份密文解不开：文案要区别于「会话凭证不可读」（临时故障 vs 需重配，iOSTester 2026-11-02）。 */
    deviceIdentityUnreadable: Boolean = false,
    /** true = 网关 token 密文解不开（≠ 从未配对）：显示「凭证失效，请重新配对」。 */
    tokenUnreadable: Boolean = false,
    /** 是否渲染搜索框右上角的「团队」入口（iOS `botRosterHeader` :656-668 的正式入口）。
     *  sheet 里那份自己不渲染入口，否则会套娃。 */
    showTeamEntry: Boolean = true
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
    // 「团队」入口（用户 2026-11-02 指出 iOS 有、Android 没有）：点开 = 底部弹出**完整 Bot 列表**
    // （iOS 是 `.sheet` 里的 `TeamView`，`WorkspaceView.swift:189-199`），内容就是本页同一套渲染。
    var showTeamSheet by rememberSaveable { mutableStateOf(false) }
    // ⚠️ `cards` 在本页只剩两处用途：行内 caution 点（`pending`）与只读预览正文
    // （`cardPrompt`）。整张卡的渲染只在**会话页**（`ConversationScreen` 的输入区上方，
    // 见 `BridgeCardRow.kt`）——用户 2026-11-02 截图里串页的那块就是从这里移走的，
    // 别再往本页加回来（C5：Bot 页 0 整卡）。
    LaunchedEffect(adapter) {
        adapter?.start()
        adapter?.refreshNow()
    }
    LaunchedEffect(bots) {
        // prune 的 keeping 集合先由**全部行**喂（hash-only 帧也要注册），再做差集删除——
        // 只在渲染时喂的话，重启后第一次 prune 会删掉在用头像（iOSDev 2026-11-02 指出）。
        bots.forEach { row -> row.avatarImageHash?.let(com.clarklevis.dsh.android.platform.BotAvatarFileCache::remember) }
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
        // 顶栏净空走共享来源（runbook §5.7 ⑨：与工作区同一表达式 statusBars+18；设置层 +0 走 Scaffold）。
        // 【历史】vc40 的 Bot tab 只给了 top=8dp 且无状态栏 inset——字标盒 52dp 露得出、右侧两枚
        // 34dp 圆钮整枚压在状态栏带里 ⇒ 用户看到「＋没了」（2026-11-02 截图）；当批修为 statusBars+8，
        // vc42 起并入 dshHomeListTopInset()（18，与工作区同层同偏移）。
        val topInset = dshHomeListTopInset()
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("bot-team-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset, bottom = 16.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // ① 共享页头（字标 + 认证圆钮 + 品牌蓝＋）：由 host 注入，Bot tab 与工作区 tab 同一份。
            header?.let { item { it() } }
            // ①′ 凭证不可读（Keystore 失效）：显式「需要重新配对」+ 入口——
            // 不再一片空白（用户 2026-11-02「明明连接了 Bot 页却是空的」），也不冒充「未配对」。
            if (adapter?.restoreFailed == true || deviceIdentityUnreadable || tokenUnreadable) {
                item {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                            .padding(14.dp)
                            .testTag("bot-needs-pairing")
                    ) {
                        Text(
                            appText(
                                when {
                                    adapter?.restoreFailed == true -> "设备凭证无法读取，需要重新配对"
                                    tokenUnreadable -> "网关凭证已失效，请重新配对"
                                    else -> "设备身份读取失败（可能为临时故障）"
                                }
                            ),
                            color = homeInk(0.92f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            appText(
                                if (adapter?.restoreFailed == true) "手机上的配对信息读不出来了，扫一次码即可恢复，不影响电脑端。"
                                else "系统密钥服务暂时不可用时可先稍后重试；一直失败再重新扫码配对。"
                            ),
                            color = homeInk(0.6f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)
                        )
                        androidx.compose.material3.TextButton(onClick = onRequestPairing) {
                            Text(appText("重新扫码配对"), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            // ② 区标题「Bot」：iOS WorkspaceView:653-655，左对齐 18 semibold（不是居中大标题）。
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        appText("Bot"),
                        color = homeInk(0.92f),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).testTag("bot-roster-title")
                    )
                    // iOS 的门控：有团队快照才给入口（`store.teamSnapshot.visible`，桌面端没开团队服务时不出空入口）。
                    if (showTeamEntry && (bots.isNotEmpty() || rooms.isNotEmpty())) {
                        TextButton(
                            onClick = { showTeamSheet = true },
                            modifier = Modifier.testTag("bot-roster-team-entry")
                        ) {
                            Text(
                                appText("团队"),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
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
                        cardPrompt = cards.firstOrNull { it.owner == row.id }
                            ?.prompt?.takeIf(String::isNotBlank),
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
            // ⑤ 卡片**不在本页成块渲染**（用户 2026-11-02 截图：卡片串到了 Bot 页）。
            // 页面归属按 iOS：08 屏只在**行内**给一句只读预览（`WorkspaceView:719-726`
            // `「prompt」` caption2 ≤2 行），整张卡只在会话页（`ConversationView:543`）。
            // 本页的入口是 `BotRow` 的 cardPrompt；整张卡由 `BridgeCardRow.kt` 在会话页渲染。
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
                        hasSession = adapter?.hasSession == true,
                        onRequestPairing = onRequestPairing
                    )
                }
            }
        }
    }
    // 「团队」→ 底部弹出**完整 Bot 列表**：iOS 是 `.sheet { TeamView }`（WorkspaceView:189-199），
    // Android 用 ModalBottomSheet 承载同一套渲染（本页复用一个实例，不另写列表）。
    // 内层 `showTeamEntry = false`：sheet 里再出「团队」就是套娃。
    // tag 用 `bot-team-sheet` 而**不是** `team-sheet` —— 后者已被「智能体团队」（`TeamUi.kt:196`，
    // 宿主自带那套 144 张几何头像）占用；用户明确要求「Bot」与「智能体团队」两套系统不许混称，
    // 同名 tag 会让验收脚本和指纹都分不清是哪一套。
    if (showTeamSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTeamSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.92f).testTag("bot-team-sheet")) {
                BotTeamScreen(
                    adapter = adapter,
                    onBack = { showTeamSheet = false },
                    pendingSessionIds = pendingSessionIds,
                    onOpenSession = onOpenSession,
                    onRequestPairing = onRequestPairing,
                    deviceIdentityUnreadable = deviceIdentityUnreadable,
                    tokenUnreadable = tokenUnreadable,
                    showTeamEntry = false
                )
            }
        }
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
        RosterAvatar(row.name, row.color, row.avatar, size = 46, botId = row.id, blink = true)
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
private fun BotRow(
    row: BridgeConversationRow,
    isMain: Boolean,
    pending: Boolean,
    /** 该 bot 名下待答卡片的正文（bridge `/cards` 的 `prompt`）：只读预览一行，不是卡片。 */
    cardPrompt: String?,
    onOpenSession: (String) -> Unit
) {
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
            RosterAvatar(row.name, row.color, row.avatar, size = 38, rowImageHash = row.avatarImageHash, botId = row.id, blink = true, blinkWorking = busy)
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
            // 卡片**只读预览**（iOS `WorkspaceView:719-726`：`「prompt」` caption2 ink(0.6) ≤2 行）。
            // 整张卡归会话窗口，不在这里成块渲染 —— 用户 2026-11-02 截图里串到 Bot 页的就是那块。
            if (cardPrompt != null) {
                Text(
                    "「$cardPrompt」",
                    color = homeInk(0.6f),
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("bot-row-card-preview-${row.name}")
                )
            }
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
            // 堆叠框高**固定 38dp**（iOS `gatewayAvatar` 的 `.frame(height: size)`，TeamView 同参）：
            // 27dp 的成员头像在其中垂直居中——之前内层 Row 是 wrap-content（27dp），
            // 比 Bot 行的 38dp 头像矮 11dp，与名字行/相邻行都错位（用户 2026-11-03 vc53 验收反馈）。
            Row(
                Modifier.width(38.dp + (stack.size - 1).dp * 16f)
                    .height(38.dp)
                    .testTag("bot-member-stack"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                stack.forEachIndexed { index, member ->
                    RosterAvatar(
                        name = member.name,
                        rowColor = member.color,
                        avatar = member.avatar,
                        size = 27,
                        rowImageHash = member.avatarImageHash, // 38 × 0.72
                        botId = member.id,
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
            // 副行 = 成员**名字**串（iOS `roomSubtitle`）。此前直接 join `row.members` ——
            // 那是 bot 的 **session id**（网关 adapter.mjs:101 过滤后原样下发），于是每行都
            // 把 `session-a5000f0e-…` 摊在界面上（用户 2026-11-02 截图），长 id 还挤坏行距。
            val memberNames = remember(row.members, bots) { roomMemberNames(row.members, bots) }
            Text(
                memberNames,
                color = homeInk(0.6f),
                fontSize = 12.sp, // iOS .caption（:494）
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 群聊副行文案 = **成员名字**串（iOS `GatewayTeamFeed.roomSubtitle`，:1209）：
 * 前 4 个名字用「、」连接，超过 4 个补「等 N 人」，一个都查不到 ⇒「群聊」。
 *
 * ⚠️ **iOS 侧同型缺陷已确认（不是"有意差异"）**：iOS 两处都露内部 id ——
 * `WorkspaceView.swift:912-916 bridgeRowSubtitle` 裸 join `row.members`（room 行不带 `role`，
 * 必然走到这一支），`GatewayTeamFeed.swift:1209-1213 roomSubtitle` 的 `botNames[$0] ?? $0`
 * 查不到就回落打印原值。依据 `row.members` 装的是 session id（网关 `adapter.mjs:101`）。
 * Chief 2026-11-02 已判 iOS 一并修（并入 73，口径与这里一致：查名、查不到丢弃）。
 * Android 这边**只认名字**：查不到就不显示，绝不把内部 id 摊在界面上。
 */
internal fun roomMemberNames(members: List<String>?, bots: List<BridgeConversationRow>): String {
    val ids = members.orEmpty()
    if (ids.isEmpty()) return appText("群聊")
    val byId = bots.associateBy { it.id }
    val names = ids.take(4).mapNotNull { byId[it]?.name?.takeIf(String::isNotBlank) }
    if (names.isEmpty()) return appText("群聊")
    val extra = if (ids.size > 4) appText(" 等 %1\$s 人", ids.size) else ""
    return names.joinToString(appText("、")) + extra
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
 * Bot 头像 shape → 符号（vc43 D4）：iOS 用 SF Symbols 表（20 项），Android 无等价符号集，
 * 按语义画了 20 个等价矢量。**未知/缺失形状 ⇒ 不画符号**（回落到色圆+首字母，
 * 记为有意差异——iOS 那层是中性圆点，但 Android 保留字母比落空点更可读，待 AndroidTester 定）。
 */
internal fun shapeIconRes(shape: String?): Int? = when (shape?.lowercase()) {
    "whale" -> R.drawable.shape_whale
    "star" -> R.drawable.shape_star
    "moon" -> R.drawable.shape_moon
    "wave" -> R.drawable.shape_wave
    "squircle" -> R.drawable.shape_squircle
    "zed" -> R.drawable.shape_zed
    "hex" -> R.drawable.shape_hex
    "gumdrop" -> R.drawable.shape_gumdrop
    "peanut" -> R.drawable.shape_peanut
    "saucer" -> R.drawable.shape_saucer
    "bunny" -> R.drawable.shape_bunny
    "kitty" -> R.drawable.shape_kitty
    "beacon" -> R.drawable.shape_beacon
    "plus" -> R.drawable.shape_plus
    "magnet" -> R.drawable.shape_magnet
    "acorn" -> R.drawable.shape_acorn
    "pebbles" -> R.drawable.shape_pebbles
    "cushion" -> R.drawable.shape_cushion
    "ring" -> R.drawable.shape_ring
    "gem" -> R.drawable.shape_gem
    else -> null
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
    /** 帧里只带 hash 不带字节时，从这里读缓存渲染（iOS 水合行同形态）。 */
    rowImageHash: String? = null,
    /** bot 的 id（`bots[].id`）：没有 avatar 字段时用它派生形象（与电脑端 `lookOf(id)` 同源）。 */
    botId: String? = null,
    /** 是否驱动「眨眼」（用户 2026-11-02：iOS Bot 页会眨、Android 不会）。节奏见 [BlinkCadence.kt]。 */
    blink: Boolean = false,
    /** 眨眼节奏按"正在干活"加密（iOS `.live(working:)`）。 */
    blinkWorking: Boolean = false,
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
    var storedHash by remember(imageDataUrl) { mutableStateOf<String?>(null) }
    LaunchedEffect(imageDataUrl) {
        storedHash = imageDataUrl?.let { BotAvatarFileCache.store(it) }
    }
    // hash 优先级：帧里带 image 就用存盘结果；只有 hash（无字节）→ 直接读缓存文件。
    val resolvedHash = storedHash ?: rowImageHash
    val cachedBitmap = remember(resolvedHash) {
        resolvedHash?.let { hash ->
            BotAvatarFileCache.remember(hash)
            BotAvatarFileCache.fileFor(hash)?.path
                ?.let { path -> android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }
        }
    }
    // 眨眼：节奏/相位与 iOS `TeamAvatarView` 同源（fnv1a 相位窗口 5s），但压的是**符号本身**
    // ——Android 画描边符号、没有独立眼睛层（`avatar.glyph` 归 D1），这条差异已登记。
    // 系统关掉动画（开发者选项/无障碍"移除动画"）时一律不动。
    val localContext = LocalContext.current
    val systemAnimationsOn = remember(localContext) {
        android.provider.Settings.Global.getFloat(
            localContext.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    }
    val blinkActive = shouldBlink(blink, systemAnimationsOn)
    val blinkSquash = remember { Animatable(1f) }
    LaunchedEffect(botId, blinkActive, blinkWorking) {
        if (!blinkActive) {
            blinkSquash.snapTo(1f)
            return@LaunchedEffect
        }
        delay(blinkPhaseMillis(botId))
        val steps = blinkStepsFor(working = blinkWorking)
        while (true) {
            for (step in steps) {
                blinkSquash.animateTo(step.squash, tween(durationMillis = step.holdMillis.toInt()))
            }
        }
    }
    val avatarHex = (avatar?.get("color") as? JsonPrimitive)?.takeIf { it.isString }?.content
    // ② 形象链 = 电脑端 `resolveLook(bot)` 的等价实现（image > glyph > **显式 shape** > 派生 > 兜底）：
    // 显式字段**只在合法时**采用（20 个角色名 / ink 名或 hex），否则按 id 派生；
    // 派生与 `lookOf(id)` 同源，所以没有 glyph 通道时也与电脑端逐行一致。
    // ⚠️ 顺序不能反：线上 Chief 那行显式 `shape=whale,color=deepseek`，只按 id 算会得到
    // `gumdrop/orange`（错），这条是 AndroidTester 真值表第 1 行。
    val shapeValue = (avatar?.get("shape") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val effectiveLook = remember(botId, shapeValue, rowColor, avatarHex) {
        RosterDerivedLook.effectiveLook(shapeValue, rowColor ?: avatarHex, botId)
    }
    val resolvedShapeName = effectiveLook.first
    val shapeRes = shapeIconRes(resolvedShapeName)
    // 兜底只剩两种：① 角色名没有同名 drawable（20/20 已由用例守住）⇒ 中性圆点；
    // ② 连 id 都没有 ⇒ 首字母。两种都不该出现在正常数据里，留着是为了"不画错"。
    val unknownShape = shapeRes == null && resolvedShapeName.isNotBlank()
    val color = remember(rowColor, avatarHex, effectiveLook, dark, fallback) {
        rosterAvatarColor(rowColor, avatarHex, effectiveLook.second, dark, fallback)
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
        } else if (shapeRes != null) {
            Icon(
                painter = painterResource(shapeRes),
                contentDescription = null,
                modifier = Modifier.size((size * 0.62f).dp).graphicsLayer { scaleY = blinkSquash.value },
                tint = Color.White
            )
        } else if (unknownShape) {
            Box(Modifier.size((size * 0.34f).dp).background(Color.White, CircleShape))
        } else Text(
            name.take(1),
            color = Color.White,
            fontSize = (size * 0.42f).sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 行级 color（ink 名/#hex）→ avatar.color hex → **派生 ink** → fallback；全 null 才轮到中性色。 */
private fun rosterAvatarColor(
    rowColor: String?,
    avatarHex: String?,
    derivedInk: String?,
    dark: Boolean,
    fallback: Color
): Color {
    BridgeInks.resolve(rowColor, dark)?.let { return parseBridgeHexColor(it, fallback) }
    avatarHex?.let { return parseBridgeHexColor(it, fallback) }
    BridgeInks.resolve(derivedInk, dark)?.let { return parseBridgeHexColor(it, fallback) }
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
    hasSession: Boolean,
    /** 未配对档的配对入口（规则 3：写了「请先配对」就必须给得到入口）。 */
    onRequestPairing: () -> Unit = {}
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
        // 折叠（iOSDev 规则：异常态只留「状态 + 原因」）：服务器名/地址属「设置→连接」组，
        // 不在 Bot 页重复四行扁平清单；排障需要时看设置页，那里是唯一来源。
        if (!hasSession) {
            Text(
                appText("尚未配对桥接设备：请先与网关配对（配对成功会自动下发团队会话）"),
                color = DshSemantic.warning,
                style = DshType.footnote
            )
            // iOSDev 规则 3：**凡文案写「请先配对」，同屏必须有可点的配对入口**——
            // 否则用户知道要做什么却无处可点（用户 2026-11-02 截图里就是这样）。
            androidx.compose.material3.TextButton(
                onClick = onRequestPairing,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
            ) {
                Text(appText("重新扫码配对"), color = MaterialTheme.colorScheme.primary, style = DshType.footnote)
            }
        }
        // 一屏只允许一条主提示（iOSDev 规则 1）：未配对档已经把原因说清，
        // 再叠一条 error 就是同一件事讲两遍（截图实测出现过三条同义提示）。
        if (error != null && hasSession) {
            Text(error, color = DshSemantic.danger, style = DshType.footnote)
        }
    }
}
