#!/usr/bin/env python3
"""Bundle ID 前缀闸门：嵌入的 Widget 扩展，其 Bundle ID 必须以上级 App 的 Bundle ID 为前缀。

背景（47 包归档失败）：合并上游把 Widget target 的 **Release** 配置从硬编码
`com.ydgg123.orcarness.AgentLiveActivityWidget` 改成了占位变量
`$(LOCAL_BUNDLE_IDENTIFIER).AgentLiveActivityWidget`，而 `config/Debug.xcconfig` 里
`LOCAL_BUNDLE_IDENTIFIER = com.example.dshmobile`（上游占位值）。于是归档时报：

    Embedded binary's bundle identifier is not prefixed with the parent app's bundle identifier.
        Embedded Binary Bundle Identifier:  com.example.dshmobile.AgentLiveActivityWidget
        Parent App Bundle Identifier:        com.ydgg123.orcarness

这类问题**类型检查、工程收录、版本一致性三个闸门全绿也照样漏**：它是构建配置层的问题，
只有真正 `archive` + `ValidateEmbeddedBinary` 才会炸掉一次出包。

为什么必须用 xcodebuild 而不是 grep pbxproj：真正决定成败的是**变量展开后的值**。
`$(LOCAL_BUNDLE_IDENTIFIER)` 在 pbxproj 里看起来"有值"，展开后才知道是不是占位符。
所以本闸门对每个 (target, configuration) 解析**变量展开后的值**来判定。
（2026-10-28 起改用 `plutil` 把 pbxproj 转 JSON 后解析 + `config/*.xcconfig` 变量链展开
—— xcodebuild 因系统层损坏对任何输入 SIGBUS，见 Docs/history-multi-segment-notes.md；
本机 xcodebuild 恢复后两种方式结果一致，解析版保留为不依赖工具链的备份路径。）

校验项（App / Widget × Debug / Release）：
1. 主 App 的 `PRODUCT_BUNDLE_IDENTIFIER` 非空，且**不是**占位符 (com.example.*)
   —— Release 尤其不能是占位符（那会把产品包打成别人的 ID）
2. Widget 的 `PRODUCT_BUNDLE_IDENTIFIER` 非空
3. Widget ID 必须以 `主AppID + "."` 为前缀（ValidateEmbeddedBinary 的硬性要求）
4. Widget ID 不得含未展开的 `$(` 变量

退出码：0 = 通过；1 = 前缀/占位违规；2 = 输入缺失或解析失败（工程打不开/plutil 异常等）。
可用 `BUNDLE_GATE_ROOT` 指定仓库根（反向测试用它指到临时副本）。
"""

from __future__ import annotations

import os
import re
import json
import pathlib
import subprocess
import sys

ROOT = os.environ.get("BUNDLE_GATE_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROJECT = os.path.join(ROOT, "DeepSeekHarnessMobile.xcodeproj")
# 2026-10-28：xcodebuild 在本机对任何输入都 SIGBUS（EXIT=138），本闸门改为
# **解析 pbxproj + xcconfig 展开**。展开语义保留：$(LOCAL_BUNDLE_IDENTIFIER) 从
# config/*.xcconfig（含 include 链）解析；47 包正是 xcconfig 覆盖导致占位值，解析器必须覆盖它。
#
# 崩溃根因（2026-10-28 定案。别再往「系统文件被删」查——那是误判，已撤回）：
#   EXC_BAD_ACCESS/SIGBUS + KERN_MEMORY_ERROR，崩溃地址三次完全一致（0x…1c43），
#   落在 dyld 共享缓存的 AppleSystemInfo __TEXT +0x1c43。同一框架用 ctypes.CDLL
#   能正常加载（文件是好的）；-help/-showsdks 正常而 -list/无参数必崩；同日早晚
#   表现不同——即**内存压力（swap 近 2GB、pagein 2400 万）下 dyld 缓存页映射失败**。
#   系统文件本身完好：macOS 自 Big Sur 起系统库只在 dyld 缓存里，磁盘上的
#   framework 目录本就没有二进制，这是正常形态，不是被删。
#   软件层无法根治（缓存重建需 sudo+重启、收益不确定），重启可缓解内存压力。
XCODEBUILD = None

def _pbxproj_text() -> str | None:
    p = pathlib.Path(PROJECT)
    if p.is_dir():
        p = p / "project.pbxproj"
    return p.read_text(errors="ignore") if p.is_file() else None

APP_TARGET = os.environ.get("BUNDLE_GATE_APP_TARGET", "DeepSeekHarnessMobile")
WIDGET_TARGET = os.environ.get("BUNDLE_GATE_WIDGET_TARGET", "AgentLiveActivityWidget")

failures: list[str] = []
input_errors: list[str] = []

def _skip_if_requested() -> None:
    """显式跳过开关：BUNDLE_GATE_SKIP=1 才生效（不认 true/yes/空串）。
    放最前面：连 pbxproj 都不读，排障时不需要文件可读。"""
    if os.environ.get("BUNDLE_GATE_SKIP") == "1":
        print("⚠️ Bundle ID 前缀闸门被 BUNDLE_GATE_SKIP=1 跳过（未校验！构建前请确认已恢复）")
        sys.exit(0)


_skip_if_requested()

PLACEHOLDERS = ("com.example.", "com.example")
IDENTIFIER_KEY = "PRODUCT_BUNDLE_IDENTIFIER"


def _pbxjson() -> dict | None:
    """plutil 把 pbxproj 转成 JSON（系统解析器，无括号/注释/嵌套坑）。失败 → None（fail-closed）。"""
    p = pathlib.Path(PROJECT)
    if not p.is_dir():
        input_errors.append(f"工程不存在：{PROJECT}")
        return None
    out = pathlib.Path("/tmp/verify-bundle-prefix.pbx.json")
    try:
        pbxfile = p / "project.pbxproj" if p.is_dir() else p   # .xcodeproj 是目录，plutil 只吃里面的 pbxproj 文件
        subprocess.run(["plutil", "-convert", "json", "-o", str(out), str(pbxfile)],
                       check=True, capture_output=True, timeout=60)
        return json.loads(out.read_text())
    except FileNotFoundError:
        input_errors.append("plutil 不可用（macOS 自带，理论上不会发生）")
        return None
    except subprocess.CalledProcessError as e:
        input_errors.append(f"plutil 解析失败：{e.stderr.decode(errors='ignore')[-120:]}")
        return None
    except Exception as e:
        input_errors.append(f"plutil/JSON 解析异常：{e}")
        return None


def _read_pbxproj_assignments(target: str) -> dict[str, str]:
    """取某 target 的 PRODUCT_BUNDLE_IDENTIFIER（Release/Debug，经 xcconfig 变量展开）。"""
    pbx = _pbxjson()
    if pbx is None:
        return {}
    objs = pbx.get("objects", {})
    out: dict[str, str] = {}
    tid = next((k for k, o in objs.items()
                if o.get("isa") == "PBXNativeTarget" and o.get("name") == target), None)
    if not tid:
        input_errors.append(f"找不到 target：{target}")
        return out
    cl = objs.get(objs[tid].get("buildConfigurationList", ""), {})
    for cid in cl.get("buildConfigurations", []):
        cfg = objs.get(cid, {})
        name = cfg.get("name")
        raw = cfg.get("buildSettings", {}).get(IDENTIFIER_KEY)
        if name and raw is not None:
            out[(name, IDENTIFIER_KEY)] = raw
    return out


def _xcconfig_value(key: str, path: pathlib.Path, seen: set[str] | None = None) -> str | None:
    """沿 #include 链解析 xcconfig 键（后写覆盖先写，与 Xcode 语义一致）。"""
    seen = seen or set()
    rp = path.resolve()
    if rp in seen or not path.is_file():
        return None
    seen.add(rp)
    value = None
    for line in path.read_text(errors="ignore").splitlines():
        inc = re.match(r"#include\s+\"?([^\"\n]+)\"?", line)
        if inc:
            sub = (path.parent / inc.group(1)).resolve()
            value = _xcconfig_value(key, sub, seen) or value
            continue
        kv = re.match(rf"\s*{re.escape(key)}\s*=\s*(.+?)\s*$", line)
        if kv:
            value = kv.group(1)
    return value


def _resolve_variable(value: str, configuration: str) -> str:
    """展开 $(VAR)：从 config/*.xcconfig 找值（47 包的坑就在这个变量的覆盖链）。"""
    def sub(m):
        key = m.group(1)
        for xc in sorted(pathlib.Path(ROOT, "config").glob("*.xcconfig")):
            v = _xcconfig_value(key, xc)
            if v is not None:
                return v
        return m.group(0)
    return re.sub(r"\$\((\w+)\)", sub, value)


def resolve(target: str, configuration: str) -> str | None:
    """取展开后的 PRODUCT_BUNDLE_IDENTIFIER；`$(VAR)` 走 xcconfig 链（保留 47 包拦截力）。"""
    assignments = _read_pbxproj_assignments(target)
    raw = assignments.get((configuration, IDENTIFIER_KEY))
    if raw is None:
        # configuration/target 名字写错（如 BUNDLE_GATE_* 反向测试传错值）→ exit 2：
        # "没读到该看的东西"，排查方向是调用方传参，不是 bundle id 本身
        input_errors.append(f"{target}/{configuration} 的 build settings 里没有 {IDENTIFIER_KEY}")
        return None
    if "$(" in raw:
        expanded = _resolve_variable(raw, configuration)
        if "$(" in expanded:
            # 未知变量 = **读到了但值不合法**（pbxproj 里确实写了 $(NO_SUCH_VARIABLE)）
            # → exit 1（指向"pbxproj 里的 bundle id 写错了"），
            #    不是 exit 2（那会让人去查文件路径/target 名，方向错）。
            failures.append(f"[{configuration}] {target} 的 Bundle ID 引用了未定义变量：{raw}"
                             "（xcconfig 里找不到，展开后仍是 $(…)）")
            return None
        return expanded
    return raw


resolved: dict[tuple[str, str], str] = {}
# BUNDLE_GATE_CONFIGURATION：反向测试用，可指定单个配置名（写错的名字 → exit 2）
CONFIGURATIONS = (os.environ.get("BUNDLE_GATE_CONFIGURATION"),) \
    if os.environ.get("BUNDLE_GATE_CONFIGURATION") else ("Release", "Debug")
for target in (APP_TARGET, WIDGET_TARGET):
    for configuration in CONFIGURATIONS:
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
