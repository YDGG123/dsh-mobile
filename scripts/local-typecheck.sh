#!/bin/bash
# 本地快速类型检查 —— 不依赖 xcodebuild / actool / ibtool。
#
# 这台机器的 Xcode 资源编译工具在 AMD Hackintosh 上会 SIGBUS 崩溃,
# 但 swiftc 本身可用。本脚本用 swiftc -typecheck 对全部 Swift 源码做
# iOS 16.0 目标的类型检查,能在几秒内发现语法错误和 API 可用性问题,
# 不用每次都 push 上去等云端 CI。
#
# 用法:
#   ./scripts/local-typecheck.sh
#
# 依赖(首次运行会自动准备):
#   - KMP 共享框架: ./gradlew :shared:linkDebugFrameworkIosX64
#   - ChatLayout 与 MarkdownUI 的 swiftmodule 桩(见脚本末尾说明)

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

D="/Applications/Xcode.app/Contents/Developer"
TC="$D/Toolchains/XcodeDefault.xctoolchain/usr/bin"
SDK="$D/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator26.2.sdk"
FW="shared/build/bin/iosX64/debugFramework"
DEPS="${TYPECHECK_DEPS:-/tmp/dsh-typecheck-deps}"

fail() { echo "❌ $1"; exit 1; }

[ -x "$TC/swiftc" ] || fail "找不到 swiftc,请确认已安装 Xcode"
[ -d "$SDK" ] || fail "找不到 iOS SDK: $SDK"
[ -d "$FW" ] || fail "缺少 KMP 框架,先运行: ./gradlew :shared:linkDebugFrameworkIosX64"

mkdir -p "$DEPS"

# ---- 准备 ChatLayout 模块 ----
if [ ! -f "$DEPS/ChatLayout.swiftmodule" ]; then
  echo "→ 准备 ChatLayout 模块..."
  CL_SRC="${CHATLAYOUT_SRC:-/tmp/ChatLayout/ChatLayout}"
  if [ ! -d "$CL_SRC" ]; then
    echo "  克隆 ChatLayout 2.4.3..."
    git clone --depth 1 --branch 2.4.3 https://github.com/ekazaev/ChatLayout.git /tmp/ChatLayout >/dev/null 2>&1 \
      || fail "克隆 ChatLayout 失败(需要能访问 GitHub)"
  fi
  "$TC/swiftc" -emit-module -emit-library \
    -sdk "$SDK" -target x86_64-apple-ios16.0-simulator -swift-version 5 \
    -module-name ChatLayout \
    -emit-module-path "$DEPS/ChatLayout.swiftmodule" \
    -o "$DEPS/libChatLayout.dylib" \
    $(find "$CL_SRC" -name "*.swift") >/dev/null 2>&1 \
    || fail "编译 ChatLayout 失败"
fi

# ---- 准备 MarkdownUI 桩模块 ----
# MarkdownUI 依赖 cmark-gfm 与 NetworkImage 两个 C/SPM 包,完整编译成本高。
# 类型检查只需要它的 API 表面,这里用最小桩模块代替。
if [ ! -f "$DEPS/MarkdownUI.swiftmodule" ]; then
  echo "→ 生成 MarkdownUI 桩模块..."
  cat > "$DEPS/MarkdownUI.swift" <<'STUB'
import SwiftUI

public struct MarkdownContent {
    public init(_ text: String) {}
    public func linkingInlineCode(_ destinations: [String: String]) -> MarkdownContent { self }
}

public struct Markdown: View {
    public init(_ content: String) {}
    public init(_ content: MarkdownContent) {}
    public var body: some View { EmptyView() }
}

public protocol CodeSyntaxHighlighter {
    func highlightCode(_ code: String, language: String?) -> Text
}

public struct Theme {
    public init() {}
    public static var basic: Theme { Theme() }
    public static var docC: Theme { Theme() }
    public static var gitHub: Theme { Theme() }

    public func text(@MarkdownTextStyleBuilder _ content: () -> some MarkdownTextStyle) -> Theme { self }
    public func code(@MarkdownTextStyleBuilder _ content: () -> some MarkdownTextStyle) -> Theme { self }
    public func paragraph(_ content: @escaping (ParagraphConfiguration) -> some View) -> Theme { self }
    public func listItem(_ content: @escaping (ListItemConfiguration) -> some View) -> Theme { self }
    public func codeBlock(_ content: @escaping (CodeBlockConfiguration) -> some View) -> Theme { self }
    public func thematicBreak(@ViewBuilder _ content: () -> some View) -> Theme { self }
}

public struct ParagraphConfiguration { public var label: AnyView { AnyView(EmptyView()) } }
public struct ListItemConfiguration { public var label: AnyView { AnyView(EmptyView()) } }
public struct CodeBlockConfiguration {
    public var label: AnyView { AnyView(EmptyView()) }
    public var language: String? { nil }
}

public protocol MarkdownTextStyle {}
public struct ForegroundColor: MarkdownTextStyle { public init(_ color: Color?) {} }
public struct BackgroundColor: MarkdownTextStyle { public init(_ color: Color?) {} }
public struct FontSize: MarkdownTextStyle {
    public init(_ size: CGFloat) {}
    public init(_ em: Em) {}
}
public struct FontFamilyVariant: MarkdownTextStyle {
    public init(_ variant: Variant) {}
    public enum Variant { case monospaced }
}
public struct FontWeight: MarkdownTextStyle { public init(_ weight: Font.Weight) {} }

@resultBuilder
public struct MarkdownTextStyleBuilder {
    public static func buildBlock() -> EmptyTextStyle { EmptyTextStyle() }
    public static func buildBlock<S: MarkdownTextStyle>(_ s: S) -> S { s }
    public static func buildBlock<A: MarkdownTextStyle, B: MarkdownTextStyle>(_ a: A, _ b: B) -> TupleTextStyle { TupleTextStyle() }
    public static func buildBlock<A: MarkdownTextStyle, B: MarkdownTextStyle, C: MarkdownTextStyle>(_ a: A, _ b: B, _ c: C) -> TupleTextStyle { TupleTextStyle() }
    public static func buildBlock<A: MarkdownTextStyle, B: MarkdownTextStyle, C: MarkdownTextStyle, D: MarkdownTextStyle>(_ a: A, _ b: B, _ c: C, _ d: D) -> TupleTextStyle { TupleTextStyle() }
    public static func buildBlock<A: MarkdownTextStyle, B: MarkdownTextStyle, C: MarkdownTextStyle, D: MarkdownTextStyle, E: MarkdownTextStyle>(_ a: A, _ b: B, _ c: C, _ d: D, _ e: E) -> TupleTextStyle { TupleTextStyle() }
}
public struct EmptyTextStyle: MarkdownTextStyle {}
public struct TupleTextStyle: MarkdownTextStyle {}

public struct Em {
    public static func em(_ v: CGFloat) -> Em { Em() }
}

public extension View {
    func markdownTheme(_ theme: Theme) -> some View { self }
    func markdownCodeSyntaxHighlighter(_ highlighter: some CodeSyntaxHighlighter) -> some View { self }
    func markdownTextStyle(@MarkdownTextStyleBuilder _ content: () -> some MarkdownTextStyle) -> some View { self }
    func markdownMargin(top: CGFloat) -> some View { self }
    func markdownMargin(bottom: CGFloat) -> some View { self }
    func markdownMargin(top: Em) -> some View { self }
    func markdownMargin(bottom: Em) -> some View { self }
    func markdownMargin(top: CGFloat, bottom: CGFloat) -> some View { self }
    func relativeLineSpacing(_ spacing: Em) -> some View { self }
}
STUB
  "$TC/swiftc" -emit-module -sdk "$SDK" -target x86_64-apple-ios16.0-simulator -swift-version 5 \
    -module-name MarkdownUI -emit-module-path "$DEPS/MarkdownUI.swiftmodule" \
    "$DEPS/MarkdownUI.swift" >/dev/null 2>&1 || fail "生成 MarkdownUI 桩失败"
fi

# ---- 模块缓存：KMP 框架一更新就清空 ----
# 否则 swiftc 会复用旧的 ObjC 头缓存，新加的共享类型表现为「cannot find type」。
CACHE="$DEPS/module-cache"
STAMP="$CACHE/.framework-stamp"
FRAMEWORK_BIN="$FW/DeepSeekHarnessShared.framework/DeepSeekHarnessShared"
FW_STAMP=$(stat -f %m "$FRAMEWORK_BIN" 2>/dev/null || echo 0)
if [ ! -f "$STAMP" ] || [ "$(cat "$STAMP" 2>/dev/null)" != "$FW_STAMP" ]; then
  rm -rf "$CACHE"
  mkdir -p "$CACHE"
  echo "$FW_STAMP" > "$STAMP"
fi

# ---- 类型检查:App(iOS 16.0) ----
echo "→ 类型检查 App 源码 (iOS 16.0)..."
LOG=$(mktemp)
"$TC/swiftc" -typecheck \
  -sdk "$SDK" -target x86_64-apple-ios16.0-simulator -swift-version 5 \
  -F "$FW" -F "$DEPS" -I "$DEPS" -module-cache-path "$CACHE" \
  $(find DeepSeekHarnessMobile -name "*.swift") > "$LOG" 2>&1
APP_ERRORS=$(grep -c "error:" "$LOG" 2>/dev/null | head -1)
APP_ERRORS=${APP_ERRORS:-0}

# ---- 类型检查:Live Activity 扩展(iOS 16.2) ----
echo "→ 类型检查 Widget 扩展 (iOS 16.2)..."
WLOG=$(mktemp)
"$TC/swiftc" -typecheck \
  -sdk "$SDK" -target x86_64-apple-ios16.2-simulator -swift-version 5 \
  -F "$DEPS" -I "$DEPS" -module-cache-path "$CACHE" \
  AgentLiveActivityWidget/*.swift \
  DeepSeekHarnessMobile/LiveActivity/AgentActivityAttributes.swift \
  DeepSeekHarnessMobile/LiveActivity/AgentApprovalIntents.swift > "$WLOG" 2>&1
WIDGET_ERRORS=$(grep -c "error:" "$WLOG" 2>/dev/null | head -1)
WIDGET_ERRORS=${WIDGET_ERRORS:-0}

# ---- 退役路径守卫（B3）----
# bridge「粘贴配对资料 JSON」这条配对路径已退役：bridge 凭证改由网关配对成功后的
# `paired` 帧 bridgeSession 字段下发。BridgeTeamAdapter.pairPastedPayload 只留作应急排查，
# **界面层不得再引用**（重新接回界面 = 让用户又面对两条看起来都能配对的路径）。
RETIRED_UI=$(grep -rn "pairPastedPayload" DeepSeekHarnessMobile/Views/ 2>/dev/null)
if [ -n "$RETIRED_UI" ]; then
  echo "❌ 退役配对路径被界面引用（B3 红线）:"
  echo "$RETIRED_UI"
  exit 1
fi

echo
if [ "$APP_ERRORS" -eq 0 ] && [ "$WIDGET_ERRORS" -eq 0 ]; then
  echo "✅ 类型检查通过 (App 0 错误, Widget 0 错误)"
  rm -f "$LOG" "$WLOG"
  exit 0
fi

echo "❌ 类型检查失败: App $APP_ERRORS 个错误, Widget $WIDGET_ERRORS 个错误"
echo
[ "$APP_ERRORS" -gt 0 ] && { echo "--- App ---"; grep -A2 "error:" "$LOG" | head -40; }
[ "$WIDGET_ERRORS" -gt 0 ] && { echo "--- Widget ---"; grep -A2 "error:" "$WLOG" | head -20; }
echo
echo "完整日志: $LOG  $WLOG"
exit 1
