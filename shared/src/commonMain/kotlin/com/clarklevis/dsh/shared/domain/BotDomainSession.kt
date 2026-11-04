package com.clarklevis.dsh.shared.domain

/**
 * 「Bot 域会话」判定（桌面端 `ds-bot/src/client/index.js:916` 语义：Bot 会话输入框
 * 不显示模型选择——"The composer carries no model picker or run statistics; a Bot's
 * model lives in its details panel"）。
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
