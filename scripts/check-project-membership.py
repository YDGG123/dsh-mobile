#!/usr/bin/env python3
"""工程收录闸门：核对磁盘上的 .swift 是否被 pbxproj **正确地**挂进对应 target。

背景（2026-10-25 教训）：local-typecheck.sh 用 find 收文件，绕过工程；新文件忘了
登记 pbxproj 时它照样绿，xcodebuild/云端构建直接报 cannot find。本脚本抓这种"假绿"。

三类检查（QA 复核建议后的第二版：从"挂在某个 phase"升级为"挂在正确 target"）：
  1. 磁盘有、工程 Sources 没有        → 下次构建必红
  2. 工程 Sources 有、磁盘上没有      → 引用悬空
  3. 挂进了**错误的 target**（如测试文件挂到 App）→ 编译报错或测试跑不到

额外口径：
  - Live Activity 的两个共享文件合法地同时进 App + Widget（白名单）。
  - **运行时必须工作树静止**：并发编辑期间 pbxproj 正在变，结果会不稳定；
    验证提交版请用 `git archive` 快照，不要在工作树变动时跑。

用法：python3 scripts/check-project-membership.py
退出码：0 一致；1 不一致（列出差异）。
"""
import os
import re
import sys

PBX = "DeepSeekHarnessMobile.xcodeproj/project.pbxproj"
EXPECTED_TARGET = {
    "DeepSeekHarnessMobile": "DeepSeekHarnessMobile",
    "AgentLiveActivityWidget": "AgentLiveActivityWidget",
    "DeepSeekHarnessMobileTests": "DeepSeekHarnessMobileTests",
    "DeepSeekHarnessMobileUITests": "DeepSeekHarnessMobileUITests",
}
# App + Widget 合法共享的 Live Activity 文件
SHARED_WITH_WIDGET = {
    "AgentActivityAttributes.swift",
    "AgentApprovalIntents.swift",
}

pb = open(PBX, encoding="utf-8").read()

# pbxproj 的顶层对象都是 `\t\t<24位ID> /* 注释 */ = {`，按"下一个对象头"切块最稳
# （单行对象如 PBXFileReference 也在同一行带 isa，不能用行尾括号判断）。
header_re = re.compile(r'^[ \t]{2,}([0-9A-Fa-f]{24}) /\* [^*]* \*/ = \{\n', re.M)
headers = [(m.start(), m.end(), m.group(1)) for m in header_re.finditer(pb)]
objects = {}
for index, (_, body_start, oid) in enumerate(headers):
    body_end = headers[index + 1][0] if index + 1 < len(headers) else len(pb)
    objects[oid] = pb[body_start:body_end]

# 每个单行对象（PBXFileReference/PBXBuildFile）另用行内切片补一次
line_re = re.compile(r'^[ \t]{2,}([0-9A-Fa-f]{24}) /\* [^*]* \*/ = \{isa = ([A-Za-z]+);(.*)$', re.M)
for m in line_re.finditer(pb):
    objects.setdefault(m.group(1), m.group(0))

# fileRef id → 文件名
ref_name = {}
for oid, body in objects.items():
    if 'PBXFileReference' not in body:
        continue
    path = re.search(r'path = ([^;]+);', body)
    if path:
        ref_name[oid] = path.group(1).strip().strip('"')

# BuildFile id → 文件名
buildfile_to_name = {}
for oid, body in objects.items():
    if 'PBXBuildFile' not in body:
        continue
    ref = re.search(r'fileRef = ([0-9A-Fa-f]{24})', body)
    if ref and ref.group(1) in ref_name:
        buildfile_to_name[oid] = ref_name[ref.group(1)]

# Sources phase id → 挂着的 BuildFile id
phase_files = {}
for oid, body in objects.items():
    if 'PBXSourcesBuildPhase' not in body:
        continue
    files = re.search(r'files = \((.*?)\);', body, re.S)
    phase_files[oid] = re.findall(r'([0-9A-Fa-f]{24})', files.group(1)) if files else []

# target 名 → Sources phase id 集合
target_sources = {}
for oid, body in objects.items():
    if 'PBXNativeTarget' not in body:
        continue
    name = re.search(r'name = ([^;]+);', body)
    phases = re.search(r'buildPhases = \((.*?)\);', body, re.S)
    if not name:
        continue
    ids = set(re.findall(r'([0-9A-Fa-f]{24})', phases.group(1))) if phases else set()
    target_sources[name.group(1).strip().strip('"')] = {pid for pid in ids if pid in phase_files}

# 文件名 → 实际被挂进的 target 集合
file_targets = {}
for target, phases in target_sources.items():
    for pid in phases:
        for bid in phase_files.get(pid, []):
            name = buildfile_to_name.get(bid)
            if name:
                file_targets.setdefault(name, set()).add(target)

disk = {}
for d in EXPECTED_TARGET:
    if not os.path.isdir(d):
        continue
    for root, _, files in os.walk(d):
        for f in files:
            if f.endswith(".swift"):
                disk[f] = d

missing = sorted(n for n in disk if n not in file_targets)
ghost = sorted(n for n in file_targets if n not in disk)
wrong = []
for name, directory in sorted(disk.items()):
    actual = file_targets.get(name, set())
    if not actual:
        continue
    allowed = {EXPECTED_TARGET[directory]}
    if name in SHARED_WITH_WIDGET:
        allowed |= {"AgentLiveActivityWidget"}
    if not actual <= allowed:
        wrong.append((name, directory, sorted(actual)))

if missing:
    print("❌ 磁盘有、工程 Sources 未收录（下次 xcodebuild/云端构建必红）:")
    for n in missing:
        print("   ", f"{n}（{disk[n]}/）")
if ghost:
    print("❌ 工程收录了、磁盘上不存在（引用悬空）:")
    for n in ghost:
        print("   ", n)
if wrong:
    print("❌ 挂进了错误的 target（编译报错或测试跑不到）:")
    for n, directory, actual in wrong:
        print(f"    {n}：期望 {EXPECTED_TARGET[directory]}，实际 {', '.join(actual)}")

if missing or ghost or wrong:
    print(f"\n❌ 工程收录不一致：磁盘 {len(disk)} / 工程 {len(file_targets)}")
    sys.exit(1)

print(f"✅ 工程收录一致：磁盘 {len(disk)} 个 .swift，全部挂在正确的 target"
      f"（App/Tests/UITests/Widget；Live Activity 双 target 白名单 {len(SHARED_WITH_WIDGET)} 个）")
