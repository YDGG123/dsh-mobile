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
KOTLIN_LOCALIZED_CALL = re.compile(r"\bappText\(\s*" + KOTLIN_STRING)
KOTLIN_UI_LITERAL = re.compile(
    r"(?:\b(?:Text|BasicText|DshMarkdownText)\(\s*|"
    r"\b(?:title|description|contentDescription|confirmLabel|dismissLabel)\s*=\s*)"
    + KOTLIN_STRING
)


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
        for match in KOTLIN_LOCALIZED_CALL.finditer(source):
            key = kotlin_string(match.group(1))
            if key not in catalog:
                errors.append(f"{path.relative_to(ROOT)}: {key!r} 未收录到 AndroidStrings")
        for match in KOTLIN_UI_LITERAL.finditer(source):
            if HAN.search(match.group(1)):
                line = source.count("\n", 0, match.start()) + 1
                errors.append(f"{path.relative_to(ROOT)}:{line} 界面文案仍为硬编码中文")
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
