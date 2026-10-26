#!/usr/bin/env python3
"""团队流后台任务清理闸门：任何**动连接**的入口都不许残留 `heartbeatTask` / `chunkSweeper`。

背景：`GatewayTeamFeed` 有两类长期后台任务（应用层心跳、分块超时清扫）。
它们只拿 weak self，所以对象销毁后任务会自己退出 —— 但"不会永久泄漏"与"主动收尾"是两件事：
- 重连时旧心跳没停 → 两代心跳同时 ping；
- 重连时只停心跳、没停清扫 → 旧清扫拿旧时间戳把**新连接**的流判死（"刚连上就重取快照"的循环）；
- 对象直接销毁 → 任务要等到下一次 tick 才发现 self 为 nil。

守的是什么（**是"不变量"，不是"四个函数的清单"**）：
  1. 凡是**动连接**的函数（函数体里出现 `socket?.cancel` / `socket = nil` / `phase = .idle` /
     `startHeartbeat()` 之一），都必须同时出现 `stopHeartbeat()` + `stopChunkSweeper()` +
     `reassembler.reset()`；
  2. `startHeartbeat()` 全文件只允许**一处调用**（多处启动必漏停）；
  3. 必须有 `deinit { }` 兜底；
  4. 已知三个入口（stop / connect / handleDisconnect）必须仍然被识别为"动连接"——
     如果连它们都识别不到，说明特征探测失效了，这比静默放过更危险，直接红。

第 1 条按"动了连接"这个**特征**自动发现入口，所以将来新增一个后台退出分支、session-clear
分支，只要它碰 socket/phase，就会自动被要求成对清理，不需要回来改闸门。

局限（**别把它当万能的**）：
  - 如果新退出路径既不碰 socket、也不碰 phase、也不启动心跳（例如某个 Task 里只把
    `wantLive = false` 就走人），本闸门**发现不了** —— 那种情况仍靠人复核与 QA。
  - 它是**源码文本闸**：函数被拆到别的文件/扩展、改成闭包或属性包装后，解析会失配并以
    退出码 2 提示"结构变了"；此时要按新结构更新闸门，不要忽略。
  - 只校验"清理调用存在"，不校验顺序、也不校验是否被提前 return 绕过。

退出码：0 通过；1 有未配对的入口；2 源文件缺失/结构变化（闸门需要更新）。
"""

from __future__ import annotations

import os
import re
import sys

ROOT = os.environ.get("FEED_GATE_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "DeepSeekHarnessMobile", "Core", "GatewayTeamFeed.swift")

REQUIRED_CALLS = ["stopHeartbeat()", "stopChunkSweeper()", "reassembler.reset()"]
# 函数体里出现任一，就算"动连接的入口"，必须成对清理后台任务。
CONNECTION_TOUCHES = ["socket?.cancel", "socket = nil", "phase = .idle", "startHeartbeat()"]


def strip_comments(text: str) -> str:
    """去掉 `//` 与 `/* */` 注释（保留字符串字面量里的 `//`，比如 URL）。

    这一步是必须的：代码里刚补的"历史教训"注释本身就提到 `startHeartbeat()`，
    不剥注释的话，注释里的提及会被当成第 2 处调用（本闸门第一版就这样假红过一次）。
    """
    out: list[str] = []
    index = 0
    length = len(text)
    in_string = False
    in_block = False
    in_line = False
    while index < length:
        char = text[index]
        pair = text[index:index + 2]
        if in_line:
            if char == "\n":
                in_line = False
                out.append(char)
            index += 1
            continue
        if in_block:
            if pair == "*/":
                in_block = False
                index += 2
                continue
            index += 1
            continue
        if in_string:
            out.append(char)
            if char == "\\":
                if index + 1 < length:
                    out.append(text[index + 1])
                index += 2
                continue
            if char == '"':
                in_string = False
            index += 1
            continue
        if pair == "//":
            in_line = True
            index += 2
            continue
        if pair == "/*":
            in_block = True
            index += 2
            continue
        out.append(char)
        if char == '"':
            in_string = True
        index += 1
    return "".join(out)


def iter_functions(text: str) -> list[tuple[str, str]]:
    """切出所有 `func name(...) {` 的函数体（本文件风格：4 空格缩进的方法）。"""
    functions: list[tuple[str, str]] = []
    pattern = re.compile(
        r"^    (?:@\w+\s+)*(?:private |internal |public )?func\s+([A-Za-z0-9_]+)\s*\(", re.M
    )
    for match in pattern.finditer(text):
        brace = text.find("{", match.end())
        if brace == -1:
            continue
        depth = 1
        index = brace + 1
        while index < len(text) and depth > 0:
            if text[index] == "{":
                depth += 1
            elif text[index] == "}":
                depth -= 1
            index += 1
        functions.append((match.group(1), text[brace + 1:index - 1]))
    return functions


def main() -> int:
    if not os.path.isfile(SOURCE):
        print(f"❌ 团队流清理闸门：源文件不存在：{SOURCE}", file=sys.stderr)
        return 2
    text = strip_comments(open(SOURCE, encoding="utf-8").read())
    functions = iter_functions(text)
    if not functions:
        print("❌ 团队流清理闸门：一个函数都没解析出来（结构变了，闸门需要更新）", file=sys.stderr)
        return 2

    failures: list[str] = []
    connection_entries: list[str] = []
    for name, body in functions:
        if not any(touch in body for touch in CONNECTION_TOUCHES):
            continue
        connection_entries.append(name)
        missing = [call for call in REQUIRED_CALLS if call not in body]
        if missing:
            failures.append(f"{name}() 动了连接但缺少清理调用：{', '.join(missing)}")

    # 已知三入口必须仍被识别（探测失效时不能静默放过）。
    for required in ("stop", "connect", "handleDisconnect"):
        if required not in connection_entries:
            failures.append(
                f"{required}() 没有被识别为连接入口（特征探测可能失效；它应包含 "
                f"{CONNECTION_TOUCHES[0]} / {CONNECTION_TOUCHES[2]} 之类）"
            )

    starts = len(re.findall(r"(?<!func )startHeartbeat\(\)", text))
    if starts != 1:
        failures.append(f"startHeartbeat() 出现 {starts} 处调用；只允许 connect() 一处，多处启动必漏停")
    if not re.search(r"\bdeinit\s*\{", text):
        failures.append("缺少 deinit 兜底（对象直接销毁时应取消心跳任务）")

    if failures:
        print("❌ 团队流清理闸门：存在未配对的退出路径")
        for item in failures:
            print(f"   - {item}")
        return 1

    print(
        "✅ 团队流后台任务清理配对：动连接的入口 "
        f"{len(connection_entries)} 个（{' / '.join(connection_entries)}），"
        "全部含停心跳 + 停清扫 + 重置重组器；startHeartbeat 仅一处；deinit 兜底在位"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
