#!/usr/bin/env python3
"""工程收录闸门：核对磁盘上的 .swift 是否被 pbxproj **正确地**挂进对应 target。

背景（2026-10-25 教训）：local-typecheck.sh 用 find 收文件，绕过工程；新文件忘了
登记 pbxproj 时它照样绿，xcodebuild/云端构建直接报 cannot find。本脚本抓这种"假绿"。

四类检查（QA 复核建议后的第二版：从"挂在某个 phase"升级为"挂在正确 target"；
2026-11-07 补第四类）：
  1. 磁盘有、工程 Sources 没有        → 下次构建必红
  2. 工程 Sources 有、磁盘上没有      → 引用悬空（按文件名）
  3. 挂进了**错误的 target**（如测试文件挂到 App）→ 编译报错或测试跑不到
  4. pbxproj 组挂错（文件在 A 目录、fileRef 挂 B 组）→ 1~3 全是绿的
     （收录对表用 basename，同名文件挪目录完全隐身），archive 按 pbxproj 真值
     解析路径才红（88 批两次 EXIT=65 的根因）。本类按祖先组 path 逐层解析每个
     .swift 引用的完整路径，磁盘不存在即判红。

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

# ---- 第四类检查（2026-11-07 盲区补丁）：按 pbxproj 逐层解析 .swift 的真实路径 ----
# 组对象（PBXGroup / PBXVariantGroup）：oid → children 与自身 path
group_children = {}
group_path = {}
for oid, body in objects.items():
    # 2026-11-07 教训：切块按「下一个 24 位 ID 头」切，连续单行对象会被吞进前一个
    # 多行对象 body（实测 A93F1F09=TsnetgoFramework target 吞进了两个测试 fileRef
    # 文本）——类型必须认对象自己的第一个 isa，不能对 body 子串包含判断。
    isa_m = re.search(r'isa = ([A-Za-z]+);', body)
    if not isa_m or isa_m.group(1) not in ("PBXGroup", "PBXVariantGroup"):
        continue
    ch = re.search(r'children = \((.*?)\);', body, re.S)
    group_children[oid] = re.findall(r'([0-9A-Fa-f]{24})', ch.group(1)) if ch else []
    p = re.search(r'\bpath = "([^"]+)"|\bpath = ([^;{}]+);', body)
    group_path[oid] = (p.group(1) or p.group(2)).strip() if p else None

# child id → 父组 oid 列表（fileRef/子组挂在哪个组下）
parents = {}
for goid, children in group_children.items():
    for c in children:
        parents.setdefault(c, []).append(goid)

def resolved_paths(oid, base):
    """fileRef 从直接父组向上爬到根，逐层拼 path，返回全部候选相对路径。"""
    out = []
    stack = [([base], p) for p in parents.get(oid, [])]
    seen = set()
    while stack:
        parts, node = stack.pop()
        if node in seen:
            continue
        seen.add(node)
        if node not in group_children:
            continue
        p = group_path.get(node)
        if p:
            parts = [p] + parts
        pars = parents.get(node, [])
        if not pars:
            out.append("/".join(parts))  # 到根组（mainGroup 自身通常无 path）
        else:
            for par in pars:
                stack.append((parts, par))
    return out

dangling = []   # (文件名, 解析出的路径列表)
ref_total = 0
for oid, body in objects.items():
    isa_m = re.search(r'isa = ([A-Za-z]+);', body)
    if not isa_m or isa_m.group(1) != "PBXFileReference" or 'sourceTree = "<group>"' not in body:
        continue
    m = re.search(r'\bpath = "([^"]+)"|\bpath = ([^;{}]+);', body)
    if not m:
        continue
    base = (m.group(1) or m.group(2)).strip().strip('"')
    if not base.endswith(".swift"):
        continue
    ref_total += 1
    cands = resolved_paths(oid, base)
    if not cands or not any(os.path.exists(c) for c in cands):
        dangling.append((base, cands))

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

if dangling:
    print("❌ pbxproj 组挂错：解析路径在磁盘不存在（archive 按 pbxproj 真值编译，必红）:")
    for base, cands in dangling:
        where = " / ".join(cands) if cands else "（无组链，解析不到任何路径）"
        print(f"    {base} → {where}")

if missing or ghost or wrong or dangling:
    print(f"\n❌ 工程收录不一致：磁盘 {len(disk)} / 工程 {len(file_targets)} / 解析缺失 {len(dangling)}")
    sys.exit(1)

print(f"✅ 工程收录一致：磁盘 {len(disk)} 个 .swift，全部挂在正确的 target"
      f"（App/Tests/UITests/Widget；Live Activity 双 target 白名单 {len(SHARED_WITH_WIDGET)} 个）")
print(f"✅ pbxproj 反向解析：{ref_total} 个 .swift 引用逐层解析后全部磁盘存在（缺失 0）")
