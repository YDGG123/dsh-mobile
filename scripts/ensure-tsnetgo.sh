#!/bin/bash
# 预置 Tsnetgo.xcframework —— 绕开本机 xcodebuild 坏页固疾的出包关键一步。
#
# 背景（2026-11-06 合流）：
#   pbxproj 里 "Build Tsnetgo XCFramework" 阶段原本 alwaysOutOfDate=1，每次 archive 都跑
#   gradlew buildTsnetXcframework → gomobile bind → 最后一步 xcodebuild -create-xcframework。
#   本机 xcodebuild 有 dyld 坏页固疾：-version 正常，但 -find / -create-xcframework 会 Bus error。
#   /tmp/xcrun-shim 只能救 -find，救不了 xcodebuild 子命令本体 ⇒ 出包被这一阶段卡死。
#
# 解法（已实装到 pbxproj）：
#   该阶段改成「产物存在即跳过」，本脚本负责把一份**已验证可用**的 xcframework 放到
#   tsnetgo/build/tsnetgo/ 下。产物是好窗口期真构建出来的（2026-11-05 23:54，153M，双切片）。
#
# 用法：
#   ./scripts/ensure-tsnetgo.sh                    # 从备份位置复制
#   ./scripts/ensure-tsnetgo.sh /path/to/xxx.xcframework   # 从指定位置复制
#
# 何时需要重新生成：tsnetgo/*.go 或 go.mod/go.sum 变了（Go 源码改了才需要，纯 app 改动不需要）。
#   重新生成必须在「好窗口」跑（见 Docs/release-feasibility-20261106.md §2），坏窗口期只能走云端。

set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

DEST="tsnetgo/build/tsnetgo/Tsnetgo.xcframework"
SRC="${1:-/tmp/Tsnetgo.xcframework}"

if [ -d "$DEST" ]; then
  echo "✅ 已存在：$DEST（$(du -sh "$DEST" | cut -f1)）"
  echo "   如需强制重装，先 rm -rf \"$DEST\""
  exit 0
fi

if [ ! -d "$SRC" ]; then
  echo "❌ 找不到源 xcframework：$SRC" >&2
  echo "" >&2
  echo "   可选做法：" >&2
  echo "   1) 指定位置：./scripts/ensure-tsnetgo.sh /path/to/Tsnetgo.xcframework" >&2
  echo "   2) 好窗口期真构建（需 Go + gomobile + DEVELOPER_DIR）：" >&2
  echo "        export DEVELOPER_DIR=/tmp/FakeXcode.app/Contents/Developer" >&2
  echo "        export GOPATH=/Volumes/应用/dsh-workspace/tools/gopath" >&2
  echo "        export PATH=\"/Volumes/应用/dsh-workspace/tools/go/bin:\$PATH\"" >&2
  echo "        ./gradlew buildTsnetXcframework" >&2
  echo "   3) 走云端 GitHub Actions（坏窗口期唯一稳定路）" >&2
  echo "" >&2
  echo "   详见 Docs/release-feasibility-20261106.md §2" >&2
  exit 1
fi

# 结构校验：必须是合法的 xcframework（Info.plist + 至少一个切片），别把半成品当成品。
if [ ! -f "$SRC/Info.plist" ]; then
  echo "❌ $SRC 不是合法 xcframework（缺 Info.plist）" >&2
  exit 1
fi
LIBS=$(python3 - "$SRC" <<'PY'
import plistlib, sys
with open(sys.argv[1] + "/Info.plist", "rb") as f:
    p = plistlib.load(f)
libs = p.get("AvailableLibraries", [])
print(len(libs))
PY
)
if [ "$LIBS" -lt 1 ]; then
  echo "❌ $SRC 的 AvailableLibraries 为空" >&2
  exit 1
fi

mkdir -p tsnetgo/build/tsnetgo
cp -R "$SRC" "$DEST"

echo "✅ 已预置：$DEST"
echo "   体积   $(du -sh "$DEST" | cut -f1)"
echo "   切片数 $LIBS（Info.plist AvailableLibraries）"
echo ""
echo "   现在 Xcode 的 Build Tsnetgo XCFramework 阶段会直接跳过，不再调 gomobile。"
