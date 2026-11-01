package com.clarklevis.dsh.android.ui

import android.app.Activity
import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.clarklevis.dsh.shared.protocol.GatewaySessionStatsSnapshot
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow as TextShadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.clarklevis.dsh.android.AndroidNotificationSessionRoute
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeState
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ROUTE_WORKSPACE = "workspace"
private const val ROUTE_CONVERSATION = "conversation"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_SETTINGS_STORAGE = "settings/storage"
private const val ROUTE_PLUGINS = "plugins"
private const val ROUTE_PLUGIN_BUNDLE = "plugins/bundle/{bundleName}"
private const val ROUTE_PLUGIN_OFFICIAL = "plugins/official/{pluginId}"
private const val ROUTE_SCHEDULED_TASKS = "scheduled-tasks"

@Composable
internal fun DshProductApp(
    stateHolder: AndroidSharedStateHolder,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    notificationRoute: AndroidNotificationSessionRoute?,
    onNotificationRouteOpened: (AndroidNotificationSessionRoute) -> Unit
) {
    val updateModel: com.clarklevis.dsh.android.update.AppUpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val updateState by updateModel.state.collectAsStateWithLifecycle()
    AppUpdateDialogs(updateModel, updateState)
    LaunchedEffect(updateModel) { updateModel.checkOnStartup() }
    val navController = rememberNavController()
    LaunchedEffect(notificationRoute, stateHolder.snapshot.sessions) {
        val route = notificationRoute ?: return@LaunchedEffect
        if (stateHolder.snapshot.sessions.none { it.id == route.sessionId }) return@LaunchedEffect
        stateHolder.selectSession(route.sessionId)
        navController.navigate(ROUTE_CONVERSATION) {
            popUpTo(ROUTE_WORKSPACE)
            launchSingleTop = true
        }
        onNotificationRouteOpened(route)
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val isWorkspace = backStackEntry?.destination?.route == null ||
        backStackEntry?.destination?.route == ROUTE_WORKSPACE
    val systemDark = isSystemInDarkTheme()
    val view = LocalView.current
    SideEffect {
        // 首页系统栏随抽屉阈值变化，由 WorkspaceDrawer 管理，避免网关刷新时覆盖。
        if (isWorkspace) return@SideEffect
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !systemDark
            isAppearanceLightNavigationBars = !systemDark
        }
    }
    NavHost(
        navController = navController,
        startDestination = ROUTE_WORKSPACE,
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(260))
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(260))
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(260))
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(260))
        }
    ) {
        composable(ROUTE_WORKSPACE) {
            val workspaceScope = rememberCoroutineScope()
            WorkspaceScreen(
                stateHolder = stateHolder,
                onOpenSession = { id ->
                    // 转场期间首页仍能收到点击，直接读取当前目的地以拦截重复事件。
                    if (navController.currentDestination?.route == ROUTE_WORKSPACE) {
                        stateHolder.selectSession(id)
                        navController.navigate(ROUTE_CONVERSATION) {
                            launchSingleTop = true
                        }
                    }
                },
                onNewSession = {
                    workspaceScope.launch {
                        if (stateHolder.prepareNewSession()) {
                            navController.navigate(ROUTE_CONVERSATION)
                        }
                    }
                },
                onPlugins = { navController.navigate(ROUTE_PLUGINS) },
                onScheduledTasks = { navController.navigate(ROUTE_SCHEDULED_TASKS) },
                updateModel = updateModel,
                // B4 §4：设置内的选择（预设/模型/字体大小）已改 sheet，不再有对应路由
                onOpenStorage = { navController.navigate(ROUTE_SETTINGS_STORAGE) }
            )
        }
        composable(ROUTE_CONVERSATION) {
            ConversationScreen(
                stateHolder, onPickImage, navController::popBackStack, onPickFile,
                onPlugins = { navController.navigate(ROUTE_PLUGINS) { launchSingleTop = true } },
                onScheduledTasks = { navController.navigate(ROUTE_SCHEDULED_TASKS) { launchSingleTop = true } },
                onSettings = { navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true } }
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                updateModel = updateModel,
                stateHolder = stateHolder,
                onBack = navController::popBackStack,
                // B4 §4：设置内的选择（预设/模型/字体大小）已改 sheet，不再有对应路由
                onOpenStorage = { navController.navigate(ROUTE_SETTINGS_STORAGE) }
            )
        }
        composable(ROUTE_SETTINGS_STORAGE) {
            StorageSpaceScreen(stateHolder, navController::popBackStack)
        }
        composable(ROUTE_PLUGINS) {
            PluginsScreen(
                stateHolder = stateHolder,
                onBack = navController::popBackStack,
                onOpenBundle = { navController.navigate("plugins/bundle/${Uri.encode(it)}") },
                onOpenOfficial = { navController.navigate("plugins/official/${Uri.encode(it)}") }
            )
        }
        composable(ROUTE_PLUGIN_BUNDLE) { entry ->
            PluginBundleDetailScreen(
                stateHolder = stateHolder,
                bundleName = entry.arguments?.getString("bundleName").orEmpty(),
                onBack = navController::popBackStack
            )
        }
        composable(ROUTE_PLUGIN_OFFICIAL) { entry ->
            OfficialPluginDetailScreen(
                stateHolder = stateHolder,
                pluginId = entry.arguments?.getString("pluginId").orEmpty(),
                onBack = navController::popBackStack
            )
        }
        composable(ROUTE_SCHEDULED_TASKS) {
            ScheduledTasksScreen(
                stateHolder = stateHolder,
                onBack = navController::popBackStack,
                onOpenSession = { sessionId ->
                    stateHolder.selectSession(sessionId)
                    navController.navigate(ROUTE_CONVERSATION) {
                        popUpTo(ROUTE_WORKSPACE)
                        launchSingleTop = true
                    }
                }
            )
        }
    }
    stateHolder.platformError?.let { error ->
        DshAlertDialog(
            title = "DeepSeek Harness",
            message = appText(error),
            onDismissRequest = stateHolder::clearPlatformError,
            dismissLabel = appText("好"),
            onDismissClick = stateHolder::clearPlatformError,
            confirmLabel = appText(
                if (stateHolder.platformErrorAction == com.clarklevis.dsh.android.PlatformErrorAction.PAIR) "重新扫码配对" else "重新连接"
            ),
            onConfirm = {
                val pair = stateHolder.platformErrorAction == com.clarklevis.dsh.android.PlatformErrorAction.PAIR
                stateHolder.clearPlatformError()
                if (pair) stateHolder.requestPairing() else stateHolder.connect()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerDestinationScreen(title: String, message: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TopBarCircleButton(
                        iconRes = R.drawable.ic_back_chevron,
                        description = appText("返回"),
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Text(message, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceScreen(
    stateHolder: AndroidSharedStateHolder,
    onOpenSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onPlugins: () -> Unit,
    onScheduledTasks: () -> Unit,
    updateModel: com.clarklevis.dsh.android.update.AppUpdateViewModel,
    onOpenAgentPresets: () -> Unit = {},
    onOpenDefaultModel: () -> Unit = {},
    onOpenFontSize: () -> Unit = {},
    onOpenStorage: () -> Unit = {}
) {
    val window = adaptiveWindowSize()
    val workspaceListState = rememberLazyListState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showManualPairing by rememberSaveable { mutableStateOf(false) }
    var showQrScanner by rememberSaveable { mutableStateOf(false) }
    var showWorkspaceMenu by rememberSaveable { mutableStateOf(false) }
    var homeTab by rememberSaveable { mutableStateOf(DshHomeTab.WORKSPACE) }
    // tab bar 内容净空 = 动态导航安全区 + 栏高 62 + 栏底上移（8 + 62/2，B2）+ 8dp 呼吸位。
    // iOS 同语义：safeAreaInset(edge:.bottom) 动态吃安全区 + `padding(.bottom, sm + totalHeight/2)`
    // = 39pt（WorkspaceView:334-339 两条真机反馈叠加）。B2 把栏抬高 35dp 后此处必须同步，
    // 否则抬起来的栏会压住最近会话最后一行。两处都引用 DshTabBar 的常量，改栏高自动跟随。
    val navBarInset = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    // 内容避让量拆两个值（规格 v1.5 / AndroidTester 21:49）—— 一个值喂两种 inset 语义会多算一整条导航栏：
    // ① tabBarInset = 栏高 62 + 栏底上移 39 = **101dp**，给**已有** safeDrawing contentPadding 的列表
    //    （工作区 LazyColumn :409，Spacer 在它内部，再加 navBarInset 就吃两次）；
    // ② tabClearance = 101 + navBarInset，给**自身零 inset 来源**的独立页（BotTeamScreen / SettingsScreen，
    //    两文件里 WindowInsets|navigationBars|safeDrawing 零命中）。
    // 顺带：**别再额外 +8** —— 那 8 已在 39 里（8 + 62/2），重复会早停 8dp（iOSTester 21:45 核出）。
    // Bot 页状态点橙档（iOS statusTint：有待审批/待回答 ⇒ caution）——数据在 shared 快照里现成。
    val pendingSessionIds = (stateHolder.snapshot.pendingApprovals.map { it.sessionId } +
        stateHolder.snapshot.pendingQuestions.map { it.sessionId }).toSet()
    val tabBarInset = DshTabBarTotalHeight + DshTabBarBottomRise
    val tabClearance = tabBarInset + navBarInset
    var showDirectoryBrowser by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(stateHolder.gatewayState.connection) { stateHolder.refreshProductState() }
    // app 级弹窗（凭证失效）点「重新扫码配对」→ 这里真正打开扫码 UI。
    LaunchedEffect(stateHolder.pairingRequestTick) {
        if (stateHolder.pairingRequestTick > 0) {
            stateHolder.clearPlatformError()
            showQrScanner = true
        }
    }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) delay(350)
        stateHolder.search(searchQuery)
    }

    val workspaces = stateHolder.availableWorkspaces
    val selectedWorkspace = stateHolder.activeWorkspace
    // 2026-10-27（fork）：会话 → 所属工作区标题，供"全部会话"视图标注归属。
    val sessionFolderLabels = remember(workspaces) {
        buildMap { workspaces.forEach { ws -> ws.sessionIds.forEach { sid -> put(sid, ws.title) } } }
    }
    val showingAllWorkspaces = stateHolder.selectedWorkspaceId == AndroidSharedStateHolder.ALL_WORKSPACES_ID
    // A1 §1.6 段头右侧「全部 N 个」：iOS sessionsHeader 的可点链接，展开最近会话列表（默认 12 行）
    var sessionsExpanded by rememberSaveable { mutableStateOf(false) }
    val ungroupedSelected = stateHolder.isUngroupedWorkspaceSelected
    val sessions = remember(
        stateHolder.snapshot.sessions,
        stateHolder.childSessionIds,
        stateHolder.gatewayState.connection,
        stateHolder.snapshot.searchResultSessionIds,
        stateHolder.localChatMatches,
        stateHolder.remoteSearchResultQuery,
        workspaces,
        stateHolder.selectedWorkspaceId,
        searchQuery
    ) {
        val scoped = workspaceScopedSessions(
            sessions = stateHolder.snapshot.sessions,
            workspaces = workspaces,
            selectedWorkspaceId = stateHolder.selectedWorkspaceId,
            childSessionIds = stateHolder.childSessionIds
        )
        if (searchQuery.isBlank()) {
            scoped
        } else {
            filterSessionsByQuery(
                scoped,
                searchQuery,
                stateHolder.localChatMatches.mapTo(mutableSetOf()) { it.sessionId },
                stateHolder.snapshot.searchResultSessionIds.toSet().takeIf {
                    stateHolder.remoteSearchResultQuery == searchQuery.trim()
                }.orEmpty()
            )
        }
    }

    WorkspaceDrawer(
        onPlugins = onPlugins,
        onScheduledTasks = onScheduledTasks,
        // iOSTester 21:55：原来这里走 `navigate(ROUTE_BOT_TEAM)` 推入整页，而 `homeTab` 仍是 WORKSPACE
        // → 底栏 pill 停在工作区、内容却是团队页（两套导航各管一半，不变量被破坏）。
        // iOS 的团队页是 **tab 内容分段**（WorkspaceView 注释：不再 push 盖住本页），所以侧栏入口改为切 tab。
        onBotTeam = { homeTab = DshHomeTab.BOTS }
    ) { openDrawer, canScrollVertically ->
        val backgroundAnimationEnabled by remember(canScrollVertically, workspaceListState) {
            derivedStateOf { canScrollVertically && !workspaceListState.isScrollInProgress }
        }
        // **host 上提到这一层**（材质批）：原先只包 WORKSPACE 分支 → tab bar 是它的**兄弟节点**，
        // `LocalDshBackdrop` 对栏恒为 null，栏只走 fallback（无模糊、只能垫白）。
        // 上提后 tab bar 成为 host 子节点，拿到同一张 backdrop → **真背景模糊**（对齐 iOS `.thinMaterial`）。
        DshLiquidGlassHost(
            modifier = Modifier.fillMaxSize(),
            background = { backdropModifier ->
                HarnessAnimatedBackground(
                    backdropModifier,
                    animationEnabled = backgroundAnimationEnabled,
                    isLight = !LocalDshIsDark.current // 背景跟主题（Tester 返工 1，方案 B 裁决）
                )
            }
        ) {
        when (homeTab) {
            DshHomeTab.WORKSPACE -> Box(Modifier.fillMaxSize().testTag("workspace-screen")) {
            // ⑨①/⑨②：top 与 Bot 页同源（dshHomeListTopInset() = statusBars+18），
            // 其它边保持 safeDrawing（左右/底部不变）。
            val density = LocalDensity.current
            val layoutDirection = LocalLayoutDirection.current
            val workspaceListPadding = PaddingValues(
                start = with(density) { WindowInsets.safeDrawing.getLeft(this, layoutDirection).toDp() },
                top = dshHomeListTopInset(),
                end = with(density) { WindowInsets.safeDrawing.getRight(this, layoutDirection).toDp() },
                bottom = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = workspaceListPadding,
                horizontalAlignment = Alignment.CenterHorizontally,
                userScrollEnabled = canScrollVertically,
                state = workspaceListState
            ) {
                item {
                    AdaptiveWorkspaceContent(
                        window = window,
                        header = {
                            // A1 §1.2：页头只有 字标 + 认证菜单 + 新建会话圆钮（iOS 无 hero、网关行不在页头）
                            WorkspaceHeader(
                                state = stateHolder.gatewayState,
                                onScan = {
                                    stateHolder.clearPlatformError()
                                    showQrScanner = true
                                },
                                onManualEntry = {
                                    stateHolder.clearPlatformError()
                                    showManualPairing = true
                                },
                                onOpenDrawer = openDrawer,
                                onNewSession = onNewSession
                            )
                        },
                        actions = {
                            // A1 §1.3/§1.4：工作区卡 → 网关行（独立一行）；hero 与整行「新建会话」按钮已删
                            // （iOS 首页没有「探索未至之境」文案，新建会话是页头右侧 34×34 圆钮）。
                            Box(Modifier.fillMaxWidth()) {
                                WorkspaceCard(
                                    workspace = selectedWorkspace,
                                    ungrouped = ungroupedSelected,
                                    ungroupedCount = stateHolder.snapshot.sessions.count { session ->
                                        session.isVisibleInHistory && session.id !in stateHolder.childSessionIds &&
                                            workspaces.none { session.id in it.sessionIds }
                                    },
                                    allWorkspaces = showingAllWorkspaces,
                                    sessionCount = sessions.size,
                                    state = stateHolder.gatewayState,
                                    onClick = { showWorkspaceMenu = true }
                                )
                                WorkspaceSelectionMenu(
                                    expanded = showWorkspaceMenu,
                                    workspaces = workspaces,
                                    selectedWorkspaceId = stateHolder.selectedWorkspaceId,
                                    onSelect = { workspaceId ->
                                        stateHolder.selectWorkspace(workspaceId)
                                        showWorkspaceMenu = false
                                    },
                                    onAddWorkspace = {
                                        showWorkspaceMenu = false
                                        showDirectoryBrowser = true
                                    },
                                    onDismiss = { showWorkspaceMenu = false }
                                )
                            }
                            GatewaySwitcherBar()
                            // A1 §1.5 运行中会话卡（iOS runningSessionCard :417）：有运行中会话才出现
                            sessions.firstOrNull { it.isRunning }?.let { running ->
                                val stats = stateHolder.snapshot.statsBySessionSnapshot[running.id]
                                LaunchedEffect(running.id) { stateHolder.requestRunningSessionStats(running.id) }
                                RunningSessionCard(
                                    session = running,
                                    stats = stats,
                                    offline = stateHolder.gatewayState.connection != GatewayConnectionState.CONNECTED,
                                    onClick = { onOpenSession(running.id) }
                                )
                            }
                        },
                        recentSessions = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // A1 §1.6 段头：「最近会话」11pt semibold ink(0.55)（iOS sessionsHeader :1254）
                                Text(appText("最近会话"), color = homeInk(0.55f), fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                Spacer(Modifier.weight(1f))
                                // 连接状态在 §1.4 网关行里已有；段头右侧按 iOS 换成「全部 N 个」+ chevron
                                Row(
                                    Modifier.clickable(role = Role.Button) { sessionsExpanded = true }
                                        .testTag("sessions-show-all"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        appText("全部 %1\$s 个", sessions.size),
                                        color = homeInk(0.4f), // iOS ink(0.40)
                                        fontSize = 11.sp
                                    )
                                    Image(
                                        painter = painterResource(R.drawable.ic_chevron_right),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(homeInk(0.4f)),
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }
                            LocalChatSearchContent(stateHolder, searchQuery, { searchQuery = it }, onOpenSession)
                            if (sessions.isEmpty()) EmptySessions()
                            else SessionList(sessions, { id ->
                                if (searchQuery.isNotBlank()) stateHolder.localChatMatches.firstOrNull { it.sessionId == id }
                                    ?.let(stateHolder::prepareLocalChatMatch)
                                onOpenSession(id)
                            }, stateHolder::renameSession, stateHolder::archiveSession,
                                folderLabels = sessionFolderLabels,
                                maxRows = if (showingAllWorkspaces || sessionsExpanded) 200 else 12)
                            Spacer(Modifier.height(24.dp))
                        }
                    )
                }
                item { Spacer(Modifier.height(tabBarInset)) } // 浮动 tab bar 净空 101（栏高 62 + 上移 39）；列表自带 safeDrawing，故不含 navBarInset
            }
            } // DshHomeTab.WORKSPACE
            // P0-1（Chief 2026-11-01 改裁决）：tab 必须切内容 —— 「点了没反应」在用户眼里就是坏的。
            // Bot/设置两页复用现成整页（自带顶栏），返回键 = 回工作区 tab；滚动尾部按 tab bar 高度留净空。
            DshHomeTab.BOTS -> BotTeamScreen(
                adapter = stateHolder.bridgeTeam,
                onBack = { homeTab = DshHomeTab.WORKSPACE },
                bottomInset = tabClearance,
                embedded = true, // tab 根分段：不自绘顶栏，页头由下方 header 注入
                pendingSessionIds = pendingSessionIds,
                onOpenSession = onOpenSession,
                onRequestPairing = {
                    stateHolder.clearPlatformError()
                    showQrScanner = true
                },
                deviceIdentityUnreadable = stateHolder.deviceIdentityUnreadable,
                tokenUnreadable = stateHolder.tokenUnreadable,
                // iOS 三个 tab 共用同一条页头（WorkspaceView:936 字标 + :950 认证圆钮 + :955 ＋新建）。
                // vc39 之前 Bot tab 只有一条居中「团队」标题，是 Android 自造的（用户 11-02 指出）。
                header = {
                    WorkspaceHeader(
                        state = stateHolder.gatewayState,
                        onScan = {
                            stateHolder.clearPlatformError()
                            showQrScanner = true
                        },
                        onManualEntry = {
                            stateHolder.clearPlatformError()
                            showManualPairing = true
                        },
                        onOpenDrawer = openDrawer,
                        onNewSession = onNewSession
                    )
                }
            )
            DshHomeTab.SETTINGS -> SettingsScreen(
                updateModel = updateModel,
                stateHolder = stateHolder,
                onBack = { homeTab = DshHomeTab.WORKSPACE },
                onOpenStorage = onOpenStorage,
                onOpenPlugins = onPlugins,
                onOpenScheduledTasks = onScheduledTasks,
                bottomInset = tabClearance,
                embedded = true // iOSDev 裁定：设置 tab 撤顶栏 + 页顶自绘「设置」大标题（对齐 iOS embedded:）
            )
        }
        DshTabBar(
            selected = homeTab,
            badges = emptyMap(), // 角标三来源（WorkspaceView :366-370）等 D 组；bridge 侧暂无 pending 数据
            onSelect = { homeTab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                // B2：栏底 = **系统底区 + 39**（8 + 62/2，照 iOS 表达式，不写死）。原来只有 4dp → 用户两轮报「太贴底」。
                // **不要为了让数字对上 iOS 而补 34pt**（Chief 2026-11-01 裁决，已由图与源码确认）：
                // iOS 那个 34 是 **Home 指示条占据的可交互区**，不是设计留白；Android 的对应物是手势条，
                // 已由 `navigationBarsPadding()` 吃掉。硬补会在系统底区之上再叠一块死白 → 判据通过、观感变差。
                // **最终判据（Chief 2026-11-01 定稿，两端同式、截图可判）**：
                //   容器底 ∈ {39, 73}（39 = 生效底区 0；73 = 39 + 34 安全区）——既不是 39 也不是 73 才算红；
                //   对应蓝 pill 底 ∈ {43, 77}，且 `蓝 pill 底 − 容器底 = 4`（内层 padding，与机型无关，可当自检）。
                // **真机已证实落在 39/43 这一支（2026-11-01 用户 iPhone 图，1284×2778 = 428×926pt @3x）**：
                //   胶囊 126.67×54.00pt、底距 43.00pt，且**Home 指示条确实渲染着**（距底 ≈9pt、居中、暗条）——
                //   那条 34pt 安全区**没有被叠加**。三个环境（26.3 模拟器 / 10-29 模拟器 / iOS 16.4 真机）一致 ⇒
                //   **「iOS 比 Android 高 34pt」的平台固有差不存在，该说法退役**：iOS 43pt 与 Android 43dp 逐值相同。
                //   （若当初在 Android 补那 34，反而会造出一个 iOS 没有的差。）
                // 依据：两张 iOS 模拟器图（首页 00:38 / 设置页 22:58）实测**都是容器底 39、pill 底 43** ⇒ 生效底区 = 0；
                // 源码侧 `WorkspaceView.swift:318-341` 前面没有 `.ignoresSafeArea`（全仓 4 处都不在 tab 容器路径上）
                // ⇒ 代码没抹掉安全区，差异在环境/窗口层。所以**不能拿模拟器图当「34 类机型」的分母**，
                // 判据仍是二选一（别的机型可能叠安全区），但**本批用户真机实测 = 39/43（已证）**，
                // 73 是"叠了安全区"那一支的备选、本批未出现——别把 73 当本批预期。
                // Android 本机 inset≈0 时量到 43dp，与真机/模拟器同值，无平台差。
                // 水平 16 只由 DshTabBar 内部那一处给（iOS 也只有一次 lg=16）：原先调用点再叠 16 = 两侧 32，比 iOS 每侧窄 16dp。
                .padding(bottom = DshTabBarBottomRise)
        )
        } // DshLiquidGlassHost（内容 + tab bar 同层 → 栏有 backdrop）
    }

    if (showQrScanner) {
        GatewayQrScannerScreen(
            onCode = { payload ->
                showQrScanner = false
                stateHolder.pair(payload)
            },
            onCancel = { showQrScanner = false },
            onFailure = { message ->
                showQrScanner = false
                stateHolder.showPlatformError(message)
            }
        )
    }
    if (showManualPairing) {
        ManualGatewayPairingSheet(stateHolder) { showManualPairing = false }
    }
    if (showDirectoryBrowser) {
        WorkspaceDirectoryBrowserSheet(
            stateHolder = stateHolder,
            onDismiss = { showDirectoryBrowser = false }
        )
    }
}

@Composable
private fun WorkspaceHeader(
    state: GatewayRuntimeState,
    onScan: () -> Unit,
    onManualEntry: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNewSession: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Row 先测量无权重的操作按钮，品牌只使用剩余宽度，避免窄屏压缩按钮。
        HarnessMark(onClick = onOpenDrawer, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        GatewayAuthenticationMenu(
            state = state,
            onScan = onScan,
            onManualEntry = onManualEntry
        )
        // B1：顶栏的设置齿轮已删 —— iOS 把「设置」收进底部 tab 后就不再留重复入口。
        // A1 §1.2：右侧是 **34×34 品牌色实心圆 + plus 15pt bold 白**，未配对时不渲染
        // （iOS WorkspaceView:931 header；这里用 endpoint 是否配置当「已配对」代理）。
        if (state.endpoint != null) {
            Spacer(Modifier.width(12.dp))
            NewSessionButton(onNewSession)
        }
    }
}

@Composable
private fun NewSessionButton(onClick: () -> Unit) {
    val dark = LocalDshIsDark.current
    Box(
        Modifier.size(34.dp)
            .clip(CircleShape)
            .background(if (dark) DshColors.OceanDark else DshColors.Ocean)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = appText("新建会话") }
            .testTag("new-session-button"),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            colorFilter = ColorFilter.tint(Color.White)
        )
    }
}

@Composable
private fun HarnessMark(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dark = LocalDshIsDark.current
    BoxWithConstraints(
        modifier = modifier.heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = appText("打开侧边栏") },
        contentAlignment = Alignment.CenterStart
    ) {
        // B1 + A2：对齐 iOS `HarnessMark`（Components/Glass.swift:186-203）
        // ① 鲸鱼已删 —— iOS 按用户 2026-10-26 真机反馈（「字的左右还有原版的图标在」）移除，Android 跟删；
        // ② 字标宽 **216**（iOS `.frame(maxWidth: 216)` 的基准值）、高 29，点按开侧栏；
        // ③ **一张图 + 按主题反相**（iOS `colorInvertIfNeeded(onDarkBackground: colorScheme == .dark)`）：
        //    不再「挑 light/dark 变体」——那正是之前硬编码 `_dark` 白字、浅色下字标变白的坑。
        //    反相用 4×5 颜色矩阵（RGB 取负 + 255 偏移，alpha 不动），等价 iOS 的 colorInvert。
        // 不按机型或屏幕像素缩放整页：仅品牌图片根据其实际可用宽度等比例收缩。
        val showWordmark = maxWidth >= 128.dp
        val scale = (maxWidth / 216.dp).coerceIn(0f, 1f)
        if (showWordmark) {
            // 共享实现（OrcarnessWordmark.kt）：反相与 29dp 高度只有一处，三处调用点共用。
            // **这里有意在调用点先乘 scale**（Android 自造：可用宽度不足 216 时连续收缩）——
            // iOS WorkspaceView.swift:937 只有 `dynamicTypeSize.isAccessibilitySize ? 150 : 216` 一档，
            // 既不做连续缩放也不隐藏。该规则**不搬进共享组件**，否则会扩散到抽屉/会话侧栏那两处
            // （iOS 那两处分别是固定 min(width-48,216) 与 116）。360dp 手机上两者都是 216，无可见差异。
            OrcarnessWordmark(width = 216.dp * scale, height = 29.dp * scale)
        }
    }
}


@Composable
internal fun OrcarnessOrcaIcon(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Image(
        painter = painterResource(R.drawable.dsh_orca_mark),
        contentDescription = "Orcarness",
        modifier = modifier,
        colorFilter = ColorFilter.tint(tint)
    )
}

@Composable
internal fun GlassCircleButton(iconRes: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier.requiredSize(52.dp)
            .dshLiquidGlass(CircleShape, DshGlassStyle.CONTROL)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(homeInk())
        )
    }
}

@Composable
private fun WorkspaceCard(
    workspace: GatewayWorkspace?,
    ungrouped: Boolean,
    ungroupedCount: Int,
    allWorkspaces: Boolean = false,
    sessionCount: Int = 0,
    state: GatewayRuntimeState,
    onClick: () -> Unit
) {
    Row(
        // A1 §1.3：壳 padding 12 全边、圆角 16、cardFill + 1pt cardStroke（iOS workspaceCard :1186/:1226）
        Modifier.fillMaxWidth()
            .dshLiquidGlass(RoundedCornerShape(16.dp), DshGlassStyle.CARD)
            .border(1.dp, homeStroke(if (LocalDshIsDark.current) 0.12f else 0.17f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
            .testTag("workspace-card"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_folder_outline),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(Color(0xFF168BFF))
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(
                if (allWorkspaces) appText("全部会话")
                else if (ungrouped) appText("未分组")
                else workspace?.title ?: "DeepseekHarnessProject",
                color = homeInk(0.92f), // iOS 标题 15pt semibold ink(0.92)
                fontSize = 15.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (allWorkspaces) appText("%1\$s 个会话（跨全部工作区）", sessionCount)
                else if (ungrouped) appText("%1\$s 个未归属会话", ungroupedCount)
                else workspace?.path ?: appText("通过 Mobile Gateway 连接"),
                color = homeInk(0.5f), // iOS 路径 mono 12pt ink(0.5)
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        ConnectionDot(state)
        // iOS 右侧是 chevron.right 11pt semibold ink(0.35)（不是下拉三角）
        Image(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            colorFilter = ColorFilter.tint(homeInk(0.35f)),
            modifier = Modifier.size(11.dp)
        )
    }
}

/**
 * A1 §1.5 运行中会话卡：VStack(spacing 12)；
 * 行 1 = 状态点 + 标题 13 semibold ink(0.92) + 相对时间 11 ink(0.45) + chevron.forward 11 品牌色；
 * 行 2 = 两列指标：数值 monoValue 17 ink(0.92) + 标签 11 ink(0.45)（「轮次」「步骤」）；
 * 壳 padding 16 / 圆角 20 / cardFill + 1pt cardStroke；离线整体 0.6 透明。
 * 指标来源：`snapshot.statsBySessionSnapshot[sessionId]`（shared 侧按会话缓存，缺失时不编数，显示 —）。
 */
@Composable
private fun RunningSessionCard(
    session: SessionSummary,
    stats: GatewaySessionStatsSnapshot?,
    offline: Boolean,
    onClick: () -> Unit
) {
    val fill = if (LocalDshIsDark.current) Color.White.copy(alpha = 0.06f)
    else Color.White.copy(alpha = 0.55f)
    Column(
        Modifier.fillMaxWidth()
            .then(if (offline) Modifier.alpha(0.6f) else Modifier)
            .background(fill, RoundedCornerShape(20.dp))
            .border(1.dp, homeStroke(if (LocalDshIsDark.current) 0.12f else 0.17f), RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp)
            .testTag("workspace-running-session"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(DshSemantic.success, CircleShape))
            Text(
                session.title,
                color = homeInk(0.92f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(relativeTime(session.lastActivityEpochSeconds), color = homeInk(0.45f), fontSize = 11.sp)
            Image(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                colorFilter = ColorFilter.tint(DshColors.OceanDark),
                modifier = Modifier.size(11.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            RunningMetric(value = stats?.stats?.turns?.toString() ?: "—", label = appText("轮次"))
            RunningMetric(value = stats?.stats?.steps?.toString() ?: "—", label = appText("步骤"))
        }
    }
}

@Composable
private fun RunningMetric(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = homeInk(0.92f), fontSize = 17.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
        Text(label, color = homeInk(0.45f), fontSize = 11.sp)
    }
}

@Composable
internal fun LocalChatSearchContent(
    stateHolder: AndroidSharedStateHolder,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onOpenSession: (String) -> Unit
) {
    SessionSearch(searchQuery, onQueryChange)
    if (searchQuery.isBlank()) return
    Text(
        text = if (stateHolder.localSearchHasMore) {
            appText("本地聊天内容（仅已缓存部分，显示前 50 条）。打开结果可定位对应消息。")
        } else {
            appText("本地聊天内容（仅已缓存部分）；联网时同时补充网关搜索。")
        },
        color = homeInk(0.7f),
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    val scopedIds = workspaceScopedSessions(
        stateHolder.snapshot.sessions,
        stateHolder.availableWorkspaces,
        stateHolder.selectedWorkspaceId,
        stateHolder.childSessionIds
    ).mapTo(mutableSetOf()) { it.id }
    val catalogIds = stateHolder.snapshot.sessions.mapTo(mutableSetOf()) { it.id }
    stateHolder.localChatMatches.filter {
        !stateHolder.isSubagentSession(it.sessionId) && (it.sessionId !in catalogIds || it.sessionId in scopedIds)
    }.forEach { match ->
        Column(
            Modifier.fillMaxWidth().clickable {
                stateHolder.prepareLocalChatMatch(match)
                onOpenSession(match.sessionId)
            }.padding(vertical = 10.dp)
        ) {
            Text(
                stateHolder.snapshot.sessions.firstOrNull { it.id == match.sessionId }?.title ?: appText("已缓存会话"),
                color = homeInk(),
                fontWeight = FontWeight.Medium
            )
            Text(match.snippet, color = homeInk(0.8f), maxLines = 3, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun SessionSearch(query: String, onChange: (String) -> Unit) {
    Row(
        // A1 §1.6 搜索框：高 42、圆角 12、cardFill + 1pt cardStroke、左右 padding 12（iOS sessionSearch :1293）
        modifier = Modifier.fillMaxWidth().height(42.dp)
            .dshLiquidGlass(RoundedCornerShape(12.dp), DshGlassStyle.FIELD)
            .border(1.dp, homeStroke(if (LocalDshIsDark.current) 0.12f else 0.17f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp)
            .testTag("workspace-session-search"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(homeInk(0.68f))
        )
        BasicTextField(
            value = query,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(color = homeInk(0.92f), fontSize = 16.sp),
            cursorBrush = SolidColor(homeInk()),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(appText("搜索会话内容"), color = homeInk(0.58f), fontSize = 15.sp) // iOS placeholder 11~15pt
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun EmptySessions() {
    Text(
        appText("暂无已知会话。连接服务后创建第一个任务。"),
        color = homeInk(0.5f),
        fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth()
            .dshLiquidGlass(RoundedCornerShape(15.dp), DshGlassStyle.SUBTLE)
            .padding(18.dp)
    )
}

// 会话归属标签：优先工作区标题，其次退回 cwd 末段；与标题相同则不显示，避免重复。
private fun sessionFolderLabel(session: SessionSummary, folderLabels: Map<String, String>): String? {
    val label = folderLabels[session.id]
        ?: session.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
    return label?.takeIf { it != session.title }
}

@Composable
private fun SessionList(
    sessions: List<SessionSummary>,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onArchive: (String) -> Unit,
    folderLabels: Map<String, String> = emptyMap(),
    maxRows: Int = 12
) {
    Column(Modifier.background(Color.Transparent)) {
        sessions.take(maxRows).forEach { session ->
            androidx.compose.runtime.key(session.id) {
                var menuVisible by remember { mutableStateOf(false) }
                var renaming by remember { mutableStateOf(false) }
                var archiving by remember { mutableStateOf(false) }
                var title by remember { mutableStateOf(session.title) }
                Box {
                    SessionRow(
                        session,
                        folderLabel = sessionFolderLabel(session, folderLabels),
                        onClick = { onOpen(session.id) },
                        onLongClick = { menuVisible = true })
                    SessionContextMenu(
                        expanded = menuVisible,
                        onDismissRequest = { menuVisible = false },
                        onRename = {
                            title = session.title
                            menuVisible = false
                            renaming = true
                        },
                        onArchive = {
                            menuVisible = false
                            archiving = true
                        }
                    )
                }
                if (renaming) RenameSessionDialog(
                    title = title,
                    onTitleChange = { title = it },
                    onDismissRequest = { renaming = false },
                    onSave = {
                        onRename(session.id, title)
                        renaming = false
                    }
                )
                if (archiving) AlertDialog(
                    onDismissRequest = { archiving = false },
                    title = { Text(appText("删除会话？")) },
                    text = { Text(appText("会话将被归档并从列表隐藏，历史记录会保留。")) },
                    confirmButton = {
                        TextButton(onClick = { onArchive(session.id); archiving = false }) { Text(appText("删除")) }
                    },
                    dismissButton = { TextButton(onClick = { archiving = false }) { Text(appText("取消")) } }
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 18.dp),
                thickness = 0.5.dp,
                color = homeStroke(0.10f)
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    folderLabel: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 4.dp, vertical = 10.dp)
            .testTag("workspace-session-${session.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        val dot = if (session.isRunning) DshSemantic.success else if (session.hasUnread) DshColors.Ocean else homeInk(0.35f)
        StatusIndicatorDot(
            color = dot,
            modifier = Modifier.size(7.dp),
            glowing = session.isRunning
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = session.title,
                color = homeInk(),
                fontSize = 15.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            Text(
                text = folderLabel?.let { "$it · ${session.id.take(16)}" } ?: session.id.take(16),
                color = homeInk(0.42f),
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
        Text(
            if (session.isRunning) appText("运行中") else relativeTime(session.lastActivityEpochSeconds),
            color = if (session.isRunning) Color(0xFF68A0FF) else homeInk(0.48f),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun ConnectionDot(state: GatewayRuntimeState) {
    val color = when (state.connection) {
        GatewayConnectionState.CONNECTED -> DshSemantic.success
        else -> Color.Red
    }
    StatusIndicatorDot(
        color = color,
        modifier = Modifier.size(8.dp),
        glowing = state.connection == GatewayConnectionState.CONNECTED
    )
}

internal fun filterSessionsByQuery(
    sessions: List<SessionSummary>,
    query: String,
    localBodySessionIds: Set<String>,
    remoteSessionIds: Set<String> = emptySet()
): List<SessionSummary> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return sessions
    return sessions.filter {
        it.title.contains(normalized, ignoreCase = true) || it.id.contains(normalized, ignoreCase = true) ||
            it.id in localBodySessionIds || it.id in remoteSessionIds
    }
}

internal fun workspaceScopedSessions(
    sessions: List<SessionSummary>,
    workspaces: List<GatewayWorkspace>,
    selectedWorkspaceId: String?,
    childSessionIds: Set<String> = emptySet()
): List<SessionSummary> {
    if (selectedWorkspaceId == AndroidSharedStateHolder.ALL_WORKSPACES_ID) {
        return sessions.filter { it.isVisibleInHistory && it.id !in childSessionIds }
    }
    val selected = workspaces.firstOrNull { it.workspaceId == selectedWorkspaceId }
        ?: workspaces.firstOrNull()
    if (
        selectedWorkspaceId == AndroidSharedStateHolder.UNGROUPED_WORKSPACE_ID ||
        selected == null
    ) {
        val assigned = workspaces.flatMapTo(mutableSetOf()) { it.sessionIds }
        return sessions.filter { it.isVisibleInHistory && it.id !in childSessionIds && it.id !in assigned }
    }
    return sessions.filter { it.isVisibleInHistory && it.id !in childSessionIds && it.id in selected.sessionIds }
}

internal fun relativeTime(
    epochSeconds: Double,
    nowMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault()
): String {
    val eventMillis = (epochSeconds * 1_000).toLong().coerceAtMost(nowMillis)
    val elapsed = ((nowMillis - eventMillis) / 1_000).coerceAtLeast(0)
    val dayDifference = localDayIndex(nowMillis, timeZone) - localDayIndex(eventMillis, timeZone)
    return when {
        dayDifference == 1L -> appText("昨天")
        dayDifference == 2L -> appText("前天")
        dayDifference > 2L -> appText("%1\$s 天前", dayDifference)
        elapsed < 60 -> appText("刚刚")
        elapsed < 3_600 -> appText("%1\$s 分钟前", elapsed / 60)
        else -> appText("%1\$s 小时前", elapsed / 3_600)
    }
}

private fun localDayIndex(epochMillis: Long, timeZone: TimeZone): Long {
    val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = epochMillis }
    val previousYear = calendar.get(Calendar.YEAR).toLong() - 1
    return previousYear * 365 +
        previousYear / 4 -
        previousYear / 100 +
        previousYear / 400 +
        calendar.get(Calendar.DAY_OF_YEAR)
}

private const val WHALE_ICON_PATH = "M22.9168 1.43018C22.6713 1.31018 22.5658 1.53918 22.4223 1.65519C21.9317 2.1697 21.5127 2.42121 20.9657 2.39121C20.1657 2.34621 19.4827 2.59771 18.8787 3.20973C18.7502 2.45521 18.3236 2.0047 17.6746 1.71569C16.5876 .856163 16.5421 .597155 16.4591 .341647C16.3536 .0301382 16.1761 .00363739 15.8326 .270145C15.5306 .822162 15.4136 1.43018 15.4251 2.0462C15.4516 3.43174 16.0366 4.53527 17.1991 5.3203C17.3651 5.4103 17.3651 5.5003 17.3236 5.63181C17.0671 6.43533 16.9351 6.64584 16.7501 6.57033C14.2475 4.63328 13.5 3.75075 12.568 3.05973C10.9524 1.68169 12.028 .923165 12.277 .833162C12.5375 .739159 12.3675 .41615 11.5259 .42015C10.6844 .42365 9.91439 .705658 8.48384 1.21267C5.70226 1.11517 3.88321 1.31768 1.36213 3.64575C.0790928 5.4103-.222916 7.41536.146595 9.50642C.535106 11.7105 1.66014 13.535 3.38869 14.9616C5.18125 16.4406 7.24581 17.1657 9.60138 17.0266C11.0319 16.9441 12.6245 16.7526 14.421 15.2321C16.7456 15.6716 17.3306 15.5851 17.7836 15.4911C18.4931 15.3411 18.4441 14.6841 18.1876 14.5636C16.1081 13.595 16.5646 13.9891 16.1496 13.67C17.2061 12.42 18.8202 10.1979 19.3182 7.17235C19.4182 5.93231 19.4562 5.86831 19.6447 5.84931C22.4833 4.65528 23.0268 3.44624 23.1548 1.9972C23.1738 1.77569 23.1508 1.54668 22.9168 1.43018Z"
