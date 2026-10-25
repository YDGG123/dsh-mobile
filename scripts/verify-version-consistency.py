#!/usr/bin/env python3
"""版本一致性闸门：主 App 与 Widget 的版本号必须来自同一组值。

背景（0548 包翻车）：两处版本号来源不同 ——
- 主 App target：`GENERATE_INFOPLIST_FILE = NO` + `INFOPLIST_FILE = DeepSeekHarnessMobile/Resources/Info.plist`，
  该 target build settings 里**没有** `CURRENT_PROJECT_VERSION`，所以 `CFBundleVersion`
  直接取这份 plist 里**硬编码的字符串**；
- Widget target：`AgentLiveActivityWidget/Info.plist` 用变量 `$(CURRENT_PROJECT_VERSION)` / `$(MARKETING_VERSION)`，
  值来自 project.pbxproj。

于是"改错 target"（只改 widget 那两处）会让主 App 停在旧 build 号、Widget 跳到新的，
CI 照常出包、只有 QA 在包里实测才发现 —— 白费一次云端构建。本脚本把这件事挪到本地拦。

校验三方：
1. 主 App plist 的 `CFBundleVersion` ↔ `CFBundleShortVersionString`（都必须非空、非变量占位）
2. Widget plist 必须使用变量（`$(CURRENT_PROJECT_VERSION)` / `$(MARKETING_VERSION)`），
   且 pbxproj 里 `CURRENT_PROJECT_VERSION` / `MARKETING_VERSION` **各恰好两处且相等**（Debug/Release）
3. 主 App 与 Widget 的 build 号相等、short version 相等
4. **比本地已出过的包新**：`build-output/*.ipa` 里同 short version 的最大 build 号必须**小于**
   当前 build 号（0620 与 0621 都是 (32) 这种"装完分不出新旧"的坑，闸门能一致但不保证更新）

退出码：0 = 一致；1 = 不一致；2 = 输入缺失/无法解析（文件不存在、键缺失、变量没生效）。
可用 `VERSION_GATE_ROOT` 指定仓库根（反向测试用）。
"""

from __future__ import annotations

import os
import plistlib
import re
import sys

ROOT = os.environ.get("VERSION_GATE_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP_PLIST = os.path.join(ROOT, "DeepSeekHarnessMobile", "Resources", "Info.plist")
WIDGET_PLIST = os.path.join(ROOT, "AgentLiveActivityWidget", "Info.plist")
PBXPROJ = os.path.join(ROOT, "DeepSeekHarnessMobile.xcodeproj", "project.pbxproj")

failures: list[str] = []
input_errors: list[str] = []


def load_plist(path: str, label: str) -> dict:
    if not os.path.isfile(path):
        input_errors.append(f"{label} 不存在：{path}")
        return {}
    try:
        with open(path, "rb") as handle:
            return plistlib.load(handle)
    except Exception as error:  # noqa: BLE001 - 闸门要把解析失败也当成红
        input_errors.append(f"{label} 无法解析：{path}（{error}）")
        return {}


def pbxproj_values(name: str) -> list[str]:
    if not os.path.isfile(PBXPROJ):
        input_errors.append(f"project.pbxproj 不存在：{PBXPROJ}")
        return []
    with open(PBXPROJ, encoding="utf-8") as handle:
        text = handle.read()
    return [value.strip().strip('"') for value in re.findall(rf"{name} = ([^;]+);", text)]


app = load_plist(APP_PLIST, "主 App Info.plist")
widget = load_plist(WIDGET_PLIST, "Widget Info.plist")

app_build = str(app.get("CFBundleVersion", "")).strip()
app_short = str(app.get("CFBundleShortVersionString", "")).strip()
widget_build = str(widget.get("CFBundleVersion", "")).strip()
widget_short = str(widget.get("CFBundleShortVersionString", "")).strip()

if not app_build or not app_short:
    input_errors.append("主 App Info.plist 缺 CFBundleVersion 或 CFBundleShortVersionString")
for value, key in ((app_build, "CFBundleVersion"), (app_short, "CFBundleShortVersionString")):
    if "$(" in value:
        input_errors.append(
            f"主 App Info.plist 的 {key} 是变量 {value}，但主 App target 没有对应 build setting "
            "（会展开成空 → 装不上的包）。要么写死字符串，要么先给 target 补设置。"
        )
if widget_build != "$(CURRENT_PROJECT_VERSION)":
    failures.append(
        f"Widget Info.plist 的 CFBundleVersion 应为 $(CURRENT_PROJECT_VERSION)，实际 {widget_build!r}"
        "（写死会让改 pbxproj 无效）"
    )
if widget_short != "$(MARKETING_VERSION)":
    failures.append(
        f"Widget Info.plist 的 CFBundleShortVersionString 应为 $(MARKETING_VERSION)，实际 {widget_short!r}"
    )

build_values = pbxproj_values("CURRENT_PROJECT_VERSION")
marketing_values = pbxproj_values("MARKETING_VERSION")
if len(build_values) != 2:
    input_errors.append(f"project.pbxproj 里 CURRENT_PROJECT_VERSION 应有 2 处（Debug/Release），实际 {len(build_values)} 处")
if len(marketing_values) != 2:
    input_errors.append(f"project.pbxproj 里 MARKETING_VERSION 应有 2 处（Debug/Release），实际 {len(marketing_values)} 处")
if build_values and len(set(build_values)) != 1:
    failures.append(f"project.pbxproj 的 CURRENT_PROJECT_VERSION 两处不一致：{build_values}")
if marketing_values and len(set(marketing_values)) != 1:
    failures.append(f"project.pbxproj 的 MARKETING_VERSION 两处不一致：{marketing_values}")

pbx_build = build_values[0] if build_values else ""
pbx_short = marketing_values[0] if marketing_values else ""

if app_build and pbx_build and app_build != pbx_build:
    failures.append(
        f"build 号不一致：主 App Info.plist={app_build}，Widget(pbxproj)={pbx_build} —— "
        "这就是 0548 那种「只改了 widget 两处」的错"
    )
if app_short and pbx_short and app_short != pbx_short:
    failures.append(f"short version 不一致：主 App={app_short}，Widget(pbxproj)={pbx_short}")

# ---- 4. 必须比本地已出过的包新（只比同 short version 的包；版本号跨档时 build 可以回小） ----
import glob  # noqa: E402 - 只在需要时用
import zipfile  # noqa: E402

released: list[tuple[str, str, str]] = []  # (name, short, build)
for ipa in sorted(glob.glob(os.path.join(ROOT, "build-output", "*.ipa"))):
    try:
        with zipfile.ZipFile(ipa) as handle:
            entry = next(
                (name for name in handle.namelist()
                 if name.startswith("Payload/") and name.endswith(".app/Info.plist")),
                None,
            )
            if entry is None:
                continue
            info = plistlib.loads(handle.read(entry))
        released.append((os.path.basename(ipa),
                         str(info.get("CFBundleShortVersionString", "")),
                         str(info.get("CFBundleVersion", ""))))
    except Exception as error:  # noqa: BLE001 - 坏包不该让版本闸门自己崩
        print(f"   ⚠️  跳过无法解析的包 {os.path.basename(ipa)}：{error}")

same_version = [(name, build) for name, short, build in released if short and short == app_short]
if same_version and app_build.isdigit():
    newest_name, newest_build = max(same_version, key=lambda item: int(item[1]) if item[1].isdigit() else -1)
    if newest_build.isdigit() and int(app_build) <= int(newest_build):
        failures.append(
            f"build 号没有递增：当前 {app_short} ({app_build})，本地已有 {newest_name} 是 "
            f"{app_short} ({newest_build}) —— 装完在设置里分不出新旧"
        )

if input_errors:
    print("❌ 版本一致性闸门：输入不完整")
    for item in input_errors:
        print(f"   - {item}")
    sys.exit(2)
if failures:
    print("❌ 版本一致性闸门：不一致")
    for item in failures:
        print(f"   - {item}")
    sys.exit(1)

released_note = ""
if same_version:
    newest_build = max((build for _, build in same_version), key=lambda value: int(value) if value.isdigit() else -1)
    released_note = f" | 本地已出包最大 build={newest_build}（当前 {app_build} 更新）"
elif not released:
    released_note = ' | 本地无 build-output 包可对比（跳过「必须更新」检查）'
print(
    "✅ 版本一致："
    f"主 App {app_short} ({app_build}) ↔ Widget $(MARKETING_VERSION) ({pbx_short}) / "
    f"$(CURRENT_PROJECT_VERSION) ({pbx_build}){released_note}"
)
