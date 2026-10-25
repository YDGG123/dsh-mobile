import SwiftUI
import CoreGraphics
import os

/// 角色矢量定义（方案 C：手机端用电脑端同一份 path 数据直接画，不做光栅化）。
///
/// 数据来源：ds-bot `src/client/characters.js` 的 `SHAPES[id] = { size, path, layers, eyes }`，
/// 由发布端作为静态字段随快照下发（`avatar.glyph`）。同一份表，**手机端不许另造一份角色数据**。
///
/// 契约：
/// - `path`：主剪影（`d`），用角色 ink 色填充
/// - `layers`：叠加层，按顺序绘制。接受两种形态：
///   - 数组：`[{ "d": "…", "fill": "#fff", "opacity": 0.16 }]`（推荐）
///   - 字符串：电脑端的 SVG 片段 `<path d="…" fill="#000" fill-opacity=".14"/>…`（兼容）
/// - `eyes`：`[[cx, cy, w, h, tilt], …]`，按电脑端语义**从所有图层挖空**（露出底色）
/// - `size`：坐标系边长（电脑端固定 100）
///
/// 已知限制：电脑端 20 个角色的 d 只用到 `M/l/z`（已实测），解析器实现 M/L/H/V/Z
/// 的绝对与相对形式；遇到其它命令返回 nil 并记诊断日志，**不静默画错**。
struct TeamAvatarGlyph: Hashable {
    struct Layer: Hashable {
        let d: String
        let fill: String
        let opacity: Double
    }

    struct Eye: Hashable {
        let cx: CGFloat
        let cy: CGFloat
        let width: CGFloat
        let height: CGFloat
        let tilt: CGFloat
    }

    let size: CGFloat
    let body: String
    let layers: [Layer]
    let eyes: [Eye]
    /// 可选的 ink key / hex。**绘制时不单独使用**：主剪影色取 roster 的 `color`
    /// （两者同源，DSBotDev 发布时都来自 `lookOf`）；保留只为契约完整与排障对拍。
    let color: String?

    private static let log = Logger(subsystem: "ai.dsh.mobile.ios", category: "team-glyph")

    init?(json: Any) {
        guard let object = json as? [String: Any] else {
            Self.log.error("glyph 不是对象，忽略")
            return nil
        }
        let rawSize = (object["size"] as? NSNumber)?.doubleValue ?? 100
        guard rawSize > 0 else {
            Self.log.error("glyph.size 非法，忽略")
            return nil
        }
        guard let body = (object["path"] as? String) ?? (object["body"] as? String), !body.isEmpty else {
            Self.log.error("glyph 缺少 path，忽略")
            return nil
        }
        // 解析失败 = 数据有问题：宁可回落到位图/符号，也不画一条错误的轮廓。
        guard SVGPath.path(from: body) != nil else {
            Self.log.error("glyph.path 解析失败（不支持的命令或非法数字），回落")
            return nil
        }
        guard let layers = Self.decodeLayers(object["layers"]) else {
            Self.log.error("glyph.layers 形态非法，忽略整份 glyph")
            return nil
        }
        let eyes = Self.decodeEyes(object["eyes"])
        self.init(size: CGFloat(rawSize), body: body, layers: layers, eyes: eyes,
                  color: object["color"] as? String)
    }

    init(size: CGFloat, body: String, layers: [Layer], eyes: [Eye], color: String?) {
        self.size = size
        self.body = body
        self.layers = layers
        self.eyes = eyes
        self.color = color
    }

    /// 内容哈希：用来做解析缓存键（换角色/换图自动失效）。FNV-1a 双轮，成本 O(n)、n 只有几 KB。
    var cacheKey: String {
        var hash: UInt64 = 0xcbf29ce484222325
        func feed(_ text: String) {
            for byte in text.utf8 { hash = (hash ^ UInt64(byte)) &* 0x100000001b3 }
        }
        feed(body)
        for layer in layers { feed(layer.d); feed(layer.fill); feed(String(layer.opacity)) }
        for eye in eyes { feed("\(eye.cx),\(eye.cy),\(eye.width),\(eye.height),\(eye.tilt)") }
        feed("\(size)")
        return String(hash, radix: 36)
    }

    // MARK: - 解码

    private static func decodeLayers(_ value: Any?) -> [Layer]? {
        guard let value else { return [] }
        if let list = value as? [[String: Any]] {
            return list.compactMap { item in
                guard let d = item["d"] as? String, !d.isEmpty, SVGPath.path(from: d) != nil else {
                    log.error("glyph.layers 里有无法解析的 d，跳过该层")
                    return nil
                }
                return Layer(d: d,
                             fill: item["fill"] as? String ?? "#000000",
                             opacity: (item["opacity"] as? NSNumber)?.doubleValue ?? 1)
            }
        }
        if let fragment = value as? String {
            return layers(fromSVGFragment: fragment)
        }
        log.error("glyph.layers 既不是数组也不是字符串，忽略整份 glyph")
        return nil
    }

    /// 兼容电脑端直接拼出来的 SVG 片段：`<path d="…" fill="#000" fill-opacity=".14"/>`
    static func layers(fromSVGFragment fragment: String) -> [Layer] {
        var result: [Layer] = []
        let pattern = #"<path\s+([^>]*?)/?>"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return result }
        let range = NSRange(fragment.startIndex..., in: fragment)
        for match in regex.matches(in: fragment, range: range) {
            guard let attrRange = Range(match.range(at: 1), in: fragment) else { continue }
            let attributes = String(fragment[attrRange])
            guard let d = attribute("d", in: attributes), SVGPath.path(from: d) != nil else { continue }
            result.append(Layer(d: d,
                                fill: attribute("fill", in: attributes) ?? "#000000",
                                opacity: Double(attribute("fill-opacity", in: attributes) ?? "1") ?? 1))
        }
        return result
    }

    private static func attribute(_ name: String, in attributes: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: #"\#(name)\s*=\s*"([^"]*)""#) else { return nil }
        let range = NSRange(attributes.startIndex..., in: attributes)
        guard let match = regex.firstMatch(in: attributes, range: range),
              let valueRange = Range(match.range(at: 1), in: attributes) else { return nil }
        return String(attributes[valueRange])
    }

    private static func decodeEyes(_ value: Any?) -> [Eye] {
        guard let list = value as? [[Any]] else { return [] }
        return list.compactMap { item in
            let numbers = item.compactMap { ($0 as? NSNumber)?.doubleValue }
            guard numbers.count >= 4 else { return nil }
            return Eye(cx: CGFloat(numbers[0]), cy: CGFloat(numbers[1]),
                       width: CGFloat(numbers[2]), height: CGFloat(numbers[3]),
                       tilt: CGFloat(numbers.count > 4 ? numbers[4] : 0))
        }
    }

    // MARK: - 绘制（Path 解析结果按内容哈希缓存）

    struct Rendered {
        let body: Path
        let layers: [(path: Path, color: Color, opacity: Double)]
        let eyes: [RenderedEye]
    }

    /// 眼睛保留「自身坐标系」而不是预先烘焙成画布坐标的 Path：
    /// 眨眼/眯眼要沿**胶囊自己**的短轴压扁（电脑端 `.bt-eye{scale:1 0}` 的语义），
    /// 若先旋转再压画布 Y 轴，斜置的眼睛会被压歪。
    struct RenderedEye {
        /// 以眼睛中心为原点、未旋转的圆角胶囊。
        let shape: Path
        let cx: CGFloat
        let cy: CGFloat
        let tilt: CGFloat
        /// 胶囊几何。视图层改用 SwiftUI 原生视图画眼睛（Canvas 在 `@State` 变化时
        /// 不保证重绘，「眨眼看不见」就是这么来的），所以宽高也要带出来。
        let width: CGFloat
        let height: CGFloat

        var radius: CGFloat { min(width, height) / 2 }
    }

    private final class RenderedBox: NSObject {
        let value: Rendered
        init(_ value: Rendered) { self.value = value }
    }

    /// 解析缓存：SwiftUI 每次 body 求值都会问头像，`d → Path` 不该重复做。
    private static let renderCache: NSCache<NSString, RenderedBox> = {
        let cache = NSCache<NSString, RenderedBox>()
        cache.countLimit = 64   // 20 个角色 × 两份快照，够用
        return cache
    }()

    /// 内部诊断（非静默）：解析失败会走这里，再由调用方回落到位图/符号。
    private static let renderLog = Logger(subsystem: "ai.dsh.mobile.ios", category: "team-glyph")

    var canRender: Bool { Self.rendered(for: self) != nil }

    static func rendered(for glyph: TeamAvatarGlyph) -> Rendered? {
        let key = NSString(string: glyph.cacheKey)
        if let cached = renderCache.object(forKey: key) { return cached.value }
        guard let body = SVGPath.path(from: glyph.body) else {
            renderLog.error("glyph 主剪影解析失败，回落位图/符号")
            return nil
        }
        var layers: [(Path, Color, Double)] = []
        for layer in glyph.layers {
            guard let path = SVGPath.path(from: layer.d) else {
                // 单层坏掉不至于整只角色不可用：跳过并留痕。
                renderLog.error("glyph 叠加层解析失败（fill=\(layer.fill, privacy: .public)），跳过该层")
                continue
            }
            if let color = Color(hexString: layer.fill) {
                layers.append((path, color, layer.opacity))
            } else {
                // 非法色值不静默：留痕并用黑色兜底（与电脑端 SVG 的缺省行为一致）。
                renderLog.error("glyph 层 fill 不是合法 hex（\(layer.fill, privacy: .public)），用黑色兜底")
                layers.append((path, .black, layer.opacity))
            }
        }
        let eyes = glyph.eyes.map { eye -> RenderedEye in
            let rect = CGRect(x: -eye.width / 2, y: -eye.height / 2, width: eye.width, height: eye.height)
            return RenderedEye(shape: Path(roundedRect: rect, cornerRadius: min(eye.width, eye.height) / 2),
                               cx: eye.cx, cy: eye.cy, tilt: eye.tilt,
                               width: eye.width, height: eye.height)
        }
        let rendered = Rendered(body: body, layers: layers, eyes: eyes)
        renderCache.setObject(RenderedBox(rendered), forKey: key)
        return rendered
    }

    /// 眼睛在画布里的最终形状：先在自身坐标系按 `squash` 压扁（眨眼/眯眼），再旋转、平移。
    /// 纯函数，便于单测（不依赖 GraphicsContext）。
    static func eyePath(_ eye: RenderedEye, squash: CGFloat) -> Path {
        let clamped = min(max(squash, 0.01), 1)
        let transform = CGAffineTransform(scaleX: 1, y: clamped)
            .concatenating(CGAffineTransform(rotationAngle: eye.tilt * .pi / 180))
            .concatenating(CGAffineTransform(translationX: eye.cx, y: eye.cy))
        return eye.shape.applying(transform)
    }

    /// 在 `size × size` 的框里画角色（调用方负责圆形裁剪）。
    /// - `bodyColor`：主剪影色（roster 的 `color`）
    /// - `eyeColor`：眼睛露出的颜色。电脑端是 mask 挖洞，露的是**页面背景**，
    ///   所以调用方必须传头像实际所在的背景色（团队页是 List 行底、工作区是页面底），
    ///   不能传淡色圆底 —— 那会让眼睛和圆底同色，实测"看不见"。
    /// - `eyeSquash`：1 = 正常；0.1 左右 = 眨眼压成一条缝；~0.7 = 思考眯眼。
    /// - `drawEyes`：是否在 Canvas 里画眼。团队头像**关掉**它，改由 SwiftUI 原生视图画
    ///   （可动画、可被 withAnimation 驱动）；其它一次性绘制的场景保持默认 true。
    func draw(in context: inout GraphicsContext, canvasSize: CGSize,
              bodyColor: Color, eyeColor: Color = .clear, eyeSquash: CGFloat = 1,
              drawEyes: Bool = true) {
        guard let rendered = Self.rendered(for: self) else { return }
        let scale = min(canvasSize.width, canvasSize.height) / max(size, 1)
        context.scaleBy(x: scale, y: scale)
        context.fill(rendered.body, with: .color(bodyColor))
        for layer in rendered.layers {
            context.fill(layer.path, with: .color(layer.color.opacity(layer.opacity)))
        }
        guard drawEyes else { return }
        // 眼睛：电脑端是 mask 挖洞，而 `<g mask>` 罩住的是 body + 全部 layers
        // （`characters.js:95-97` 的 bodyOf 把 layers 一起包进去），所以 shade 与
        // light 高光层同样会被挖掉 —— 这里「最后画眼」等价于同一效果。
        for eye in rendered.eyes {
            context.fill(Self.eyePath(eye, squash: eyeSquash), with: .color(eyeColor))
        }
    }
}

// MARK: - SVG path 子集解析

/// 只实现电脑端角色数据实际用到的命令：`M/m L/l H/h V/v Z/z`。
/// 其它命令（C/S/Q/T/A）返回 nil —— 数据里出现就意味着发布端换了画法，
/// 必须显式报错而不是猜一条错的曲线。
enum SVGPath {
    static func path(from d: String) -> Path? {
        var scanner = Scanner(text: d)
        var path = Path()
        var current = CGPoint.zero
        var subpathStart = CGPoint.zero
        var hasPoint = false

        while true {
            guard let command = scanner.nextCommand() else { break }
            switch command {
            case "M", "m":
                guard var point = scanner.nextPoint() else { return nil }
                if command == "m" {
                    point = CGPoint(x: current.x + point.x, y: current.y + point.y)
                }
                path.move(to: point)
                current = point
                subpathStart = point
                hasPoint = true
                // M 之后的坐标对按规范当作隐式 lineto
                while let next = scanner.peekNumber(), let pair = scanner.nextPoint() {
                    _ = next
                    let target = command == "m"
                        ? CGPoint(x: current.x + pair.x, y: current.y + pair.y)
                        : pair
                    path.addLine(to: target)
                    current = target
                }
            case "L", "l":
                guard hasPoint, let point = scanner.nextPoint() else { return nil }
                let target = command == "l"
                    ? CGPoint(x: current.x + point.x, y: current.y + point.y)
                    : point
                path.addLine(to: target)
                current = target
                while scanner.peekNumber() != nil, let pair = scanner.nextPoint() {
                    let next = command == "l"
                        ? CGPoint(x: current.x + pair.x, y: current.y + pair.y)
                        : pair
                    path.addLine(to: next)
                    current = next
                }
            case "H", "h":
                guard hasPoint, let number = scanner.nextNumber() else { return nil }
                let x = command == "h" ? current.x + CGFloat(number) : CGFloat(number)
                let target = CGPoint(x: x, y: current.y)
                path.addLine(to: target)
                current = target
            case "V", "v":
                guard hasPoint, let number = scanner.nextNumber() else { return nil }
                let y = command == "v" ? current.y + CGFloat(number) : CGFloat(number)
                let target = CGPoint(x: current.x, y: y)
                path.addLine(to: target)
                current = target
            case "Z", "z":
                path.closeSubpath()
                current = subpathStart
            default:
                return nil
            }
        }
        return path.isEmpty ? nil : path
    }

    private struct Scanner {
        private let characters: [Character]
        private var index = 0

        init(text: String) { characters = Array(text) }

        mutating func nextCommand() -> Character? {
            skipSeparators()
            while index < characters.count {
                let c = characters[index]
                if c.isLetter {
                    index += 1
                    return c
                }
                if c.isNumber || c == "-" || c == "+" || c == "." { return nil }
                index += 1
            }
            return nil
        }

        mutating func peekNumber() -> Double? {
            let saved = index
            let value = nextNumber()
            index = saved
            return value
        }

        mutating func nextPoint() -> CGPoint? {
            guard let x = nextNumber(), let y = nextNumber() else { return nil }
            return CGPoint(x: x, y: y)
        }

        mutating func nextNumber() -> Double? {
            skipSeparators()
            var text = ""
            if index < characters.count, characters[index] == "-" || characters[index] == "+" {
                text.append(characters[index]); index += 1
            }
            var seenDot = false
            while index < characters.count {
                let c = characters[index]
                if c.isNumber { text.append(c); index += 1 }
                else if c == ".", !seenDot { seenDot = true; text.append(c); index += 1 }
                else { break }
            }
            guard !text.isEmpty, text != "-", text != "+", text != "." else { return nil }
            return Double(text)
        }

        private mutating func skipSeparators() {
            while index < characters.count, characters[index] == " " || characters[index] == ","
                || characters[index] == "\n" || characters[index] == "\t" || characters[index] == "\r" {
                index += 1
            }
        }
    }
}

// MARK: - 颜色

extension Color {
    /// `#RGB` / `#RRGGBB`；解析失败返回 nil（调用方决定回落）。
    init?(hexString: String) {
        var text = hexString.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if text.hasPrefix("#") { text.removeFirst() }
        if text.count == 3 { text = text.map { "\($0)\($0)" }.joined() }
        guard text.count == 6, let value = UInt32(text, radix: 16) else { return nil }
        self.init(red: Double((value >> 16) & 0xFF) / 255,
                  green: Double((value >> 8) & 0xFF) / 255,
                  blue: Double(value & 0xFF) / 255)
    }
}

// MARK: - 眼睛取色（电脑端是「挖洞透底」，手机端要保证洞看得见）

/// 0–1 的 sRGB 颜色，只用于眼睛取色计算（纯数据，便于单测）。
struct RGBColor: Equatable {
    var red: Double
    var green: Double
    var blue: Double

    static let black = RGBColor(red: 0, green: 0, blue: 0)

    /// WCAG 相对亮度。
    var luminance: Double {
        func channel(_ value: Double) -> Double {
            value <= 0.03928 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
    }

    func contrast(with other: RGBColor) -> Double {
        let a = luminance, b = other.luminance
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    func scaled(by factor: Double) -> RGBColor {
        RGBColor(red: red * factor, green: green * factor, blue: blue * factor)
    }

    /// 朝 `other` 线性混色（amount 0 = 自己，1 = other）。
    func mixed(with other: RGBColor, amount: Double) -> RGBColor {
        let t = min(max(amount, 0), 1)
        return RGBColor(red: red + (other.red - red) * t,
                        green: green + (other.green - green) * t,
                        blue: blue + (other.blue - blue) * t)
    }
}

/// 电脑端的眼睛是 **mask 挖空**（`client.js` 里 `fill="#000"` 只是遮罩的裁剪色），洞透出的是
/// 容器底色；手机端等效做法就是把洞填成容器底色。但透底本身不保证看得见，两头的失败案例都实测过：
/// - 浅色底 + 亮车身：yellow 1.75:1、green 2.08:1、cyan 2.11:1（10/19 个不合格）
/// - 深色底 + 暗车身：charcoal 1.02–1.08:1（近黑车身在深底上同样等于没有眼睛）
/// 所以这里加一条下限，且**方向按车身亮度选**：车身暗就把洞往白提，车身亮就往黑压，
/// 直到与车身达到 minimum（只动亮度/明暗方向，不做换色）。
enum TeamAvatarEyeColor {
    /// 目标对比度下限。取 3:1（图形元素的可辨识下限），与列表里其他头像符号一致。
    static let minimumContrast: Double = 3
    /// 车身亮度低于此值视为「暗车身」，洞色应往白推而不是往黑压。
    static let darkBodyLuminance: Double = 0.18

    static func resolve(backdrop: RGBColor, body: RGBColor,
                        minimum: Double = minimumContrast) -> RGBColor {
        if backdrop.contrast(with: body) >= minimum { return backdrop }
        let white = RGBColor(red: 1, green: 1, blue: 1)
        // 暗车身 → 往白；亮车身 → 往黑。两个方向各自单调提升对比度。
        let target = body.luminance < darkBodyLuminance ? white : .black
        var amount = 0.1
        while amount <= 1.0 {
            let candidate = backdrop.mixed(with: target, amount: amount)
            if candidate.contrast(with: body) >= minimum { return candidate }
            amount += 0.1
        }
        return target
    }
}
