package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.protocol.GatewayAgentPreset
import com.clarklevis.dsh.shared.protocol.GatewayModelGroup
import com.clarklevis.dsh.shared.protocol.GatewayModelItem
import com.clarklevis.dsh.shared.protocol.GatewayPermissionOption
import com.clarklevis.dsh.shared.protocol.GatewayReasoningEffort
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    stateHolder: AndroidSharedStateHolder,
    onBack: () -> Unit,
    onOpenStorage: () -> Unit = {},
    onOpenPlugins: () -> Unit = {},
    onOpenScheduledTasks: () -> Unit = {},
    updateModel: com.clarklevis.dsh.android.update.AppUpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    bottomInset: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    /** tab 内嵌形态（iOSDev 裁定 2026-11-01，对齐 iOS SettingsView embedded:）：
     *  撤系统导航栏（无 TopAppBar / 无返回键），页顶自绘「设置」大标题
     *  = DSHType.title(18 semibold) + ink(0.92)，左对齐（SettingsView:222-228）。
     *  独立路由（非 embedded）保持原顶栏。 */
    embedded: Boolean = false
) {
    val updateState by updateModel.state.collectAsStateWithLifecycle()
    // B4 §4 交互纪律：设置内的选择一律弹层（sheet），不 push。
    // 理由照 iOS 注释：避免「推入 × 设置」死锁面。
    var activeSelectionSheet by remember { mutableStateOf<SettingsSelectionSheet?>(null) }
    var showPermissionPicker by remember { mutableStateOf(false) }
    var pendingPermission by remember { mutableStateOf<String?>(null) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var showReleaseNotes by remember { mutableStateOf(false) }
    var showCrashLog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val releaseInfo = remember(context) { AppReleaseInfo.load(context) }
    val connectedGatewayVersion = if (isConnected(stateHolder) && !stateHolder.pluginsLoading && stateHolder.pluginsError == null) {
        stateHolder.pluginCatalog?.bundles?.firstOrNull { it.name == "dsh-plugin-mobile-gateway" }?.version
    } else null
    val releaseScope = rememberCoroutineScope()
    fun openRelease(repository: String, version: String?) {
        releaseScope.launch {
            val url = withContext(Dispatchers.IO) { resolveReleaseUrl(repository, version) }
            runCatching { uriHandler.openUri(url) }
        }
    }
    val dark = isSystemInDarkTheme()
    // 材质批：底色改用 iOS 自己的 pageBackground（spec §0）——浅 `#F4F6F9`（真机实测 245.8，
    // 原 `#F1F1F6` 折算 241.4，差 4.4 灰阶）/ 深 navy `#06172B`（原 `#0D1118`）。
    val pageBackground = if (dark) DshColors.Navy else Color(0xFFF4F6F9)
    LaunchedEffect(stateHolder.gatewayState.connection) {
        stateHolder.refreshProductState()
        stateHolder.refreshCacheStorage()
        if (isConnected(stateHolder)) {
            stateHolder.refreshPlugins()
            if (stateHolder.scheduledTasks.isEmpty()) stateHolder.refreshScheduledTasks()
        }
    }
    Scaffold(
        topBar = {
            if (!embedded) CenterAlignedTopAppBar(
                title = { Text(appText("设置"), style = DshType.title) },
                navigationIcon = {
                    TopBarCircleButton(
                        iconRes = R.drawable.ic_back_chevron,
                        description = appText("返回"),
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = pageBackground)
            )
        },
        containerColor = pageBackground
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = bottomInset)
        ) {
            if (embedded) {
                // iOS SettingsView:222-228 embedded 自绘标题：DSHType.title + ink(0.92)，左对齐
                item {
                    Text(
                        appText("设置"),
                        style = DshType.title,
                        color = homeInk(0.92f),
                        modifier = Modifier.adaptiveContentWidth()
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)
                            .testTag("settings-embedded-title")
                    )
                }
            }
            item {
                Column(
                    Modifier.adaptiveContentWidth().testTag("settings-content")
                        // B4 §1：水平边距 16、顶部 4（iOS SettingsView:223-400）
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    // B4 §3：组间距 20（iOS DSHSpacing）
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // ① 外观（iOS 顺序第一组）
                    SettingsSection(
                        title = appText("外观"),
                        footer = appText("语言设置将在重新启动应用后生效。支持简体中文和英文。")
                    ) {
                        ThemeSegmentedRow()
                        SettingsDivider()
                        LanguageSettingsRow()
                        SettingsDivider()
                        FontSizeSettingsRow { activeSelectionSheet = SettingsSelectionSheet.FONT_SIZE }
                    }
                    // ② 新会话
                    SettingsSection(
                        title = appText("新会话"),
                        footer = appText("与 WebUI 使用同一份部署级设置。修改只影响之后新建的会话，运行中的会话保持启动时的配置。")
                    ) {
                        SettingsValueRow(
                            title = appText("Agent 预设"),
                            value = selectedPreset(stateHolder),
                            enabled = isConnected(stateHolder),
                            isLoading = stateHolder.defaultConfigurationLoadingKinds.any {
                                it == "defaults" || it == "agent-presets"
                            }
                        ) {
                            activeSelectionSheet = SettingsSelectionSheet.AGENT_PRESETS
                        }
                        SettingsDivider()
                        SettingsValueRow(
                            title = appText("默认模型"),
                            value = selectedModel(stateHolder),
                            enabled = isConnected(stateHolder),
                            isLoading = stateHolder.defaultConfigurationLoadingKinds.any {
                                it == "default-model" || it == "save-default-model"
                            }
                        ) {
                            activeSelectionSheet = SettingsSelectionSheet.DEFAULT_MODEL
                        }
                        SettingsDivider()
                        PermissionSettingsRow(
                            value = stateHolder.snapshot.permissionDefaultOptions
                                .firstOrNull { it.value == stateHolder.snapshot.permissionDefault }?.name
                                ?: permissionName(stateHolder.snapshot.permissionDefault),
                            selectedPermission = stateHolder.snapshot.permissionDefault,
                            options = stateHolder.snapshot.permissionDefaultOptions,
                            expanded = showPermissionPicker,
                            enabled = isConnected(stateHolder) &&
                                "set-default" !in stateHolder.defaultConfigurationLoadingKinds,
                            onExpandedChange = { showPermissionPicker = it },
                            onPermissionSelected = { pendingPermission = it }
                        )
                    }
                    // ③ 扩展：iOS 该行 → ExtensionsView（聚合入口页：插件 + 定时任务 + 一句说明），
                    // 不是直接进插件页（ExtensionsView.swift:1-10 注释：只做两个入口，不重做列表）
                    SettingsSection(title = appText("扩展")) {
                        SettingsValueRow(
                            title = appText("全部扩展"),
                            // iOS 该行值形态：「0 插件 · 0 任务」（06-settings 实测）
                            value = appText(
                                "%1\$s 插件 · %2\$s 任务",
                                stateHolder.pluginCatalog?.bundles?.size ?: 0,
                                stateHolder.scheduledTasks.size
                            ),
                            onClick = { activeSelectionSheet = SettingsSelectionSheet.EXTENSIONS }
                        )
                    }
                    // ④ 连接
                    SettingsSection(title = appText("连接")) {
                        GatewayEndpointRow(stateHolder)
                        SettingsDivider()
                        // iOS 该组只有 3 行：网关地址 / 连接状态 / Ping 网关 —— 「连接|断开」是状态行本身的动作
                        GatewayStatusRow(stateHolder)
                        SettingsDivider()
                        SettingsActionRow(appText("Ping 网关"), enabled = isConnected(stateHolder), onClick = stateHolder::pingGateway)
                    }
                    // **平台必需差异**（Chief 2026-11-01 裁定）：Android 特有的连接信息
                    // （版本 / 已连接会话 / cwd），iOS 的对应信息在「连接」组内，语义等价，保留。
                    stateHolder.snapshot.hostSnapshot?.let { host ->
                        SettingsSection("DSH Host") {
                            SettingsValueRow(appText("版本"), dshHostVersion(host.dshVersion, host.version))
                            SettingsDivider()
                            SettingsValueRow(appText("已连接会话"), "${host.attachedSessions ?: 0}")
                            host.cwd?.let { cwd ->
                                SettingsDivider()
                                SettingsValueRow("cwd", cwd)
                            }
                        }
                    }
                    // ⑤ 关于
                    SettingsSection(title = appText("关于")) {
                        // iOS SettingsView:356「当前版本」= 纯值行（无 chevron、不可点）；更新入口是下面的「检查更新」
                        SettingsValueRow(
                            title = appText("当前版本"),
                            value = updateModel.currentVersion,
                            isLoading = updateState.busy
                        )
                        SettingsDivider()
                        SettingsValueRow(appText("本版本新功能"), appText("查看发布说明")) { showReleaseNotes = true }
                        SettingsDivider()
                        SettingsActionRow(appText("隐私政策")) {
                            showPrivacyPolicy = true
                        }
                        SettingsDivider()
                        // **平台必需差异**（Chief 2026-11-01 裁定）：iOS 走 App Store 更新，
                        // Android 无对应系统通道，必须自留更新入口；不是漏删项。
                        SettingsActionRow(appText("检查更新"), enabled = !updateState.busy, onClick = updateModel::check)
                        SettingsDivider()
                        // 取证入口：闪退日志落盘在应用私有目录，用户没电脑也能看/截图（用户 2026-11-02「挂着会闪退」）。
                        val crash = com.clarklevis.dsh.android.platform.CrashLog.read(context)
                        SettingsActionRow(appText("上次崩溃日志"), enabled = crash != null) {
                            showCrashLog = true
                        }
                    }
                    // ⑥ 更多
                    SettingsSection(title = appText("更多")) {
                        // iOS SettingsView:377-390：两行都是**纯值**（无 chevron、不可点）
                        SettingsValueRow(
                            appText("要求网关版本"),
                            releaseInfo?.minimumGatewayVersion?.let { "≥ $it" } ?: appText("未提供")
                        )
                        SettingsDivider()
                        SettingsValueRow(
                            appText("已连接网关版本"),
                            when {
                                !isConnected(stateHolder) -> appText("未连接")
                                stateHolder.pluginsLoading -> appText("读取中…")
                                else -> connectedGatewayVersion ?: appText("未提供")
                            }
                        )
                        SettingsDivider()
                        SettingsValueRow(
                            title = appText("存储空间"),
                            value = stateHolder.storageUsage?.let {
                                android.text.format.Formatter.formatFileSize(context, it.totalBytes)
                            }.orEmpty(),
                            modifier = Modifier.testTag("storage-space-settings-row"),
                            onClick = onOpenStorage
                        )
                    }
                    stateHolder.platformError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, style = DshType.footnote)
                    }
                    Spacer(Modifier.padding(bottom = 20.dp))
                }
            }
        }
    }
    pendingPermission?.let { value ->
        DshAlertDialog(
            title = appText("修改全局默认权限？"),
            message = appText("将新会话的默认权限改为“%1\$s”。这会更新部署级设置，并同步影响 WebUI。", stateHolder.snapshot.permissionDefaultOptions.firstOrNull { it.value == value }?.name ?: permissionName(value)),
            confirmLabel = appText("确认修改"),
            onDismissRequest = { pendingPermission = null },
            onConfirm = {
                stateHolder.setDefault("permission", value)
                pendingPermission = null
            }
        )
    }
    if (showCrashLog) {
        // 只读展示 + 可选清空：日志含堆栈与版本，用户可直接截图发群（无需电脑）。
        val crashText = com.clarklevis.dsh.android.platform.CrashLog.read(context).orEmpty()
        DshAlertDialog(
            title = appText("上次崩溃日志"),
            onDismissRequest = { showCrashLog = false },
            dismissLabel = appText("关闭"),
            confirmLabel = appText("清空"),
            onConfirm = {
                com.clarklevis.dsh.android.platform.CrashLog.clear(context)
                showCrashLog = false
            },
            content = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        crashText,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = homeInk(0.85f),
                        modifier = Modifier.testTag("crash-log-text")
                    )
                }
            }
        )
    }
    if (showReleaseNotes) {
        DshAlertDialog(
            title = appText("本版本新功能 %1\$s", updateModel.currentVersion),
            onDismissRequest = { showReleaseNotes = false },
            dismissLabel = appText("完成"),
            confirmLabel = appText("GitHub 发布页"),
            onConfirm = { openRelease("dsh-mobile", null) },
            content = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    DshMarkdownText(
                        markdown = if (releaseInfo?.version == updateModel.currentVersion) releaseInfo.notes
                            else appText("本版本发布说明暂未提供，请查看 GitHub 发布页。"),
                        compact = true
                    )
                }
            }
        )
    }
    if (showPrivacyPolicy) {
        PrivacyPolicyBottomSheet { showPrivacyPolicy = false }
    }
    activeSelectionSheet?.let { sheet ->
        ModalBottomSheet(
            onDismissRequest = { activeSelectionSheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Box(Modifier.fillMaxHeight(0.88f)) {
                when (sheet) {
                    SettingsSelectionSheet.AGENT_PRESETS ->
                        AgentPresetSelectionScreen(stateHolder, onBack = { activeSelectionSheet = null }, embedded = true)
                    SettingsSelectionSheet.DEFAULT_MODEL ->
                        DefaultModelSelectionScreen(stateHolder, onBack = { activeSelectionSheet = null }, embedded = true)
                    SettingsSelectionSheet.FONT_SIZE ->
                        FontSizeSettingsScreen(onBack = { activeSelectionSheet = null }, embedded = true)
                    SettingsSelectionSheet.EXTENSIONS -> ExtensionsSheet(
                        pluginCount = stateHolder.pluginCatalog?.bundles?.size ?: 0,
                        taskCount = stateHolder.scheduledTasks.size,
                        onOpenPlugins = { activeSelectionSheet = null; onOpenPlugins() },
                        onOpenScheduledTasks = { activeSelectionSheet = null; onOpenScheduledTasks() }
                    )
                }
            }
        }
    }
}

/** B4 §4：设置内的选择弹层种类（语言/主题/权限已有各自弹层）。 */
private enum class SettingsSelectionSheet { AGENT_PRESETS, DEFAULT_MODEL, FONT_SIZE, EXTENSIONS }

/** 对齐 iOS `ExtensionsView`（S14）：聚合入口页 —— 插件 / 定时任务两行 + 一句说明，
 *  不在这里重做两个列表（iOS 注释：Chief 2026-10-29 明确要求）。 */
@Composable
private fun ExtensionsSheet(
    pluginCount: Int,
    taskCount: Int,
    onOpenPlugins: () -> Unit,
    onOpenScheduledTasks: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .then(
                    if (LocalDshIsDark.current) Modifier.background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                    else Modifier.background(Color.White.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                )
                .border(
                    width = 1.dp,
                    color = homeStroke(if (LocalDshIsDark.current) 0.12f else 0.17f),
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            SettingsValueRow(appText("插件"), appText("%1\$s 个", pluginCount), onClick = onOpenPlugins)
            SettingsDivider()
            SettingsValueRow(appText("定时任务"), appText("%1\$s 个", taskCount), onClick = onOpenScheduledTasks)
        }
        Text(
            appText("插件需在桌面端安装，手机端只能启停和查看运行记录。"),
            color = homeInk(0.4f),
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentPresetSelectionScreen(
    stateHolder: AndroidSharedStateHolder,
    onBack: () -> Unit,
    embedded: Boolean = false
) {
    var pendingPreset by remember { mutableStateOf<GatewayAgentPreset?>(null) }
    val presets = stateHolder.snapshot.agentPresets
    val isLoading = "agent-presets" in stateHolder.defaultConfigurationLoadingKinds && presets.isEmpty()
    val isBusy = "set-default" in stateHolder.defaultConfigurationLoadingKinds
    LaunchedEffect(Unit) {
        if (presets.isEmpty()) stateHolder.refreshDefaultConfiguration()
    }

    SettingsSelectionScaffold(title = appText("Agent 预设"), onBack = onBack, embedded = embedded) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SettingsSelectionHeader(
                    title = appText("Agent 预设"),
                    description = appText("预设决定 Agent 使用的工具、提示词与能力。选择后只对新建会话生效。")
                )
            }
            when {
                isLoading -> item { SettingsLoadingState(appText("正在读取 Agent 预设…")) }
                presets.isEmpty() -> item {
                    SettingsEmptyState(
                        title = appText("没有可用的 Agent 预设"),
                        description = appText("请确认 Mobile Gateway 已升级到 v0.1.11 并保持连接。")
                    )
                }
                else -> items(presets, key = GatewayAgentPreset::id) { preset ->
                    AgentPresetCard(
                        preset = preset,
                        selected = preset.id == stateHolder.snapshot.agentPresetDefault,
                        busy = isBusy,
                        onClick = { pendingPreset = preset }
                    )
                }
            }
            if (stateHolder.agentPresetsAuthorable || stateHolder.agentPresetsHasDocument) {
                item {
                    Text(
                        text = "ⓘ  ${presetCapabilityText(stateHolder)}",
                        color = homeInk(0.52f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }

    pendingPreset?.let { preset ->
        DshAlertDialog(
            title = appText("设为全局默认预设？"),
            message = appText("将“%1\$s”设为新会话的默认 Agent 预设。这会更新部署级设置，并同步影响 WebUI。", agentPresetDisplayName(preset.id, preset.name)),
            confirmLabel = appText("设为默认"),
            onDismissRequest = { pendingPreset = null },
            onConfirm = {
                stateHolder.setDefault("agent-preset", preset.id)
                pendingPreset = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DefaultModelSelectionScreen(
    stateHolder: AndroidSharedStateHolder,
    onBack: () -> Unit,
    embedded: Boolean = false
) {
    var pendingChange by remember { mutableStateOf<PendingDefaultModelChange?>(null) }
    val groups = stateHolder.snapshot.modelCatalog?.groups.orEmpty()
    val isLoading = "models" in stateHolder.defaultConfigurationLoadingKinds && groups.isEmpty()
    val isBusy = "save-default-model" in stateHolder.defaultConfigurationLoadingKinds
    LaunchedEffect(Unit) { stateHolder.ensureDefaultModelConfiguration() }

    SettingsSelectionScaffold(title = appText("默认模型"), onBack = onBack, embedded = embedded) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                SettingsSelectionHeader(
                    title = appText("默认模型"),
                    description = appText("为之后新建的会话设置默认模型与思考等级。这会更新部署级设置，同步影响 WebUI，运行中的会话保持启动时的配置。")
                )
            }
            when {
                isLoading -> item { SettingsLoadingState(appText("正在读取模型列表…")) }
                groups.isEmpty() -> item {
                    SettingsEmptyState(
                        title = appText("暂无可用模型"),
                        description = appText("未能读取到模型列表，请检查网关连接后重试。")
                    )
                }
                else -> groups.forEach { group ->
                    item(key = "group-${group.id}") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                group.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = homeInk(0.52f)
                            )
                            group.models.forEach { model ->
                                val selected = stateHolder.snapshot.defaultModel?.let {
                                    it.provider == group.id && it.model == model.id
                                } == true
                                DefaultModelCard(
                                    model = model,
                                    selected = selected,
                                    currentEffort = stateHolder.snapshot.defaultModel?.reasoningEffort,
                                    busy = isBusy,
                                    onSelectModel = {
                                        pendingChange = pendingDefaultModelChange(
                                            group = group,
                                            model = model,
                                            currentEffort = stateHolder.snapshot.defaultModel?.reasoningEffort
                                        )
                                    },
                                    onSelectEffort = { effort ->
                                        pendingChange = PendingDefaultModelChange(
                                            provider = group.id,
                                            providerName = group.name,
                                            model = model.id,
                                            modelName = model.name,
                                            reasoningEffort = effort.id,
                                            effortName = effort.name
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingChange?.let { change ->
        DshAlertDialog(
            title = appText("修改默认模型？"),
            message = appText("将新会话的默认模型改为“%1\$s”。这会更新部署级设置，并同步影响 WebUI。", change.summary),
            confirmLabel = appText("确认修改"),
            onDismissRequest = { pendingChange = null },
            onConfirm = {
                stateHolder.saveDefaultModel(change.provider, change.model, change.reasoningEffort)
                pendingChange = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSelectionScaffold(
    title: String,
    onBack: () -> Unit,
    /** B4 §4：设置内选择一律弹层 —— embedded=true 时不出自带顶栏/整页底色，由 sheet 承载。 */
    embedded: Boolean = false,
    content: @Composable () -> Unit
) {
    if (embedded) {
        Box(Modifier.fillMaxSize().adaptiveContentWidth().testTag("settings-selection-content")) { content() }
        return
    }
    val dark = isSystemInDarkTheme()
    val background = if (dark) Color(0xFF0D1118) else Color(0xFFF1F1F6)
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, style = DshType.title) },
                navigationIcon = {
                    TopBarCircleButton(
                        iconRes = R.drawable.ic_back_chevron,
                        description = appText("返回"),
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background)
            )
        },
        containerColor = background
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).adaptiveContentWidth().testTag("settings-selection-content")) { content() }
    }
}

@Composable
private fun SettingsSelectionHeader(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            description,
            color = homeInk(0.52f),
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun SettingsLoadingState(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, color = homeInk(0.52f))
    }
}

@Composable
private fun SettingsEmptyState(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = DshType.title)
        Text(
            description,
            color = homeInk(0.52f),
            style = DshType.footnote,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun AgentPresetCard(
    preset: GatewayAgentPreset,
    selected: Boolean,
    busy: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val enabled = !selected && !busy && preset.broken != true
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                shape = shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                agentPresetDisplayName(preset.id, preset.name),
                style = DshType.title
            )
            Text(
                preset.id,
                modifier = Modifier.background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                    RoundedCornerShape(50)
                ).padding(horizontal = 7.dp, vertical = 3.dp),
                style = DshType.mono,
                color = homeInk(0.52f)
            )
            Spacer(Modifier.weight(1f))
            if (selected) CurrentDefaultBadge()
        }
        Text(
            agentPresetDisplayDescription(preset.id, preset.description),
            color = homeInk(0.52f),
            fontSize = 14.sp,
            lineHeight = 19.sp
        )
        if (preset.broken == true) {
            Text(
                appText("⚠ 该预设存在配置错误，暂时不能设为默认值"),
                color = Color(0xFFF08A16),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun DefaultModelCard(
    model: GatewayModelItem,
    selected: Boolean,
    currentEffort: String?,
    busy: Boolean,
    onSelectModel: () -> Unit,
    onSelectEffort: (GatewayReasoningEffort) -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                shape = shape
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onSelectModel),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(model.name, style = DshType.title, modifier = Modifier.weight(1f))
            if (selected) CurrentDefaultBadge()
        }
        val efforts = model.reasoning?.efforts.orEmpty()
        if (efforts.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    appText("思考等级"),
                    color = homeInk(0.52f),
                    fontSize = 12.sp
                )
                efforts.forEach { effort ->
                    val active = selected && effort.id == currentEffort
                    Text(
                        effort.name,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(
                                if (active) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            )
                            .clickable(enabled = !busy) { onSelectEffort(effort) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        color = if (active) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentDefaultBadge() {
    Text(
        appText("当前使用"),
        modifier = Modifier.background(Color.Black, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold
    )
}

internal data class PendingDefaultModelChange(
    val provider: String,
    val providerName: String,
    val model: String,
    val modelName: String,
    val reasoningEffort: String?,
    val effortName: String?
) {
    val summary: String
        get() = listOfNotNull(providerName, modelName, effortName).joinToString(" · ")
}

internal fun pendingDefaultModelChange(
    group: GatewayModelGroup,
    model: GatewayModelItem,
    currentEffort: String?
): PendingDefaultModelChange {
    val efforts = model.reasoning?.efforts.orEmpty()
    val retainedEffort = currentEffort?.takeIf { current -> efforts.any { it.id == current } }
    val effortId = retainedEffort ?: model.reasoning?.defaultEffort
    return PendingDefaultModelChange(
        provider = group.id,
        providerName = group.name,
        model = model.id,
        modelName = model.name,
        reasoningEffort = effortId,
        effortName = effortId?.let { id -> efforts.firstOrNull { it.id == id }?.name }
    )
}

private fun presetCapabilityText(holder: AndroidSharedStateHolder): String = when {
    holder.agentPresetsAuthorable && holder.agentPresetsHasDocument ->
        appText("服务端支持编写自定义预设，并提供预设配置文档。")
    holder.agentPresetsAuthorable -> appText("服务端支持编写自定义 Agent 预设。")
    holder.agentPresetsHasDocument -> appText("服务端提供 Agent 预设配置文档。")
    else -> ""
}

@Composable
private fun SettingsSection(
    title: String,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // B4 §3：组标题 11pt ink(0.45)、左缩进 4
        Text(
            title,
            modifier = Modifier.padding(start = 4.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = homeInk(0.45f)
        )
        Column(
            Modifier.fillMaxWidth()
                // B4 §3：组卡圆角 16；底 = cardFill（浅色半透明白 0.55 / 深 white 6%）+ 1pt cardStroke
                .then(
                    if (LocalDshIsDark.current) Modifier.background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                    else Modifier.background(Color.White.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                )
                .border(
                    width = 1.dp,
                    color = homeStroke(if (LocalDshIsDark.current) 0.12f else 0.17f),
                    shape = RoundedCornerShape(16.dp)
                ),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) { content() }
        footer?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 20.dp),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = homeInk(0.52f)
            )
        }
    }
}

@Composable
private fun SettingsValueRow(
    title: String,
    value: String,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    // B4 §3 行：水平 padding 14、minHeight 50；标题 15pt ink(0.92)；值 13pt ink(0.5)；chevron 11pt ink(0.3)
    val rowAlpha = if (enabled) 1f else 0.4f
    Row(
        modifier.fillMaxWidth().heightIn(min = 50.dp)
            .padding(horizontal = 14.dp)
            .clickable(enabled = enabled && onClick != null) { onClick?.invoke() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = homeInk(0.92f).copy(alpha = rowAlpha), fontSize = 15.sp)
        Spacer(Modifier.weight(1f).padding(horizontal = 6.dp))
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = homeInk(0.42f)
            )
        } else {
            Text(
                value,
                color = homeInk(if (enabled) 0.5f else 0.3f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onClick != null) {
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = homeInk(if (enabled) 0.3f else 0.15f),
                modifier = Modifier.padding(start = 8.dp).size(11.dp)
            )
        }
    }
}

@Composable
internal fun FontSizeSettingsRow(onClick: () -> Unit) {
    val appearance = LocalAppearanceSettings.current
    SettingsValueRow(
        title = appText("字体大小"),
        value = if (appearance.fontFollowsSystem) appText("跟随系统") else "${(appearance.fontScale * 100).roundToInt()}%",
        onClick = onClick
    )
}

/** B4 §2：主题 = **三档分段控件**（跟随系统 / 浅色 / 深色），宽 232（iOS 06-settings 实测形态）。
 *  原先做成「值 + 弹层」，与 iOS 不符 —— 分段控件是 iOS 这一行的原生形态。 */
@Composable
internal fun ThemeSegmentedRow() {
    val appearance = LocalAppearanceSettings.current
    val dark = LocalDshIsDark.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(appText("主题"), color = homeInk(0.92f), fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.width(232.dp).height(32.dp)
                .background(if (dark) Color.White.copy(alpha = 0.08f) else homeVeil(0.07f), RoundedCornerShape(9.dp))
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InterfaceStyle.entries.forEach { style ->
                val selected = style == appearance.interfaceStyle
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(
                            if (selected) (if (dark) Color.White.copy(alpha = 0.16f) else Color.White)
                            else Color.Transparent,
                            RoundedCornerShape(7.dp)
                        )
                        .clickable(role = Role.Button) { appearance.selectInterfaceStyle(style) }
                        .testTag("settings-theme-${style.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        style.title,
                        color = homeInk(if (selected) 0.92f else 0.5f),
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
internal fun LanguageSettingsRow() {
    val appearance = LocalAppearanceSettings.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsValueRow(appText("语言"), appearance.language.title) { expanded = true }
        DshSelectionPopup(
            expanded = expanded,
            options = AppLanguage.entries.map { DshSelectionOption(it.name, it.title) },
            selectedKey = appearance.language.name,
            onDismissRequest = { expanded = false },
            onSelect = { key ->
                appearance.selectLanguage(AppLanguage.valueOf(key))
                expanded = false
            }
        )
    }
}

@Composable
private fun PermissionSettingsRow(
    value: String,
    selectedPermission: String?,
    options: List<GatewayPermissionOption>,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPermissionSelected: (String) -> Unit
) {
    Box {
        SettingsValueRow(
            title = appText("权限"),
            value = value,
            enabled = enabled,
            onClick = { onExpandedChange(true) }
        )
        DshSelectionPopup(
            expanded = expanded,
            options = options.map { permission ->
                DshSelectionOption(
                    permission.value,
                    permission.name,
                    when (permission.value) {
                        "read-only" -> R.drawable.ic_permission_read
                        "workspace-write" -> R.drawable.ic_menu_check
                        else -> R.drawable.ic_permission_warning
                    }
                )
            },
            selectedKey = selectedPermission,
            onDismissRequest = { onExpandedChange(false) },
            onSelect = { key ->
                onExpandedChange(false)
                onPermissionSelected(key)
            }
        )
    }
}

@Composable
private fun SettingsDivider() {
    // B4 §3：1pt、左缩进 14、末行不画（调用方控制）
    HorizontalDivider(
        modifier = Modifier.padding(start = 14.dp),
        thickness = 1.dp,
        color = homeStroke(if (LocalDshIsDark.current) 0.12f else 0.17f)
    )
}

@Composable
private fun GatewayEndpointRow(stateHolder: AndroidSharedStateHolder) {
    BasicTextField(
        value = stateHolder.endpoint,
        onValueChange = { stateHolder.endpoint = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 14.dp),
        textStyle = TextStyle(color = homeInk(0.92f), fontSize = 15.sp),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (stateHolder.endpoint.isBlank()) {
                    Text(
                        "ws://host:3080/ws/mobile",
                        color = homeInk(0.42f),
                        fontSize = 15.sp
                    )
                }
                innerTextField()
            }
        }
    )
}

@Composable
private fun GatewayStatusRow(stateHolder: AndroidSharedStateHolder) {
    Row(
        // 状态行本身可点：未连接点=连接、已连接点=断开（对齐 iOS 连接组只有 3 行的形态）
        Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 14.dp)
            .clickable(role = Role.Button) {
                if (isConnected(stateHolder)) stateHolder.disconnect() else stateHolder.connect()
            }
            .testTag("settings-gateway-status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(9.dp).background(statusColor(stateHolder), CircleShape))
        // iOS SettingsView:304-321：状态行 = dot + label(ink 0.5) + Port(ink 0.4) + chevron.forward
        Text(connectionLabel(stateHolder), color = homeInk(0.5f), fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        stateHolder.gatewayState.serverPort?.let { port ->
            Text("Port $port", color = homeInk(0.4f), fontSize = 11.sp)
        }
        Image(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            colorFilter = ColorFilter.tint(homeInk(0.3f)),
            modifier = Modifier.padding(start = 6.dp).size(11.dp)
        )
    }
}

@Composable
private fun SettingsActionRow(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 14.dp)
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.35f),
            fontSize = 15.sp
        )
    }
}

private fun isConnected(holder: AndroidSharedStateHolder) = holder.gatewayState.connection == GatewayConnectionState.CONNECTED

private fun selectedPreset(holder: AndroidSharedStateHolder): String {
    val id = holder.snapshot.agentPresetDefault ?: return appText("未读取")
    val gatewayName = holder.snapshot.agentPresets.firstOrNull { it.id == id }?.name
    return agentPresetDisplayName(id, gatewayName)
}

private fun selectedModel(holder: AndroidSharedStateHolder): String {
    val selection = holder.snapshot.defaultModel ?: return appText("未读取")
    val item = holder.snapshot.modelCatalog?.groups?.flatMap { it.models }?.firstOrNull { it.id == selection.model }
    val base = item?.name ?: when (selection.model) {
        "deepseek-chat" -> "DeepSeek Chat"
        "deepseek-reasoner" -> "DeepSeek Reasoner"
        else -> selection.model
    }
    val effortName = selection.reasoningEffort?.let { effortId ->
        item?.reasoning?.efforts?.firstOrNull { it.id == effortId }?.name
            ?: when (effortId.lowercase()) {
                "low" -> appText("低")
                "medium" -> appText("中")
                "high" -> appText("高")
                else -> effortId.replaceFirstChar(Char::uppercase)
            }
    }
    return effortName?.let { "$base · $it" } ?: base
}

private fun permissionName(value: String?) = when (value) {
    "read-only" -> appText("只读")
    "workspace-write" -> appText("工作区写入")
    "danger-full-access" -> appText("完全访问")
    else -> appText("未读取")
}

private fun connectionLabel(holder: AndroidSharedStateHolder) = when (holder.gatewayState.connection) {
    GatewayConnectionState.CONNECTED -> appText("已连接")
    GatewayConnectionState.CONNECTING, GatewayConnectionState.AUTHENTICATING -> appText("连接中")
    GatewayConnectionState.WAITING_FOR_NETWORK -> appText("等待网络")
    GatewayConnectionState.FAILED -> appText("连接失败")
    GatewayConnectionState.SUSPENDED -> appText("已挂起")
    GatewayConnectionState.DISCONNECTED -> appText("未连接")
}

@Composable
private fun statusColor(holder: AndroidSharedStateHolder) = when (holder.gatewayState.connection) {
    GatewayConnectionState.CONNECTED -> DshSemantic.success
    GatewayConnectionState.FAILED -> Color.Red
    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
}
