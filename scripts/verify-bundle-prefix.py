#!/usr/bin/env python3
"""Bundle ID 前缀闸门：嵌入的 Widget 扩展，其 Bundle ID 必须以上级 App 的 Bundle ID 为前缀。

背景（47 包归档失败）：合并上游把 Widget target 的 **Release** 配置从硬编码
`com.ydgg123.orcarness.AgentLiveActivityWidget` 改成了占位变量
`$(LOCAL_BUNDLE_IDENTIFIER).AgentLiveActivityWidget`，而 `config/Debug.xcconfig` 里
`LOCAL_BUNDLE_IDENTIFIER = com.example.dshmobile`（上游占位值）。于是归档时 xcodebuild 报：

    Embedded binary's bundle identifier is not prefixed with the parent app's bundle identifier.
        Embedded Binary Bundle Identifier:  com.example.dshmobile.AgentLiveActivityWidget
        Parent App Bundle Identifier:        com.ydgg123.orcarness

这类问题**类型检查、工程收录、版本一致性三个闸门全绿也照样漏**：它是构建配置层的问题，
只有真正 `archive` + `ValidateEmbeddedBinary` 才会炸掉一次出包。

为什么必须用 xcodebuild 而不是 grep pbxproj：真正决定成败的是**变量展开后的值**。
`$(LOCAL_BUNDLE_IDENTIFIER)` 在 pbxproj 里看起来"有值"，展开后才知道是不是占位符。
所以本闸门对每个 (target, configuration) 跑一次 `-showBuildSettings`，用展开值判定。

校验项（App / Widget × Debug / Release）：
1. 主 App 的 `PRODUCT_BUNDLE_IDENTIFIER` 非空，且**不是**占位符 (com.example.*)
   —— Release 尤其不能是占位符（那会把产品包打成别人的 ID）
2. Widget 的 `PRODUCT_BUNDLE_IDENTIFIER` 非空
3. Widget ID 必须以 `主AppID + "."` 为前缀（ValidateEmbeddedBinary 的硬性要求）
4. Widget ID 不得含未展开的 `$(` 变量

退出码：0 = 通过；1 = 前缀/占位违规；2 = 输入缺失或 xcodebuild 无法解析（工程打不开等）。
可用 `BUNDLE_GATE_ROOT` 指定仓库根，`BUNDLE_GATE_XCODEBUILD` 指定 xcodebuild 路径（反向测试用）。
"""

from __future__ import annotations

import os
import re
import subprocess
import sys

ROOT = os.environ.get("BUNDLE_GATE_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROJECT = os.path.join(ROOT, "DeepSeekHarnessMobile.xcodeproj")
XCODEBUILD = os.environ.get("BUNDLE_GATE_XCODEBUILD") or "xcodebuild"

APP_TARGET = os.environ.get("BUNDLE_GATE_APP_TARGET", "DeepSeekHarnessMobile")
WIDGET_TARGET = os.environ.get("BUNDLE_GATE_WIDGET_TARGET", "AgentLiveActivityWidget")

failures: list[str] = []
input_errors: list[str] = []

PLACEHOLDERS = ("com.example.", "com.example")
IDENTIFIER_KEY = "PRODUCT_BUNDLE_IDENTIFIER"


def resolve(target: str, configuration: str) -> str | None:
    """用 xcodebuild 展开 target 的 build settings，取 PRODUCT_BUNDLE_IDENTIFIER。"""
    if not os.path.isdir(PROJECT):
        input_errors.append(f"工程不存在：{PROJECT}")
        return None
    try:
        proc = subprocess.run(
            [XCODEBUILD, "-project", PROJECT, "-target", target,
             "-configuration", configuration, "-showBuildSettings"],
            capture_output=True, text=True, timeout=180, check=False,
        )
    except FileNotFoundError:
        input_errors.append(f"找不到 xcodebuild（{XCODEBUILD}）—— 本闸门需要 macOS 工具链")
        return None
    except subprocess.TimeoutExpired:
        input_errors.append(f"xcodebuild 解析 {target}/{configuration} 超时（180s）")
        return None

    if proc.returncode != 0:
        # 只取最后几行，避免把整份 stderr 灌进闸门输出
        tail = " / ".join(line.strip() for line in proc.stderr.strip().splitlines()[-3:])
        input_errors.append(f"xcodebuild 解析 {target}/{configuration} 失败（exit {proc.returncode}）：{tail}")
        return None

    for line in proc.stdout.splitlines():
        match = re.match(rf"\s*{IDENTIFIER_KEY}\s*=\s*(.+?)\s*$", line)
        if match:
            return match.group(1).strip().strip('"')
    input_errors.append(f"{target}/{configuration} 的 build settings 里没有 {IDENTIFIER_KEY}")
    return None


resolved: dict[tuple[str, str], str] = {}
for target in (APP_TARGET, WIDGET_TARGET):
    for configuration in ("Release", "Debug"):
        value = resolve(target, configuration)
        if value is not None:
            resolved[(target, configuration)] = value

for configuration in ("Release", "Debug"):
    app_id = resolved.get((APP_TARGET, configuration))
    widget_id = resolved.get((WIDGET_TARGET, configuration))
    if app_id is None or widget_id is None:
        continue

    label = f"{configuration}"

    # 1. 变量必须真的展开；没展开说明 build setting 没定义，包里会是字面量 "$(...)"。
    for role, value in (("主 App", app_id), ("Widget", widget_id)):
        if "$(" in value:
            failures.append(f"[{label}] {role} 的 Bundle ID 未展开：{value}（宿主找不到该变量）")
        if not value.strip():
            failures.append(f"[{label}] {role} 的 Bundle ID 为空")

    # 2. 占位符。Debug 允许占位（模拟器用），Release 绝不允许 —— 出包就是别人的 ID。
    if configuration == "Release":
        if app_id.startswith(PLACEHOLDERS):
            failures.append(
                f"[{label}] 主 App 的 Bundle ID 是占位值 {app_id} —— "
                "Release 配置被上游占位变量覆盖了，打出的包不能发布"
            )
        if widget_id.startswith(PLACEHOLDERS):
            failures.append(
                f"[{label}] Widget 的 Bundle ID 是占位值 {widget_id} —— "
                "归档时 ValidateEmbeddedBinary 会拒绝（47 包就是这个）"
            )

    # 3. ValidateEmbeddedBinary 的硬性要求：嵌入二进制 ID 必须以上级 App ID 为前缀。
    if app_id and widget_id and not widget_id.startswith(app_id + "."):
        failures.append(
            f"[{label}] 嵌入 Widget 的 Bundle ID ({widget_id}) 不是以主 App "
            f"Bundle ID ({app_id}) 为前缀 —— 归档会被 ValidateEmbeddedBinary 拒绝"
        )

if input_errors:
    print("❌ Bundle ID 前缀闸门：无法解析")
    for item in input_errors:
        print(f"   - {item}")
    sys.exit(2)

if failures:
    print("❌ Bundle ID 前缀闸门：不一致")
    for item in failures:
        print(f"   - {item}")
    sys.exit(1)

detail = " | ".join(
    f"{configuration}: App={resolved[(APP_TARGET, configuration)]} "
    f"Widget={resolved[(WIDGET_TARGET, configuration)]}"
    for configuration in ("Release", "Debug")
    if (APP_TARGET, configuration) in resolved and (WIDGET_TARGET, configuration) in resolved
)
print(f"✅ Bundle ID 前缀一致（Widget 以 App ID 为前缀，Release 无占位值）：{detail}")
