#!/usr/bin/env python3
"""检查中英文目录、格式参数，以及 Swift / Android 的静态本地化调用。"""

from __future__ import annotations

import json
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
IOS_RESOURCES = ROOT / "DeepSeekHarnessMobile" / "Resources"
IOS_SOURCES = ROOT / "DeepSeekHarnessMobile"
ANDROID_RESOURCES = ROOT / "androidApp" / "src" / "main" / "res"
ANDROID_SOURCES = ROOT / "androidApp" / "src" / "main" / "kotlin"
HAN = re.compile(r"[\u3400-\u9fff]")
IOS_ARGUMENT = re.compile(r"%(?:\d+\$)?(?:@|lld|ld|zd|d|lu|u|f|g|s|c|i)")
ANDROID_ARGUMENT = re.compile(r"%(?:\d+\$)?(?:s|d|f)")
LOCALIZED_CALL = re.compile(r'String\s*\(\s*localized:\s*"((?:\\.|[^"\\])*)"')
UI_CALL_NAMES = (
    "Text", "Button", "Label", "TextField", "SecureField", "Toggle", "Picker",
    "Section", "navigationTitle", "accessibilityLabel", "accessibilityValue",
    "confirmationDialog", "ContentUnavailableView", "ProgressView", "DatePicker",
    "alert", "Menu",
)
UI_CALL = re.compile(
    r"(?<![A-Za-z0-9_])(?:" + "|".join(UI_CALL_NAMES) + r')\s*\(\s*"((?:\\.|[^"\\])*)"'
)
KOTLIN_STRING = r'"((?:\\.|[^"\\])*)"'
KOTLIN_TRANSLATION = re.compile(KOTLIN_STRING + r"\s+to\s+" + KOTLIN_STRING)


SWIFT_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "0": "\0", '"': '"', "'": "'", "\\": "\\"}


def decode_swift_escapes(text: str) -> str:
    """把 Swift 字面量里的常见转义解码成实际字符（与目录键的口径对齐）。"""
    out, i = [], 0
    while i < len(text):
        ch = text[i]
        if ch == "\\" and i + 1 < len(text):
            out.append(SWIFT_ESCAPES.get(text[i + 1], text[i + 1]))
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def arguments(value: str, pattern: re.Pattern[str]) -> list[str]:
    return sorted(re.sub(r"^%\d+\$", "%", match) for match in pattern.findall(value))


def value(entry: dict, language: str) -> str | None:
    return entry.get("localizations", {}).get(language, {}).get("stringUnit", {}).get("value")


def check_catalog(name: str, errors: list[str]) -> set[str]:
    data = json.loads((IOS_RESOURCES / f"{name}.xcstrings").read_text())
    strings = data["strings"]
    for key, entry in strings.items():
        english = value(entry, "en")
        chinese = value(entry, "zh-Hans")
        if not english or not english.strip():
            errors.append(f"{name}: {key!r} 缺少英文译文")
        if (name == "InfoPlist" or not HAN.search(key)) and (not chinese or not chinese.strip()):
            errors.append(f"{name}: {key!r} 缺少简体中文译文")
        if english and HAN.search(english):
            errors.append(f"{name}: {key!r} 的英文译文含中文")
        for language, localization in entry.get("localizations", {}).items():
            unit = localization.get("stringUnit", {})
            if not unit.get("value", "").strip() or unit.get("state") != "translated":
                errors.append(f"{name}: {key!r} 的 {language} 译文为空或未审定")
        if english:
            source = chinese if chinese is not None else key
            if arguments(source, IOS_ARGUMENT) != arguments(english, IOS_ARGUMENT):
                errors.append(f"{name}: {key!r} 的中英文格式参数不一致")
    return set(strings)


def check_swift_calls(keys: set[str], errors: list[str]) -> None:
    for path in IOS_SOURCES.rglob("*.swift"):
        source = path.read_text()
        for pattern in (LOCALIZED_CALL, UI_CALL):
            for match in pattern.finditer(source):
                raw = match.group(1)
                # 先按**原始字面量**判断是不是插值（`\(…)` 那种由类型决定 specifier，
                # 不在这里管）；再解码转义后与目录键比对——目录里存的是实际字符
                # （多行文案在 JSON 里是真实换行，拿源码里的 `\n` 直接比会永远不匹配）。
                if not raw or "\\(" in raw:
                    continue
                key = decode_swift_escapes(raw)
                if key and key not in keys:
                    errors.append(f"{path.relative_to(ROOT)}: {key!r} 未收录到 Localizable.xcstrings")


def android_strings(language: str) -> dict[str, str]:
    suffix = "" if language == "zh-Hans" else "-en"
    path = ANDROID_RESOURCES / f"values{suffix}" / "strings.xml"
    return {node.attrib["name"]: "".join(node.itertext()) for node in ET.parse(path).getroot().findall("string")}


def check_android(errors: list[str]) -> int:
    chinese = android_strings("zh-Hans")
    english = android_strings("en")
    for key in sorted(chinese.keys() | english.keys()):
        zh, en = chinese.get(key), english.get(key)
        if not zh or not zh.strip() or not en or not en.strip():
            errors.append(f"Android: {key!r} 缺少中文或英文译文")
        elif arguments(zh, ANDROID_ARGUMENT) != arguments(en, ANDROID_ARGUMENT):
            errors.append(f"Android: {key!r} 的中英文格式参数不一致")
    return len(chinese.keys() | english.keys())


def kotlin_string(raw: str) -> str:
    return json.loads('"' + raw.replace(r"\$", "$") + '"')


# —— Kotlin 界面文案识别 ——
#
# 早先只匹配「紧跟 `Text(` 的字面量」，`Text(if (a) "…" else "…")`、`Text(when { … })`、
# `val s = when { … }; Text(s)`、`Text(PHRASES[k])` 这四种形态全部漏判（QA 在
# TeamUi.kt:199/236、ConversationScreen.kt:1208/1445 复现）。这里按「实参表达式」而不是
# 「下一个字面量」来判：先把整段实参抠出来，再排除已包在 `appText(...)` 里的键，
# 剩下的中文才算硬编码；配合「局部变量流向」与「字符串表」两条补漏规则覆盖后两种形态。
KOTLIN_SINK_CALL = re.compile(r"(?<![A-Za-z0-9_])(?:Text|BasicText|DshMarkdownText)\s*\(")
KOTLIN_NAMED_SINK = re.compile(
    r"\b(?:title|description|contentDescription|confirmLabel|dismissLabel|onClickLabel)\s*=\s*"
)
KOTLIN_APPTEXT_CALL = re.compile(r"\bappText\s*\(")
KOTLIN_LOCAL_DECL = re.compile(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+?)?=\s*")
KOTLIN_STRING_TABLE = re.compile(
    r"^\s*(?:private\s+|internal\s+|public\s+)?"
    r"(?:val\s+([A-Za-z_]\w*)\s*(?::[^=\n]+?)?|fun\s+([A-Za-z_]\w*)\s*\([^)]*\)\s*(?::[^=\n]+?)?)"
    r"=\s*(?:listOf|mapOf|arrayOf|setOf)\s*\(",
    re.MULTILINE,
)
KOTLIN_LITERAL = re.compile(r'"((?:\\.|[^"\\])*)"')
MAX_ARGUMENT_CHARS = 8000


def blank_ranges(text: str, ranges: list[tuple[int, int]]) -> str:
    """把若干区间抹成空格（保留换行，行号不变），用于排除 appText(...) / 注释 / 字面量内部。"""
    if not ranges:
        return text
    chars = list(text)
    for start, end in ranges:
        for index in range(max(0, start), min(len(text), end)):
            if chars[index] != "\n":
                chars[index] = " "
    return "".join(chars)


def kotlin_scan(source: str, start: int, depth: int, stop_at_comma: bool = True) -> int:
    """从 start 起扫一段表达式，返回结束位置。

    depth=1 表示刚吃掉一个左括号（按顶层逗号/右括号收口）；
    depth=0 表示 `=` 右侧、未知长度的表达式（按顶层逗号/右括号/换行收口）。
    """
    index, length = start, len(source)
    current = depth
    in_string = False
    escaped = False
    in_line = False
    in_block = False
    while index < length:
        char = source[index]
        if index - start > MAX_ARGUMENT_CHARS:
            return index
        if in_line:
            if char == "\n":
                in_line = False
                if depth == 0 and current == 0:
                    return index
            index += 1
            continue
        if in_block:
            if source.startswith("*/", index):
                in_block = False
                index += 2
                continue
            index += 1
            continue
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            index += 1
            continue
        if source.startswith("//", index):
            in_line = True
            index += 2
            continue
        if source.startswith("/*", index):
            in_block = True
            index += 2
            continue
        if char == '"':
            in_string = True
            index += 1
            continue
        if char in "([{":
            current += 1
            index += 1
            continue
        if char in ")]}":
            current -= 1
            if current < depth:
                return index
            if depth == 1 and current == 0:
                return index
            index += 1
            continue
        if stop_at_comma and current == depth and char == ",":
            return index
        if depth == 0 and current == 0 and char == "\n":
            return index
        index += 1
    return index


def kotlin_apptext_ranges(text: str) -> list[tuple[int, int]]:
    """找出 text 里每个 appText(...) 的区间（含括号），用于把它们从硬编码判定里排除。"""
    ranges: list[tuple[int, int]] = []
    for match in KOTLIN_APPTEXT_CALL.finditer(text):
        end = kotlin_scan(text, match.end(), 1)
        ranges.append((match.start(), end + 1))
    return ranges


def kotlin_display(raw: str) -> str:
    """把 Kotlin 字面量转成可读文本（仅用于报错信息，不参与目录比对）。

    多行/原生换行字面量会让 json 解码失败，这里退化成按裸串报告，
    保证闸门「报错而不是崩掉」。
    """
    try:
        return kotlin_string(raw)
    except (ValueError, json.JSONDecodeError):
        return raw.replace("\n", "\\n")


def kotlin_hardcoded_literals(text: str) -> list[tuple[int, str]]:
    """text 里未包在 appText(...) 内的中文串字面量，返回 [(相对偏移, 解码后文本)]。"""
    masked = blank_ranges(text, kotlin_apptext_ranges(text))
    results: list[tuple[int, str]] = []
    for match in KOTLIN_LITERAL.finditer(masked):
        if HAN.search(match.group(1)):
            results.append((match.start(), kotlin_display(match.group(1))))
    return results


def kotlin_sink_spans(source: str) -> list[tuple[int, int]]:
    """所有 UI 文案实参表达式的区间（按 code mask 匹配，避免命中注释/字符串里的同名文本）。"""
    code = blank_ranges(source, kotlin_literal_and_comment_ranges(source))
    spans: list[tuple[int, int]] = []
    for match in KOTLIN_SINK_CALL.finditer(code):
        spans.append((match.end(), kotlin_scan(source, match.end(), 1)))
    for match in KOTLIN_NAMED_SINK.finditer(code):
        spans.append((match.end(), kotlin_scan(source, match.end(), 0)))
    return spans


def kotlin_literal_and_comment_ranges(source: str) -> list[tuple[int, int]]:
    """注释与字符串内部的区间（字面量本身保留引号，内容抹掉），供 code mask 使用。"""
    ranges: list[tuple[int, int]] = []
    index, length = 0, len(source)
    while index < length:
        if source.startswith("//", index):
            end = source.find("\n", index)
            end = length if end < 0 else end
            ranges.append((index, end))
            index = end
            continue
        if source.startswith("/*", index):
            end = source.find("*/", index + 2)
            end = length if end < 0 else end + 2
            ranges.append((index, end))
            index = end
            continue
        if source[index] == '"':
            end = index + 1
            while end < length:
                if source[end] == "\\":
                    end += 2
                    continue
                if source[end] == '"':
                    break
                end += 1
            ranges.append((index + 1, min(end, length)))
            index = end + 1
            continue
        index += 1
    return ranges


def check_android_ui(errors: list[str]) -> int:
    path = ANDROID_SOURCES / "com/clarklevis/dsh/android/localization/AndroidStrings.kt"
    source = path.read_text().split("internal val english:", 1)[1]
    catalog: dict[str, str] = {}
    for match in KOTLIN_TRANSLATION.finditer(source):
        key, english = (kotlin_string(raw) for raw in match.groups())
        if key in catalog:
            errors.append(f"Android UI: {key!r} 重复收录")
        catalog[key] = english
        if not english.strip() or HAN.search(english):
            errors.append(f"Android UI: {key!r} 缺少完整英文译文")
        if arguments(key, ANDROID_ARGUMENT) != arguments(english, ANDROID_ARGUMENT):
            errors.append(f"Android UI: {key!r} 的中英文格式参数不一致")
    for path in ANDROID_SOURCES.rglob("*.kt"):
        if path.name == "AndroidStrings.kt":
            continue
        source = path.read_text()
        code = blank_ranges(source, kotlin_literal_and_comment_ranges(source))
        # 规则 4：`appText(...)` 实参里的每个字面量都必须是目录键。
        # 早先只校验紧跟括号的第一个字面量，`appText(if (a) "x" else "y")` 的 else 分支
        # 永远不会被检查（QA 报的「三元」形态）。
        for match in KOTLIN_APPTEXT_CALL.finditer(code):
            end = kotlin_scan(source, match.end(), 1)
            argument = source[match.end():end]
            for literal in KOTLIN_LITERAL.finditer(blank_ranges(argument, kotlin_apptext_ranges(argument))):
                if not HAN.search(literal.group(1)):
                    continue
                key = kotlin_display(literal.group(1))
                if key not in catalog:
                    offset = match.end() + literal.start()
                    line = source.count("\n", 0, offset) + 1
                    errors.append(f"{path.relative_to(ROOT)}:{line} {key!r} 未收录到 AndroidStrings")

        reported: set[tuple[int, str]] = set()

        def report(offset: int, value: str, how: str) -> None:
            line = source.count("\n", 0, offset) + 1
            if (line, value) in reported:
                return
            reported.add((line, value))
            # 逃生口：解析用/测量用的中文（如「（推荐）」后缀表）不是展示文案，
            # 同行写 `// i18n-skip: 原因` 即豁免，避免逼着大家把数据当文案翻译。
            lines = source.splitlines()
            if "i18n-skip" in lines[line - 1] or (line >= 2 and "i18n-skip" in lines[line - 2]):
                return
            errors.append(f"{path.relative_to(ROOT)}:{line} 界面文案仍为硬编码中文（{how}：{value!r}）")

        # 规则 1：UI 实参表达式里的中文（覆盖 `Text(if …)`、`Text(when …)`、三元、命名参数）。
        spans = kotlin_sink_spans(source)
        for start, end in spans:
            for offset, value in kotlin_hardcoded_literals(source[start:end]):
                report(start + offset, value, "UI 实参")

        # 规则 2：先赋给局部变量、再喂给 UI 的中文（覆盖 `val s = when { … }` / `Text(s)`）。
        sink_text = " ".join(source[start:end] for start, end in spans)
        for match in KOTLIN_LOCAL_DECL.finditer(code):
            name = match.group(1)
            end = kotlin_scan(source, match.end(), 0)
            for offset, value in kotlin_hardcoded_literals(source[match.end():end]):
                if re.search(rf"(?<![A-Za-z0-9_]){re.escape(name)}(?![A-Za-z0-9_])", sink_text):
                    report(match.end() + offset, value, f"局部变量 {name} 流向 UI")

        # 规则 3：UI 文件里的中文文案表（覆盖 `mapOf(… → listOf("梳理脉络中", …))` 这类轮换短语）。
        if spans:
            for match in KOTLIN_STRING_TABLE.finditer(code):
                end = kotlin_scan(source, match.end(), 1, stop_at_comma=False)
                for offset, value in kotlin_hardcoded_literals(source[match.end():end]):
                    report(match.end() + offset, value, f"文案表 {match.group(1) or match.group(2)}")
    return len(catalog)



def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-only", action="store_true", help="仅检查 Android 本地化")
    options = parser.parse_args()
    errors: list[str] = []
    keys = set()
    if not options.android_only:
        keys = check_catalog("Localizable", errors)
        check_catalog("InfoPlist", errors)
        check_swift_calls(keys, errors)
    # 裁剪副本（只拷 iOS 相关目录）里没有 androidApp：跳过 Android 两项并明确提示，
    # 而不是抛 FileNotFoundError 的 traceback。
    if ANDROID_RESOURCES.is_dir() and ANDROID_SOURCES.is_dir():
        android_count = check_android(errors)
        android_ui_count = check_android_ui(errors)
    else:
        missing = [str(p.relative_to(ROOT)) for p in (ANDROID_RESOURCES, ANDROID_SOURCES) if not p.is_dir()]
        print(f"ℹ️  跳过 Android 本地化检查（副本里缺少 {', '.join(missing)}）")
        android_count = android_ui_count = 0
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    if android_count or android_ui_count:
        print(f"本地化检查通过：{len(keys)} 条 iOS 文案，{android_count} 条 Android 系统文案，"
              f"{android_ui_count} 条 Android 界面文案。")
    else:
        print(f"本地化检查通过：{len(keys)} 条 iOS 文案（Android 部分已跳过）。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
