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
    @Environment(\.scenePhase) private var scenePhase
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

    /// 眨眼驱动。时序照抄电脑端 CSS（`styles.js`），不用随机间隔：
    /// - 干活中 `bt-blink` **2.4s**：46% 压到 `scaleY(.03)`、48% 回弹 `1.06`、50% 回正
    /// - 空闲 `bt-blink-idle` **5s** 眨两次：32.2% / 71.8% 压到 `.03`，33.8% / 73.4% 回弹 `1.06`
    /// - 相位按 bot id 固定错开（对应电脑端 `--bt-delay: -(fnv1a(botID) % 5000)/1000 s`），
    ///   所以同一屏里各头像不会同时闭眼，但每个头像自己的节奏是稳定的（可对拍）。
    /// 不引 TimelineView：列表里每个头像持续重绘不划算，离散档位已经等于 CSS 的瞬时压扁。
    private func driveEyes() async {
        guard motion.isLive, !reduceMotion else {
            eyeSquash = 1
            return
        }
        defer { eyeSquash = 1 }
        // 相位错开：先睡 bot 专属偏移，再进入固定周期。
        let delay = Self.blinkPhaseDelay(botID: row.id, period: motion.isWorking ? 2.4 : 5)
        if delay > 0.01 {
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
        }
        while !Task.isCancelled {
            // 后台/被打断时停手：动画不该在不可见时跑（用户做过减弱动效实验，这条他会撞上）。
            if scenePhase != .active {
                eyeSquash = 1
                try? await Task.sleep(nanoseconds: 500_000_000)
                continue
            }
            if motion.isWorking {
                // 2.4s 周期：0–42%(1.008s) 睁 → 46%(1.104s) 闭 → 48%(1.152s) 过冲 → 50%(1.2s) 回正
                guard await blinkStep(to: 1, after: 1.008),
                      await blinkStep(to: 0.03, after: 0.096),
                      await blinkStep(to: 1.06, after: 0.048),
                      await blinkStep(to: 1, after: 1.248) else { return }
            } else {
                // 5s 周期两次：1.61s 闭 → 1.69s 过冲 → 1.75s 回正 → 3.59s 闭 → 3.67s 过冲
                //             → 3.73s 回正 → 5s 循环
                guard await blinkStep(to: 1, after: 1.61),
                      await blinkStep(to: 0.03, after: 0.08),
                      await blinkStep(to: 1.06, after: 0.06),
                      await blinkStep(to: 1, after: 1.84),
                      await blinkStep(to: 0.03, after: 0.08),
                      await blinkStep(to: 1.06, after: 0.06),
                      await blinkStep(to: 1, after: 1.27) else { return }
            }
        }
    }

    /// 设一个档位并睡 `after` 秒；返回 false 表示任务已取消（调用方立刻退出）。
    private func blinkStep(to value: CGFloat, after seconds: Double) async -> Bool {
        eyeSquash = value
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
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
