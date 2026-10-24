import SwiftUI
import XCTest
@testable import DeepSeekHarnessMobile

/// 头像/颜色**两端一致性**对拍（任务 2 的回归闸门）。
///
/// 夹具由 ds-bot 侧生成（`ds-bot/scripts/export-roster-look-fixture.mjs`，
/// 直接调用电脑端渲染与 roster 共用的 `lookOf()` / `accentInk()`），
/// 字段语义见该脚本头部。这里的断言全部是「手机端消费 = 电脑端产出」：
///   - 19 个 ink 的浅色/深色 hex 逐值相同（含 accentInk 白/近黑二选一）
///   - roster 给什么 shape 就显示什么符号，**不得自行推导**
///   - 缺字段时落中性块，不再按名字猜（最初「两端不一样」的成因）
@MainActor
final class RosterLookParityTests: XCTestCase {

    private struct RowFixture {
        let id: String
        let name: String
        let storedShape: String?
        let storedColor: String?
        let hostShape: String
        let hostColor: String
        let hostHex: String
        let darkHostHex: String
    }

    private struct InkFixture {
        let key: String
        let lightHex: String
        let darkHex: String
        let lightInk: String
        let darkInk: String
    }

    /// ds-bot 生成，勿手改；重跑脚本即可刷新。
    private static let rows: [RowFixture] = [
        .init(id: "ink-deepseek", name: "Ink deepseek", storedShape: nil, storedColor: "deepseek", hostShape: "peanut", hostColor: "deepseek", hostHex: "#4D6BFE", darkHostHex: "#6A84FF"),
        .init(id: "ink-charcoal", name: "Ink charcoal", storedShape: nil, storedColor: "charcoal", hostShape: "star", hostColor: "charcoal", hostHex: "#141416", darkHostHex: "#F1F2F4"),
        .init(id: "ink-graphite", name: "Ink graphite", storedShape: nil, storedColor: "graphite", hostShape: "acorn", hostColor: "graphite", hostHex: "#2D2D2D", darkHostHex: "#D6D6DA"),
        .init(id: "ink-rose", name: "Ink rose", storedShape: nil, storedColor: "rose", hostShape: "acorn", hostColor: "rose", hostHex: "#EC2F6B", darkHostHex: "#FF5A7A"),
        .init(id: "ink-sky", name: "Ink sky", storedShape: nil, storedColor: "sky", hostShape: "gumdrop", hostColor: "sky", hostHex: "#0AA8D6", darkHostHex: "#2EE6E0"),
        .init(id: "ink-tangerine", name: "Ink tangerine", storedShape: nil, storedColor: "tangerine", hostShape: "peanut", hostColor: "tangerine", hostHex: "#FF6900", darkHostHex: "#FF8A2E"),
        .init(id: "ink-cobalt", name: "Ink cobalt", storedShape: nil, storedColor: "cobalt", hostShape: "acorn", hostColor: "cobalt", hostHex: "#082DFF", darkHostHex: "#5C78FF"),
        .init(id: "ink-black", name: "Ink black", storedShape: nil, storedColor: "black", hostShape: "peanut", hostColor: "black", hostHex: "#000000", darkHostHex: "#FFFFFF"),
        .init(id: "ink-brown", name: "Ink brown", storedShape: nil, storedColor: "brown", hostShape: "gumdrop", hostColor: "brown", hostHex: "#A27952", darkHostHex: "#855C36"),
        .init(id: "ink-red", name: "Ink red", storedShape: nil, storedColor: "red", hostShape: "bunny", hostColor: "red", hostHex: "#FF3E51", darkHostHex: "#E02135"),
        .init(id: "ink-orange", name: "Ink orange", storedShape: nil, storedColor: "orange", hostShape: "gumdrop", hostColor: "orange", hostHex: "#FF781C", darkHostHex: "#FF6700"),
        .init(id: "ink-yellow", name: "Ink yellow", storedShape: nil, storedColor: "yellow", hostShape: "peanut", hostColor: "yellow", hostHex: "#FFAF38", darkHostHex: "#FF9800"),
        .init(id: "ink-green", name: "Ink green", storedShape: nil, storedColor: "green", hostShape: "peanut", hostColor: "green", hostHex: "#00C972", darkHostHex: "#009957"),
        .init(id: "ink-cyan", name: "Ink cyan", storedShape: nil, storedColor: "cyan", hostShape: "gumdrop", hostColor: "cyan", hostHex: "#1CC3B0", darkHostHex: "#00A592"),
        .init(id: "ink-blue", name: "Ink blue", storedShape: nil, storedColor: "blue", hostShape: "peanut", hostColor: "blue", hostHex: "#2A92FE", darkHostHex: "#0E74E0"),
        .init(id: "ink-violet", name: "Ink violet", storedShape: nil, storedColor: "violet", hostShape: "acorn", hostColor: "violet", hostHex: "#A97EFE", darkHostHex: "#804EE0"),
        .init(id: "ink-magenta", name: "Ink magenta", storedShape: nil, storedColor: "magenta", hostShape: "star", hostColor: "magenta", hostHex: "#FF5EB1", darkHostHex: "#E02A88"),
        .init(id: "ink-gray", name: "Ink gray", storedShape: nil, storedColor: "gray", hostShape: "acorn", hostColor: "gray", hostHex: "#959595", darkHostHex: "#777777"),
        .init(id: "ink-aurora", name: "Ink aurora", storedShape: nil, storedColor: "aurora", hostShape: "kitty", hostColor: "aurora", hostHex: "#00B8D4", darkHostHex: "#2EE6E0"),
        .init(id: "a", name: "Bot a", storedShape: nil, storedColor: nil, hostShape: "gumdrop", hostColor: "cyan", hostHex: "#1CC3B0", darkHostHex: "#00A592"),
        .init(id: "b", name: "Bot b", storedShape: nil, storedColor: nil, hostShape: "peanut", hostColor: "yellow", hostHex: "#FFAF38", darkHostHex: "#FF9800"),
        .init(id: "c", name: "Bot c", storedShape: nil, storedColor: nil, hostShape: "moon", hostColor: "orange", hostHex: "#FF781C", darkHostHex: "#FF6700"),
        .init(id: "chief", name: "Bot chief", storedShape: nil, storedColor: nil, hostShape: "star", hostColor: "blue", hostHex: "#2A92FE", darkHostHex: "#0E74E0"),
        .init(id: "coder", name: "Bot coder", storedShape: nil, storedColor: nil, hostShape: "peanut", hostColor: "blue", hostHex: "#2A92FE", darkHostHex: "#0E74E0"),
        .init(id: "tester", name: "Bot tester", storedShape: nil, storedColor: nil, hostShape: "moon", hostColor: "magenta", hostHex: "#FF5EB1", darkHostHex: "#E02A88"),
        .init(id: "bot-1", name: "Bot bot-1", storedShape: nil, storedColor: nil, hostShape: "gumdrop", hostColor: "blue", hostHex: "#2A92FE", darkHostHex: "#0E74E0"),
        .init(id: "bot-42", name: "Bot bot-42", storedShape: nil, storedColor: nil, hostShape: "moon", hostColor: "green", hostHex: "#00C972", darkHostHex: "#009957"),
        .init(id: "s1", name: "Stored s1", storedShape: "star", storedColor: "#123456", hostShape: "star", hostColor: "#123456", hostHex: "#123456", darkHostHex: "#123456"),
        .init(id: "s2", name: "Stored s2", storedShape: "acorn", storedColor: "magenta", hostShape: "acorn", hostColor: "magenta", hostHex: "#FF5EB1", darkHostHex: "#E02A88"),
    ]

    private static let inks: [InkFixture] = [
        .init(key: "deepseek", lightHex: "#4D6BFE", darkHex: "#6A84FF", lightInk: "#ffffff", darkInk: "#141414"),
        .init(key: "charcoal", lightHex: "#141416", darkHex: "#F1F2F4", lightInk: "#ffffff", darkInk: "#141414"),
        .init(key: "graphite", lightHex: "#2D2D2D", darkHex: "#D6D6DA", lightInk: "#ffffff", darkInk: "#141414"),
        .init(key: "rose", lightHex: "#EC2F6B", darkHex: "#FF5A7A", lightInk: "#141414", darkInk: "#141414"),
        .init(key: "sky", lightHex: "#0AA8D6", darkHex: "#2EE6E0", lightInk: "#141414", darkInk: "#141414"),
        .init(key: "tangerine", lightHex: "#FF6900", darkHex: "#FF8A2E", lightInk: "#141414", darkInk: "#141414"),
        .init(key: "cobalt", lightHex: "#082DFF", darkHex: "#5C78FF", lightInk: "#ffffff", darkInk: "#141414"),
        .init(key: "black", lightHex: "#000000", darkHex: "#FFFFFF", lightInk: "#ffffff", darkInk: "#141414"),
        .init(key: "brown", lightHex: "#A27952", darkHex: "#855C36", lightInk: "#141414", darkInk: "#ffffff"),
        .init(key: "red", lightHex: "#FF3E51", darkHex: "#E02135", lightInk: "#141414", darkInk: "#ffffff"),
        .init(key: "orange", lightHex: "#FF781C", darkHex: "#FF6700", lightInk: "#141414", darkInk: "#141414"),
        .init(key: "yellow", lightHex: "#FFAF38", darkHex: "#FF9800", lightInk: "#141414", darkInk: "#141414"),
        .init(key: "green", lightHex: "#00C972", darkHex: "#009957", lightInk: "#141414", darkInk: "#141414"),
        .init(key: "cyan", lightHex: "#1CC3B0", darkHex: "#00A592", lightInk: "#141414", darkInk: "#141414"),
        .init(key: "blue", lightHex: "#2A92FE", darkHex: "#0E74E0", lightInk: "#141414", darkInk: "#ffffff"),
        .init(key: "violet", lightHex: "#A97EFE", darkHex: "#804EE0", lightInk: "#141414", darkInk: "#ffffff"),
        .init(key: "magenta", lightHex: "#FF5EB1", darkHex: "#E02A88", lightInk: "#141414", darkInk: "#ffffff"),
        .init(key: "gray", lightHex: "#959595", darkHex: "#777777", lightInk: "#141414", darkInk: "#ffffff"),
        .init(key: "aurora", lightHex: "#00B8D4", darkHex: "#2EE6E0", lightInk: "#141414", darkInk: "#141414"),
    ]

    // MARK: - 19 色 × 浅/深

    func testInkPaletteMatchesComputerBothSchemes() {
        XCTAssertEqual(Self.inks.count, 19)
        for ink in Self.inks {
            XCTAssertEqual(GatewayTeamFeed.inkHex(ink.key, scheme: .light), ink.lightHex, "\(ink.key) 浅色")
            XCTAssertEqual(GatewayTeamFeed.inkHex(ink.key, scheme: .dark), ink.darkHex, "\(ink.key) 深色")
        }
    }

    func testSymbolInkMatchesComputerAccentRule() {
        for ink in Self.inks {
            XCTAssertEqual(
                GatewayTeamFeed.symbolInkHex(forAccentHex: ink.lightHex).uppercased(),
                ink.lightInk.uppercased(),
                "\(ink.key) 浅色符号墨色"
            )
            XCTAssertEqual(
                GatewayTeamFeed.symbolInkHex(forAccentHex: ink.darkHex).uppercased(),
                ink.darkInk.uppercased(),
                "\(ink.key) 深色符号墨色"
            )
        }
    }

    /// 白符号看不清的回归：至少证明「不是所有底色都用白」。
    func testAccentRuleDoesNotAlwaysPickWhite() {
        let picks = Set(Self.inks.flatMap { [$0.lightInk.uppercased(), $0.darkInk.uppercased()] })
        XCTAssertEqual(picks, ["#FFFFFF", "#141414"], "应当白/近黑两档都被选中")
    }

    // MARK: - 形状/颜色按 host 产出消费

    func testRosterLookIsConsumedVerbatim() {
        XCTAssertGreaterThanOrEqual(Self.rows.count, 29)
        for fixture in Self.rows {
            let row = makeRow(fixture)
            // 形状：host 给什么符号就展示什么，不参与任何推导
            XCTAssertEqual(
                GatewayTeamFeed.avatarSymbol(row),
                GatewayTeamFeed.shapeSymbol(fixture.hostShape),
                "\(fixture.id) 形状"
            )
            XCTAssertFalse(GatewayTeamFeed.avatarSymbol(row).isEmpty)
            // 颜色：浅色 = hostHex
            XCTAssertEqual(hex(GatewayTeamFeed.avatarColor(row)), normalize(fixture.hostHex), "\(fixture.id) 颜色(浅)")
        }
    }

    func testInkColorResolvesPerScheme() {
        for fixture in Self.rows {
            XCTAssertEqual(
                GatewayTeamFeed.inkColor(fixture.hostColor, scheme: .light).map(hex),
                normalize(fixture.hostHex),
                "\(fixture.id) 浅色解析"
            )
            XCTAssertEqual(
                GatewayTeamFeed.inkColor(fixture.hostColor, scheme: .dark).map(hex),
                normalize(fixture.darkHostHex),
                "\(fixture.id) 深色解析"
            )
        }
    }

    // MARK: - 20 个内置形状全覆盖（防止删分支/撞符号不被发现）

    /// ds-bot 电脑端的 20 个内置角色（`CHARACTERS` 的 key）。夹具只用到其中 7 个，
    /// 所以这里单独锁住映射表：每条都有专属符号、互不重复、未知 id 落默认。
    private static let builtinShapes = [
        "whale", "star", "moon", "wave", "squircle", "zed", "hex", "gumdrop", "peanut", "saucer",
        "bunny", "kitty", "beacon", "plus", "magnet", "acorn", "pebbles", "cushion", "ring", "gem",
    ]

    func testEveryBuiltInShapeHasItsOwnSymbol() {
        let fallback = GatewayTeamFeed.shapeSymbol(nil)
        var symbols: [String: String] = [:]
        for shape in Self.builtinShapes {
            let symbol = GatewayTeamFeed.shapeSymbol(shape)
            XCTAssertNotEqual(symbol, fallback, "\(shape) 落到了默认符号")
            XCTAssertFalse(symbol.isEmpty)
            symbols[symbol] = shape
        }
        XCTAssertEqual(symbols.count, Self.builtinShapes.count, "存在两个形状共用同一符号：\(symbols)")
        // 未知形状必须是中性默认，不许猜
        XCTAssertEqual(GatewayTeamFeed.shapeSymbol("unknown-shape"), fallback)
        XCTAssertEqual(GatewayTeamFeed.shapeSymbol(nil), fallback)
    }

    // MARK: - 缺失字段不得按名字推导（最初 bug）

    func testMissingLookFallsBackNeutralNotByName() {
        let first = GatewayTeamFeed.Row(id: "bot-a", kind: .bot, name: "同名 Bot", role: nil, sessionId: "bot-a")
        let second = GatewayTeamFeed.Row(id: "bot-b", kind: .bot, name: "同名 Bot", role: nil, sessionId: "bot-b")
        XCTAssertEqual(GatewayTeamFeed.avatarSymbol(first), GatewayTeamFeed.avatarSymbol(second))
        XCTAssertEqual(GatewayTeamFeed.avatarSymbol(first), GatewayTeamFeed.shapeSymbol(nil))
        XCTAssertEqual(hex(GatewayTeamFeed.avatarColor(first)), hex(GatewayTeamFeed.neutralBlockColor))
        XCTAssertEqual(hex(GatewayTeamFeed.avatarColor(second)), hex(GatewayTeamFeed.neutralBlockColor))
    }

    // MARK: - helpers

    private func makeRow(_ fixture: RowFixture) -> GatewayTeamFeed.Row {
        GatewayTeamFeed.Row(
            id: fixture.id,
            kind: .bot,
            name: fixture.name,
            role: nil,
            sessionId: fixture.id,
            avatarShape: fixture.hostShape,
            colorKey: fixture.hostColor
        )
    }

    private func normalize(_ hex: String) -> String {
        let body = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        return "#" + body.uppercased()
    }

    private func hex(_ color: Color) -> String {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        UIColor(color).getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        return String(format: "#%02X%02X%02X",
                      Int((red * 255).rounded()),
                      Int((green * 255).rounded()),
                      Int((blue * 255).rounded()))
    }
}
