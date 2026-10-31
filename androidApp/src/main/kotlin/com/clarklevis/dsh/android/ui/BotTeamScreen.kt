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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.AndroidBridgeTeamAdapter
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.domain.TeamPhase
import com.clarklevis.dsh.shared.protocol.BridgeConversationRow

/**
 * Bot 团队页（C1 最小版）：Mobile Bridge 的 `/info` + `/state` + `/conversations`
 * 合流后渲染真实名册。当前只做「看得见真实 Bot 名」，头像/行状态/写操作按 C2–C3 推进。
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
            if (bots.isNotEmpty()) {
                item { SectionTitle(appText("Bot（${bots.size}）")) }
                items(bots, key = { it.id }) { row -> RosterRow(row) }
            }
            if (rooms.isNotEmpty()) {
                item { SectionTitle(appText("群聊（${rooms.size}）")) }
                items(rooms, key = { it.id }) { row -> RosterRow(row) }
            }
            if (cards.isNotEmpty()) {
                item { SectionTitle(appText("卡片（${cards.size}）")) }
                items(cards, key = { it.id }) { card ->
                    BridgeCardRow(card, ownerName = cardOwnerNames[card.owner])
                }
            }
            if (bots.isEmpty() && rooms.isEmpty()) {
                item {
                    Text(
                        appText("还没有收到团队名册数据"),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        fontSize = 14.sp,
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

@Composable
private fun SectionTitle(text: String) {    Text(
        text,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun RosterRow(row: BridgeConversationRow) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("bot-row-${row.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // C2 换成 hostGlyph 矢量头像；C1 先用首字母占位，避免假装头像已对齐。
        Box(
            Modifier.size(34.dp)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f), RoundedCornerShape(17.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                row.name.take(1),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Column(Modifier.weight(1f)) {
            Text(row.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            val detail = row.role ?: row.model ?: row.kind
            Text(
                detail,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                fontSize = 12.sp
            )
        }
        if (row.pinned == true) {
            Text(appText("置顶"), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f), fontSize = 11.sp)
        }
        if (row.busy == true) {
            Text(appText("运行中"), color = DshSemantic.success, fontSize = 11.sp)
        }
        if (row.waiting == true) {
            Text(appText("等你"), color = DshSemantic.warning, fontSize = 11.sp)
        }
    }
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
            Text(label, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp)
        }
        Text(
            appText("服务器：") + (serverName ?: "—"),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            fontSize = 12.sp
        )
        Text(
            appText("地址：") + (origin ?: "—"),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            fontSize = 12.sp
        )
        if (!hasSession) {
            Text(
                appText("尚未配对桥接设备：请先与网关配对（配对成功会自动下发团队会话）"),
                color = DshSemantic.warning,
                fontSize = 12.sp
            )
        }
        if (error != null) {
            Text(error, color = DshSemantic.danger, fontSize = 12.sp)
        }
    }
}
