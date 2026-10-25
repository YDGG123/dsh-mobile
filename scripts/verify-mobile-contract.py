#!/usr/bin/env python3
"""手机端契约闸门：核对 Docs/mobile-contract-v1.md 里写的端点/帧/字段/红线，
在真实实现（网关插件、ds-bot、本仓库手机端）里都还能找到对应物；找不到就红。

为什么需要这个闸门：
  契约文档最常见的死法不是写错，而是「写完就没人管」——实现改了名、删了端点，
  文档还写着老样子，半年后谁都不敢信它。所以这里给每一条断言钉一个**实现探针**
  （needle）：文档说 `/mobile/v1/read-markers`，那就必须在网关路由里找到它；
  文档说 `teamServing`，那 `/info` 的实现里就必须有这个键。

口径：
  - 只做**存在性**核对（找得到/找不到），不做语义对拍——语义靠人复核，见交付单。
  - 探针用「实现里的真实写法」而不是自然语言：例如路由是 `${PREFIX}/info` 模板，
    就探 `${PREFIX}/info`，不是 `/mobile/v1/info`。
  - 文档改了名、实现没改 → 红；实现删了端点、文档没改 → 红。两边都得动才对得上，
    这正是我们要的「脱节必须被人看见」。

用法：
  python3 scripts/verify-mobile-contract.py                    # 正常跑
  python3 scripts/verify-mobile-contract.py --list             # 只列探针，不判定
  python3 scripts/verify-mobile-contract.py --allow-missing-repos
      # 逃生口：网关/ds-bot 目录不在时降级为跳过并留痕（不得用于正式交付）

仓库位置：
  默认取本仓库的兄弟目录 ../dsh-plugin-mobile-gateway、../ds-bot；
  可用环境变量 MOBILE_GATEWAY_DIR、DS_BOT_DIR 覆盖。
  注意 ds-bot 的源码在仓库内的 ds-bot/ 子目录（不是仓库根）。
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOC = ROOT / "Docs" / "mobile-contract-v1.md"

GATEWAY = Path(os.environ.get("MOBILE_GATEWAY_DIR", str(ROOT.parent / "dsh-plugin-mobile-gateway")))
BOT_REPO = Path(os.environ.get("DS_BOT_DIR", str(ROOT.parent / "ds-bot")))
# ds-bot 仓库里真正的插件源码在嵌套的 ds-bot/ 子目录
BOT = BOT_REPO / "ds-bot" if (BOT_REPO / "ds-bot" / "index.js").is_file() else BOT_REPO

GW_SERVER = GATEWAY / "lib" / "mobile-v1" / "server.mjs"
GW_TEAM = GATEWAY / "lib" / "mobile-v1" / "team-feed.mjs"
GW_CARDS = GATEWAY / "lib" / "mobile-v1" / "card-frames.mjs"
GW_ACTIONS = GATEWAY / "lib" / "mobile-v1" / "actions.mjs"
GW_CHUNK = GATEWAY / "lib" / "mobile-v1" / "chunked.mjs"
BOT_TEAM = BOT / "src" / "host" / "team-feed.js"
BOT_CARDS = BOT / "src" / "host" / "card-feed.js"
BOT_INDEX = BOT / "index.js"
MOBILE_GLYPH = ROOT / "DeepSeekHarnessMobile" / "Core" / "TeamGlyph.swift"
MOBILE_FEED = ROOT / "DeepSeekHarnessMobile" / "Core" / "GatewayTeamFeed.swift"
MOBILE_CHUNK = ROOT / "DeepSeekHarnessMobile" / "Core" / "FrameReassembler.swift"
# 本轮唯一标记 pending_merge 的目标：手机端分块重组器（① 在途，尚未并入主干）
PENDING_MERGE_FILES = {MOBILE_CHUNK}

# 每条 = (分组, 文档里的断言原文, 实现文件, 探针)
# 第 5 项可选 `pending_merge=True`：该实现文件属于**尚未并入主干**的在途批次时，
# 文件缺席只记「待并入」而不判红（契约 §8.6 已声明手机端 ① 在途）；一旦文件出现，
# 探针立刻按正常规则生效——缺席不是永久豁免，只是等它进树。
# 双向核对：断言原文必须在**文档里**仍然出现（防止改名后文档没人动），
# 探针必须逐字出现在**实现里**（防止实现删了文档还写着）。两边缺一即红。
CHECKS: list[tuple[str, str, Path, str] | tuple[str, str, Path, str, bool]] = [
    # —— §1 端点：静态路径（网关路由用 `${PREFIX}/xxx` 模板）——
    ("端点", "/mobile/v1/info", GW_SERVER, "${PREFIX}/info"),
    ("端点", "/mobile/v1/pairings", GW_SERVER, "${PREFIX}/pairings"),
    ("端点", "/mobile/v1/pairings/complete", GW_SERVER, "${PREFIX}/pairings/complete"),
    ("端点", "/mobile/v1/auth/refresh", GW_SERVER, "${PREFIX}/auth/refresh"),
    ("端点", "/mobile/v1/state", GW_SERVER, "${PREFIX}/state"),
    ("端点", "/mobile/v1/conversations", GW_SERVER, "${PREFIX}/conversations"),
    ("端点", "/mobile/v1/submissions", GW_SERVER, "${PREFIX}/submissions"),
    ("端点", "/mobile/v1/actions", GW_SERVER, "${PREFIX}/actions"),
    ("端点", "/mobile/v1/devices", GW_SERVER, "${PREFIX}/devices"),
    ("端点", "/mobile/v1/read-markers", GW_SERVER, "${PREFIX}/read-markers"),
    ("端点", "/mobile/v1/team", GW_SERVER, "${PREFIX}/team"),
    ("端点", "/mobile/v1/cards", GW_SERVER, "${PREFIX}/cards"),
    ("端点", "/mobile/v1/events", GW_SERVER, "${PREFIX}/events"),
    # —— §1 端点：带参数路径（网关按 path segments 匹配）——
    ("端点", "/mobile/v1/conversations/{id}/messages", GW_SERVER, "parts[2] === 'messages'"),
    ("端点", "/mobile/v1/conversations/{id}/target", GW_SERVER, "parts[2] === 'target'"),
    ("端点", "/mobile/v1/conversations/{id}/cancel", GW_SERVER, "parts[2] === 'cancel'"),
    ("端点", "/mobile/v1/submissions/{requestId}", GW_SERVER, "parts[0] === 'submissions'"),
    ("端点", "/mobile/v1/devices/{id}", GW_SERVER, "parts[0] === 'devices'"),
    # —— §3.1 团队帧 ——
    ("团队帧", "snapshot", GW_TEAM, "kind: 'snapshot'"),
    ("团队帧", "delta", GW_TEAM, "kind: 'delta'"),
    ("团队帧", "order", GW_TEAM, "kind: 'order'"),
    ("团队帧", "avatar", GW_TEAM, "kind: 'avatar'"),
    ("团队帧", "ready", GW_SERVER, "type: 'ready'"),
    # —— §3.5 /info 能力位 ——
    ("能力位", "capabilities.team", GW_SERVER, "team: !!team"),
    ("能力位", "teamDegraded", GW_SERVER, "teamDegraded:"),
    ("能力位", "cardsDegraded", GW_SERVER, "cardsDegraded:"),
    ("能力位", "teamServing", GW_SERVER, "teamServing:"),
    ("能力位", "cardsServing", GW_SERVER, "cardsServing:"),
    # —— §4 卡片帧 ——
    ("卡片帧", "card-snapshot", GW_CARDS, "kind: 'card-snapshot'"),
    ("卡片帧", '`card`', GW_CARDS, "kind: 'card'"),
    ("卡片帧", "card.shown", GW_CARDS, "'card.shown'"),
    ("卡片帧", "placeholder", GW_CARDS, "placeholder"),
    ("卡片帧", "questions", GW_CARDS, "questions"),
    ("卡片帧", "secret", GW_CARDS, "secret"),
    ("卡片帧", "approval", GW_CARDS, "approval"),
    # —— §3.2 Bot 易变字段（发布端 ds-bot）——
    ("名册字段", "activity", BOT_TEAM, "activity:"),
    ("名册字段", "work", BOT_TEAM, "work:"),
    ("名册字段", "approvals", BOT_TEAM, "approvals:"),
    ("名册字段", "waiting", BOT_TEAM, "waiting:"),
    ("名册字段", "lastActivityAt", BOT_TEAM, "lastActivityAt"),
    ("名册字段", "avatar", BOT_TEAM, "avatar:"),
    ("名册字段", "glyph", BOT_TEAM, "glyph"),
    # —— §5 体积红线（数字必须还写在实现里）——
    ("体积红线", "44KB", GW_TEAM, "44 * 1024"),
    ("体积红线", "1MB", GW_TEAM, "1_000_000"),
    ("体积红线", "256KB", GW_TEAM, "256 * 1024"),
    ("体积红线", "ops > 200", GW_TEAM, "MAX_OPS = 200"),
    ("体积红线", "8MB", GW_SERVER, "8 * 1024 * 1024"),
    ("体积红线", "2MB", GW_SERVER, "2 * 1024 * 1024"),
    ("体积红线", "32768", GW_ACTIONS, "32768"),
    # —— §6 glyph 绘制契约（手机端解析 + 发布端存在性）——
    ("glyph", "layers", MOBILE_GLYPH, "layers"),
    ("glyph", "eyes", MOBILE_GLYPH, "eyes"),
    ("glyph", "path", MOBILE_GLYPH, "path"),
    ("glyph", "size", MOBILE_GLYPH, "size"),
    # —— §3.4 客户端同步规则（手机端确实在守）——
    ("同步规则", "delta.base ≠ 本地 seq", MOBILE_FEED, "base"),
    ("同步规则", "instanceId", MOBILE_FEED, "instanceId"),
    ("同步规则", "order", MOBILE_FEED, 'case "order"'),
    # —— §8 分块传输（chunked-frames-v1）——
    ("分块传输", "chunked-frames-v1", GW_CHUNK, "CHUNK_CAPABILITY = 'chunked-frames-v1'"),
    ("分块传输", ("`maxFrameBytes`", "512 KiB"), GW_CHUNK, "MAX_FRAME_BYTES = 512 * 1024"),
    ("分块传输", ("`maxReassembledBytes`", "4 MiB"), GW_CHUNK, "MAX_REASSEMBLED_BYTES = 4 * 1024 * 1024"),
    ("分块传输", ("`maxStreamsPerConnection`", "2"), GW_CHUNK, "MAX_STREAMS_PER_CONNECTION = 2"),
    ("分块传输", "/info` 公布位置", GW_SERVER, "maxFrameBytes: MAX_FRAME_BYTES"),
    ("分块传输", "`kind` | `\"chunk\"`", GW_CHUNK, "kind: 'chunk'"),
    ("分块传输", "`finalKind`", GW_CHUNK, "finalKind,"),
    ("分块传输", "`part` | string", GW_CHUNK, "part,"),
    ("分块传输", "`at` | number", GW_CHUNK, "at,"),
    ("分块传输", "UTF-8 字符边界", GW_CHUNK, "utf8Parts"),
    ("分块传输", "too-large", GW_CHUNK, "'too-large'"),
    ("分块传输", "too-many-streams", GW_CHUNK, "'too-many-streams'"),
    ("分块传输", "streamTimeout", MOBILE_CHUNK, "streamTimeout: TimeInterval = 30", True),
    ("分块传输", "absoluteReassembledCap", MOBILE_CHUNK, "absoluteReassembledCap = 8 * 1024 * 1024", True),

    # —— §7.4 发布端装入判据 ——
    ("装入判据", "dsBotCards", BOT_INDEX, "dsBotCards"),
    ("装入判据", "card.shown", BOT_CARDS, "card.shown"),
]


def doc_line_of(claim: "str | tuple[str, ...]") -> int | None:
    """在文档里定位一条断言的行号，用于报错时指路。

    claim 为元组时表示「同一行必须同时出现这些 token」——用于字段名与数值这类
    必须成对出现的断言（否则文档别处一句「4 MiB」就能把上限改错的事实盖住）。
    """
    try:
        lines = DOC.read_text().splitlines()
    except FileNotFoundError:
        return None
    tokens = (claim,) if isinstance(claim, str) else claim
    for number, line in enumerate(lines, 1):
        if all(token in line for token in tokens):
            return number
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--list", action="store_true", help="只列探针与命中情况，不做判定")
    parser.add_argument(
        "--allow-missing-repos",
        action="store_true",
        help="网关/ds-bot 目录不在时降级为跳过（会在输出里留痕，不得用于正式交付）",
    )
    options = parser.parse_args()

    if not DOC.is_file():
        print(f"✗ 契约文档不存在：{DOC}", file=sys.stderr)
        return 1

    missing_repos = [str(path) for path in (GATEWAY, BOT) if not path.is_dir()]
    if missing_repos:
        message = "缺少实现仓库，契约无从对拍：" + "、".join(missing_repos)
        if not options.allow_missing_repos:
            print(f"✗ {message}", file=sys.stderr)
            print("  用 MOBILE_GATEWAY_DIR / DS_BOT_DIR 指定位置，或加 --allow-missing-repos 降级跳过。", file=sys.stderr)
            return 1
        print(f"⚠️  {message}")
        print("   --allow-missing-repos 已生效：本闸门降级为「跳过」，结果不得作为交付证据。")
        return 0

    text_cache: dict[Path, str] = {}

    def source_of(path: Path) -> str:
        if path not in text_cache:
            text_cache[path] = path.read_text()
        return text_cache[path]

    doc_text = DOC.read_text()
    errors: list[str] = []
    probed = 0
    pending: list[str] = []
    for entry in CHECKS:
        group, claim, path, needle = entry[0], entry[1], entry[2], entry[3]
        probe_ok = bool(entry[4]) if len(entry) > 4 else False
        probed += 1
        try:
            body = source_of(path)
        except FileNotFoundError:
            if probe_ok and path in PENDING_MERGE_FILES:
                shown = claim if isinstance(claim, str) else " + ".join(claim)
                pending.append(f"[{group}] {shown}（{path.name} 待并入）")
                continue
            errors.append(f"[{group}] 实现文件不存在：{path}（断言：{claim}）")
            continue
        in_doc = doc_line_of(claim) is not None
        hit = needle in body
        if options.list:
            shown = claim if isinstance(claim, str) else " + ".join(claim)
            print(f"{'✓' if hit else '✗'} [{group}] {shown} → {path.name}: {needle!r}"
                  f"{'' if in_doc else '  ← 文档里已没有这条断言'}")
            continue
        if not in_doc:
            shown = claim if isinstance(claim, str) else " + ".join(claim)
            errors.append(f"{DOC.relative_to(ROOT)} 已不再写明「{shown}」，但实现里还有（探针 {path.name}: {needle!r}）——文档实现脱节")
        if not hit:
            shown = claim if isinstance(claim, str) else " + ".join(claim)
            line = doc_line_of(claim)
            where = f"{DOC.relative_to(ROOT)}:{line}" if line else str(DOC.relative_to(ROOT))
            errors.append(
                f"{where} 契约说的「{shown}」在实现里找不到（探针 {path.name}: {needle!r}）"
            )

    if options.list:
        print(f"\n探针 {probed} 条")
        return 0

    if errors:
        print("\n".join(errors), file=sys.stderr)
        print(f"\n契约闸门：{len(errors)} 处脱节（共 {probed} 条探针）", file=sys.stderr)
        return 1

    if pending:
        print(f"⏳ {len(pending)} 条探针待并入主干（不算脱节，也不给绿）：")
        for item in pending:
            print(f"   · {item}")
    print(f"契约闸门通过：{probed - len(pending)}/{probed} 条探针命中"
          f"（文档 ↔ 网关/ds-bot/手机端三方一致）"
          f"{f'，{len(pending)} 条待并入' if pending else ''}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
