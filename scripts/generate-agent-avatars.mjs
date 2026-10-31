#!/usr/bin/env node
/**
 * generate-agent-avatars.mjs —— 生成 144 个**几何抽象** Bot 头像（双端共 288 个文件）。
 *
 * 设计目标（2026-10-31，开源改名项目）：
 *   完全原创（不参考任何既有头像）；几何抽象语汇；低饱和柔和配色；64×64 viewBox。
 *
 * 为什么这样写：
 *   - **可复跑**：随机数由 `seed + index` 独立派生（mulberry32），同 seed 必出同一批；
 *     改 count 也不会让已有编号变形。
 *   - **可调参**：数量 / seed / 色相 / 饱和 / 明度区间都可从命令行改。
 *   - **零依赖**：只拼 SVG 字符串 + node 内置模块。
 *   - **两端同源**：同一份 SVG 写两处（iOS `avatar.svg` / Android `agent_avatar_NNN.svg`），
 *     双端图形逐字节一致。
 *   - **族内可辨**：18 族 × 8 variant；`v % 4` 选**结构**（4 种明显不同的形态）、
 *     `v >= 4` 加**装饰**，所以同族 8 个在结构上就彼此不同，不是只换尺寸颜色。144 = 18 × 8。
 *
 * 用法：
 *   node scripts/generate-agent-avatars.mjs --out /tmp/avatar-preview   # 预览（不动仓库）
 *   node scripts/generate-agent-avatars.mjs --write-ios <Assets.xcassets>
 *   node scripts/generate-agent-avatars.mjs --write-android <res/raw>
 *   node scripts/generate-agent-avatars.mjs --sheet 1 --cols 12          # 预览大图
 *
 * 当前采用的版本（2026-10-31 用户拍板）：**首版**。脚本默认参数就是首版，
 * 用 `--out` 空跑即可**逐字节复现**仓库里那 288 个文件（已实测 diff -r 0 差异）。
 * 首版口径：单一明度带（sat 32-52 / light 56-72）+ 同色系浅色副色（shift 18-44）、
 * 无外环、无描边、无底块、点阵族原始点半径（`--dot-scale 1`）。
 *
 * ⚠️ 复现首版靠的是 **mulberry32 的抽取顺序**，不是只看数值区间：
 *   avatar() 里 r() 的调用序列（hue → sat → light → shift → 正负号，共 5 次）必须原样保留，
 *   `--ink none` 时锚点必须**不计算**（否则白抽一次，后面几何族拿到的随机数全错）。
 *   改这个顺序会静默产出另一批图形 —— 所以有 `--scheme plain/split/band` 三条独立分支。
 *
 * 已经试过并被否掉的版本（保留代码与参数，便于以后回看，**不再是默认**）：
 *   - v2 `--scheme split --ink ring --ring-w 3.4`：每图外套一个同色系外环。
 *     用户否掉：「144 个全变成圆圈，统一外环把 18 个几何族压成同一种容器构图」。
 *   - v3-spec `--scheme split --ink none --light-min 32 --light-max 84 --span-min 40`：
 *     深档 32-42 / 浅档 72-84 面对面。实测最差底色 0.087（v1 只有 0.044，等于没赢）。
 *   - v3-band `--scheme band --ink none`：明度按色相逐个求解，使每色对白底与近黑底都 ≥3:1。
 *     实测最差底色 0.206（是 v2 的 1.57 倍），但观感偏"安静"，未被采用。
 *   三者的衡量方法见 `evidence/`（网格化渲染 + 逐像素对比度统计）。
 *
 * 用法：
 *   node scripts/generate-agent-avatars.mjs --out /tmp/avatar-preview   # 预览（不动仓库）
 *   node scripts/generate-agent-avatars.mjs --write-ios <Assets.xcassets>
 *   node scripts/generate-agent-avatars.mjs --write-android <res/raw>
 *   node scripts/generate-agent-avatars.mjs --sheet 1 --cols 12          # 预览大图
 *
 * 默认参数（= 首版）：--count 144 --seed 20261031
 *   --hue-min 175 --hue-max 255   --sat-min 32 --sat-max 52
 *   --light-min 56 --light-max 72 --shift-min 18 --shift-max 44
 *   --scheme plain --ink none --dot-scale 1 --cols 12
 */
import fs from 'node:fs'
import path from 'node:path'

const SIZE = 64
const C = SIZE / 2
// 点半径倍率：**只**作用于 dotMatrix / dotArc 两族（见 `--dot-scale` 说明）。
let DOT_SCALE = 1

/* ---------- 确定性随机 ---------- */
function mulberry32(seed) {
  let a = seed >>> 0
  return function () {
    a = (a + 0x6D2B79F5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
// 每个编号独立派生：改 count 不会移动已有头像。
const streamFor = (index, seed) => mulberry32((seed + index * 2654435761) >>> 0)

/* ---------- 几何辅助 ---------- */
const n = v => Number(v.toFixed(2))
const pt = (r, deg) => {
  const a = ((deg - 90) * Math.PI) / 180
  return [n(C + r * Math.cos(a)), n(C + r * Math.sin(a))]
}
const int = (r, min, max) => min + Math.floor(r() * (max - min + 1))

// hsl → hex：SVG 里用 hex 最稳（hsl() 在 actool / 部分渲染器上不可靠）。
function hslToHex(h, s, l) {
  h = ((h % 360) + 360) % 360
  s /= 100
  l /= 100
  const a = s * Math.min(l, 1 - l)
  const f = k => {
    const kk = (k + h / 30) % 12
    return l - a * Math.max(-1, Math.min(kk - 3, Math.min(9 - kk, 1)))
  }
  const to = x => Math.round(255 * x).toString(16).padStart(2, '0')
  return `#${to(f(0))}${to(f(8))}${to(f(4))}`
}
// hex → HSL（只用于自检：把"对比度够不够"变成可断言的数字，而不是靠肉眼）。
function hexToHsl(hex) {
  const v = hex.replace('#', '')
  const [rr, gg, bb] = [0, 2, 4].map(i => parseInt(v.slice(i, i + 2), 16) / 255)
  const max = Math.max(rr, gg, bb)
  const min = Math.min(rr, gg, bb)
  const l = (max + min) / 2
  const d = max - min
  let h = 0
  if (d) {
    if (max === rr) h = ((gg - bb) / d) % 6
    else if (max === gg) h = (bb - rr) / d + 2
    else h = (rr - gg) / d + 4
  }
  return { h: ((h * 60) % 360 + 360) % 360, s: d ? (d / (1 - Math.abs(2 * l - 1))) * 100 : 0, l: l * 100 }
}
// WCAG 相对亮度与对比度（自检用）。
const relLum = hex => {
  const v = hex.replace('#', '')
  const c = [0, 2, 4].map(i => parseInt(v.slice(i, i + 2), 16) / 255)
    .map(x => (x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4))
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
}
const contrast = (a, b) => {
  const [x, y] = [relLum(a), relLum(b)].sort((p, q) => q - p)
  return (x + 0.05) / (y + 0.05)
}
// 深色 UI 的近黑底（iOS 深色模式的分组背景近似 #1C1C1E，这里用更黑的 #121212 做保守判据）。
const DARK_BG = '#121212'
// 在 [lo,hi] 里按 **0.5 点步长**枚举明度，返回每个明度对"白底/近黑底"里较小那个对比度。
// 为什么必须枚举求解而不是写死区间：同一个 HSL 明度在不同色相下的 WCAG 亮度差很多——
// 青（hue 175）在 L=40 就已经过不了白底（2.7:1），靛（hue 255）在 L=50 过不了深底（2.2:1），
// 两者的可行明度区间几乎不相交。写死一个区间必然有一半颜色掉到 3:1 以下。
function scanLightness(h, s, lo, hi) {
  const out = []
  for (let l = lo; l <= hi + 1e-6; l += 0.5) {
    const hex = hslToHex(h, s, l)
    out.push({ l, hex, score: Math.min(contrast(hex, '#ffffff'), contrast(hex, DARK_BG)) })
  }
  return out
}
const argmax = arr => arr.reduce((p, q) => (q.score > p.score ? q : p))
// 双档带：主色取"两种底色下最平衡"的那个明度；次色在**同样可行**的候选里，挑与主色明度差最大的
// —— 这样内部照样有对比，但两边底色上都能看清全部墨迹。
function fitPair(h1, s1, h2, s2, lo, hi) {
  const A = scanLightness(h1, s1, lo, hi)
  const a = argmax(A)
  const B = scanLightness(h2, s2, lo, hi)
  const strict = B.filter(x => x.score >= 3.4)
  const pool = strict.length ? strict : B.filter(x => x.score >= 3)
  const b = (pool.length ? pool : [argmax(B)]).reduce((p, q) => (Math.abs(q.l - a.l) > Math.abs(p.l - a.l) ? q : p))
  return { a, b }
}
const arcPath = (rad, a0, a1) => {
  const [x0, y0] = pt(rad, a0)
  const [x1, y1] = pt(rad, a1)
  const large = Math.abs(a1 - a0) > 180 ? 1 : 0
  return `M ${x0} ${y0} A ${rad} ${rad} 0 ${large} 1 ${x1} ${y1}`
}
const polyPath = (sides, rad, rot) => {
  const pts = Array.from({ length: sides }, (_, i) => pt(rad, rot + (i * 360) / sides))
  return `M ${pts.map(p => `${p[0]} ${p[1]}`).join(' L ')} Z`
}
const ring = (rad, w, color, opacity) =>
  `<circle cx="${C}" cy="${C}" r="${n(rad)}" fill="none" stroke="${color}" stroke-width="${n(w)}"${opacity === undefined ? '' : ` opacity="${opacity}"`}/>`
const disc = (rad, color, opacity) =>
  `<circle cx="${C}" cy="${C}" r="${n(rad)}" fill="${color}"${opacity === undefined ? '' : ` opacity="${opacity}"`}/>`
const dotAt = (x, y, rr, color) => `<circle cx="${n(x)}" cy="${n(y)}" r="${n(rr)}" fill="${color}"/>`
const dotsOnRing = (rad, count, size, color, offset = 15) =>
  Array.from({ length: count }, (_, i) => {
    const [x, y] = pt(rad, (360 * i) / count + offset)
    return dotAt(x, y, size, color)
  }).join('')

/* ==========================================================================
 * 18 个几何族，签名 (r, c1, c2, v)：v ∈ 0..7。
 *   v % 4  → 结构（4 种明显不同的形态）
 *   v >= 4 → 装饰（加一圈点 / 内环 / 中心球等）
 * ========================================================================== */

// 1 同心环
function ringStack(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const w = int(r, 3, 5)
  if (b === 0) return ring(25, w, c1) + ring(20 - w, Math.max(2, w - 1), c2) + (deco ? dotsOnRing(28, 4, 2.4, c2) : '')
  if (b === 1) return ring(26, 5, c1) + ring(17, 4, c2) + ring(9, 3, c1)
  if (b === 2) return ring(25, w, c1) + disc(7, c2) + (deco ? ring(12, 2, c2, 0.8) : '')
  return ring(24, 8, c1) + ring(11, 3, c2) + (deco ? disc(4, c1) : '')
}

// 2 缺口弧
function arcPair(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const w = int(r, 4, 6)
  const arc = (rad, a0, a1, color) => `<path d="${arcPath(rad, a0, a1)}" fill="none" stroke="${color}" stroke-width="${w}" stroke-linecap="round"/>`
  if (b === 0) return arc(24, -60, 60, c1) + arc(24, 120, 240, c2)
  if (b === 1) return [0, 120, 240].map((a, i) => arc(23, a, a + 80, i % 2 ? c2 : c1)).join('') + (deco ? disc(5, c1) : '')
  if (b === 2) return arc(24, -70, 70, c1) + arc(24, 110, 250, c2) + disc(6, c2)
  return [0, 90, 180, 270].map((a, i) => arc(22, a + 12, a + 68, i % 2 ? c2 : c1)).join('')
}

// 3 扇形
function wedgeFan(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const slice = (rad, a0, sweep, color) => {
    const [x0, y0] = pt(rad, a0)
    const [x1, y1] = pt(rad, a0 + sweep)
    return `<path d="M ${C} ${C} L ${x0} ${y0} A ${rad} ${rad} 0 0 1 ${x1} ${y1} Z" fill="${color}"/>`
  }
  if (b === 0) return slice(26, 20, 140, c1)
  if (b === 1) return slice(25, -45, 85, c1) + slice(25, 135, 85, c2)
  if (b === 2) return [-90, 30, 150].map((a, i) => slice(25, a, 100, i % 2 ? c2 : c1)).join('')
  return [0, 90, 180, 270].map((a, i) => slice(24, a + 8, 74, i % 2 ? c2 : c1)).join('')
}

// 4 正多边形
function polyShape(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const rot = (v * 17) % 360
  if (b === 0) return `<path d="${polyPath(3, 26, rot)}" fill="${c1}"/>`
  if (b === 1) return `<path d="${polyPath(4, 24, 45 + rot)}" fill="none" stroke="${c1}" stroke-width="5" stroke-linejoin="round"/>` + (deco ? disc(6, c2) : '')
  if (b === 2) return `<path d="${polyPath(5, 26, rot)}" fill="${c1}"/><path d="${polyPath(5, 13, rot)}" fill="${c2}" opacity="0.85"/>`
  return `<path d="${polyPath(6, 25, rot)}" fill="none" stroke="${c1}" stroke-width="4" stroke-linejoin="round"/>` + (deco ? `<path d="${polyPath(6, 14, rot)}" fill="${c2}" opacity="0.8"/>` : '')
}

// 5 点阵
// 点阵/弧上点这两族天生墨覆盖率低（16 个 r=2.7 的点只占约 9% 面积），小尺寸下会散掉。
// 用户 2026-10-31 批准：**只对这两族**放大点半径（--dot-scale），把覆盖面积抬到 20%+；
// 半径上限 9 是为了不在格点上互相吃掉（相邻格点间距最小 12）。
function dotMatrix(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const D = rr => Math.min(9, n(rr * DOT_SCALE))
  if (b === 0) {
    const step = 17
    const s0 = C - step
    return [0, 1, 2].flatMap(y => [0, 1, 2].map(x => dotAt(s0 + x * step, s0 + y * step, D((x + y) % 2 ? 3.4 : 4.6), (x + y) % 2 ? c2 : c1))).join('')
  }
  if (b === 1) {
    const step = 18
    const s0 = C - step
    return [[0, 0], [2, 0], [0, 2], [2, 2], [1, 1]].map(([x, y], i) => dotAt(s0 + x * step, s0 + y * step, D(i === 4 ? 6 : 4), i === 4 ? c2 : c1)).join('')
  }
  if (b === 2) {
    const step = 12
    const s0 = C - 1.5 * step
    return [0, 1, 2, 3].flatMap(y => [0, 1, 2, 3].map(x => dotAt(s0 + x * step, s0 + y * step, D(2.7), (x + y) % 2 ? c2 : c1))).join('')
  }
  const arms = [[0, -1], [0, 1], [-1, 0], [1, 0]].map(([x, y], i) => dotAt(C + x * 17, C + y * 17, D(5), i % 2 ? c2 : c1)).join('')
  return dotAt(C, C, D(deco ? 7 : 4), c1) + arms
}

// 6 平行条
function barSet(r, c1, c2, v) {
  const b = v % 4
  const bars = (vertical, thick, gap, lens, colors) => {
    const span = lens.length * thick + (lens.length - 1) * gap
    const s0 = C - span / 2 + thick / 2
    return lens.map((len, i) => {
      const off = s0 + i * (thick + gap)
      const color = colors[i % colors.length]
      return vertical
        ? `<rect x="${n(off - thick / 2)}" y="${n(C - len / 2)}" width="${n(thick)}" height="${n(len)}" rx="${n(thick / 2)}" fill="${color}"/>`
        : `<rect x="${n(C - len / 2)}" y="${n(off - thick / 2)}" width="${n(len)}" height="${n(thick)}" rx="${n(thick / 2)}" fill="${color}"/>`
    }).join('')
  }
  if (b === 0) return bars(true, 9, 5, [40, 40, 40], [c1, c2, c1])
  if (b === 1) return bars(true, 7, 4, [20, 30, 40, 30], [c1, c2, c1, c2])
  if (b === 2) return bars(false, 9, 5, [40, 40, 40], [c1, c2, c1])
  return bars(false, 7, 4, [22, 32, 44, 32, 22], [c1, c2, c1, c2, c1])
}

// 7 象限块
function quadrantBlocks(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const size = deco ? 19 : 22
  const gap = 4
  const rad = 5 + (v % 5)
  const ox = C - size - gap / 2
  const oy = C - size - gap / 2
  const cell = (x, y, color) => `<rect x="${n(ox + x * (size + gap))}" y="${n(oy + y * (size + gap))}" width="${n(size)}" height="${n(size)}" rx="${rad}" fill="${color}"/>`
  if (b === 0) return cell(0, 0, c1) + cell(1, 1, c2)
  if (b === 1) return cell(0, 0, c1) + cell(1, 0, c2) + cell(0, 1, c2)
  if (b === 2) return cell(0, 0, c1) + cell(1, 0, c2) + cell(0, 1, c2) + cell(1, 1, c1)
  return cell(0, 0, c1) + cell(0, 1, c2)
}

// 8 三角堆
function triStack(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const tri = (cx, cy, size, color) => {
    const h = size * 0.84
    return `<path d="M ${n(cx)} ${n(cy - h * 0.55)} L ${n(cx - size / 2)} ${n(cy + h * 0.45)} L ${n(cx + size / 2)} ${n(cy + h * 0.45)} Z" fill="${color}"/>`
  }
  if (b === 0) return tri(C - 9, C + 2, 20, c1) + tri(C + 9, C - 2, 20, c2)
  if (b === 1) return tri(C - 14, C + 4, 17, c1) + tri(C, C, 17, c2) + tri(C + 14, C - 4, 17, c1)
  if (b === 2) return tri(C, C - 10, 21, c1) + tri(C, C + 11, 21, c2)
  return tri(C, C - 11, 18, c1) + tri(C, C + 1, 18, c2) + tri(C, C + 13, 18, c1) + (deco ? disc(3, c2) : '')
}

// 9 轨道点
function orbitDots(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const core = deco ? 11 : 13
  const body = disc(core, c1) + (deco ? disc(core - 5, c2) : '')
  if (b === 0) return body + dotAt(C - 20, C - 20, 5, c2) + dotAt(C + 20, C + 20, 5, c2)
  if (b === 1) return body + dotsOnRing(22, 3, 4.2, c2)
  if (b === 2) return body + dotsOnRing(22, 4, 4, c2)
  return body + dotAt(C, C - 21, 3, c2) + dotAt(C + 19, C + 9, 5.5, c2) + dotAt(C - 19, C + 9, 4.4, c1)
}

// 10 星芒
function starBurst(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const points = [4, 5, 6, 8][b]
  const outer = deco ? 24 : 26
  const inner = deco ? 10 : 9
  const rot = (v * 23) % 360
  const pts = []
  for (let i = 0; i < points * 2; i++) pts.push(pt(i % 2 ? inner : outer, rot + (i * 180) / points))
  return `<path d="M ${pts.map(p => `${p[0]} ${p[1]}`).join(' L ')} Z" fill="${c1}"/>` + (deco ? disc(4, c2) : '')
}

// 11 多弧同心
function arcRings(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const w = int(r, 3, 5)
  const arc = (rad, a0, a1, color, width) => `<path d="${arcPath(rad, a0, a1)}" fill="none" stroke="${color}" stroke-width="${width}" stroke-linecap="round"/>`
  if (b === 0) return arc(25, -70, 130, c1, w) + arc(16, -70, 130, c2, w)
  if (b === 1) return [0, 40, 80].map((off, i) => arc(25 - i * 8, -40 + off, 140 + off, i % 2 ? c2 : c1, w)).join('')
  if (b === 2) return arc(25, -60, 60, c1, w) + arc(25, 120, 240, c2, w) + (deco ? disc(4, c1) : '')
  return arc(26, 0, 200, c1, w) + arc(14, 180, 320, c2, w)
}

// 12 块阵
function blockGrid(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const grid = b < 2 ? 2 : 3
  const cell = grid === 2 ? (deco ? 19 : 22) : 13
  const gap = grid === 2 ? 4 : 3
  const rad = 4 + (v % 4)
  const s0 = C - (grid * cell + (grid - 1) * gap) / 2
  const block = (x, y, color) => `<rect x="${n(s0 + x * (cell + gap))}" y="${n(s0 + y * (cell + gap))}" width="${n(cell)}" height="${n(cell)}" rx="${rad}" fill="${color}"/>`
  if (b === 0) return [0, 1].flatMap(y => [0, 1].map(x => block(x, y, (x + y) % 2 ? c2 : c1))).join('')
  if (b === 1) return block(0, 0, c1) + block(1, 0, c2) + block(0, 1, c2)
  if (b === 2) return [0, 1, 2].flatMap(y => [0, 1, 2].map(x => block(x, y, (x + y) % 2 ? c2 : c1))).join('')
  return [0, 1, 2].flatMap(y => [0, 1, 2].map(x => ((x + y) % 2 === 0 ? block(x, y, c1) : ''))).join('')
}

// 13 V 形堆
function chevronStack(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const stack = (num, w, down, shrink) => {
    const gapY = w * 1.5
    let y = C - ((num - 1) * gapY) / 2 - w * 0.4
    const out = []
    for (let i = 0; i < num; i++) {
      const half = 17 - i * shrink
      const depth = (down ? 1 : -1) * w * 1.05
      out.push(`<path d="M ${n(C - half)} ${n(y)} L ${C} ${n(y + depth)} L ${n(C + half)} ${n(y)}" fill="none" stroke="${i % 2 ? c2 : c1}" stroke-width="${n(w)}" stroke-linecap="round" stroke-linejoin="round"/>`)
      y += gapY
    }
    return out.join('')
  }
  if (b === 0) return stack(3, 6, true, 2)
  if (b === 1) return stack(4, 5, true, 1.4)
  if (b === 2) return stack(3, 6, false, 2)
  return stack(5, 4.5, true, 1) + (deco ? disc(3, c2) : '')
}

// 14 光环
function haloRing(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const arc = (rad, a0, a1, color, width) => `<path d="${arcPath(rad, a0, a1)}" fill="none" stroke="${color}" stroke-width="${width}" stroke-linecap="round"/>`
  if (b === 0) return ring(25, 7, c1) + disc(9, c2)
  if (b === 1) return ring(26, 5, c1) + ring(16, 4, c2) + disc(7, c1)
  if (b === 2) return arc(25, -80, 100, c1, 7) + arc(25, 100, 280, c2, 7) + disc(8, c1)
  return ring(26, 4, c2) + disc(13, c1) + (deco ? ring(19, 2, c2, 0.7) : '')
}

// 15 分半圆
function splitCircle(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const rad = deco ? 24 : 26
  const edge = [0, 90, 135, 0][b]
  const [x0, y0] = pt(rad, edge)
  const [x1, y1] = pt(rad, edge + 180)
  const body = `<path d="M ${x0} ${y0} A ${rad} ${rad} 0 0 1 ${x1} ${y1} L ${C} ${C} Z" fill="${c1}"/>` +
    `<path d="M ${x1} ${y1} A ${rad} ${rad} 0 0 1 ${x0} ${y0} L ${C} ${C} Z" fill="${c2}"/>`
  return b === 3 || deco ? body + disc(5, c1) : body
}

// 16 弧上点（同 dotMatrix：只放大点半径，不动结构与排布）
function dotArc(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const [num, span] = [[5, 150], [7, 250], [6, 180], [8, 340]][b]
  const rad = deco ? 22 : 20
  const start = b === 2 ? 90 : 30
  const D = rr => Math.min(9, n(rr * DOT_SCALE))
  const out = []
  for (let i = 0; i < num; i++) {
    const [x, y] = pt(rad, start + (span * i) / (num - 1))
    out.push(dotAt(x, y, D(2.6 + ((i + b) % 3) * 1.2), i % 2 ? c2 : c1))
  }
  return deco ? disc(D(4), c1) + out.join('') : out.join('')
}

// 17 菱形阵
function diamondGrid(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const diamond = (cx, cy, rr, color) =>
    `<rect x="${n(cx - rr)}" y="${n(cy - rr)}" width="${n(rr * 2)}" height="${n(rr * 2)}" rx="1.6" transform="rotate(45 ${n(cx)} ${n(cy)})" fill="${color}"/>`
  if (b === 0) return diamond(C, C, deco ? 13 : 18, c1)
  if (b === 1) return [-1, 0, 1].map(i => diamond(C + i * 19, C, deco ? 8 : 11, i % 2 ? c2 : c1)).join('')
  if (b === 2) return [[-1, -1], [1, -1], [-1, 1], [1, 1]].map(([x, y]) => diamond(C + x * 12, C + y * 12, 9, (x + y) % 2 ? c2 : c1)).join('')
  return [[0, 0], [0, -1], [0, 1], [-1, 0], [1, 0]].map(([x, y], i) => diamond(C + x * 17, C + y * 17, i === 0 ? (deco ? 11 : 8) : 7, i === 0 ? c1 : c2)).join('')
}

// 18 十字胶囊
function capsuleCross(r, c1, c2, v) {
  const b = v % 4
  const deco = v >= 4
  const len = 40 + (v % 3) * 2
  const th = b === 3 ? 6 : 10
  const rot = [0, 45, 0, 90][b]
  const capsule = vertical => vertical
    ? `<rect x="${n(C - th / 2)}" y="${n(C - len / 2)}" width="${n(th)}" height="${n(len)}" rx="${n(th / 2)}" fill="${c1}"/>`
    : `<rect x="${n(C - len / 2)}" y="${n(C - th / 2)}" width="${n(len)}" height="${n(th)}" rx="${n(th / 2)}" fill="${c2}"/>`
  let body = `<g transform="rotate(${rot} ${C} ${C})">${capsule(true)}${capsule(false)}</g>`
  if (b === 2 || deco) body += disc(4, c1)
  if (b === 3) body += dotsOnRing(22, 4, 3.4, c2)
  return body
}

const FAMILIES = [
  ringStack, arcPair, wedgeFan, polyShape, dotMatrix, barSet, quadrantBlocks, triStack, orbitDots,
  starBurst, arcRings, blockGrid, chevronStack, haloRing, splitCircle, dotArc, diamondGrid, capsuleCross,
]
const VARIANTS = 8 // 144 = 18 × 8

/* ---------- 反差锚点（只加视觉层，不动几何族） ----------
 * 小尺寸糊掉的根因是"整片中间调"：既没有压住的深色，也没有提亮的高光。
 * 这里按 `--ink` 选一种形态注入锚点；颜色由主导色决定——深色主导的补**浅色光晕**、
 * 浅色主导的补**深色墨**，保证浅底与深底 UI 上都各有一侧边界。
 */
function withAnchor(body, anchor, mode, r, ringW = 2.4) {
  if (!mode || mode === 'none') return body
  const pick = mode === 'mixed' ? ['stroke', 'ring', 'core'][Math.floor(r() * 3)] : mode
  if (pick === 'ring') return body + ring(28.6, ringW + r() * 0.8, anchor)
  if (pick === 'both') {
    return `<g stroke="${anchor}" stroke-width="${n(1.2 + r() * 0.4)}" stroke-linejoin="round">${body}</g>` +
      ring(28.6, ringW + r() * 0.6, anchor)
  }
  if (pick === 'stroke') {
    // 实心元素没有 stroke 属性 → 继承这里的锚点色，几何轮廓自然被"描"出来；
    // 已经有自己 stroke 的弧/环/线保持原色，不被覆盖。
    return `<g stroke="${anchor}" stroke-width="${n(1.5 + r() * 0.5)}" stroke-linejoin="round">${body}</g>` +
      ring(28.6, 2, anchor)
  }
  return body + disc(5.6 + r() * 1.2, anchor)
}

/* ---------- 单个头像 ---------- */
function avatar(index, palette, seed) {
  const r = streamFor(index, seed)
  const family = FAMILIES[index % FAMILIES.length]
  const variant = Math.floor(index / FAMILIES.length) % VARIANTS

  const hue = palette.hueMin + ((index * 37 + Math.floor(r() * 40)) % (palette.hueMax - palette.hueMin))
  const sat = palette.satMin + r() * (palette.satMax - palette.satMin)
  // 谁深谁浅按编号交替：约一半深色主导、一半浅色主导，避免 144 个都是同一种"重量感"。
  const darkFirst = index % 2 === 0
  let c1, c2, hue2 = hue, sat2 = sat
  if (palette.scheme === 'plain') {
    /* 首版（plain，用户 2026-10-31 拍板采用的版本）：单一明度带 + 同色系浅色副色，无锚点、无容器。
     * ⚠️ 这里的 r() 抽取**顺序和次数必须原样保留**（hue → sat → light → shift → 正负号，共 5 次），
     * 因为 mulberry32 是序列流：多抽或少抽一次，后面几何族拿到的随机数就全错，复现不出首版。 */
    const light = palette.lightMin + r() * (palette.lightMax - palette.lightMin)
    const shift = 18 + r() * 26
    c1 = hslToHex(hue, sat, light)
    c2 = hslToHex(hue + (r() > 0.5 ? shift : -shift), sat * 0.82, Math.min(84, light + 12))
  } else {
    const shift = palette.shiftMin + r() * (palette.shiftMax - palette.shiftMin)
    hue2 = hue + (r() > 0.5 ? shift : -shift)
    sat2 = Math.min(78, sat * 1.04)
    if (palette.scheme === 'band') {
      /* 双档带（band）：两色都在**白底与近黑底都 ≥3:1** 的窗口里，靠求解而不是硬编码区间
       * ——因为同一个 HSL 明度在不同色相下 WCAG 亮度差很多（深蓝在 L=43 就掉到 2.9:1）。
       * 于是两种底色上都能看到**全部**墨迹；内部差异由色相 + 十来点明度承载，不含任何容器。 */
      const t = fitPair(hue, sat, hue2, sat2, palette.bandMin, palette.bandMax)
      // 明度是针对**各自色相/饱和**求解出来的，必须连色一起用——把 b 的明度套到 hue 上会配错色。
      c1 = darkFirst ? t.a.hex : t.b.hex
      c2 = darkFirst ? t.b.hex : t.a.hex
    } else {
      // 分层（split）：两档色相相近、明度刻意拉开 → 每个头像"一亮一暗"面对面。
      const span = palette.lightMax - palette.lightMin
      let darkL = palette.lightMin + r() * span * palette.tier
      const lightL = palette.lightMax - r() * span * palette.tier
      // 强制内部跨度 ≥ --span-min：不足时**压暗深档**（往浅档提亮对白底更没救）。
      darkL = Math.max(palette.darkFloor, Math.min(darkL, lightL - palette.spanMin))
      c1 = hslToHex(hue, sat, darkFirst ? darkL : lightL)
      c2 = hslToHex(hue2, sat2, darkFirst ? lightL : darkL)
    }
  }
  // 锚点 = 缺失的那一档：深色主导 → 浅色光晕；浅色主导 → 深色墨（同 hue，不引入新色相）。
  // `--ink none` 时**不计算**（否则会白抽一次随机数，打乱几何族的序列 → 复现不出首版）。
  const anchor = palette.ink === 'none'
    ? ''
    : darkFirst
      ? hslToHex(hue, Math.min(28, sat * 0.45), palette.hazeMin + r() * (palette.hazeMax - palette.hazeMin))
      : hslToHex(hue, Math.min(80, sat * 1.2), palette.inkMin + r() * (palette.inkMax - palette.inkMin))

  const body = withAnchor(family(r, c1, c2, variant), anchor, palette.ink, r, palette.ringW)
  return { body, family: family.name, variant, darkFirst, c1, c2, anchor }
}
const svgOf = body =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${SIZE}" height="${SIZE}" viewBox="0 0 ${SIZE} ${SIZE}" fill="none">${body}</svg>`

/* ---------- 参数 ---------- */
function usage() {
  return fs.readFileSync(new URL(import.meta.url), 'utf8').split('*/')[0].replace(/^\/\*\*?/, '').replace(/^\s*\* ?/gm, '')
}
function parseArgs(argv) {
  const out = {
    count: 144, seed: 20261031, cols: 12,
    hueMin: 175, hueMax: 255, satMin: 32, satMax: 52, lightMin: 56, lightMax: 72,
    tier: 0.2, spanMin: 40, darkFloor: 26, scheme: 'plain', midGap: 5,
    bandMin: 22, bandMax: 72,
    dotScale: 1,
    shiftMin: 18, shiftMax: 44, ink: 'none', inkMin: 16, inkMax: 24, hazeMin: 88, hazeMax: 92,
    ringW: 3.4,
    out: '', writeIos: '', writeAndroid: '', sheet: 0, compare: '', ids: '', tags: '',
    cellBg: '#f4f6fa', pageBg: '#ffffff', cell: 64,
  }
  for (let i = 0; i < argv.length; i++) {
    const key = argv[i]
    const val = argv[i + 1]
    const take = () => { i++; return val }
    if (key === '--count') out.count = Number(take())
    else if (key === '--seed') out.seed = Number(take())
    else if (key === '--cols') out.cols = Number(take())
    else if (key === '--hue-min') out.hueMin = Number(take())
    else if (key === '--hue-max') out.hueMax = Number(take())
    else if (key === '--sat-min') out.satMin = Number(take())
    else if (key === '--sat-max') out.satMax = Number(take())
    else if (key === '--light-min') out.lightMin = Number(take())
    else if (key === '--light-max') out.lightMax = Number(take())
    else if (key === '--tier') out.tier = Number(take())
    else if (key === '--span-min') out.spanMin = Number(take())
    else if (key === '--dark-floor') out.darkFloor = Number(take())
    else if (key === '--scheme') out.scheme = take()
    else if (key === '--band-min') out.bandMin = Number(take())
    else if (key === '--band-max') out.bandMax = Number(take())
    else if (key === '--mid-gap') out.midGap = Number(take())
    else if (key === '--band-cap') out.bandCap = Number(take()) // 兼容旧参数（已并入 --band-max）
    else if (key === '--dot-scale') out.dotScale = Number(take())
    else if (key === '--tags') out.tags = take()
    else if (key === '--shift-min') out.shiftMin = Number(take())
    else if (key === '--shift-max') out.shiftMax = Number(take())
    else if (key === '--ink') out.ink = take()
    else if (key === '--ink-min') out.inkMin = Number(take())
    else if (key === '--ink-max') out.inkMax = Number(take())
    else if (key === '--ring-w') out.ringW = Number(take())
    else if (key === '--cell') out.cell = Number(take())
    else if (key === '--haze-min') out.hazeMin = Number(take())
    else if (key === '--haze-max') out.hazeMax = Number(take())
    else if (key === '--compare') out.compare = take()
    else if (key === '--ids') out.ids = take()
    else if (key === '--cell-bg') out.cellBg = take()
    else if (key === '--page-bg') out.pageBg = take()
    else if (key === '--out') out.out = take()
    else if (key === '--write-ios') out.writeIos = take()
    else if (key === '--write-android') out.writeAndroid = take()
    else if (key === '--sheet') out.sheet = Number(take())
    else if (key === '--help' || key === '-h') { console.log(usage()); process.exit(0) }
    else throw new Error(`unknown argument: ${key}`)
  }
  if (!['none', 'ring', 'stroke', 'core', 'mixed', 'both'].includes(out.ink)) throw new Error(`--ink 只能是 none/ring/stroke/core/mixed/both，收到 ${out.ink}`)
  if (!['plain', 'split', 'band'].includes(out.scheme)) throw new Error(`--scheme 只能是 plain/split/band，收到 ${out.scheme}`)
  return out
}

const pad3 = i => String(i).padStart(3, '0')

/* ---------- 预览大图 ---------- */
function sheetSvg(items, { cols = 12, cell = 64, gap = 6, label = true, cellBg = '#f4f6fa', pageBg = '#ffffff' } = {}) {
  const rows = Math.ceil(items.length / cols)
  const labelH = label ? 12 : 0
  const W = cols * (cell + gap) + gap
  const H = rows * (cell + gap + labelH) + gap
  const scale = cell / SIZE
  const parts = [`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">`,
    `<rect width="${W}" height="${H}" fill="${pageBg}"/>`]
  items.forEach((item, i) => {
    const x = gap + (i % cols) * (cell + gap)
    const y = gap + Math.floor(i / cols) * (cell + gap + labelH)
    parts.push(`<rect x="${x}" y="${y}" width="${cell}" height="${cell}" rx="${n(cell * 0.22)}" fill="${cellBg}"/>`)
    parts.push(`<g transform="translate(${x} ${y}) scale(${scale})">${item.body}</g>`)
    if (label) parts.push(`<text x="${x + cell / 2}" y="${y + cell + 9}" font-family="-apple-system,Helvetica" font-size="9" fill="${pageBg === '#ffffff' ? '#8a93a6' : '#7d8698'}" text-anchor="middle">${pad3(i)}</text>`)
  })
  parts.push('</svg>')
  return parts.join('')
}

/* ---------- 新旧并排对比图 ---------- */
const dataUri = svg => 'data:image/svg+xml;base64,' + Buffer.from(svg).toString('base64')

function compareSvg(pairs, { cell = 104, inner = 6, pairGap = 26, rowGap = 16, labelH = 16, cellBg = '#f4f6fa', pageBg = '#ffffff' } = {}) {
  const perRow = 4
  const cols = pairs[0]?.svgs.length ?? 2
  const pairW = cell * cols + inner * (cols - 1)
  const rows = Math.ceil(pairs.length / perRow)
  const W = perRow * (pairW + pairGap) + pairGap
  const H = rows * (cell + labelH + rowGap) + rowGap + 26
  const parts = [`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">`,
    `<rect width="${W}" height="${H}" fill="${pageBg}"/>`,
    `<text x="${pairGap}" y="20" font-family="-apple-system,Helvetica" font-size="13" fill="#3b465c">${(pairs[0]?.tags ?? []).join('　→　')}（同一编号并排）</text>`]
  pairs.forEach((pair, i) => {
    const px = pairGap + (i % perRow) * (pairW + pairGap)
    const py = 26 + rowGap + Math.floor(i / perRow) * (cell + labelH + rowGap)
    pair.svgs.forEach((svg, k) => {
      const x = px + k * (cell + inner)
      parts.push(`<rect x="${x}" y="${py}" width="${cell}" height="${cell}" rx="${n(cell * 0.18)}" fill="${cellBg}"/>`)
      parts.push(`<image x="${x}" y="${py}" width="${cell}" height="${cell}" href="${dataUri(svg)}"/>`)
      parts.push(`<text x="${x + cell / 2}" y="${py + cell + 12}" font-family="-apple-system,Helvetica" font-size="10" fill="#8a93a6" text-anchor="middle">${pair.tags[k]} #${pad3(pair.id)}</text>`)
    })
  })
  parts.push('</svg>')
  return parts.join('')
}

/* ---------- 主流程 ---------- */
const args = parseArgs(process.argv.slice(2))
const palette = {
  hueMin: args.hueMin, hueMax: args.hueMax, satMin: args.satMin, satMax: args.satMax,
  lightMin: args.lightMin, lightMax: args.lightMax, tier: args.tier,
  scheme: args.scheme, spanMin: args.spanMin, darkFloor: args.darkFloor,
  midGap: args.midGap, bandMin: args.bandMin, bandMax: args.bandMax,
  shiftMin: args.shiftMin, shiftMax: args.shiftMax, ink: args.ink,
  inkMin: args.inkMin, inkMax: args.inkMax, hazeMin: args.hazeMin, hazeMax: args.hazeMax, ringW: args.ringW,
}
DOT_SCALE = args.dotScale
const items = Array.from({ length: args.count }, (_, i) => avatar(i, palette, args.seed))

if (args.out) {
  for (const [sub, nameOf] of [['ios', i => `AgentAvatar${pad3(i)}.svg`], ['android', i => `agent_avatar_${pad3(i)}.svg`]]) {
    const dir = path.join(args.out, sub)
    fs.mkdirSync(dir, { recursive: true })
    items.forEach((item, i) => fs.writeFileSync(path.join(dir, nameOf(i)), svgOf(item.body) + '\n'))
  }
  console.log(`→ 预览输出 ${items.length} × 2 端 = ${items.length * 2} 个文件到 ${args.out}`)
}

if (args.writeIos) {
  // 只替换既有 imageset 里的 avatar.svg，**不动 Contents.json**（目录结构与代码保持不变）。
  let replaced = 0
  const missing = []
  items.forEach((item, i) => {
    const dir = path.join(args.writeIos, `AgentAvatar${pad3(i)}.imageset`)
    if (!fs.existsSync(dir)) { missing.push(`AgentAvatar${pad3(i)}.imageset`); return }
    fs.writeFileSync(path.join(dir, 'avatar.svg'), svgOf(item.body) + '\n')
    replaced++
  })
  console.log(`→ iOS：替换 ${replaced} 个 avatar.svg${missing.length ? `；缺失 ${missing.length} 个 imageset（不新建，需人工确认）：${missing.slice(0, 3).join(', ')}…` : ''}`)
}

if (args.writeAndroid) {
  items.forEach((item, i) => fs.writeFileSync(path.join(args.writeAndroid, `agent_avatar_${pad3(i)}.svg`), svgOf(item.body) + '\n'))
  console.log(`→ Android：写入 ${items.length} 个 agent_avatar_NNN.svg`)
}

if (args.sheet) {
  const per = Math.ceil(items.length / args.sheet)
  for (let s = 0; s < args.sheet; s++) {
    const slice = items.slice(s * per, (s + 1) * per)
    if (!slice.length) break
    const file = path.join(args.out || '.', `sheet-${s + 1}.svg`)
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, sheetSvg(slice, { cols: args.cols, cell: args.cell, cellBg: args.cellBg, pageBg: args.pageBg }))
    console.log(`→ 预览大图 ${file}（${slice.length} 个，编号 ${s * per}–${s * per + slice.length - 1}）`)
  }
}

/* ---------- 多版并排对比（--compare 目录1,目录2,… --ids … --tags 旧,v2,v3；目录写 `-` 表示"本次生成"） ---------- */
if (args.compare) {
  const ids = args.ids
    ? args.ids.split(',').map(s => Number(s.trim())).filter(v => Number.isFinite(v))
    : [0, 9, 20, 40, 75, 92, 110, 143]
  const dirs = args.compare.split(',').map(s => s.trim())
  const tags = args.tags ? args.tags.split(',') : dirs.map((d, i) => (d === '-' ? 'v3' : `v${i + 1}`))
  const svgsFor = id => dirs.map(d => (d === '-' ? svgOf(items[id].body) : fs.readFileSync(path.join(d, `AgentAvatar${pad3(id)}.svg`), 'utf8')))
  const pairs = ids.map(id => ({ id, tags, svgs: svgsFor(id) }))
  const dir = args.out || '.'
  fs.mkdirSync(dir, { recursive: true })
  const out = path.join(dir, 'compare-old-new.svg')
  fs.writeFileSync(out, compareSvg(pairs, { cell: args.cell, cellBg: args.cellBg, pageBg: args.pageBg }))
  console.log(`→ 对比图 ${out}（${dirs.length} 列：${tags.join('/')}；${ids.map(pad3).join(', ')}）`)
}

/* ---------- 自检：144 个必须互不相同（含族分布） ---------- */
if (args.count === 144) {
  const seen = new Map()
  let dup = 0
  items.forEach((item, i) => {
    const svg = svgOf(item.body)
    if (seen.has(svg)) { dup++; console.error(`  ✗ 与 #${pad3(seen.get(svg))} 完全相同：#${pad3(i)}`) }
    else seen.set(svg, i)
  })
  const famCount = {}
  items.forEach(it => { famCount[it.family] = (famCount[it.family] ?? 0) + 1 })
  const counts = [...new Set(Object.values(famCount))]
  console.log(`→ 自检：${items.length} 个 SVG 唯一性（重复 ${dup} 个）；族分布 ${Object.keys(famCount).length} 族，每族 ${counts.length === 1 ? counts[0] : counts.join('/')} 个`)

  /* 自检（把要求变成可断言的数字，而不是"看起来还行"）。
   * plain（首版）方案本身是"单一明度带 + 同色系副色"，没有深/浅档的概念，
   * 所以那一支只报**内部明度跨度**，不报深浅档 —— 否则输出会让人误以为它有明暗分层。
   * split/band 才跑对应不变式（split：内外跨度；band：每个颜色对两种底色都 ≥3:1）。 */
  let noDark = 0
  let noLight = 0
  let under = 0
  let thin = 0
  let minSpan = Infinity
  let sumSpan = 0
  let minRatio = Infinity
  let sumRatio = 0
  const DARK_L = 48 // L ≤ 48 记作深色档
  const LIGHT_L = 68 // L ≥ 68 记作浅色档
  items.forEach((it, i) => {
    const drawn = palette.ink === 'none' ? [it.c1, it.c2] : [it.c1, it.c2, it.anchor]
    const ls = drawn.map(hexToHsl)
    const dark = ls.filter(x => x.l <= DARK_L)
    const light = ls.filter(x => x.l >= LIGHT_L)
    if (palette.scheme === 'split' && !dark.length) { noDark++; console.error(`  ✗ #${pad3(i)} 无深色档：${ls.map(x => x.l.toFixed(0)).join('/')}`) }
    if (palette.scheme === 'split' && !light.length) { noLight++; console.error(`  ✗ #${pad3(i)} 无浅色档：${ls.map(x => x.l.toFixed(0)).join('/')}`) }
    const span = Math.abs(ls[0].l - ls[1].l)
    if (palette.scheme === 'split' && span < palette.spanMin - 0.5) { thin++; console.error(`  ✗ #${pad3(i)} 内部跨度只有 ${span.toFixed(1)} 点（要求 ≥ ${palette.spanMin}）`) }
    minSpan = Math.min(minSpan, span)
    sumSpan += span
    const darkest = ls.reduce((a, b) => (a.l < b.l ? a : b))
    const lightest = ls.reduce((a, b) => (a.l > b.l ? a : b))
    const ratio = contrast(hslToHex(darkest.h, darkest.s, darkest.l), hslToHex(lightest.h, lightest.s, lightest.l))
    if (ratio < 3) under++
    minRatio = Math.min(minRatio, ratio)
    sumRatio += ratio
  })
  if (palette.scheme === 'plain') {
    console.log(`→ 颜色自检：同图两色内部明度跨度 最小 ${minSpan.toFixed(1)} 点，平均 ${(sumSpan / items.length).toFixed(1)} 点（首版为单一明度带，无深浅分层要求）`)
  } else {
    console.log(`→ 对比度自检：缺深色档 ${noDark} 个 / 缺浅色档 ${noLight} 个 / <3:1 ${under} 个；内部明度跨度 最小 ${minSpan.toFixed(1)} 点，平均 ${(sumSpan / items.length).toFixed(1)} 点（跨度不足 ${thin} 个）；顶-底对比度 最小 ${minRatio.toFixed(2)}:1，平均 ${(sumRatio / items.length).toFixed(2)}:1`)
  }

  if (palette.scheme === 'band') {
    // 双档带的**定义性不变式**：画出来的每一个颜色，对白底与近黑底都 ≥3:1。
    // 这条成立就等于"两种底色上都能看到全部墨迹"——不是估计，是可断言的。
    let violations = 0
    let worst = Infinity
    items.forEach((it, i) => {
      for (const hex of [it.c1, it.c2]) {
        const s = Math.min(contrast(hex, '#ffffff'), contrast(hex, DARK_BG))
        worst = Math.min(worst, s)
        if (s < 3) { violations++; console.error(`  ✗ #${pad3(i)} ${hex} 对某一底色只有 ${s.toFixed(2)}:1`) }
      }
    })
    console.log(`→ 双档带不变式：每个颜色对白底与近黑底都 ≥3:1 —— 违规 ${violations} 处，最差 ${worst.toFixed(2)}:1`)
  }
}