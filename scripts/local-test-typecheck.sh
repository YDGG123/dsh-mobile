#!/bin/bash
# 测试目标类型检查（第五路闸门）—— 让「用例能编」变成硬闸门。
#
# 为什么需要它（2026-10-26 教训）：
# `scripts/local-typecheck.sh` 只类型检查 App / Widget 四条路，**从不编译
# `DeepSeekHarnessMobileTests/`**。于是 `FileIconAssetTests.swift` 里
# `controller.safeAreaRegions = []`（iOS 16.4+ API、无 `#available` 保护）在 16.0
# 部署目标下编不过，却在本地一直全绿——因为没人编测试目标。
# 这一路补上：App 模块以 `-enable-testing` 编出模块，再对全部测试源码做 typecheck。
#
# 用法：
#   ./scripts/local-test-typecheck.sh              # 单跑（绿 exit 0，红 exit 1）
#   ./scripts/local-test-typecheck.sh --self-test   # 反向自证：造三个错，必须都变红
#   DSH_TEST_SOURCES=<dir> ...                     # 换一份测试源码（自证用）
#
# 依赖：先跑过一次 `./scripts/local-typecheck.sh`（它准备 MarkdownUI 桩与 KMP 框架）。

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

D="/Applications/Xcode.app/Contents/Developer"
TC="$D/Toolchains/XcodeDefault.xctoolchain/usr/bin"
SDK=$(ls -d "$D"/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator*.sdk 2>/dev/null | tail -1)
# XCTest 的 Swift overlay 不在 framework 里，在平台 Developer/usr/lib（踩过：只加 -F 会
# 退化成 C 模块，报 “macro 'XCTAssertTrue' unavailable: function like macros not supported”）。
XCT_OVERLAY="$D/Platforms/iPhoneSimulator.platform/Developer/usr/lib"
XCT_FW="$D/Platforms/iPhoneSimulator.platform/Developer/Library/Frameworks"
FW="shared/build/bin/iosX64/debugFramework"
DEPS="${TYPECHECK_DEPS:-/tmp/dsh-typecheck-deps}"
TEST_DEPS="${TEST_TYPECHECK_DEPS:-/tmp/dsh-test-typecheck-deps}"
MOD="$TEST_DEPS/module"
CACHE="$DEPS/module-cache"
SOURCES="${DSH_TEST_SOURCES:-DeepSeekHarnessMobileTests}"
# 测试目标的部署目标：**17.0**（App 仍是 16.0）。
# 原因（2026-10-26 实测）：测试源码大量直接用 16.1/16.2/17.0 才有的 API
# （Live Activity 的 16.2 类、traitOverrides/UITraitUserInterfaceStyle 的 17.0），
# 在 16.0 下整份 GatewayProtocolTests 有 54 个编译错误——也就是说这个测试目标
# **在 16.0 部署目标下从来编不过**。测试不随包发布、只在 17+ 模拟器上跑，
# 所以把测试目标提到 17.0 是实事求是的修法；App 的 16.0 红线不受影响。
TEST_TARGET_OS="17.0"

fail() { echo "❌ $1"; exit 1; }

[ -x "$TC/swiftc" ] || fail "找不到 swiftc，请确认已安装 Xcode"
[ -n "$SDK" ] && [ -d "$SDK" ] || fail "找不到 iOS Simulator SDK"
[ -d "$FW" ] || fail "缺少 KMP 框架，先运行: ./gradlew :shared:linkDebugFrameworkIosX64"
[ -d "$SOURCES" ] || fail "找不到测试源码目录: $SOURCES"
[ -f "$DEPS/MarkdownUI.swift" ] || fail "缺少 MarkdownUI 桩源码（/tmp 被清理过）。自救：CHATLAYOUT_SRC 指向 DerivedData 的 ChatLayout checkout 后重跑 ./scripts/local-typecheck.sh；ChatLayout.swiftmodule 若也缺，用脚本同款 swiftc -emit-module 命令手动重建（本次踩过：脚本自身的 -emit-library 调用在这台机器上会静默失败，手动执行即可）"

mkdir -p "$TEST_DEPS" "$MOD" "$CACHE"

# ---- MarkdownUI 桩的「可测试版」 ----
# App 模块带 -enable-testing 编出后，测试侧 import 会连带要求它依赖的模块也可测，
# 否则报 “module 'MarkdownUI' was not compiled for testing”。这里单独编一份，-I 排前面。
if [ ! -f "$TEST_DEPS/MarkdownUI.swiftmodule" ] || [ "$DEPS/MarkdownUI.swift" -nt "$TEST_DEPS/MarkdownUI.swiftmodule" ]; then
  "$TC/swiftc" -emit-module -enable-testing -D DEBUG \
    -sdk "$SDK" -target "x86_64-apple-ios${TEST_TARGET_OS}-simulator" -swift-version 5 \
    -module-name MarkdownUI -emit-module-path "$TEST_DEPS/MarkdownUI.swiftmodule" \
    "$DEPS/MarkdownUI.swift" >/dev/null 2>&1 || fail "生成可测试版 MarkdownUI 桩失败"
fi

# ---- App 模块（-enable-testing -D DEBUG）----
# 为什么要 -D DEBUG：App 里的测试专用入口（`awaitConversationProjectionForTesting` 等）
# 都在 `#if DEBUG` 内，与 Xcode 跑 XCTest 的 Debug 配置一致。不加会报一堆
# “value of type 'AppStore' has no member ...ForTesting”。
# 缓存：App 源码比 .swiftmodule 新才重建（一次约 1 分钟）。
build_app_module() {
  # MarkdownUI 可测试版桩缺失时先重建（/tmp 清理会把它和 ChatLayout 一起带走的）
  if [ ! -f "$TEST_DEPS/MarkdownUI.swiftmodule" ] || [ "$DEPS/MarkdownUI.swift" -nt "$TEST_DEPS/MarkdownUI.swiftmodule" ]; then
    "$TC/swiftc" -emit-module -enable-testing -D DEBUG \
      -sdk "$SDK" -target x86_64-apple-ios16.0-simulator -swift-version 5 \
      -module-name MarkdownUI -emit-module-path "$TEST_DEPS/MarkdownUI.swiftmodule" \
      "$DEPS/MarkdownUI.swift" >/dev/null 2>&1 || fail "生成可测试版 MarkdownUI 桩失败"
  fi
  echo "→ 编 App 测试模块（-enable-testing -D DEBUG）..."
  "$TC/swiftc" -emit-module -enable-testing -D DEBUG \
    -sdk "$SDK" -target "x86_64-apple-ios${TEST_TARGET_OS}-simulator" -swift-version 5 \
    -F "$FW" -F "$DEPS" -I "$TEST_DEPS" -I "$DEPS" -module-cache-path "$CACHE" \
    -module-name DeepSeekHarnessMobile \
    -emit-module-path "$MOD/DeepSeekHarnessMobile.swiftmodule" \
    $(find DeepSeekHarnessMobile -name "*.swift") > "$TEST_DEPS/appmod.log" 2>&1
  local status=$?
  if [ $status -ne 0 ]; then
    echo "❌ App 测试模块编译失败（App 源码本身编不过，先看四路 typecheck）"
    grep "error:" "$TEST_DEPS/appmod.log" | head -20
    return 1
  fi
  return 0
}

# 缓存判据用**内容指纹**（路径+mtime+大小），别只比 mtime：
# 2026-10-27 踩过——源码与模块在同一秒内变动时 `find -newer` 判不出来，
# 结果拿着旧模块去编测试，报出"AppStore has no member ..."这种假红。
STAMP="$MOD/.sources-stamp"
FINGERPRINT=$(find DeepSeekHarnessMobile -name '*.swift' -exec stat -f '%m %z %N' {} + | sort | shasum -a 256 | cut -d' ' -f1)
if [ ! -f "$MOD/DeepSeekHarnessMobile.swiftmodule" ] || [ "$(cat "$STAMP" 2>/dev/null)" != "$FINGERPRINT" ]; then
  build_app_module || exit 1
  printf '%s' "$FINGERPRINT" > "$STAMP"
fi

# ---- 测试源码 typecheck ----
run_check() {
  local src="${1:-$SOURCES}"
  local log="$TEST_DEPS/tests.log"
  "$TC/swiftc" -typecheck -D DEBUG \
    -sdk "$SDK" -target "x86_64-apple-ios${TEST_TARGET_OS}-simulator" -swift-version 5 \
    -F "$FW" -F "$DEPS" -F "$XCT_FW" \
    -I "$TEST_DEPS" -I "$DEPS" -I "$MOD" -I "$XCT_OVERLAY" \
    -module-cache-path "$CACHE" \
    $(find "$src" -name "*.swift") > "$log" 2>&1
  local status=$?
  local errs
  errs=$(grep -c "error:" "$log" 2>/dev/null | head -1)
  errs=${errs:-0}
  if [ "$status" -eq 0 ] && [ "$errs" -eq 0 ]; then
    return 0
  fi
  echo "❌ 测试目标类型检查失败：$errs 个错误（$src）"
  grep "error:" "$log" | head -20
  return 1
}

if [ "${1:-}" = "--self-test" ]; then
  echo "=== 反向自证：造三个错，每个都必须变红 ==="
  BASE="$TEST_DEPS/selftest"
  rm -rf "$BASE"
  mkdir -p "$BASE/case1" "$BASE/case2" "$BASE/case3"

  # 基线：真实测试必须绿（自证本身的前置条件）
  echo "→ [基线] 真实测试源码"
  if ! run_check; then
    echo "❌ 基线不是绿的，先修真实错误再谈反向自证"
    exit 1
  fi
  echo "  ✅ 基线 exit 0"

  # 反例 1：普通类型错误（最基础的一类）
  cat > "$BASE/case1/Probe.swift" <<'SWIFT'
import XCTest
final class DSHGateProbeTests: XCTestCase {
    func testProbe() {
        let bad: Int = "not an int"
        XCTAssertEqual(bad, 0)
    }
}
SWIFT
  # 反例 2：import 不存在的模块（抓依赖/模块路径配错）
  cat > "$BASE/case2/Probe.swift" <<'SWIFT'
import XCTest
import DefinitelyNotARealModule
final class DSHGateProbeTests: XCTestCase {
    func testProbe() { XCTAssertTrue(true) }
}
SWIFT
  # 反例 3：**本次事故的同型复现**——比测试目标更新的 API 没加 #available 保护。
  # 原事故是 16.4 的 `UIHostingController.safeAreaRegions`（当时测试目标还是 16.0）；
  # 测试目标提到 17.0 后，改用 18.0 的 `Color.mix` 仍能证明闸门抓得住这类错。
  cat > "$BASE/case3/Probe.swift" <<'SWIFT'
import XCTest
import SwiftUI
final class DSHGateProbeTests: XCTestCase {
    func testProbe() {
        let blended = Color.red.mix(with: .blue, by: 0.5)
        XCTAssertNotNil(blended)
    }
}
SWIFT

  rc=0
  for n in 1 2 3; do
    echo "→ [反例 $n] $BASE/case$n"
    if run_check "$BASE/case$n"; then
      echo "  ❌ 反例 $n 竟然绿了——闸门没抓到，退出码 $?"
      rc=1
    else
      echo "  ✅ 反例 $n 按预期变红（exit 1）"
    fi
  done

  if [ "$rc" -eq 0 ]; then
    echo
    echo "✅ 反向自证通过：基线绿 + 三个反例全红"
  else
    echo
    echo "❌ 反向自证失败：有反例没变红，闸门不可信"
  fi
  exit "$rc"
fi

echo "→ 类型检查测试目标（iOS ${TEST_TARGET_OS}, -D DEBUG）..."
if run_check; then
  echo "✅ 测试目标类型检查通过（$(find "$SOURCES" -name '*.swift' | wc -l | tr -d ' ') 个文件 0 错误）"
  exit 0
fi
exit 1
