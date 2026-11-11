package com.clarklevis.dsh.shared.domain

/**
 * 「Bot 域会话」判定（桌面端 `ds-bot/src/client/index.js:916` 语义：Bot 会话输入框
 * 不显示模型选择——"The composer carries no model picker or run statistics; a Bot's
 * model lives in its details panel"）。
 *
 * ⚠️ **2026-11-06 起模型/思考 chip 判定不再用它**（改 [modelControlsHidden]，用户
 * 两轮裁定收敛为「只防群聊」）；本函数保留给其它需要「Bot 域」语义的调用方
 * （当前无生产消费方——仅测试在用；若新增使用请先对口径，与 iOS `isBotDomainSession`
 * 同一条警示）。
 *
 * 域 = ① Bot 自己的会话（bridge 名册 `bots` 按 sessionId 命中）
 *      ② 群聊 room（同一名册 `rooms` 按 sessionId 命中，与 BotTeamScreen 的
 *        `state?.rooms` 同源）。
 *
 * 名册未就绪时的口径（Chief 2026-11-03 派单：要 fail-closed，不许「先显示再隐藏」，
 * 两个候选里选「不显示」，理由如下）：
 * - IDLE（未配对）⇒ Bot 域入口根本不存在 ⇒ 一律按普通会话。此处不能 fail-closed：
 *   未配对用户的名册永远不会到达，按「可能在 Bot 域」处理会让全体普通会话
 *   永久丢失模型/思考 chip，是不可接受的回归。
 * - LOADING（已配对、名册从未到达）⇒ **按「可能在 Bot 域」隐藏**（fail-closed）：
 *   宁可普通会话在首刷前的窗口里短暂没有 chip，也不给群聊留下能切模型的入口——
 *   那个入口正是 MODEL_SWITCH_COMPACTION_FAILED 卡死三群、丢用户消息的事故形态
 *   （2026-11-03）。与 iOS 的差异如实记录：iOS 名册是推送型，可以「等一次推送再判」；
 *   Android 是 8s 轮询 + 前台 refreshNow，Compose 必须当帧出结果，无法「等」，
 *   等价物就是首刷到达前不显示。
 * - READY / OFFLINE / NEEDS_PAIRING ⇒ 名册即权威。OFFLINE（网络/超时/5xx）与
 *   NEEDS_PAIRING（session 失效）都是 `copy` 保留最后一次好数据（TeamReducer
 *   的 phase 迁移），房间不会因为手机侧 token 过期就不再是房间，照常按命中判定。
 *
 * 纯函数：shared/commonTest 直接断言三种反向用例（普通/Bot/room），不依赖模拟器。
 */
fun isBotDomainSession(
    sessionId: String?,
    bridgePhase: TeamPhase?,
    botSessionIds: Set<String>,
    roomSessionIds: Set<String>,
): Boolean {
    if (sessionId == null) return false
    return when (bridgePhase) {
        // 桥适配层不存在（bridgeTeam == null）或从未配对：Bot 域不可达 ⇒ 普通会话。
        null, TeamPhase.IDLE -> false
        // 已配对但名册一次都没拿到：fail-closed，见上。
        TeamPhase.LOADING -> true
        // READY/OFFLINE/NEEDS_PAIRING：有名册（或最后一次好名册）⇒ 按命中判定。
        else -> sessionId in botSessionIds || sessionId in roomSessionIds
    }
}

/**
 * **群聊会话判定**（纯函数，可单测，iOS `BridgeTeamMirror.isRoomSession` 同源）：
 * `sessionId` 命中 **rooms** ⇒ 群聊。2026-11-06 用户改口径：输入框模型/思考档
 * chip **只对群聊隐藏**——Bot 会话要能切模型（Bot 的模型本就独立于会话，桌面端
 * ds-bot 走自己的 ModelChoiceDock），只有群聊不许切（11-04 三群聊
 * `MODEL_SWITCH_COMPACTION_FAILED` 事故防线）。
 */
fun isRoomSession(sessionId: String?, roomSessionIds: Set<String>): Boolean {
    if (sessionId.isNullOrEmpty()) return false
    return sessionId in roomSessionIds
}

/**
 * 视图层入口：输入框模型/思考 chip 是否隐藏（iOS `BridgeTeamAdapter.modelControlsHidden`
 * 的 Kotlin 镜像，**三态 × 只防群聊**，2026-11-06 用户两轮裁定收敛）：
 * - READY / OFFLINE：命中 **rooms** ⇒ 隐藏（11-04 群聊卡死事故防线，必须保住）；
 *   命中 bots 或未命中 ⇒ 显示（Bot 会话放开切模型）。
 * - NEEDS_PAIRING：bridge 没在跑，不可能有群聊会话 ⇒ 显示（iOS 同口径，保留名册不认）。
 * - IDLE / LOADING：fail-closed——名册不在手上，此刻确实可能是群聊；名册到了立即按
 *   命中揭示。⚠️ 此口径成立的前提是轮询**必须继续跑**（2026-11-04 的 missing 态
 *   省流量跳过已在 2026-11-11 撤销：跳过会让插件缺失网关永远停在 IDLE ⇒ 普通会话
 *   chip 永久消失；而 iOS 从不跳过——/state 在插件缺失时照样 200 返回空名册，
 *   READY 后按命中显示）。
 * - `bridgePhase == null`（桥适配层不存在）⇒ 显示。
 *
 * 切模型失败可见性（iOS 86 的风险判断，Android 同款）：selectModel 走 session
 * control，失败写 lastError → 全局弹窗，不会静默丢消息；Bot 会话理论可复现
 * MODEL_SWITCH_COMPACTION_FAILED（ds-bot host steward parts.js:183），但 ds-bot
 * 自带 model-switch rescue 自愈——真机若复现，回退口径。
 */
fun modelControlsHidden(
    sessionId: String?,
    bridgePhase: TeamPhase?,
    roomSessionIds: Set<String>,
): Boolean {
    if (sessionId.isNullOrEmpty()) return false
    return when (bridgePhase) {
        null -> false
        TeamPhase.READY, TeamPhase.OFFLINE -> isRoomSession(sessionId, roomSessionIds)
        TeamPhase.NEEDS_PAIRING -> false
        else -> true
    }
}
