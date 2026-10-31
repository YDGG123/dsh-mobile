#!/usr/bin/env node
/**
 * 只读探针：钉住「实时状态暂不可用」长期挂着的三条链路结论（2026-10-31 根因实测）。
 *
 * 它直接驱动**已安装的网关插件** `team-feed.mjs`（不起真实 DSH、不开端口、不落盘、
 * 不做任何写操作），用可控的 resolveService / fallback 模拟源服务在位与缺席，
 * 抓「发给订阅者的每一条帧」的 degraded 值序列。读数见 Docs/team-degraded-notes.md。
 *
 * 三条断言（任一不成立 → 退出码 1）：
 *   ① `resume` 命中且 seq 已追平（caught-up）时，网关**零帧** —— 客户端拿不到任何 degraded 更新
 *      （这是「经常不可用」的主根因：缓存水合置的 true 没有帧能清）
 *   ② 源服务缺席期间，已连接的订阅者**收不到帧** —— 手机不知道降级了（反向谎报，待办 C）
 *   ③ 源服务恢复后 bind() 的 recovering 分支会补发快照，degraded 翻回 false
 *
 * 用法：
 *   node scripts/team_feed_degraded_probe.mjs
 *   MOBILE_GATEWAY_LIB=/path/to/lib/mobile-v1/team-feed.mjs node scripts/team_feed_degraded_probe.mjs
 *
 * 退出码：0 = 三条都成立（与实测一致）；1 = 有断言不成立（网关行为变了）；2 = 找不到网关库
 */
import { createRequire } from 'node:module'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'

const require = createRequire(import.meta.url)

function locateGatewayLib() {
  // 显式指定也要验存在：路径写错必须 exit 2（fail-closed），不能等 import 抛未捕获异常。
  if (process.env.MOBILE_GATEWAY_LIB) {
    return fs.existsSync(process.env.MOBILE_GATEWAY_LIB) ? process.env.MOBILE_GATEWAY_LIB : null
  }
  const home = os.homedir()
  const roots = []
  const profiles = path.join(home, '.dsh', 'profiles')
  if (fs.existsSync(profiles)) {
    for (const name of fs.readdirSync(profiles)) {
      roots.push(path.join(profiles, name, 'node_modules', 'dsh-plugin-mobile-gateway', 'lib', 'mobile-v1'))
    }
  }
  for (const root of roots) {
    const candidate = path.join(root, 'team-feed.mjs')
    if (fs.existsSync(candidate)) return candidate
  }
  return null
}

const libPath = locateGatewayLib()
if (!libPath) {
  console.error('❌ 找不到 gateway 的 team-feed.mjs。用 MOBILE_GATEWAY_LIB 显式指定路径。')
  process.exit(2)
}
console.log(`探针目标: ${libPath}\n`)

const { createTeamFeed } = await import(libPath)
const sleep = ms => new Promise(r => setTimeout(r, ms))

let failed = 0
const check = (cond, msg) => {
  if (cond) console.log(`  ✅ ${msg}`)
  else { console.log(`  ❌ ${msg}`); failed += 1 }
}

// ---- 可控的源服务（模拟 ds-bot 在位 / 缺席）----
let serviceAvailable = true
const bots = [{ id: 'bot-1', name: 'Alpha' }, { id: 'bot-2', name: 'Beta' }]
const rosterService = {
  instanceId: 'inst-probe',
  seq: () => 7,
  snapshot: () => ({ bots: bots.map(b => ({ ...b })), rooms: [], mainBotIds: ['bot-1'] }),
  subscribe: () => () => {},
}
const fallback = {
  state: async () => ({ bots: bots.map(b => ({ ...b, sessionId: `sess-${b.id}` })), rooms: [], mainBotIds: ['bot-1'] }),
}
// 会话列表稳定 → maybeOrder 的签名不变 → 静止期不发 order 帧（真实形态）
const sessions = [{ sessionId: 's-1', updatedAt: 1000 }]

const frames = []
const feed = createTeamFeed({
  resolveService: () => (serviceAvailable ? rosterService : null),
  fallback,
  loadSessions: async () => sessions.map(s => ({ ...s })),
  log: () => {},
  pollMs: 30, orderMs: 30,
})
feed.subscribe(f => frames.push({ kind: f.kind, degraded: f.degraded, seq: f.seq }))
feed.retain()   // 与 server.mjs 一致：常驻订阅（所以 stop() 不会被调用）

console.log('① resume caught-up（afterSeq === head）时网关是否零帧')
await feed.snapshot()
await sleep(120)
frames.length = 0
const answer = feed.history(7)
console.log(`   history(7) → mode=${answer.mode} frames=${answer.frames.length}`)
await sleep(200)
check(answer.mode === 'ok' && answer.frames.length === 0 && frames.length === 0,
      `caught-up 路径零帧（history 回放 ${answer.frames.length} 帧、其后 200ms 收到 ${frames.length} 帧）`)
console.log('   → 客户端缓存水合置的 degraded=true 在该路径上没有帧能清掉\n')

console.log('② 源服务缺席期间，已连接的订阅者是否收到帧')
frames.length = 0
serviceAvailable = false
await sleep(500)
check(frames.length === 0,
      `500ms 内收到 ${frames.length} 帧（0 表示静默：手机继续显示旧实时态，待办 C）`)

console.log('\n③ 源服务恢复后 recovering 是否补发快照把 degraded 翻回')
serviceAvailable = true
await sleep(400)
const recovered = frames.filter(f => f.kind === 'snapshot' && f.degraded === false)
check(recovered.length >= 1,
      `恢复后收到 ${recovered.length} 帧 snapshot(degraded=false) 用于翻回实时态`)

console.log(failed === 0 ? '\nall ok' : `\n${failed} 条断言不成立（网关行为与 2026-10-31 实测不一致）`)
process.exit(failed === 0 ? 0 : 1)