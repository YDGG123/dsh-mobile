#!/usr/bin/env node
// ② 对账工具（2026-11-06）：从网关日志提取「frame→client」行，按客户端/会话汇总
// 网关实际转发的帧——与手机端渲染条数对比，定位丢帧发生在哪一层。
// 用法：node scripts/reconcile-frame-logs.mjs [日志路径]（缺省取 $TMPDIR/mobile-gateway.log）
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const logPath = process.argv[2] || path.join(process.env.TMPDIR || os.tmpdir(), 'mobile-gateway.log')
const text = fs.readFileSync(logPath, 'utf8')
const lines = text.split('\n').filter((line) => line.includes('frame→client'))

if (lines.length === 0) {
  console.log('无 frame→client 记录。确认网关已带对账日志（067eb34+）且手机已复现。')
  process.exit(0)
}

const byClient = new Map()
for (const line of lines) {
  const m = line.match(/^\[([^\]]+)\] frame→client id=(\d+) ch=(\S+) session=(\S+) type=(\S+) seq=(\S+)/)
  if (!m) continue
  const [, ts, id, ch, session, type, seq] = m
  const key = `client${id}(${ch})`
  if (!byClient.has(key)) byClient.set(key, { events: 0, snapshots: 0, sessions: new Map(), first: ts, last: ts })
  const entry = byClient.get(key)
  entry.last = ts
  if (type === 'event') entry.events += 1
  if (type === 'snapshot') entry.snapshots += 1
  if (!entry.sessions.has(session)) entry.sessions.set(session, { events: 0, seqMin: seq, seqMax: seq, first: ts, last: ts })
  const s = entry.sessions.get(session)
  s.last = ts
  if (type === 'event') {
    s.events += 1
    const n = Number(seq)
    if (Number.isSafeInteger(n)) {
      if (s.seqMin === '?' || n < Number(s.seqMin)) s.seqMin = String(n)
      if (s.seqMax === '?' || n > Number(s.seqMax)) s.seqMax = String(n)
    }
  }
  if (type === 'snapshot') s.snapshots += 1
}

console.log(`日志：${logPath}`)
console.log(`frame→client 总行数：${lines.length}\n`)
for (const [key, entry] of byClient) {
  console.log(`${key}  ${entry.first} → ${entry.last}`)
  console.log(`  events=${entry.events} snapshots=${entry.snapshots}`)
  for (const [session, s] of entry.sessions) {
    console.log(`  session=${session} events=${s.events} snapshots=${s.snapshots} seqRange=${s.seqMin}..${s.seqMax} (${s.first} → ${s.last})`)
  }
}
console.log('\n对账方法：把上面的 events 数与手机端聊天里实际出现的消息条数（含图片消息）对比。')
console.log('网关已发、手机未显示 ⇒ 丢在客户端帧处理/UI 层；网关就没发 ⇒ 丢在订阅/resume 层。')
