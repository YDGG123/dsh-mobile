#!/usr/bin/env python3
"""团队流后台任务清理闸门：任何退出路径都不许残留 `heartbeatTask` / `chunkSweeper`。

背景：`GatewayTeamFeed` 有两类长期后台任务（应用层心跳、分块超时清扫）。
它们只拿 weak self，所以对象销毁后任务会自己退出 —— 但"不会永久泄漏"与"主动收尾"是两件事：
- 重连时旧心跳没停 → 两代心跳同时 ping；
- 只跑 `requestResync` 没停清扫 → 旧清扫对着新重组器空转；
- 对象直接销毁 → 任务要等到下一次 tick 才发现 self 为 nil。

所以把这条约束固化成闸门：三个**退出/重入口**必须显式清理同一组资源：
`stop()`、`connect()`、`handleDisconnect()` 都要调用
`stopHeartbeat()` + `stopChunkSweeper()` + `reassembler.reset()`；
`startHeartbeat()` 只允许出现在 `connect()` 里（别在多处启动，否则一定漏停）。

退出码：0 通过；1 有未配对的入口；2 源文件缺失/函数找不到（说明结构变了，闸门需要更新）。
"""

from __future__ import annotations

import os
import re
import sys

ROOT = os.environ.get("FEED_GATE_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "DeepSeekHarnessMobile", "Core", "GatewayTeamFeed.swift")

REQUIRED_CALLS = ["stopHeartbeat()", "stopChunkSweeper()", "reassembler.reset()"]


def function_body(text: str, signature_pattern: str) -> str | None:
    """粗略切出函数体：从签名行到下一个顶格 `}`（本文件风格是 4 空格缩进的方法）。"""
    match = re.search(signature_pattern, text)
    if not match:
        return None
    start = match.end()
    depth = 1  # 签名所在行的 `{` 已计入
    index = start
    while index < len(text):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:index]
        index += 1
    return text[start:]


def main() -> int:
    if not os.path.isfile(SOURCE):
        print(f"❌ 团队流清理闸门：源文件不存在：{SOURCE}", file=sys.stderr)
        return 2
    text = open(SOURCE, encoding="utf-8").read()

    entries = {
        "stop()": r"func stop\(\)\s*\{",
        "connect()": r"private func connect\(\)\s*\{",
        "handleDisconnect(_:reason:)": r"private func handleDisconnect\([^)]*\)\s*\{",
    }
    failures: list[str] = []
    for label, pattern in entries.items():
        body = function_body(text, pattern)
        if body is None:
            print(f"❌ 团队流清理闸门：找不到 {label}（结构变了，闸门需要更新）", file=sys.stderr)
            return 2
        missing = [call for call in REQUIRED_CALLS if call not in body]
        if missing:
            failures.append(f"{label} 缺少清理调用：{', '.join(missing)}")

    connect_body = function_body(text, entries["connect()"]) or ""
    # 只数**调用点**：`func startHeartbeat()` 是定义，不算启动。
    starts = len(re.findall(r"(?<!func )startHeartbeat\(\)", text))
    if "startHeartbeat()" not in connect_body:
        failures.append("connect() 里没有 startHeartbeat()（心跳不启动，判死永远不会发生）")
    if starts != 1:
        failures.append(
            f"startHeartbeat() 在文件中出现 {starts} 处；只允许 connect() 里一处，"
            "多处启动就一定会漏停"
        )
    # 只认真正的 deinit 块：注释里出现 "deinit" 不算（上一版就是这样假绿的）。
    if not re.search(r"\bdeinit\s*\{", text):
        failures.append("缺少 deinit 兜底（对象直接销毁时应取消心跳任务）")

    if failures:
        print("❌ 团队流清理闸门：存在未配对的退出路径")
        for item in failures:
            print(f"   - {item}")
        return 1

    print("✅ 团队流后台任务清理配对：stop() / connect() / handleDisconnect() 三入口全覆盖；startHeartbeat 仅 connect() 一处；deinit 兜底在位")
    return 0


if __name__ == "__main__":
    sys.exit(main())
