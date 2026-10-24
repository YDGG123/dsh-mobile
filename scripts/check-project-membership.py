#!/usr/bin/env python3
"""工程收录闸门：核对磁盘上的 .swift 与 pbxproj 的 Sources 收录是否一致。

背景（2026-10-25 教训）：local-typecheck.sh 用 find 收文件，绕过工程；新文件忘了
登记 pbxproj 时它照样绿，xcodebuild/云端构建直接报 cannot find。本脚本抓这种"假绿"。

用法：python3 scripts/check-project-membership.py
退出码：0 一致；1 不一致（列出差异）。
"""
import os, re, sys

PBX = "DeepSeekHarnessMobile.xcodeproj/project.pbxproj"
DIRS = ["DeepSeekHarnessMobile", "AgentLiveActivityWidget",
        "DeepSeekHarnessMobileTests", "DeepSeekHarnessMobileUITests"]

pb = open(PBX, encoding="utf-8").read()
in_sources = set(re.findall(r'/\* (\S+\.swift) in Sources \*/', pb))

disk = set()
for d in DIRS:
    if not os.path.isdir(d):
        continue
    for root, _, files in os.walk(d):
        for f in files:
            if f.endswith(".swift"):
                disk.add(f)

missing = sorted(disk - in_sources)   # 会造成"local-typecheck 假绿"
ghost  = sorted(in_sources - disk)    # 会造成 xcodebuild 直接错

if missing:
    print("❌ 磁盘有、工程 Sources 未收录（下次 xcodebuild/云端构建必红）:")
    for n in missing: print("   ", n)
if ghost:
    print("❌ 工程收录了、磁盘上不存在（引用悬空）:")
    for n in ghost: print("   ", n)

if missing or ghost:
    print(f"\n❌ 工程收录不一致：磁盘 {len(disk)} / 工程 {len(in_sources)}")
    sys.exit(1)

print(f"✅ 工程收录一致：磁盘 {len(disk)} / 工程 Sources {len(in_sources)}，无缺失无悬空")
