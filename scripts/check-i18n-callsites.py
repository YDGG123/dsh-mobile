#!/usr/bin/env python3
"""本地化调用点闸门：抓「注册了也永远不生效」的字符串。

背景（2026-11-01 教训）：`TeamView.swift` 的 `emptyRow(_ text: String)` / `badge(_ text: String)` /
`labeledRow(_ label: String, ...)` 形参是 `String`，于是调用点传中文裸串时**不做任何查表** ——
即使该键已经注册进 `Localizable.xcstrings`，界面照旧显示中文。当天有 8 条属于这一类
（主 / 运行中 / Main Bot / Hidden / 成员 / 发言模式 / 会话 / 状态 / 本机）；
既有盘点器只查「目录里注册没注册」，抓不到这一类。

判据两条（都只看我们自己源码里声明的函数，SDK 的 `Text(_:LocalizedStringKey)` 不在其中，
所以不会把本来就会本地化的位置误报）：

  1. **String 形参位置的中文裸串**：`func f(_ a: String, …)` 的调用点里，第 a 个实参是中文
     字面量 → 红线（改成 `LocalizedStringKey` 形参，或在调用点包 `String(localized:)`）。
  2. **`String(localized: "X")` 的键不在目录**：X 非插值时直接查 `Localizable.xcstrings`；
     不在目录 = 英文界面下原样显示 X。

用法：
  python3 scripts/check-i18n-callsites.py              # 对照基线判定（有新增即退出码 1）
  python3 scripts/check-i18n-callsites.py --report     # 只打印全量清单，不判退出码
  python3 scripts/check-i18n-callsites.py --update-baseline

基线说明：存量债（今天之前就存在的调用点）冻在 `scripts/i18n-callsite-baseline.txt`，
**只拦新增**；修掉一条时基线里那条会被自动忽略（缺失不报错），修完可用
`--update-baseline` 把基线收缩到当前全量。
"""
import argparse
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCES = ["DeepSeekHarnessMobile", "AgentLiveActivityWidget"]
CATALOG = os.path.join(ROOT, "DeepSeekHarnessMobile/Resources/Localizable.xcstrings")
BASELINE = os.path.join(ROOT, "scripts/i18n-callsite-baseline.txt")

CJK = re.compile(r"[\u4e00-\u9fff]")
# 我们的函数声明：func name(params)
DECL = re.compile(r"\bfunc\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(")
# String(localized: "…")，排除带插值的（键是格式串，另算）
LOCALIZED = re.compile(r'String\(localized:\s*"((?:[^"\\]|\\.)*)"')


def swift_files():
    for base in SOURCES:
        for root, _, files in os.walk(os.path.join(ROOT, base)):
            for name in sorted(files):
                if name.endswith(".swift"):
                    yield os.path.join(root, name)


def split_top_level(text):
    """按顶层逗号切分参数/实参（跳过括号、方括号、字符串里的逗号）。"""
    parts, depth, current, in_string = [], 0, [], False
    escaped = False
    for ch in text:
        if in_string:
            current.append(ch)
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
            current.append(ch)
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(current).strip())
            current = []
            continue
        current.append(ch)
    tail = "".join(current).strip()
    if tail:
        parts.append(tail)
    return parts


def balanced_call(src, open_index):
    """从 '(' 处取回配平的括号内文本。"""
    depth, in_string, escaped = 0, False, False
    for i in range(open_index, len(src)):
        ch = src[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                return src[open_index + 1:i]
    return ""


def string_param_indexes(params):
    """返回形参里类型为 String（非 LocalizedStringKey、非 [String]）的序号。"""
    indexes = []
    for i, param in enumerate(params):
        if "LocalizedStringKey" in param:
            continue
        # 只看形参「类型」部分，避免把默认值里的 "String" 当类型
        head = param.split("=", 1)[0].strip()
        # 判定：类型就是 String / String?；带方括号的集合类型（[String]、[String: X]）跳过
        if not re.search(r"[\[\]]", head) and re.search(r":\s*String\??$", head):
            indexes.append(i)
    return indexes


VIEW_RETURN = re.compile(r"->\s*(some\s+)?View\b")


def collect_string_param_helpers(src):
    """只收「返回 View 的 helper」——Chief 指的就是这一类。

    刻意不收普通函数/模型构造器：它们的 String 形参多承载 **领域数据**
    （wire code、诊断名、卡片标题来源），扫进来只会产出噪音，让人开始无视闸门。
    """
    helpers = {}
    for m in DECL.finditer(src):
        open_index = src.index("(", m.start())
        params_text = balanced_call(src, open_index)
        close_index = open_index + len(params_text) + 1
        signature_tail = src[close_index:close_index + 400].split("{", 1)[0]
        if not VIEW_RETURN.search(signature_tail):
            continue
        idx = string_param_indexes(split_top_level(params_text))
        if idx:
            helpers.setdefault(m.group(1), set()).update(idx)
    return helpers


def scan_file(path, violations, localized_missing, helpers_note):
    rel = os.path.relpath(path, ROOT)
    try:
        src = open(path, encoding="utf-8").read()
    except OSError:
        return
    helpers = collect_string_param_helpers(src)
    if helpers:
        helpers_note.update(helpers.keys())
    for name, indexes in helpers.items():
        for call in re.finditer(r"\b" + re.escape(name) + r"\s*\(", src):
            # 跳过声明自身
            line_start = src.rfind("\n", 0, call.start()) + 1
            prefix = src[line_start:call.start()]
            if "func " in prefix:
                continue
            args = split_top_level(balanced_call(src, call.end() - 1))
            for index in indexes:
                if index >= len(args):
                    continue
                arg = args[index]
                if arg.startswith('"') and CJK.search(arg):
                    violations.append((rel, arg[1:-1] if arg.endswith('"') else arg))
    for m in LOCALIZED.finditer(src):
        key = m.group(1)
        if "\\(" in key:      # 插值：键是格式串，另行统计
            continue
        if key not in CATALOG_KEYS:
            localized_missing.append((rel, key))


def load_catalog():
    try:
        doc = json.load(open(CATALOG, encoding="utf-8"))
        return set(doc.get("strings", {}).keys())
    except (OSError, json.JSONDecodeError) as error:
        print(f"❌ 读不到本地化目录 {CATALOG}: {error}")
        sys.exit(2)


CATALOG_KEYS = load_catalog()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", action="store_true", help="只打印，不判退出码")
    parser.add_argument("--update-baseline", action="store_true", help="把当前全量写成基线")
    args = parser.parse_args()

    violations, localized_missing, helpers_note = [], [], set()
    for path in swift_files():
        scan_file(path, violations, localized_missing, helpers_note)

    entries = sorted({f"{rel}|{text}" for rel, text in violations} |
                     {f"{rel}|String(localized:)|{key}" for rel, key in localized_missing})

    baseline = set()
    if os.path.exists(BASELINE):
        baseline = {line.strip() for line in open(BASELINE, encoding="utf-8")
                    if line.strip() and not line.startswith("#")}

    if args.update_baseline:
        with open(BASELINE, "w", encoding="utf-8") as handle:
            handle.write("# 本地化调用点基线（--update-baseline 生成）：只拦新增，存量债冻结。\n")
            handle.write("# 格式：<相对路径>|<中文串> 或 <相对路径>|String(localized:)|<键>\n")
            for entry in entries:
                handle.write(entry + "\n")
        print(f"✅ 基线已更新：{len(entries)} 条 → {os.path.relpath(BASELINE, ROOT)}")
        return 0

    new = [e for e in entries if e not in baseline]
    fixed = [e for e in baseline if e not in entries]

    print(f"本地化调用点扫描：{len(list(swift_files()))} 个 Swift 文件")
    print(f"  自声明 String 形参 helper：{len(helpers_note)} 个 → {sorted(helpers_note)}")
    print(f"  命中总数 {len(entries)}（去重后；String 位置裸串 "
          f"{len({'%s|%s' % (r, t) for r, t in violations})} / "
          f"String(localized:) 缺键 {len({'%s|%s' % (r, k) for r, k in localized_missing})}）")
    print(f"  基线内 {len(entries) - len(new)} 条；**新增 {len(new)} 条**；基线里已修好 {len(fixed)} 条")

    if new:
        print("\n❌ 新增的「注册了也不生效 / 键不在目录」条目：")
        for entry in new:
            print("   ", entry)
    if fixed:
        print("\nℹ️  以下基线条目已不存在（可 --update-baseline 收缩）：")
        for entry in fixed:
            print("   ", entry)
    if args.report:
        print("\n（--report：不判退出码）")
        return 0
    if new:
        print("\n修法：形参改 LocalizedStringKey，或在调用点包 String(localized:)，"
              "并把键补进 Localizable.xcstrings。")
        return 1
    print("\n✅ 无新增：调用点本地化没有回退。")
    return 0


if __name__ == "__main__":
    sys.exit(main())