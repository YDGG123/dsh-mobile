#!/usr/bin/env python3
"""i18n 盘点器：把 Swift 源码里的**全部中文字面量**分成四桶，产出可复现的清单。

与 `verify-localizations.py` 的分工：
- `verify-localizations.py` 是**校验器**（目录是否成对、格式参数是否对得上、静态调用是否合规），
  返回"通过/不通过"。
- 本脚本是**盘点器**：回答"还有多少中文没能跟着语言走、卡在哪一类"，产出
  `Docs/i18n-inventory.md` + `Docs/i18n-inventory.csv` 当 TODO 清单。
  两脚本共用同一份 `Localizable.xcstrings`，口径不同、互不替代。

四桶口径（判定顺序自上而下）
  1. `registered`       键就是中文，且已登记进 `Localizable.xcstrings`（`Text("…")`、`Label("…")` 等）
  2. `localized_default` `String(localized: "key", defaultValue: "中文")` 里的 defaultValue
                        —— 中文只是兜底，真正的键是 `key`，已本地化
  3. `unregistered`     **(c) 漏登记**：出现在本地化调用里，但目录里没有对应键
                        （重启也仍是中文；补目录即可解决 → 分期第 ① 步）
  4. `hardcoded`        **(a) 真硬编码**：不在任何本地化调用里（`String` 变量 / 三元 / 数组 /
                        非本地化实参等）。重启、免重启都救不了，必须逐个改成本地化调用
                        （→ 分期第 ③ 步）

⚠ 不确定性（**别当精确值用**）
  带字符串插值的条目靠"前缀匹配目录键"判定（Swift 会把 `"已同步 \\(n) 个"` 提取成 `"已同步 %lld 个"`）。
  这类条目：硬编码桶约 102 条、漏登记桶约 13 条，**边缘计数有 ±10 的误差**；不带插值的条目是确定判定。

用法
  python3 scripts/i18n-inventory.py                # 打印计数（只读，不写文件）
  python3 scripts/i18n-inventory.py --write        # 另外写出 Docs/i18n-inventory.md + .csv
  python3 scripts/i18n-inventory.py --check        # 当闸门用：见下
  python3 scripts/i18n-inventory.py --write --check

退出码
  0 = 盘点完成；`--check` 时表示 (b) 目录缺 en == 0 且 (c) 漏登记 == 0
  1 = `--check` 下发现不符合项（(b) 或 (c) 非空）——这是分期第 ① 步的验收条件
  2 = 用法错误或关键文件缺失（目录 / 源码路径读不到）

零依赖：只用 Python 3 标准库。
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "DeepSeekHarnessMobile" / "Resources" / "Localizable.xcstrings"
SWIFT_DIRS = ("DeepSeekHarnessMobile", "AgentLiveActivityWidget")
WIDGET_DIR = "AgentLiveActivityWidget"
PBXPROJ = ROOT / "DeepSeekHarnessMobile.xcodeproj" / "project.pbxproj"

CJK = re.compile(r"[\u3000-\u303f\u3400-\u9fff\uff00-\uffef]")
STRING_LITERAL = re.compile(r'"((?:\\.|[^"\\])*)"')

# 会把第一个字面量当本地化键的调用（SwiftUI / Foundation）。
LOCALIZING_CALLS = {
    "Text", "Label", "Button", "Toggle", "Section", "Picker", "Link", "Menu", "NavigationLink",
    "Stepper", "TextField", "SecureField", "LocalizedStringKey", "NSLocalizedString", "LabeledContent",
    "navigationTitle", "navigationBarTitle", "alert", "confirmationDialog", "accessibilityLabel",
    "accessibilityHint", "accessibilityValue", "help", "tabItem", "searchable", "badge",
    "ContentUnavailableView", "GroupBox", "TextEditor",
}
# 这些实参标签下的字面量**不会**被本地化（`String(format:)` 等）。
NON_LOCALIZING_LABELS = {"format", "describing", "init", "verbatim"}


def strip_comments(source: str) -> str:
    """把注释换成等长空白，字符串原样保留（保证行号与位置不变）。"""
    out: list[str] = []
    i, n, state = 0, len(source), "code"
    while i < n:
        ch = source[i]
        nxt = source[i + 1] if i + 1 < n else ""
        if state == "code":
            if ch == "/" and nxt == "/":
                state, pad = "line", "  "
            elif ch == "/" and nxt == "*":
                state, pad = "block", "  "
            elif source[i:i + 3] == '"""':
                state, pad = "multiline", "   "
            elif ch == '"':
                state, pad = "string", ch
            else:
                out.append(ch)
                i += 1
                continue
            out.append(pad)
            i += len(pad)
            continue
        if state == "line":
            if ch == "\n":
                state = "code"
                out.append("\n")
            else:
                out.append(" ")
            i += 1
            continue
        if state == "block":
            if ch == "*" and nxt == "/":
                state = "code"
                out.append("  ")
                i += 2
                continue
            out.append("\n" if ch == "\n" else " ")
            i += 1
            continue
        if state == "string":
            if ch == "\\":
                out.append(source[i:i + 2])
                i += 2
                continue
            if ch == '"':
                state = "code"
            out.append(ch)
            i += 1
            continue
        # multiline
        if source[i:i + 3] == '"""':
            state = "code"
            out.append("   ")
            i += 3
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def enclosing_call(text: str, pos: int) -> tuple[str, str]:
    """返回字面量所在的最内层调用名与它前面的实参标签。

    往前扫描时跳过先前的字符串字面量，因此 `Text(a ? "x" : "中文")` 也能认出 `Text`。
    """
    j, depth = pos - 1, 0
    while j >= 0:
        ch = text[j]
        if ch == '"':
            k = j - 1
            while k >= 0:
                if text[k] == "\\":
                    k -= 2
                    continue
                if text[k] == '"':
                    j = k - 1
                    break
                k -= 1
            else:
                return "(无调用)", ""
            continue
        if ch == ")":
            depth += 1
        elif ch == "(":
            if depth == 0:
                break
            depth -= 1
        j -= 1
    if j < 0:
        return "(无调用)", ""
    label = ""
    m = re.search(r"(\w+)\s*:\s*$", text[max(0, j + 1):pos])
    if m:
        label = m.group(1)
    k = j - 1
    while k >= 0 and text[k] in " \t\n":
        k -= 1
    end = k
    while k >= 0 and (text[k].isalnum() or text[k] in "_$"):
        k -= 1
    return (text[k + 1:end + 1] or "(无调用)"), label


def balanced_call_args(text: str, open_index: int) -> str:
    """取从 `(` 起平衡括号内的文本。"""
    depth, i, in_string = 0, open_index, False
    while i < len(text):
        ch = text[i]
        if in_string:
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                in_string = False
        else:
            if ch == '"':
                in_string = True
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    return text[open_index + 1:i]
        i += 1
    return ""


def collect_default_values(paths: list[Path]) -> tuple[set[tuple[Path, int]], list[tuple[str, str]]]:
    """找出 `String(localized:)` 的 defaultValue 字面量位置，并返回 (位置集合, 键缺失列表)。"""
    spans: set[tuple[Path, int]] = set()
    missing: list[tuple[str, str]] = []
    for path in paths:
        clean = strip_comments(path.read_text(encoding="utf-8"))
        for m in re.finditer(r"String\(\s*localized\s*:", clean):
            open_index = clean.index("(", m.start())
            args = balanced_call_args(clean, open_index)
            base = open_index + 1
            key_match = re.search(r'localized\s*:\s*"((?:\\.|[^"\\])*)"', clean[base:base + len(args) + 40])
            key = key_match.group(1) if key_match else None
            default_match = re.search(r'defaultValue\s*:\s*"', clean[base:])
            if default_match:
                spans.add((path, base + default_match.end() - 1))
            if key is not None:
                missing.append((str(path), key))
    return spans, missing


def swift_files() -> list[Path]:
    files: list[Path] = []
    for base in SWIFT_DIRS:
        root = ROOT / base
        if root.is_dir():
            files.extend(sorted(p for p in root.rglob("*.swift")))
    return files


def catalog_keys() -> dict[str, dict]:
    if not CATALOG.is_file():
        print(f"i18n-inventory: 找不到目录文件 {CATALOG}", file=sys.stderr)
        sys.exit(2)
    return json.loads(CATALOG.read_text(encoding="utf-8")).get("strings", {})


def catalog_gaps(catalog: dict[str, dict]) -> dict[str, list]:
    """(b) 目录自身的问题：缺 en / en 未翻译。"""
    missing_en, untranslated = [], []
    for key, entry in catalog.items():
        localizations = entry.get("localizations", {})
        if "en" not in localizations:
            missing_en.append(key)
        elif localizations["en"].get("stringUnit", {}).get("state") not in (None, "translated"):
            untranslated.append(key)
    return {"missing_en": missing_en, "untranslated_en": untranslated}


def widget_ships_catalog() -> bool | None:
    """AgentLiveActivityWidget 这个 target 是否把 Localizable.xcstrings 打进了 Resources。"""
    if not PBXPROJ.is_file():
        return None
    text = PBXPROJ.read_text(encoding="utf-8", errors="ignore")
    build_file = re.search(r"([A-F0-9]{24}) /\* Localizable\.xcstrings in Resources \*/", text)
    if build_file is None:
        return None
    phase_ids = re.findall(
        r"([A-F0-9]{24}) /\* Resources \*/ = \{\s*isa = PBXResourcesBuildPhase;.*?files = \((.*?)\);",
        text, re.S)
    owning = [pid for pid, body in phase_ids if build_file.group(1) in body]
    for target_match in re.finditer(
            r"([A-F0-9]{24}) /\* ([\w.]+) \*/ = \{\s*isa = PBXNativeTarget;.*?buildPhases = \((.*?)\);",
            text, re.S):
        _, name, body = target_match.groups()
        for pid in owning:
            if re.search(pid + r" /\* Resources \*/", body) and name == "AgentLiveActivityWidget":
                return True
    return False


def classify() -> dict:
    catalog = catalog_keys()
    keys = set(catalog)
    files = swift_files()
    if not files:
        print("i18n-inventory: 没找到 Swift 源码", file=sys.stderr)
        sys.exit(2)
    default_spans, localized_keys = collect_default_values(files)
    buckets: dict[str, list[dict]] = {k: [] for k in
                                      ("registered", "localized_default", "unregistered", "hardcoded")}
    for path in files:
        clean = strip_comments(path.read_text(encoding="utf-8"))
        rel = str(path.relative_to(ROOT))
        bundle = "Widget/LiveActivity" if rel.startswith(WIDGET_DIR) or "/LiveActivity/" in rel else "App"
        for m in STRING_LITERAL.finditer(clean):
            raw = m.group(1)
            if not CJK.search(raw):
                continue
            text = raw.replace('\\"', '"')
            record = {
                "file": rel,
                "line": clean[:m.start()].count("\n") + 1,
                "text": text,
                "bundle": bundle,
                "context": "",
                "label": "",
            }
            if (path, m.start()) in default_spans:
                record["context"] = "String(localized:…defaultValue:)"
                buckets["localized_default"].append(record)
                continue
            in_catalog = text in keys
            if not in_catalog:
                prefix = text.split("\\(")[0]
                if len(prefix) >= 2 and any(k.startswith(prefix) for k in keys):
                    in_catalog = True
            if in_catalog:
                record["context"] = "(目录键命中)"
                buckets["registered"].append(record)
                continue
            call, label = enclosing_call(clean, m.start())
            record["context"], record["label"] = call, label
            localizing = call in LOCALIZING_CALLS and label not in NON_LOCALIZING_LABELS
            buckets["unregistered" if localizing else "hardcoded"].append(record)
    return {
        "buckets": buckets,
        "catalog": catalog,
        "gaps": catalog_gaps(catalog),
        "localized_call_keys_missing": [k for _, k in localized_keys if k not in keys],
        "widget_ships_catalog": widget_ships_catalog(),
        "files": len(files),
    }


def interpolated(entries: list[dict], bucket: str) -> int:
    return sum(1 for e in entries if "\\(" in e["text"]) if bucket != "i" else 0


def by_file(entries: list[dict]) -> list[tuple[str, int]]:
    return Counter(e["file"] for e in entries).most_common()


def render_markdown(result: dict) -> str:
    b = result["buckets"]
    total = sum(len(v) for v in b.values())
    hard, unreg = b["hardcoded"], b["unregistered"]
    app_hard = [e for e in hard if e["bundle"] == "App"]
    widget = [e for e in hard + unreg if e["bundle"] != "App"]
    switchable = len(b["registered"]) + len(b["localized_default"])
    stuck = len(hard) + len(unreg)
    lines: list[str] = []
    add = lines.append
    add("# i18n 中文清单（盘点）")
    add("")
    add("> 由 `scripts/i18n-inventory.py --write` 生成，**不要手改**：重跑即更新。")
    add("> 口径、四桶定义、以及不确定性说明都在脚本头部注释里，以那里为准。")
    add("")
    add("## ⚠️ 先读：不确定性")
    add("")
    add(f"- 带字符串插值的条目靠**前缀匹配目录键**判定：硬编码桶约 "
        f"**{sum(1 for e in hard if chr(92) + '(' in e['text'])} 条**、漏登记桶约 "
        f"**{sum(1 for e in unreg if chr(92) + '(' in e['text'])} 条**，")
    add("  **边缘计数有 ±10 的误差**，不要当精确值引用；不带插值的条目是确定判定。")
    add("- `Localizable.xcstrings` 的键会被 Swift 提取成 `%@` / `%lld` 形式，脚本用前缀匹配近似。")
    add("")
    add("## 计数")
    add("")
    add("| 桶 | 条数 | 含义 |")
    add("| --- | --- | --- |")
    add(f"| `registered` | {len(b['registered'])} | 键就是中文，已登记（重启后即可跟随语言） |")
    add(f"| `localized_default` | {len(b['localized_default'])} | `String(localized:defaultValue:)` 的中文兜底（已本地化） |")
    add(f"| `unregistered` (c) | {len(unreg)} | **漏登记**：本地化调用里但目录无键 → 补目录（第 ① 步） |")
    add(f"| `hardcoded` (a) | {len(hard)} | **真硬编码**：重启/免重启都救不了（第 ③ 步） |")
    add(f"| **合计** | **{total}** | 源码含中文字面量（{result['files']} 个 Swift 文件） |")
    add("")
    add(f"- 能跟随语言（重启后）：**{switchable}**（{switchable * 100 / total:.1f}%）")
    add(f"- 重启也不会变：**{stuck}**（{stuck * 100 / total:.1f}%）= 硬编码 {len(hard)} + 漏登记 {len(unreg)}"
        f"，其中 Widget/LiveActivity 占 {len(widget)}")
    add(f"- 目录自身缺 `en`：**{len(result['gaps']['missing_en'])}** 条；`en` 未翻译："
        f"**{len(result['gaps']['untranslated_en'])}** 条")
    add(f"- `String(localized:)` 的键不在目录：**{len(result['localized_call_keys_missing'])}** 处"
        f"（多为插值键，见不确定性）")
    shipped = result["widget_ships_catalog"]
    add(f"- 结构：`AgentLiveActivityWidget` target 是否带 `Localizable.xcstrings`："
        f"**{'是' if shipped else '否' if shipped is False else '未判定'}**"
        + ("（否 → 该扩展的文案无论怎么切语言都不变）" if shipped is False else ""))
    add("")
    add("## (c) 漏登记：本地化调用里、目录无键")
    add("")
    for file, count in by_file(unreg):
        add(f"### `{file}` （{count}）")
        add("")
        for e in [x for x in unreg if x["file"] == file]:
            add(f"- `:{e['line']}` `[{e['context']}]` {e['text'][:60]}")
        add("")
    add("## (a) 真硬编码：不在本地化调用里")
    add("")
    add("### 按文件")
    add("")
    for file, count in by_file(hard):
        add(f"- {count:4d}  `{file}`")
    add("")
    add("### App 侧 `Views/` 的用户可见条目")
    add("")
    for e in [x for x in app_hard if "/Views/" in x["file"]]:
        add(f"- `{e['file']}:{e['line']}` `[{e['context']}]` {e['text'][:60]}")
    add("")
    return "\n".join(lines) + "\n"


def write_outputs(result: dict, out_dir: Path) -> tuple[Path, Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    markdown_path = out_dir / "i18n-inventory.md"
    csv_path = out_dir / "i18n-inventory.csv"
    markdown_path.write_text(render_markdown(result), encoding="utf-8")
    order = ("registered", "localized_default", "unregistered", "hardcoded")
    with csv_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["bucket", "bundle", "file", "line", "context", "label", "text"])
        for bucket in order:
            for e in sorted(result["buckets"][bucket], key=lambda x: (x["file"], x["line"])):
                writer.writerow([bucket, e["bundle"], e["file"], e["line"], e["context"], e["label"], e["text"]])
    return markdown_path, csv_path


def main() -> int:
    parser = argparse.ArgumentParser(description="盘点 Swift 源码里的中文字面量与本地化缺口")
    parser.add_argument("--write", action="store_true", help="写出 Docs/i18n-inventory.md 与 .csv")
    parser.add_argument("--out-dir", type=Path, default=ROOT / "Docs", help="输出目录（默认 Docs/）")
    parser.add_argument("--check", action="store_true",
                        help="当闸门：目录缺 en != 0 或漏登记 != 0 时以 1 退出")
    args = parser.parse_args()

    result = classify()
    b = result["buckets"]
    hard, unreg = b["hardcoded"], b["unregistered"]
    print(f"Swift 文件 {result['files']} 个；含中文字面量 {sum(len(v) for v in b.values())} 条")
    print(f"  registered        {len(b['registered'])}")
    print(f"  localized_default {len(b['localized_default'])}")
    print(f"  (c) unregistered  {len(unreg)}   App {sum(1 for e in unreg if e['bundle'] == 'App')}"
          f" / Widget {sum(1 for e in unreg if e['bundle'] != 'App')}")
    print(f"  (a) hardcoded     {len(hard)}   App {sum(1 for e in hard if e['bundle'] == 'App')}"
          f" / Widget {sum(1 for e in hard if e['bundle'] != 'App')}")
    print(f"  目录缺 en {len(result['gaps']['missing_en'])} / en 未翻译 {len(result['gaps']['untranslated_en'])}"
          f" / String(localized:) 键缺失 {len(result['localized_call_keys_missing'])}")
    shipped = result["widget_ships_catalog"]
    print(f"  Widget target 带目录: {'是' if shipped else '否' if shipped is False else '未判定'}")

    if args.write:
        markdown_path, csv_path = write_outputs(result, args.out_dir)
        print(f"已写出 {markdown_path.relative_to(ROOT)} 与 {csv_path.relative_to(ROOT)}")

    if args.check:
        problems = []
        if result["gaps"]["missing_en"]:
            problems.append(f"目录缺 en {len(result['gaps']['missing_en'])} 条")
        if unreg:
            problems.append(f"漏登记 {len(unreg)} 条")
        if problems:
            print("i18n-inventory: 不通过 — " + "；".join(problems), file=sys.stderr)
            return 1
        print("i18n-inventory: 通过（目录缺 en 0、漏登记 0）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
