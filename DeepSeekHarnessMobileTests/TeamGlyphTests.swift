import SwiftUI
import XCTest
@testable import DeepSeekHarnessMobile

/// 方案 C 绘制器的红绿用例：解析 → 逐层渲染 → eyes 坐标。
/// 数据取自电脑端 `SHAPES.whale`（同一张表），不是手画的新数据。
final class TeamGlyphTests: XCTestCase {

    /// 电脑端 SHAPES.whale 原样数据（DSBotDev 发布的 glyph 格式：layers 是结构化数组）。
    private static let whaleJSON: [String: Any] = [
        "shape": "whale", "color": "deepseek", "size": 100,
        "path": "M65.3 20.9l5.1 .1 4.8 .9 4.8 1.7 4.3 2.3 3.8 3.1 3 3.4 2.3 3.8 1.6 4.3 .9 4.3 0 4.8-.8 4.8-1.4 4.3-2.3 4.3-3.2 4.3-3.4 3.3-4.3 3.1-7.2 3.5-7.6 1.7-7.2 .1-9.6-1.6-3.3-1-2.9-1.7-8.8-8.9-6-4.7-3.3-.5-7.7 1.1-4.8-.1-4.7-1.2-1.9-1.2-1-1-.5-1.5 .4-1.4 1.1-1.3 1.9-1.3 4.7-1.5 7.7-.6 1.1-.5 .7-1 0-1.4-2.8-7.2-1.2-5.3-.1-4.3 .4-1.4 .9-1.2 1.9-.2 2 1 2.2 2.3 2.2 3.4 3.2 7.6 1.9 9.7 .5-.1 1.1-7.2 1.8-4.8 3.2-5.2 3.9-4.3 4.9-3.9 5.6-2.9 5.8-1.9z",
        "layers": [[String: Any]]([
                ["d": "M93.3 55.8l1-.3 .1 1.2-2.4 5.3-3.4 4.8-3.8 3.8-4.8 3.4-5.7 2.8-4.8 1.5-4.8 .4 20.1-17.4 5.2-3.7z", "fill": "#fff", "opacity": 1.0],
                ["d": "M88.6 29.5l2.4 2.9 1.9 2.8 2.4 6.3 .5 3.3 .1 5.3-1.5 6.6-1.9 4.3-2 3.3-4.8 5.4-5.7 4.3-7.2 3.3-6.2 1.5-6.7 .1-3.3-.2-9.6-1.8-3.3-1.5-2.4-1.9-8.6-8.9-5.3-3.9-3.3-.1-2.9 .2-3.3 .8-5.8 0-4.3-1-2.9-1.9 .6-.6 3.8-1.3 10.5-2.5 4.3 .9 1.4-.1 2.2-1.7 .5-1.4-.3-1.5-1.2-1.4-2.2-1.4-1.2-1.9-.6-12 .4-3.3 .5-.5 2.9 3.8 2.4 4.8 1.9 5.7 .7 5.8 .7 2.8 2.1 2.4 2.7 5.5 3.8 4.3 2.9 2.2 3.3 1 3.4-.4 6.2-3 7.1-4.5 7.3-5.6 6.6-5.9 5.9-6.5 3.7-5.3 1.7-4.2 .1-2.9z", "fill": "#000", "opacity": 0.14],
                ["d": "M55.6 22.8l1.9-.3 1.5 .3 .6 1 .1 .9-2 4.3-3.8 5.3-5.8 6.2-5.4 4.4-4.8 2.3-1.4 0-1.2-.5-.8-1.9 .5-3.3 2.5-4.8 3.3-4.3 2.4-2.4 5.3-3.9zM19.3 28.1l-1.4 2 .3-1.1zM21.7 49.1l.6 .5-.3 .5-.8 0zM35.8 52l1.2-.4 .9 .7 .7 1.6-.4 .9-1.2 .1-2.6-.5zM7.2 52.9l-2.9 2.9 1.2-1.6z", "fill": "#fff", "opacity": 0.16],
        ]),
        "eyes": [[72.5, 48.4, 9.3, 23.0, -15.8], [85.6, 43.4, 7.0, 22.9, -21.2]],
    ]

    // MARK: - 解析

    func testParsesComputerGlyph() throws {
        let glyph = try XCTUnwrap(TeamGlyph(json: Self.whaleJSON), "whale glyph 应能解析")
        XCTAssertEqual(glyph.size, 100)
        XCTAssertEqual(glyph.layers.count, 3, "whale 有 3 层：decal + shade + light")
        XCTAssertEqual(glyph.eyes.count, 2)
        XCTAssertEqual(glyph.color, "deepseek")
        // 层顺序即绘制顺序：decal(#fff) → shade(#000, .14) → light(#fff, .16)
        XCTAssertEqual(glyph.layers.map(\.fill), ["#fff", "#000", "#fff"])
        XCTAssertEqual(glyph.layers.count, 3)
        for (index, expected) in [1.0, 0.14, 0.16].enumerated() {
            XCTAssertEqual(glyph.layers[index].opacity, expected, accuracy: 0.001)
        }
    }

    func testRejectsUnparsableBody() {
        var broken = Self.whaleJSON
        broken["path"] = "C 1 2 3 4 5 6"   // 数据里没有的命令：宁可回落，也不猜曲线
        XCTAssertNil(TeamGlyph(json: broken))
    }

    func testRejectsNonObject() {
        XCTAssertNil(TeamGlyph(json: "not-an-object"))
        XCTAssertNotNil(TeamGlyph(json: ["path": "M0 0"]), "缺 size 用默认 100，path 合法即可用")
    }

    // MARK: - SVG path 子集

    func testAbsoluteAndRelativeCommands() throws {
        let path = try XCTUnwrap(SVGPath.path(from: "M0 0L100 0L100 100Z"))
        XCTAssertEqual(path.boundingRect.width, 100, accuracy: 0.001)
        XCTAssertEqual(path.boundingRect.height, 100, accuracy: 0.001)

        let relative = try XCTUnwrap(SVGPath.path(from: "m10 10 l5 0 l0 5 z"))
        XCTAssertEqual(relative.boundingRect.minX, 10, accuracy: 0.001)
        XCTAssertEqual(relative.boundingRect.width, 5, accuracy: 0.001)
        XCTAssertEqual(relative.boundingRect.height, 5, accuracy: 0.001)
    }

    func testHorizontalVerticalAndImplicitLine() throws {
        let hv = try XCTUnwrap(SVGPath.path(from: "M10 10H50V40Z"))
        XCTAssertEqual(hv.boundingRect, CGRect(x: 10, y: 10, width: 40, height: 30))

        // M 之后的坐标对按规范是隐式 lineto
        let implicit = try XCTUnwrap(SVGPath.path(from: "M0 0 10 0 10 10Z"))
        XCTAssertEqual(implicit.boundingRect, CGRect(x: 0, y: 0, width: 10, height: 10))
    }

    func testUnsupportedCommandReturnsNil() {
        XCTAssertNil(SVGPath.path(from: "M0 0C1 1 2 2 3 3"))
        XCTAssertNil(SVGPath.path(from: ""))
    }

    // MARK: - 兼容：layers 也可以是 SVG 片段字符串

    func testLayersFromSVGFragment() {
        let fragment = "<path d=\"M0 0L10 0L10 10Z\" fill=\"#000\" fill-opacity=\".14\"/><path d=\"M1 1L2 2Z\" fill=\"#fff\"/>"
        let layers = TeamGlyph.layers(fromSVGFragment: fragment)
        XCTAssertEqual(layers.count, 2)
        XCTAssertEqual(layers[0].fill, "#000")
        XCTAssertEqual(layers[0].opacity, 0.14, accuracy: 0.001)
        XCTAssertEqual(layers[1].opacity, 1, accuracy: 0.001)
    }

    // MARK: - 缓存键

    func testCacheKeyTracksContent() throws {
        let glyph = try XCTUnwrap(TeamGlyph(json: Self.whaleJSON))
        let same = try XCTUnwrap(TeamGlyph(json: Self.whaleJSON))
        XCTAssertEqual(glyph.cacheKey, same.cacheKey)

        var changedEyes = Self.whaleJSON
        changedEyes["eyes"] = [[1.0, 2.0, 3.0, 4.0, 0.0]]
        let other = try XCTUnwrap(TeamGlyph(json: changedEyes))
        XCTAssertNotEqual(glyph.cacheKey, other.cacheKey, "换眼睛/换层必须换缓存键")
    }

    // MARK: - 真实发布形态（glyph 在 bot 顶层）

    /// 契约与 ds-bot `team-feed.js` 都把 glyph 放在 bot **顶层**（与 avatar 平级）。
    /// 这条用例喂的是真实形态，能抓住「读成 avatar["glyph"] → row.glyph 恒 nil」的回归。
    @MainActor
    func testParsesGlyphFromBotTopLevel() throws {
        let feed = GatewayTeamFeed()
        let raw: [String: Any] = [
            "id": "bot-whale", "kind": "bot", "name": "Whale", "sessionId": "s-whale",
            "color": "deepseek",
            "avatar": ["shape": "whale"],
            "glyph": Self.whaleJSON,
        ]
        let row = try XCTUnwrap(feed.row(from: raw, kind: .bot))
        let glyph = try XCTUnwrap(row.glyph, "顶层 glyph 必须被解析（否则方案 C 静默失效）")
        XCTAssertEqual(glyph.layers.count, 3)
        XCTAssertEqual(glyph.eyes.count, 2)
    }

    /// 过渡兼容：早期把 glyph 塞进 avatar 里的形态也要能读。
    @MainActor
    func testParsesGlyphNestedInAvatar() throws {
        let feed = GatewayTeamFeed()
        let raw: [String: Any] = [
            "id": "bot-nested", "name": "Nested", "sessionId": "s-nested",
            "avatar": ["shape": "whale", "glyph": Self.whaleJSON],
        ]
        let row = try XCTUnwrap(feed.row(from: raw, kind: .bot))
        XCTAssertNotNil(row.glyph)
    }

    // MARK: - 三层兜底顺序（契约：image > glyph > symbol）

    func testFallbackOrder() {
        XCTAssertEqual(TeamAvatarSource.decide(hasImage: true, hasGlyph: true), .image)
        XCTAssertEqual(TeamAvatarSource.decide(hasImage: true, hasGlyph: false), .image)
        XCTAssertEqual(TeamAvatarSource.decide(hasImage: false, hasGlyph: true), .glyph)
        XCTAssertEqual(TeamAvatarSource.decide(hasImage: false, hasGlyph: false), .symbol)
    }
}
