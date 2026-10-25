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
        let glyph = try XCTUnwrap(TeamAvatarGlyph(json: Self.whaleJSON), "whale glyph 应能解析")
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
        XCTAssertNil(TeamAvatarGlyph(json: broken))
    }

    func testRejectsNonObject() {
        XCTAssertNil(TeamAvatarGlyph(json: "not-an-object"))
        XCTAssertNotNil(TeamAvatarGlyph(json: ["path": "M0 0"]), "缺 size 用默认 100，path 合法即可用")
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
        let layers = TeamAvatarGlyph.layers(fromSVGFragment: fragment)
        XCTAssertEqual(layers.count, 2)
        XCTAssertEqual(layers[0].fill, "#000")
        XCTAssertEqual(layers[0].opacity, 0.14, accuracy: 0.001)
        XCTAssertEqual(layers[1].opacity, 1, accuracy: 0.001)
    }

    // MARK: - 缓存键

    func testCacheKeyTracksContent() throws {
        let glyph = try XCTUnwrap(TeamAvatarGlyph(json: Self.whaleJSON))
        let same = try XCTUnwrap(TeamAvatarGlyph(json: Self.whaleJSON))
        XCTAssertEqual(glyph.cacheKey, same.cacheKey)

        var changedEyes = Self.whaleJSON
        changedEyes["eyes"] = [[1.0, 2.0, 3.0, 4.0, 0.0]]
        let other = try XCTUnwrap(TeamAvatarGlyph(json: changedEyes))
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

    // MARK: - 眼睛：眨眼沿自身短轴压扁（电脑端 .bt-eye{scale:1 0}）

    private func eye(tilt: CGFloat, cx: CGFloat = 50, cy: CGFloat = 50,
                     width: CGFloat = 10, height: CGFloat = 20) -> TeamAvatarGlyph.RenderedEye {
        let rect = CGRect(x: -width / 2, y: -height / 2, width: width, height: height)
        return TeamAvatarGlyph.RenderedEye(
            shape: Path(roundedRect: rect, cornerRadius: min(width, height) / 2),
            cx: cx, cy: cy, tilt: tilt, width: width, height: height)
    }

    /// 眼睛现在由原生 SwiftUI 视图画（Canvas 不保证随 @State 重绘，曾经导致「眨眼看不见」）。
    /// 这条钉住视图层的摆放换算：glyph 坐标 → 头像点坐标的缩放、尺寸、圆角、旋转角。
    func testEyeViewLayoutScalesGlyphGeometry() {
        let target = eye(tilt: -15.8, cx: 72.5, cy: 48.4, width: 9.3, height: 23)
        let scale: CGFloat = 0.46          // 46pt 头像 / 100 单位坐标系
        let layout = TeamAvatarView.eyeLayout(target, scale: scale)
        XCTAssertEqual(layout.center.x, 72.5 * 0.46, accuracy: 0.001)
        XCTAssertEqual(layout.center.y, 48.4 * 0.46, accuracy: 0.001)
        XCTAssertEqual(layout.size.width, 9.3 * 0.46, accuracy: 0.001)
        XCTAssertEqual(layout.size.height, 23 * 0.46, accuracy: 0.001)
        XCTAssertEqual(layout.radius, 9.3 / 2 * 0.46, accuracy: 0.001)
        XCTAssertEqual(layout.tilt, -15.8, accuracy: 0.001)
        // 小头像下界：不能出现 0 尺寸/0 圆角（0 尺寸视图可能根本不渲染）。
        let tiny = TeamAvatarView.eyeLayout(target, scale: 0.0001)
        XCTAssertGreaterThanOrEqual(tiny.size.width, 0.5)
        XCTAssertGreaterThanOrEqual(tiny.size.height, 0.5)
        XCTAssertGreaterThanOrEqual(tiny.radius, 0.25)
    }

    func testEyeSquashCompressesHeightAndKeepsCenter() {
        let target = eye(tilt: 0)
        let normal = TeamAvatarGlyph.eyePath(target, squash: 1).boundingRect
        let blink = TeamAvatarGlyph.eyePath(target, squash: 0.1).boundingRect

        XCTAssertEqual(normal.height, 20, accuracy: 0.001, "正常态就是胶囊高度")
        XCTAssertEqual(blink.height, 2, accuracy: 0.05, "squash=0.1 应压成 1/10 高度（一条缝，不是消失）")
        XCTAssertEqual(blink.width, normal.width, accuracy: 0.001, "只压竖轴，宽度不变")
        XCTAssertEqual(blink.midX, normal.midX, accuracy: 0.001, "中心不动")
        XCTAssertEqual(blink.midY, normal.midY, accuracy: 0.001)
        XCTAssertGreaterThan(blink.height, 0, "眨眼不能退化成 0 高度")
    }

    func testEyeSquashUsesOwnAxisForTiltedEyes() {
        // 斜置的眼睛：先压自身短轴再旋转，所以 90° 旋转后同一 squash 应体现在画布宽度上。
        let tilted = eye(tilt: 90)
        let normal = TeamAvatarGlyph.eyePath(tilted, squash: 1).boundingRect
        let blink = TeamAvatarGlyph.eyePath(tilted, squash: 0.1).boundingRect
        XCTAssertEqual(blink.width, 2, accuracy: 0.1, "旋转 90° 后压扁体现在横向")
        XCTAssertEqual(blink.height, normal.height, accuracy: 0.01)
    }

    func testEyeSquashIsClampedAndKeepsPosition() {
        let target = eye(tilt: -15.8, cx: 72.5, cy: 48.4)
        let squashed = TeamAvatarGlyph.eyePath(target, squash: 0).boundingRect
        XCTAssertGreaterThan(squashed.height, 0, "squash=0 被夹到下界，不产生退化图形")
        XCTAssertEqual(squashed.midX, 72.5, accuracy: 0.5)
        XCTAssertEqual(squashed.midY, 48.4, accuracy: 1.0)
        // 越界（>1）按 1 处理：不会把眼睛拉长。
        let over = TeamAvatarGlyph.eyePath(target, squash: 3).boundingRect
        let normal = TeamAvatarGlyph.eyePath(target, squash: 1).boundingRect
        XCTAssertEqual(over.height, normal.height, accuracy: 0.001)
    }

    /// 眼睛颜色由调用方按「所在容器背景色」给：电脑端是 mask 挖洞透底，不是黑色胶囊。
    /// 同时钉住动效档位：默认静止，只有团队页显式 .live。
    @MainActor
    func testEyeColorComesFromCallSiteBackdrop() throws {
        let feed = GatewayTeamFeed()
        let raw: [String: Any] = [
            "id": "bot-backdrop", "name": "Backdrop", "sessionId": "s-backdrop",
            "glyph": Self.whaleJSON,
        ]
        _ = try XCTUnwrap(feed.row(from: raw, kind: .bot))
        XCTAssertEqual(TeamAvatarMotion.still.isLive, false)
        XCTAssertEqual(TeamAvatarMotion.live(working: true).isWorking, true)
        XCTAssertEqual(TeamAvatarMotion.live(working: false).isWorking, false)
        XCTAssertEqual(TeamAvatarMotion.live(working: false).isLive, true)
    }

    // MARK: - 眼睛取色下限（浅色下 10/19 个车身透底后低于 3:1，必须压暗）

    /// 车身深、透底够清楚时不改色：deepseek 蓝 + 浅色页面底 ≈ 4.12:1。
    func testEyeColorKeepsBackdropWhenContrastIsEnough() {
        let backdrop = RGBColor(red: 0.961, green: 0.980, blue: 1.0)   // 工作区浅色页面底
        let deepseek = RGBColor(red: 0.302, green: 0.420, blue: 0.996) // #4D6BFE
        let resolved = TeamAvatarEyeColor.resolve(backdrop: backdrop, body: deepseek)
        XCTAssertEqual(resolved, backdrop, "对比 ≥3:1 时保持容器底色，不做任何偏移")
    }

    /// 亮车身（yellow #FFAF38）透浅底只有 1.75:1 —— 必须被压暗到 ≥3:1，且只能是变暗、不是换色相。
    func testEyeColorDarkensBrightBodyUntilReadable() {
        let backdrop = RGBColor(red: 0.961, green: 0.980, blue: 1.0)
        let yellow = RGBColor(red: 1.0, green: 0.686, blue: 0.220)
        XCTAssertLessThan(backdrop.contrast(with: yellow), 3, "前提：原始透底确实不合格")
        let resolved = TeamAvatarEyeColor.resolve(backdrop: backdrop, body: yellow)
        XCTAssertGreaterThanOrEqual(resolved.contrast(with: yellow), 3, "压暗后必须 ≥3:1")
        XCTAssertLessThan(resolved.red, backdrop.red, "方向是压暗（红通道也要变暗）")
        // 只压亮度、不改色相：三通道是同一个系数的等比缩放（洞仍保持容器底色的冷暖）。
        let scale = resolved.red / backdrop.red
        XCTAssertEqual(resolved.green / backdrop.green, scale, accuracy: 0.001, "绿通道同比缩放")
        XCTAssertEqual(resolved.blue / backdrop.blue, scale, accuracy: 0.001, "蓝通道同比缩放")
    }

    /// 深色底 + 近黑车身（charcoal 1.02:1）方向必须反过来：往白提，不能继续压暗。
    /// 这条是 QA 提「至少三种底」时暴露的：只会压暗的规则在深色主题下对暗车身无效。
    func testEyeColorLightensDarkBodyOnDarkBackdrop() {
        let darkRow = RGBColor(red: 0.110, green: 0.110, blue: 0.118)   // 深色 List 行底
        let charcoal = RGBColor(red: 0.078, green: 0.078, blue: 0.086)  // #141416
        XCTAssertLessThan(darkRow.contrast(with: charcoal), 3, "前提：近黑车身在深底上确实不合格")
        let resolved = TeamAvatarEyeColor.resolve(backdrop: darkRow, body: charcoal)
        XCTAssertGreaterThanOrEqual(resolved.contrast(with: charcoal), 3, "提亮后必须 ≥3:1")
        XCTAssertGreaterThan(resolved.luminance, darkRow.luminance, "方向是提亮，不是压暗")
    }

    /// 整张电脑端色板 × 浅/深两种容器底：任何组合取到的眼睛色都必须 ≥3:1（「看得见」的定义）。
    func testEyeColorMeetsContrastForEveryDesktopInk() {
        for backdrop in [RGBColor(red: 0.961, green: 0.980, blue: 1.0),
                         RGBColor(red: 0.110, green: 0.110, blue: 0.118)] {
            let inks: [(String, RGBColor)] = [
                ("deepseek", RGBColor(red: 0.302, green: 0.420, blue: 0.996)),
                ("charcoal", RGBColor(red: 0.078, green: 0.078, blue: 0.086)),
                ("rose", RGBColor(red: 0.925, green: 0.184, blue: 0.420)),
                ("sky", RGBColor(red: 0.039, green: 0.659, blue: 0.839)),
                ("tangerine", RGBColor(red: 1.0, green: 0.412, blue: 0.0)),
                ("black", RGBColor(red: 0, green: 0, blue: 0)),
                ("yellow", RGBColor(red: 1.0, green: 0.686, blue: 0.220)),
                ("green", RGBColor(red: 0.0, green: 0.788, blue: 0.447)),
                ("cyan", RGBColor(red: 0.110, green: 0.765, blue: 0.690)),
                ("violet", RGBColor(red: 0.663, green: 0.494, blue: 0.996)),
                ("gray", RGBColor(red: 0.584, green: 0.584, blue: 0.584)),
                ("aurora", RGBColor(red: 0.0, green: 0.722, blue: 0.831)),
            ]
            for (name, ink) in inks {
                let resolved = TeamAvatarEyeColor.resolve(backdrop: backdrop, body: ink)
                XCTAssertGreaterThanOrEqual(resolved.contrast(with: ink), 3,
                                            "\(name) 的眼睛对比不足 3:1（底 \(backdrop)）")
            }
        }
    }

    /// 相位错开：同一个 bot 永远拿到同一个偏移（可对拍），不同 bot 应错开，且落在 [0, period)。
    func testBlinkPhaseDelayIsStableAndStaggered() {
        let a = TeamAvatarView.blinkPhaseDelay(botID: "bot-alpha", period: 2.4)
        let b = TeamAvatarView.blinkPhaseDelay(botID: "bot-alpha", period: 2.4)
        XCTAssertEqual(a, b, "同一 bot 的相位必须稳定")
        XCTAssertGreaterThanOrEqual(a, 0)
        XCTAssertLessThan(a, 2.4)
        let phases = Set(["b1", "bot-alpha", "bot-beta", "main", "whale"].map {
            TeamAvatarView.blinkPhaseDelay(botID: $0, period: 2.4)
        })
        XCTAssertGreaterThan(phases.count, 1, "多个 bot 不应同相位（同时闭眼）")
    }

    // MARK: - 「头像不会动」回归守卫：入口条件 + 眨眼计划

    /// 用户真机反馈「头像不会动」的根因分类：
    /// - `.still`（工作区）本来就不该动 → 必须 false
    /// - `.live(working: false)`（团队页空闲）**电脑端 bt-blink-idle 会眨** → 必须 true
    /// - 减弱动态效果优先 → 必须 false
    func testShouldAnimateKeepsIdleAvatarsAlive() {
        XCTAssertFalse(TeamAvatarView.shouldAnimate(.still, reduceMotion: false),
                       "工作区头像不该挂定时器")
        XCTAssertTrue(TeamAvatarView.shouldAnimate(.live(working: false), reduceMotion: false),
                      "空闲头像必须动：电脑端空闲也走 bt-blink-idle")
        XCTAssertTrue(TeamAvatarView.shouldAnimate(.live(working: true), reduceMotion: false))
        XCTAssertFalse(TeamAvatarView.shouldAnimate(.live(working: false), reduceMotion: true),
                       "减弱动态效果优先于团队页")
        XCTAssertFalse(TeamAvatarView.shouldAnimate(.live(working: true), reduceMotion: true))
    }

    /// 眨眼计划必须与电脑端 keyframe 的百分比一致（直接由 CSS 换算，不是凭感觉）。
    func testBlinkStepsMatchDesktopKeyframes() {
        let idle = TeamAvatarView.blinkSteps(working: false)
        XCTAssertEqual(idle.reduce(0) { $0 + $1.hold }, 5.0, accuracy: 0.0001, "空闲周期 5s")

        var start = 0.0
        var closedAt: [Double] = []
        for step in idle {
            if step.squash == 0.03 { closedAt.append(start) }
            start += step.hold
        }
        XCTAssertEqual(closedAt.count, 2, "空闲周期眨两次")
        XCTAssertEqual(closedAt[0], 1.61, accuracy: 0.0001, "32.2% × 5s")
        XCTAssertEqual(closedAt[1], 3.59, accuracy: 0.0001, "71.8% × 5s")

        let working = TeamAvatarView.blinkSteps(working: true)
        XCTAssertEqual(working.reduce(0) { $0 + $1.hold }, 2.4, accuracy: 0.0001, "干活周期 2.4s")
        XCTAssertEqual(working.count, 4)
        XCTAssertEqual(working[1].squash, 0.03, "46% 压成一条缝")
        XCTAssertEqual(working[1].hold, 0.096, accuracy: 0.0001, "闭眼窗口 4% × 2.4s")
        XCTAssertEqual(working[2].squash, 1.06, "48% 回弹过冲不能丢")
    }

    /// 负延迟语义：相位落在最后一个档位中途时，应立即进入该档位而不是先空等一个相位长度。
    /// 这里用纯计算复核「跳段」逻辑的边界（View 里的循环与该算法同构）。
    func testBlinkPhaseSkipsWholeStepsAndKeepsRemainder() {
        let steps = TeamAvatarView.blinkSteps(working: false)
        // 落在第二次眨眼闭眼窗口内的相位：3.59 + 0.02
        let resume = TeamAvatarView.blinkResume(steps: steps, phase: 3.61)
        XCTAssertEqual(resume.index, 4, "整段跳过前 4 档（1.61 + 0.08 + 0.06 + 1.84）")
        XCTAssertEqual(resume.hold, 0.06, accuracy: 0.0001, "只等当前档位的剩余部分")

        let fromStart = TeamAvatarView.blinkResume(steps: steps, phase: 0)
        XCTAssertEqual(fromStart.index, 0, "相位 0 从第一档开始")
        XCTAssertEqual(fromStart.hold, 1.61, accuracy: 0.0001)
    }
}
