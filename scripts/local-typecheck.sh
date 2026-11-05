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

# python3 解析器：这台机器的 `/usr/bin/python3` 是个 shim，会通过
# `xcodebuild -find python3` 去找 SDK 里的解释器；而 xcodebuild 除 -version 外
# 一律 Bus error（系统级问题，换哪个 Xcode 都一样）→ shim 必然失败并弹「安装命令行工具」。
# 直接指向 CLT 里的真实解释器，闸门里的 4 处 python 检查才不是假绿。
# 2026-10-30 实测：shim 失败时退出码仍可能为 0，check-* 脚本因此形同虚设。
PYTHON3=""
for candidate in \
    "${DSH_PYTHON3:-}" \
    /Library/Developer/CommandLineTools/usr/bin/python3 \
    "$HOME/bin/python3"; do
    [ -n "$candidate" ] && [ -x "$candidate" ] || continue
    if "$candidate" -c 'import sys; sys.exit(0)' >/dev/null 2>&1; then
        PYTHON3="$candidate"
        break
    fi
done
if [ -z "$PYTHON3" ]; then
    echo "⚠️  找不到可用的 python3（系统 shim 被 xcodebuild 崩溃拖垮）；python 类闸门将跳过并显式报错"
fi

D="/Applications/Xcode.app/Contents/Developer"
TC="$D/Toolchains/XcodeDefault.xctoolchain/usr/bin"
SDK="$D/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator26.2.sdk"
FW="shared/build/bin/iosX64/debugFramework"
DEPS="${TYPECHECK_DEPS:-/tmp/dsh-typecheck-deps}"

fail() { echo "❌ $1"; exit 1; }

[ -x "$TC/swiftc" ] || fail "找不到 swiftc,请确认已安装 Xcode"
[ -d "$SDK" ] || fail "找不到 iOS SDK: $SDK"
[ -d "$FW" ] || fail "缺少 KMP 框架,先运行: ./gradlew :shared:linkDebugFrameworkIosX64"

# ---- KMP 共享层三闸门（2026-11-02 教训：iOS 整平台编不出来、闸门却一路绿）----
# 事故原文：`07552f4` 往 commonMain 写了 `putIfAbsent`（JVM 专有），
#   Android 的 JVM 单测 283/572 例全绿、iOS 的 `:shared:compileKotlinIosX64` 直接失败。
#   而这里当时只做 `[ -d "$FW" ]`（目录在不在）——**框架过期时，四路类型检查是在旧框架上跑的**。
# 三个判据按代价从低到高，任一条红即 exit 1：

# ③ 禁用 API 扫描（最便宜）：commonMain 是两端共用层，JVM 专有 API 在 native 目标上编不过。
#    必须**排除注释行**——记录这次教训的注释自己就含 `putIfAbsent`，否则判据被自己弄红。
if [ -n "$PYTHON3" ]; then
  "$PYTHON3" - <<'PYCHECK' || exit 1
import pathlib, re
pat = re.compile(r'\bputIfAbsent\b|ConcurrentHashMap|java\.util|java\.lang|synchronized\(')
bad = []
for path in sorted(pathlib.Path('shared/src/commonMain').rglob('*.kt')):
    for line_no, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        code = line.split('//')[0]            # 去掉行尾注释后再判
        if pat.search(code):
            bad.append(f"   {path}:{line_no}: {line.strip()[:90]}")
if bad:
    print("❌ commonMain 出现 JVM 专有 API（iOS/native 目标编不过）：")
    print("\n".join(bad[:10]))
    raise SystemExit(1)
print(f"✅ commonMain 无 JVM 专有 API（扫描 {len(list(pathlib.Path('shared/src/commonMain').rglob('*.kt')))} 个 .kt）")
PYCHECK
fi

# ② 脏检查（一行）：框架比 commonMain 源码旧 ⇒ 后面四路类型检查读的是过期框架，结果不可信。
# ⚠️ 锚点必须是**产物本身**（`.framework` bundle），不能是它的父目录 `$FW`：
#    `$FW` 的 mtime 不随链接更新（实测 11-01 22:40，而里面的 bundle 是 11-02 08:17:57），
#    拿父目录当锚会恒报"脏"（10 个 .kt 比它新），而锚到 bundle 上是 0 —— 判据没变，被量的参照物错了。
FW_ANCHOR="$(ls -d "$FW"/*.framework 2>/dev/null | head -1)"
[ -n "$FW_ANCHOR" ] || FW_ANCHOR="$FW"
STALE_SOURCE="$(find shared/src/commonMain -name '*.kt' -newer "$FW_ANCHOR" | head -1)"
if [ -n "$STALE_SOURCE" ]; then
  echo "→ KMP 框架早于 commonMain 源码（例：$STALE_SOURCE）"

  # ① 真编译 iOS 目标。**`UP-TO-DATE` 不算编译过**（Gradle 会直接跳过）⇒ 用 --rerun-tasks。
  #    只看 `BUILD SUCCESSFUL` 不够：AndroidCoder 那次 1m31s 的“成功”其实是
  #    `1 executed, 1 from cache, 2 up-to-date`，执行的还不是编译。
  echo "→ 真编译 iOS 目标（--rerun-tasks）..."
  KMP_LOG="$(mktemp)"
  if ! ./gradlew :shared:compileKotlinIosX64 --rerun-tasks --console=plain > "$KMP_LOG" 2>&1; then
    tail -20 "$KMP_LOG"
    rm -f "$KMP_LOG"
    fail "commonMain 在 iOS 目标上编不过（上面是 Gradle 报错；Android 的 JVM 测试照不到这一层）"
  fi
  rm -f "$KMP_LOG"

  # 编译过了但框架仍是旧的 ⇒ 四路类型检查用的还是过期框架，必须重链才算数。
  [ -z "$(find shared/src/commonMain -name '*.kt' -newer "$FW_ANCHOR" | head -1)" ] \
    || fail "commonMain 已能编译，但 $FW_ANCHOR 仍是旧的：请先跑 ./gradlew :shared:linkDebugFrameworkIosX64 再回来"
fi

mkdir -p "$DEPS"

# ---- Tsnetgo 框架（上游 1.7.15 Tailscale：Core/TsnetTunnel.swift 裸 import Tsnetgo）----
# 不用桩——真产物就在仓库外预置（scripts/ensure-tsnetgo.sh 拷贝，153M 双切片），
# 对着真 gomobile API 做类型检查比手写桩可信。缺产物 = 明确报错指路，不静默降级。
TSNETGO_FW_SLICE="tsnetgo/build/tsnetgo/Tsnetgo.xcframework/ios-arm64_x86_64-simulator"
if [ ! -d "$TSNETGO_FW_SLICE" ]; then
  fail "缺少 Tsnetgo 模拟器切片：$TSNETGO_FW_SLICE —— 先跑 scripts/ensure-tsnetgo.sh 预置产物（见 Docs/release-feasibility-20261106.md）"
fi

# ---- 准备 ChatLayout 模块 ----
if [ ! -f "$DEPS/ChatLayout.swiftmodule" ]; then
  echo "→ 准备 ChatLayout 模块..."
  CL_SRC="${CHATLAYOUT_SRC:-/tmp/ChatLayout/ChatLayout}"
  # 持久缓存优先：/tmp 会被清理脚本清掉，联网又不稳（2026-11-02 起）
  [ -d "$CL_SRC" ] || CL_SRC="$HOME/.cache/dsh-typecheck-src/ChatLayout/ChatLayout"
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

# ---- 准备 SwiftMath 模块（上游 1.7.12 新增依赖，MarkdownMath.swift 用）----
# 与 ChatLayout 同款：优先编真源码（类型检查才有效）；SPM 的 Bundle.module 访问器
# 是构建时自动生成的，直接用 swiftc 编源码需补一个同名 shim。
if [ ! -f "$DEPS/SwiftMath.swiftmodule" ]; then
  echo "→ 准备 SwiftMath 模块..."
  SM_SRC="${SWIFTMATH_SRC:-/tmp/SwiftMath/Sources/SwiftMath}"
  # 同上：持久缓存优先
  [ -d "$SM_SRC" ] || SM_SRC="$HOME/.cache/dsh-typecheck-src/SwiftMath/Sources/SwiftMath"
  if [ ! -d "$SM_SRC" ]; then
    echo "  克隆 SwiftMath 1.7.3..."
    git clone --depth 1 --branch 1.7.3 https://github.com/mgriebling/SwiftMath.git /tmp/SwiftMath >/dev/null 2>&1 \
      || fail "克隆 SwiftMath 失败(需要能访问 GitHub)"
    SM_SRC=/tmp/SwiftMath/Sources/SwiftMath
  fi
  cat > "$DEPS/SwiftMathBundleShim.swift" <<'SHIM'
import Foundation
extension Bundle { static var module: Bundle { Bundle.main } }
SHIM
  "$TC/swiftc" -emit-module -emit-library \
    -sdk "$SDK" -target x86_64-apple-ios16.0-simulator -swift-version 5 \
    -module-name SwiftMath \
    -emit-module-path "$DEPS/SwiftMath.swiftmodule" \
    -o "$DEPS/libSwiftMath.dylib" \
    $(find "$SM_SRC" -name "*.swift") "$DEPS/SwiftMathBundleShim.swift" >/dev/null 2>&1 \
    || fail "编译 SwiftMath 失败"
fi

# ---- 准备 MarkdownUI 桩模块 ----
# MarkdownUI 依赖 cmark-gfm 与 NetworkImage 两个 C/SPM 包,完整编译成本高。
# 类型检查只需要它的 API 表面,这里用最小桩模块代替。
# 桩源码每次都重写（幂等），模块只在源码比 .swiftmodule 新时重建。
# 教训：以前只写 `if [ ! -f .swiftmodule ]`，改了下面的桩源码也不会生效，
# 表现为"明明补了 API 还一直报 no member"（本次合并上游新增 API 时踩到）。
cat > "$DEPS/MarkdownUI.swift" <<'STUB'
import SwiftUI
import UIKit

/// 上游 Vendor（MarkdownTableCard）：表格列的水平对齐。
@frozen public enum TextTableColumnAlignment {
    case leading, center, trailing
}

public struct MarkdownContent {
    public init(_ text: String) {}
    public func linkingInlineCode(_ destinations: [String: String]) -> MarkdownContent { self }
    /// 上游 Vendor（MarkdownTableCard）：表格单元格、纯文本行、列对齐。
    /// MarkdownTableCard 只读这三个访问器，桩按真实签名补即可，不需要实现。
    public var tableCells: [[MarkdownContent]]? { nil }
    public var tablePlainTextRows: [[String]] { [] }
    public var tableColumnAlignments: [TextTableColumnAlignment] { [] }
    /// 上游 Vendor（ConversationView）：判断是否含表格 / 复制选择区段的 HTML。
    public var containsTable: Bool { false }
    public func renderSelectionHTML() -> String { "" }
    /// 上游 Vendor 新增：复制消息用纯文本 / 可选中富文本（全屏选择页）。
    public func renderPlainText() -> String { "" }
    /// 文件引用链接（`dsh-file-reference:///`）的 HTML 输出，测试用它断言顺序/去重。
    public func renderHTML() -> String { "" }
    /// 上游 Vendor 1.7.12 新增 `codeHighlighter` 参数（全屏选择页的代码高亮回调）。
    /// 桩必须跟源码形状一致，否则 App 侧传该参数会报 "extra argument"（合并踩到）。
    public func renderSelectableText(
        font: UIFont, textColor: UIColor = .label,
        codeHighlighter: ((String, String?) -> NSAttributedString)? = nil
    ) -> NSAttributedString {
        NSAttributedString(string: "")
    }
}

/// 上游 Vendor（MarkdownContent+SelectableText）：代码块在富文本里的标记与尺寸。
public enum SelectableCodeBlockStyle {
    public static let blockID = NSAttributedString.Key("MarkdownSelectableCodeBlockID")
    public static let language = NSAttributedString.Key("MarkdownSelectableCodeLanguage")
    public static let decoration = NSAttributedString.Key("MarkdownSelectableCodeDecoration")
    public static let horizontalPadding: CGFloat = 12
    public static let headerHeight: CGFloat = 34
    public static let bottomPadding: CGFloat = 12
    public static let backgroundColor = UIColor.secondarySystemBackground
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
    /// 上游 Vendor：表格样式（MarkdownTableCard 走这条）。
    public func table(@ViewBuilder _ content: @escaping (BlockConfiguration) -> some View) -> Theme { self }
    public func tableCell(@ViewBuilder _ content: @escaping (TableCellConfiguration) -> some View) -> Theme { self }
}

public struct BlockConfiguration {
    /// 表格块的 MarkdownContent（MarkdownTableCard 用它取 cells / alignments）。
    public var content: MarkdownContent { MarkdownContent("") }
    public var label: AnyView { AnyView(EmptyView()) }
}

public struct TableCellConfiguration {
    public var label: AnyView { AnyView(EmptyView()) }
}

public struct ParagraphConfiguration { public var label: AnyView { AnyView(EmptyView()) } }
public struct ListItemConfiguration { public var label: AnyView { AnyView(EmptyView()) } }
public struct CodeBlockConfiguration {
    public var label: AnyView { AnyView(EmptyView()) }
    public var language: String? { nil }
    /// 上游新增：代码块原文（复制按钮用）。
    public var content: String { "" }
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
public struct EmptyTextStyle: MarkdownTextStyle, TextStyle {
    public func _collectAttributes(in attributes: inout AttributeContainer) {}
}
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

// ---- 测试目标（`@testable import MarkdownUI`）会用到的内部 API ----
// 闸门 `scripts/local-test-typecheck.sh` 要用同一份桩把测试源码也编一遍，
// 所以这里把测试引用到的符号补齐。**只求签名一致，不求行为**——真正跑测试的是 Xcode。
// 补的原则：形状照抄 `Vendor/swift-markdown-ui/Sources/MarkdownUI`，别自己发明语义。
public struct SoftBreak {
    public enum Mode { case space, lineBreak }
}

/// Vendor 的 `TextStyle` 是协议（`Theme.code` 等属性就是它）。
public protocol TextStyle {
    func _collectAttributes(in attributes: inout AttributeContainer)
}

enum InlineNode: Hashable, Sendable {
    case text(String)
    case softBreak
    case lineBreak
    case code(String)
    case html(String)
    indirect case emphasis(children: [InlineNode])
    indirect case strong(children: [InlineNode])
    indirect case strikethrough(children: [InlineNode])
    indirect case link(destination: String, children: [InlineNode])
    indirect case image(source: String, children: [InlineNode])
}

extension InlineNode {
    var children: [InlineNode] {
        switch self {
        case .emphasis(let c), .strong(let c), .strikethrough(let c), .link(_, let c), .image(_, let c):
            return c
        default:
            return []
        }
    }

    /// 上游 Vendor 1.7.12 新增（`InlineNode+RawImageData`）：把粗体/斜体/链接里的公式图片收进来。
    /// 桩里只保留测试用到的「数量」语义，形状按源码（递归 + dsh-math: 前缀过滤）。
    /// 教训：桩缺成员会报 "has no member"（合并上游新增 API 时踩到第二次）。
    var inlineImages: [String] {
        if case .image(let source, _) = self { return [source] }
        return children.flatMap(\.inlineImages).filter { $0.hasPrefix("dsh-math:") }
    }

    func renderAttributedString(
        baseURL: URL?, textStyles: InlineTextStyles,
        softBreakMode: SoftBreak.Mode, attributes: AttributeContainer
    ) -> AttributedString { AttributedString() }
}

struct InlineTextStyles {
    let code: TextStyle
    let emphasis: TextStyle
    let strong: TextStyle
    let strikethrough: TextStyle
    let link: TextStyle
    init(code: TextStyle, emphasis: TextStyle, strong: TextStyle,
         strikethrough: TextStyle, link: TextStyle) {
        self.code = code
        self.emphasis = emphasis
        self.strong = strong
        self.strikethrough = strikethrough
        self.link = link
    }
}

extension Array where Element == InlineNode {
    func renderText(
        baseURL: URL?, textStyles: InlineTextStyles, images: [String: Image],
        softBreakMode: SoftBreak.Mode, attributes: AttributeContainer
    ) -> Text { Text("") }

    func renderAttributedString(
        baseURL: URL?, textStyles: InlineTextStyles,
        softBreakMode: SoftBreak.Mode, attributes: AttributeContainer
    ) -> AttributedString { AttributedString() }
}

struct InlineText: View {
    let hasInlineCode: Bool
    init(_ inlines: [InlineNode]) { self.hasInlineCode = !inlines.isEmpty }
    var body: some View { EmptyView() }
}

struct TextStyleAttributesReader<Content: View>: View {
    private let content: (AttributeContainer) -> Content
    init(@ViewBuilder content: @escaping (_ attributes: AttributeContainer) -> Content) {
        self.content = content
    }
    var body: some View { content(AttributeContainer()) }
}

@available(iOS 18.0, *)
struct MarkdownInlineCodeBackgroundRenderer: TextRenderer {
    func draw(layout: Text.Layout, in context: inout GraphicsContext) {}
}

public protocol ImageProvider {
    associatedtype Body: View
    @ViewBuilder func makeImage(url: URL?) -> Body
}

public protocol InlineImageProvider {
    func image(with url: URL, label: String) async throws -> Image
}

public struct DefaultImageProvider: ImageProvider {
    public init() {}
    public static var `default`: DefaultImageProvider { .init() }
    public func makeImage(url: URL?) -> some View { Color.clear }
}

public struct DefaultInlineImageProvider: InlineImageProvider {
    public init() {}
    public static var `default`: DefaultInlineImageProvider { .init() }
    public func image(with url: URL, label: String) async throws -> Image { Image(systemName: "photo") }
}

public extension View {
    func markdownImageProvider<I: ImageProvider>(_ imageProvider: I) -> some View { self }
    func markdownInlineImageProvider(_ inlineImageProvider: InlineImageProvider) -> some View { self }
}

enum BlockNode: Hashable {
    case codeBlock(fenceInfo: String?, content: String)
    indirect case paragraph(content: [InlineNode])
    case other
}

extension MarkdownContent {
    var blocks: [BlockNode] { [] }
}

public extension Theme {
    var code: TextStyle { EmptyTextStyle() }
    var emphasis: TextStyle { EmptyTextStyle() }
    var strong: TextStyle { EmptyTextStyle() }
    var strikethrough: TextStyle { EmptyTextStyle() }
    var link: TextStyle { EmptyTextStyle() }
}

extension EnvironmentValues {
    var theme: Theme {
        get { Theme() }
        set {}
    }
    var baseURL: URL? {
        get { nil }
        set {}
    }
    var softBreakMode: SoftBreak.Mode {
        get { .space }
        set {}
    }
}

// 内联图片：`InlineText` / `ImageFlow` 走这套（测试里有 task、provider、base URL 三个探针）。
struct RawImageData: Hashable {
    var source: String
    var alt: String
    var destination: String?
}

extension InlineNode {
    var imageData: RawImageData? { nil }
}

struct StubInlineImageProvider: InlineImageProvider {
    func image(with url: URL, label: String) async throws -> Image { Image(systemName: "photo") }
}

public extension InlineImageProvider {
    static var `default`: InlineImageProvider { StubInlineImageProvider() }
}

extension EnvironmentValues {
    var inlineImageProvider: InlineImageProvider {
        get { StubInlineImageProvider() }
        set {}
    }
    /// Vendor 里是 `URL?`（测试设置时传 URL 会自动提升为 Optional）。
    var imageBaseURL: URL? {
        get { nil }
        set {}
    }
}

STUB

# 桩源码变了就重建模块（时间戳比对），否则改动不生效。
if [ ! -f "$DEPS/MarkdownUI.swiftmodule" ] || [ "$DEPS/MarkdownUI.swift" -nt "$DEPS/MarkdownUI.swiftmodule" ]; then
  echo "→ 生成 MarkdownUI 桩模块..."
  "$TC/swiftc" -emit-module -sdk "$SDK" -target x86_64-apple-ios16.0-simulator -swift-version 5 \
    -module-name MarkdownUI -emit-module-path "$DEPS/MarkdownUI.swiftmodule" \
    "$DEPS/MarkdownUI.swift" >/dev/null 2>&1 || fail "生成 MarkdownUI 桩失败"
fi

# ---- 准备 WebRTC 桩模块（上游 1.7.15 新增依赖，GatewayRtcLink.swift 用）----
# webrtc 154.0.0 是 SPM 二进制 xcframework 工件（SourcePackages/artifacts），
# 本地零缓存且网络不通时拿不到真模块。类型检查只需要 API 表面——按
# GatewayRtcLink.swift 实际用到的类型/协议出桩（与 MarkdownUI 桩同款先例）。
# 边界如实：桩保证「本仓调用 WebRTC 的代码类型正确」，不保证 WebRTC 本体行为；
# 协议只列本仓实现的必需方法，真包到位（联网/云端）后以真包为准。
# 桩源码每次都重写（幂等），模块只在源码比 .swiftmodule 新时重建（同 MarkdownUI 教训）。
cat > "$DEPS/WebRTC.swift" <<'STUB'
import Foundation

public enum RTCSdpSemantics { case unifiedPlan, planB }
public enum RTCContinualGatheringPolicy { case gatherOnce, gatherContinually }
public enum RTCSdpType { case offer, pranswer, answer, rollback }
public enum RTCSignalingState { case stable, haveLocalOffer, haveRemoteOffer, haveLocalPrAnswer, haveRemotePrAnswer, closed }
public enum RTCIceConnectionState { case new, checking, connected, completed, failed, disconnected, closed, count }
public enum RTCPeerConnectionState { case new, connecting, connected, disconnected, failed, closed }
public enum RTCIceGatheringState { case new, gathering, complete }
public enum RTCDataChannelState { case connecting, open, closing, closed }

public class RTCPeerConnectionFactory {
    public init() {}
    public func peerConnection(with configuration: RTCConfiguration, constraints: RTCMediaConstraints,
                               delegate: RTCPeerConnectionDelegate?) -> RTCPeerConnection? { nil }
}

public class RTCConfiguration {
    public init() {}
    public var sdpSemantics: RTCSdpSemantics = .unifiedPlan
    public var continualGatheringPolicy: RTCContinualGatheringPolicy = .gatherOnce
    public var iceServers: [RTCIceServer] = []
}

public class RTCIceServer {
    public init(urlStrings: [String]) {}
}

public class RTCMediaConstraints {
    public init(mandatoryConstraints: [String: String]?, optionalConstraints: [String: String]?) {}
}

public class RTCDataChannelConfiguration {
    public init() {}
    public var isOrdered: Bool = true
}

public class RTCSessionDescription {
    public init(type: RTCSdpType, sdp: String) {}
    public var sdp: String { "" }
}

public class RTCIceCandidate {
    public init(sdp: String, sdpMLineIndex: Int32, sdpMid: String?) {}
    public var sdp: String { "" }
    public var sdpMid: String? { nil }
    public var sdpMLineIndex: Int32 { 0 }
}

public struct RTCDataBuffer {
    public init(data: Data, isBinary: Bool) {}
    public var data: Data { Data() }
    public var isBinary: Bool { false }
}

public class RTCStatistics {
    public var id: String { "" }
    public var type: String { "" }
    public var values: [String: Any] { [:] }
}

public class RTCStatisticsReport {
    public var statistics: [String: RTCStatistics] { [:] }
}

public class RTCMediaStream {}

public class RTCPeerConnection {
    public weak var delegate: RTCPeerConnectionDelegate?
    public func dataChannel(forLabel label: String, configuration: RTCDataChannelConfiguration) -> RTCDataChannel? { nil }
    public func offer(for constraints: RTCMediaConstraints,
                      completionHandler: @escaping (RTCSessionDescription?, Error?) -> Void) {}
    public func setLocalDescription(_ description: RTCSessionDescription,
                                    completionHandler: @escaping (Error?) -> Void) {}
    public func setRemoteDescription(_ description: RTCSessionDescription,
                                     completionHandler: @escaping (Error?) -> Void) {}
    public func add(_ candidate: RTCIceCandidate, completionHandler: @escaping (Error?) -> Void) {}
    public func statistics(completionHandler: @escaping (RTCStatisticsReport) -> Void) {}
    public func close() {}
}

public class RTCDataChannel {
    public weak var delegate: RTCDataChannelDelegate?
    public var readyState: RTCDataChannelState { .closed }
    public var bufferedAmount: UInt64 { 0 }
    @discardableResult public func sendData(_ buffer: RTCDataBuffer) -> Bool { false }
    public func close() {}
}

public protocol RTCPeerConnectionDelegate: AnyObject {
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState)
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream)
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream)
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection)
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState)
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState)
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState)
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate)
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate])
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel)
}

public protocol RTCDataChannelDelegate: AnyObject {
    func dataChannelDidChangeState(_ dataChannel: RTCDataChannel)
    func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer)
}
STUB
if [ ! -f "$DEPS/WebRTC.swiftmodule" ] || [ "$DEPS/WebRTC.swift" -nt "$DEPS/WebRTC.swiftmodule" ]; then
  echo "→ 生成 WebRTC 桩模块..."
  "$TC/swiftc" -emit-module -sdk "$SDK" -target x86_64-apple-ios16.0-simulator -swift-version 5 \
    -module-name WebRTC -emit-module-path "$DEPS/WebRTC.swiftmodule" \
    "$DEPS/WebRTC.swift" >/dev/null 2>&1 || fail "生成 WebRTC 桩失败"
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
  -F "$FW" -F "$DEPS" -F "$TSNETGO_FW_SLICE" -I "$DEPS" -module-cache-path "$CACHE" \
  $(find DeepSeekHarnessMobile -name "*.swift") > "$LOG" 2>&1
APP_ERRORS=$(grep -c "error:" "$LOG" 2>/dev/null | head -1)
APP_ERRORS=${APP_ERRORS:-0}

# ---- 类型检查:App(iOS 16.0, Debug 配置)----
# 第三个结构性盲区（2026-10-25）：xcodebuild 的 **Debug 配置会定义 DEBUG 宏**，
# 而上面那一路不带 -D DEBUG，整个 `#if DEBUG` 块被剔除 —— 于是块内的 iOS 17+ API
# （上游 deployment target 是 17.0，合并进我们的 16.0 后遗留）在本地全绿，
# 但任何 Debug 构建 / 跑 XCTest 都会编译失败。这里再跑一路带 -D DEBUG 的，
# 把「Debug 配置能编」变成硬闸门。只多几秒。
echo "→ 类型检查 App 源码 (iOS 16.0, -D DEBUG)..."
DLOG=$(mktemp)
"$TC/swiftc" -typecheck -D DEBUG \
  -sdk "$SDK" -target x86_64-apple-ios16.0-simulator -swift-version 5 \
  -F "$FW" -F "$DEPS" -F "$TSNETGO_FW_SLICE" -I "$DEPS" -module-cache-path "$CACHE" \
  $(find DeepSeekHarnessMobile -name "*.swift") > "$DLOG" 2>&1
DEBUG_ERRORS=$(grep -c "error:" "$DLOG" 2>/dev/null | head -1)
DEBUG_ERRORS=${DEBUG_ERRORS:-0}

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

# ---- 类型检查:Live Activity 扩展(iOS 16.2, Debug 配置)----
# 扩展同样有 `#if DEBUG` 块（Live Activity 的 `#Preview(as:using:contentStates:)`
# 是 iOS 17+ API，16.2 target 下不加可用性守卫就编不过）。Release 剔除 DEBUG 宏，
# 所以只有 Debug 构建 / XCTest 会撞上——与 App 那一路同型，一起当硬闸门。
echo "→ 类型检查 Widget 扩展 (iOS 16.2, -D DEBUG)..."
WDLOG=$(mktemp)
"$TC/swiftc" -typecheck -D DEBUG \
  -sdk "$SDK" -target x86_64-apple-ios16.2-simulator -swift-version 5 \
  -F "$DEPS" -I "$DEPS" -module-cache-path "$CACHE" \
  AgentLiveActivityWidget/*.swift \
  DeepSeekHarnessMobile/LiveActivity/AgentActivityAttributes.swift \
  DeepSeekHarnessMobile/LiveActivity/AgentApprovalIntents.swift > "$WDLOG" 2>&1
WIDGET_DEBUG_ERRORS=$(grep -c "error:" "$WDLOG" 2>/dev/null | head -1)
WIDGET_DEBUG_ERRORS=${WIDGET_DEBUG_ERRORS:-0}

# ---- Bundle ID 前缀闸门（2026-10-28 教训：47 包归档被 ValidateEmbeddedBinary 拒绝）----
# 合并上游把 Widget target 的 Release 配置从硬编码改成了 "$(LOCAL_BUNDLE_IDENTIFIER).AgentLiveActivityWidget"，
# 而 config/Debug.xcconfig 里该变量 = com.example.dshmobile（上游占位值）→ 嵌入二进制 ID 不再以主 App
# com.ydgg123.orcarness 为前缀 → 真正 archive 时才炸。上面三闸门（typecheck/收录/版本）全绿也照样漏。
# 判据必须用 xcodebuild 展开值：pbxproj 里看着有值，展开后才知道是不是占位符。
if ! "$PYTHON3" "$(dirname "${BASH_SOURCE[0]}")/verify-bundle-prefix.py"; then
  echo "   Widget 的 PRODUCT_BUNDLE_IDENTIFIER 必须硬编码 com.ydgg123.orcarness.AgentLiveActivityWidget（Release），"
  echo "   不要用 \$(LOCAL_BUNDLE_IDENTIFIER) 占位——那会展开成上游的 com.example.dshmobile。"
  exit 1
fi

# ---- 团队流后台任务清理闸门（2026-10-26）----
# heartbeatTask / chunkSweeper 必须在 stop()、connect()、handleDisconnect() 三处显式成对清理，
# startHeartbeat() 只允许 connect() 一处；deinit 兜底必须在位。
if ! "$PYTHON3" "$(dirname "${BASH_SOURCE[0]}")/verify-feed-cleanup.py"; then
  exit 1
fi

# ---- 工程收录守卫（2026-10-25 教训）----
# 本工程是**显式 fileRef**（不是 Xcode 16 的同步组）：新加的 .swift 如果没登记进 pbxproj，
# find 式的类型检查照样绿，CI 才炸。守卫已升级为**按 target 归属**核对
# （QA 建议：挂在错的 target 也要红）。注意：并发编辑时工作树在变，本守卫可能抖动，
# 验证提交版请用 `git archive` 快照运行。
if ! "$PYTHON3" "$(dirname "${BASH_SOURCE[0]}")/check-project-membership.py"; then
  exit 1
fi

# ---- 本地化目录顶层结构守卫（2026-10-30 教训）----
# 五路 typecheck 走 swiftc 前端，**不解析 .xcstrings**；顶层缺 `version` 时闸门全绿，
# 只有 xcodebuild 的 `xcstringstool compile` 阶段才报 "Missing required key 'version'" →
# ARCHIVE FAILED（合上游 1.7.12 时冲突解决把该键丢了，出包才暴露）。
# 这里守四件事：JSON 合法 + 顶层键齐备（sourceLanguage / version / strings）+ strings 非空
# + **每条 `localizations` 必须是字典**。
#
# ⚠️ 第四条是 2026-11-04 的教训（79 出包 archive EXIT=65）：手写新增条目时把
# `localizations` 写成了数组，全文件其余 912 条都是字典形态；Xcode 的 xcstringstool
# 只认字典 → archive 直接失败。**前三件全绿也查不出这条**，所以必须逐条验类型。
"$PYTHON3" - "$PWD/DeepSeekHarnessMobile/Resources/Localizable.xcstrings" <<'PYCHECK' || exit 1
import json, sys
path = sys.argv[1]
try:
    data = json.load(open(path, encoding='utf-8'))
except Exception as e:
    print("❌ xcstrings 不是合法 JSON：%s" % e); sys.exit(1)
missing = [k for k in ('sourceLanguage', 'version', 'strings') if k not in data]
if missing:
    print("❌ xcstrings 顶层缺键 %s（该键只有 xcstringstool 阶段才校验，闸门其他项查不出）" % missing)
    sys.exit(1)
if not isinstance(data.get('strings'), dict) or not data['strings']:
    print("❌ xcstrings 的 strings 为空或类型不对"); sys.exit(1)
# 逐条验 localizations 形态：Xcode 只接受字典（{locale: {...}}），数组会让 archive 65 退出。
bad_shape = []
for key, entry in data['strings'].items():
    if not isinstance(entry, dict):
        bad_shape.append((key, 'entry:' + type(entry).__name__)); continue
    loc = entry.get('localizations')
    if loc is not None and not isinstance(loc, dict):
        bad_shape.append((key, 'localizations:' + type(loc).__name__))
if bad_shape:
    print("❌ xcstrings 有 %d 条 localizations 不是字典（Xcode 只认字典，archive 会 EXIT=65）：" % len(bad_shape))
    for key, why in bad_shape[:10]:
        print("   %s  → %s" % (key, why))
    if len(bad_shape) > 10:
        print("   …另有 %d 条" % (len(bad_shape) - 10))
    sys.exit(1)
print("✅ xcstrings 顶层结构齐备：sourceLanguage=%s version=%s strings=%d 条（localizations 全部为字典）"
      % (data['sourceLanguage'], data['version'], len(data['strings'])))
PYCHECK

# ---- 本地化调用点闸门（2026-11-01 教训：**注册了 ≠ 生效**）----
# `emptyRow(_ text: String)` / `badge(_ text: String)` / `labeledRow(_ label: String, …)`
# 这类 helper 的形参是 `String`，调用点传中文裸串时**不做查表** —— 键早就注册进
# xcstrings 了，界面照样显示中文（当天 8 条属于这一类：主 / 运行中 / 成员 / 会话 …）。
# 既有盘点器只查「目录里注册没注册」，抓不到。这里扫两条：
#   ① 返回 View 的 helper 上、String 形参位置的中文裸串；
#   ② `String(localized: "X")` 的键不在目录（非插值）。
# 存量债冻在 scripts/i18n-callsite-baseline.txt，**只拦新增**。
if ! "$PYTHON3" "$(dirname "${BASH_SOURCE[0]}")/check-i18n-callsites.py"; then
  echo "   修法：形参改 LocalizedStringKey；或调用点包 String(localized:)；键补进 Localizable.xcstrings。"
  exit 1
fi

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

# ---- 测试目标类型检查（第五路，2026-10-26）----
# 上面四路只编 App / Widget，**从不编 DeepSeekHarnessMobileTests**。于是"只在测试目标里
# 编不过"的错误一直没人发现：`FileIconAssetTests` 用 iOS 16.4 的 `safeAreaRegions` 没加
# 保护、`GatewayProtocolTests` 直接用 16.1/16.2/17.0 的 API……实测把测试目标当编译单元
# 编一遍，16.0 部署目标下有 54 个错误（也就是说这个测试目标长期处于"从未编过"的状态）。
# 这里把"用例能编"变成硬闸门；`--self-test` 是该闸门的反向自证。
if ! bash "$(dirname "${BASH_SOURCE[0]}")/local-test-typecheck.sh"; then
  echo "   测试目标编不过：先看上面报错（App 四路绿 ≠ 测试能编）"
  exit 1
fi

# ---- Android 侧总闸门（第六路，2026-11-04 vc63 还债批）----
# monorepo 同一个闸门入口：androidTest 源集自 11-02 起整体编不过、无人发现（与 iOS 第五路
# 同根因：闸门不编测试源集）。主 APK + androidTest 编译 + JVM 用例全绿才放行；
# `--self-test` 反向自证见 local-android-gate.sh。
if ! bash "$(dirname "${BASH_SOURCE[0]}")/local-android-gate.sh"; then
  echo "   Android 闸门编不过：先看上面报错（iOS 各路绿 ≠ 安卓能出包）"
  exit 1
fi

# ---- 版本一致性闸门（2026-10-26 教训：0548 包主 App 31 / widget 32）----
# 主 App 的 build/version 取自硬编码的 Resources/Info.plist（该 target 无 CURRENT_PROJECT_VERSION），
# Widget 走 pbxproj 变量。只改一侧会出"主 App 停在旧 build 号"的包，云端照常构建成功。
#
# **放在最后（2026-11-02 挪位）**：它原先排在四路类型检查之后、测试目标之前。后果是
# **版本号没升就当场 exit，第五路（测试目标）根本不执行** —— 实测那一跑的日志里没有
# 「测试目标类型检查通过」，于是"App 四路绿 + 测试目标绿"这句话在那次跑里是没有证据的
# （这就是 `VERSION_GATE_ALLOW_REUSE=1` 这个本地逃生口存在的背景）。
# 挪到最后之后：任何一次跑都会把四条检查 + 第五路走完再判版本，读数不再依赖逃生口。
# 判定语义不变（该红还是红，退出码还是 1），只是"谁先报"变了。
if ! "$PYTHON3" "$(dirname "${BASH_SOURCE[0]}")/verify-version-consistency.py"; then
  echo "   版本号必须同时改：Resources/Info.plist 的 CFBundleVersion + pbxproj 两处 CURRENT_PROJECT_VERSION"
  exit 1
fi

echo
if [ "$APP_ERRORS" -eq 0 ] && [ "$DEBUG_ERRORS" -eq 0 ] \
   && [ "$WIDGET_ERRORS" -eq 0 ] && [ "$WIDGET_DEBUG_ERRORS" -eq 0 ]; then
  echo "✅ 类型检查通过 (App 0 错误, App(Debug) 0 错误, Widget 0 错误, Widget(Debug) 0 错误)"
  rm -f "$LOG" "$DLOG" "$WLOG" "$WDLOG"
  exit 0
fi

echo "❌ 类型检查失败: App $APP_ERRORS 个错误, App(Debug) $DEBUG_ERRORS 个错误, Widget $WIDGET_ERRORS 个错误, Widget(Debug) $WIDGET_DEBUG_ERRORS 个错误"
echo
[ "$APP_ERRORS" -gt 0 ] && { echo "--- App ---"; grep -A2 "error:" "$LOG" | head -40; }
[ "$DEBUG_ERRORS" -gt 0 ] && { echo "--- App (Debug, -D DEBUG) ---"; grep -A2 "error:" "$DLOG" | head -40; }
[ "$WIDGET_ERRORS" -gt 0 ] && { echo "--- Widget ---"; grep -A2 "error:" "$WLOG" | head -20; }
[ "$WIDGET_DEBUG_ERRORS" -gt 0 ] && { echo "--- Widget (Debug, -D DEBUG) ---"; grep -A2 "error:" "$WDLOG" | head -20; }
echo
echo "完整日志: $LOG  $DLOG  $WLOG  $WDLOG"
exit 1
