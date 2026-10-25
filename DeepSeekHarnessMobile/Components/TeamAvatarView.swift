import SwiftUI
import os

/// 团队头像统一渲染：**glyph → image → shape+color 符号** 三层兜底（方案 C）。
///
/// - image：位图 PNG/JPEG（用户上传优先）
/// - glyph：电脑端同一份矢量表，用 Path 直接画（100% 一致，无光栅化）
/// - 符号：SF Symbol 近似（最老数据，缺 shape 时是中性圆点）
///
/// 解析结果按 glyph 内容哈希缓存（`TeamAvatarGlyph.renderCache`），避免 SwiftUI 每次 body
/// 求值都重新把 `d` 解析成 Path。
/// 三层兜底的**纯判定**（便于单测；视图只负责画）。
enum TeamAvatarSource: Equatable {
    case image
    case glyph
    case symbol

    /// 契约顺序：用户照片 > 电脑端矢量 glyph > shape+color 符号。
    static func decide(hasImage: Bool, hasGlyph: Bool) -> TeamAvatarSource {
        if hasImage { return .image }
        if hasGlyph { return .glyph }
        return .symbol
    }
}

/// 头像动效档位。电脑端那套会动是 **CSS 表现层**（`styles.js`：blink / squint / fidget / gaze），
/// 不是发布数据；手机端只复刻性价比最高的两个：空闲眨眼、干活中眯眼。
///
/// 只在团队页开（列表里每个头像挂定时器），工作区等其他位置保持 `.still`。
enum TeamAvatarMotion: Equatable {
    /// 不驱动：非团队页，或用户开了「减弱动态效果」。
    case still
    /// 团队页：`working == nil` 时空闲眨眼；`working != nil` 时持续眯眼（电脑端 bt-squint）。
    case live(working: Bool)

    var isLive: Bool {
        if case .live = self { return true }
        return false
    }

    var isWorking: Bool {
        if case .live(let working) = self { return working }
        return false
    }
}

struct TeamAvatarView: View {
    let row: GatewayTeamFeed.Row
    let size: CGFloat
    /// 头像**实际所在容器**的背景色：电脑端眼睛是 mask 挖洞、透出页面背景，
    /// 等效实现就是用它填眼睛。传淡色圆底会让眼睛和圆底同色（实测「看不见」）。
    var backdrop: Color = Color(.systemGroupedBackground)
    /// 动效档位，默认不动（只有团队页显式开）。
    var motion: TeamAvatarMotion = .still

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
    /// 眼睛竖向压扁系数：1 = 正常，~0.1 = 眨眼成缝，~0.7 = 眯眼；>1 是电脑端的回弹过冲。
    @State private var eyeSquash: CGFloat = 1

    private static let log = Logger(subsystem: "ai.dsh.mobile.ios", category: "team-avatar")

    var body: some View {
        let image = GatewayTeamFeed.avatarImage(row)
        let glyph = row.glyph.flatMap { $0.canRender ? $0 : nil }
        Group {
            switch TeamAvatarSource.decide(hasImage: image != nil, hasGlyph: glyph != nil) {
            case .image:
                Image(uiImage: image!)
                    .resizable()
                    .scaledToFill()
                    .frame(width: size, height: size)
                    .clipShape(Circle())
            case .glyph:
                glyphAvatar(glyph!)
            case .symbol:
                symbolAvatar
            }
        }
        // `.task(id:)`：视图消失或档位变化（工作↔空闲、开关减弱动效）时自动取消，不会漏挂定时器。
        .task(id: motionKey) { await driveEyes() }
    }

    private var motionKey: String {
        "\(motion.isLive)-\(motion.isWorking)-\(reduceMotion)-\(row.id)"
    }

    /// 眼睛实际填色：容器底色（电脑端的洞透底），对比不足 3:1 时按 `TeamAvatarEyeColor` 压暗。
    private var eyeFill: Color {
        let resolved = TeamAvatarEyeColor.resolve(backdrop: Self.rgb(backdrop, colorScheme: colorScheme),
                                                  body: Self.rgb(GatewayTeamFeed.avatarColor(row), colorScheme: colorScheme))
        return Color(.sRGB, red: resolved.red, green: resolved.green, blue: resolved.blue, opacity: 1)
    }

    /// 把 SwiftUI 动态色解析成当前外观下的 sRGB 分量。
    private static func rgb(_ color: Color, colorScheme: ColorScheme) -> RGBColor {
        let traits = UITraitCollection(userInterfaceStyle: colorScheme == .dark ? .dark : .light)
        let resolved = UIColor(color).resolvedColor(with: traits)
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        guard resolved.getRed(&red, green: &green, blue: &blue, alpha: &alpha) else {
            return RGBColor(red: 0, green: 0, blue: 0)
        }
        return RGBColor(red: Double(red), green: Double(green), blue: Double(blue))
    }

    /// 有 glyph：淡色圆底 + 矢量化角色（与电脑端同路径、同色、同挖空眼睛）。
    private func glyphAvatar(_ glyph: TeamAvatarGlyph) -> some View {
        let ink = GatewayTeamFeed.avatarColor(row)
        let disc = ink.opacity(0.16)
        let eye = eyeFill
        return Circle()
            .fill(disc)
            .frame(width: size, height: size)
            .overlay {
                Canvas { context, canvasSize in
                    glyph.draw(in: &context, canvasSize: canvasSize,
                               bodyColor: ink, eyeColor: eye, eyeSquash: eyeSquash)
                }
                .frame(width: size, height: size)
            }
            .clipShape(Circle())
            .accessibilityHidden(true)
    }

    /// 一次眨眼周期里的档位序列：`hold` 是该档位保持的秒数（等价电脑端 keyframe 的百分比）。
    /// 数据照抄 `styles.js`，纯值便于单测（不依赖 View）。
    static func blinkSteps(working: Bool) -> [(squash: CGFloat, hold: Double)] {
        if working {
            // bt-blink 2.4s：42%(1.008s) 睁 → 46%(1.104s) 闭 → 48%(1.152s) 过冲 → 50%(1.2s) 回正
            return [(1, 1.008), (0.03, 0.096), (1.06, 0.048), (1, 1.248)]
        }
        // bt-blink-idle 5s 眨两次：32.2%(1.61s) 闭 → 33.8%(1.69s) 过冲 → 35%(1.75s) 回正
        //                         → 71.8%(3.59s) 闭 → 73.4%(3.67s) 过冲 → 74.6%(3.73s) 回正
        return [(1, 1.61), (0.03, 0.08), (1.06, 0.06), (1, 1.84),
                (0.03, 0.08), (1.06, 0.06), (1, 1.27)]
    }

    /// 是否驱动动效。**`.still` 不动**（工作区头像不给列表挂定时器），
    /// 但 `.live(working: false)` 的空闲头像**必须动** —— 电脑端空闲也走 `bt-blink-idle`，
    /// 这条断言就是「头像不会动」那个回归的守卫点（别再把它从入口条件里排除掉）。
    static func shouldAnimate(_ motion: TeamAvatarMotion, reduceMotion: Bool) -> Bool {
        motion.isLive && !reduceMotion
    }

    /// 眨眼驱动。相位按 bot id 固定错开（电脑端 `--bt-delay: -(fnv1a(botID) % 5000)/1000 s`）。
    /// 不引 TimelineView：列表里每个头像持续重绘不划算，离散档位已经等于 CSS 的瞬时压扁。
    ///
    /// 三个曾经让它「看着不动」的坑，这里都堵掉：
    /// 1. **@MainActor**：`.task` 的闭包是 `@Sendable`，不标主线程时 `@State` 写入可能不触发布局；
    /// 2. **相位语义**：电脑端是**负**延迟（从周期中途开始），不是先空等一个相位长度；
    /// 3. **前台判断**：读实时的 `applicationState`，不是 `.task` 启动时捕获的环境快照。
    /// 负延迟的等价计算：从周期的第 `phase` 秒开始时，应先跳过哪些档位、第一个要显示的档位还剩多久。
    /// 返回 `(首个要显示的档位下标, 该档位需保持的秒数)`。
    /// 抽成静态纯函数是为了让用例**直接调实现**，而不是在测试里重写一遍算法（否则实现漂移测试仍绿）。
    static func blinkResume(steps: [(squash: CGFloat, hold: Double)],
                            phase: Double) -> (index: Int, hold: Double) {
        guard let last = steps.last else { return (0, 0) }
        var remaining = max(phase, 0)
        for (index, step) in steps.enumerated() {
            if remaining >= step.hold { remaining -= step.hold; continue }
            return (index, step.hold - remaining)
        }
        // 相位越过整个周期（调用方保证不会）：退化成最后一档的完整时长。
        return (steps.count - 1, last.hold)
    }

    @MainActor
    private func driveEyes() async {
        guard Self.shouldAnimate(motion, reduceMotion: reduceMotion) else {
            eyeSquash = 1
            return
        }
        defer { eyeSquash = 1 }
        let steps = Self.blinkSteps(working: motion.isWorking)
        let period = steps.reduce(0) { $0 + $1.hold }
        // 首次从周期中途开始（对应负延迟），之后每轮都从第 0 档走完整个周期。
        var resume = Self.blinkResume(steps: steps,
                                      phase: Self.blinkPhaseDelay(botID: row.id, period: period))
        while !Task.isCancelled {
            for index in resume.index..<steps.count {
                let hold = index == resume.index ? resume.hold : steps[index].hold
                guard await holdEye(steps[index].squash, for: hold) else { return }
            }
            resume = (index: 0, hold: steps[0].hold)
        }
    }

    /// 设一个档位并保持 `seconds`；返回 false 表示任务已取消（调用方立刻退出）。
    /// 进后台时停手并回正（每 0.5s 复查一次实时状态）。
    @MainActor
    private func holdEye(_ squash: CGFloat, for seconds: Double) async -> Bool {
        guard UIApplication.shared.applicationState == .active else {
            eyeSquash = 1
            try? await Task.sleep(nanoseconds: 500_000_000)
            return !Task.isCancelled
        }
        eyeSquash = squash
        try? await Task.sleep(nanoseconds: UInt64(max(seconds, 0) * 1_000_000_000))
        return !Task.isCancelled
    }

    /// 电脑端 `--bt-delay` 的等价物：FNV-1a(botID) 取模 5s，再折到一个周期内。
    static func blinkPhaseDelay(botID: String, period: Double) -> Double {
        guard period > 0 else { return 0 }
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in botID.utf8 { hash = (hash ^ UInt64(byte)) &* 0x100000001b3 }
        let offset = Double(hash % 5000) / 1000
        return offset.truncatingRemainder(dividingBy: period)
    }

    private var symbolAvatar: some View {
        Circle()
            .fill(GatewayTeamFeed.avatarColor(row))
            .frame(width: size, height: size)
            .overlay {
                Image(systemName: GatewayTeamFeed.avatarSymbol(row))
                    .appFont(.system(size: size * 0.44, weight: .semibold))
                    // 电脑端同款规则：白 / 近黑二选一，符号压在真彩底上也看得见。
                    .foregroundStyle(GatewayTeamFeed.avatarInk(row))
            }
    }
}
