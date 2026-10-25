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
    /// 眼睛竖向压扁系数：1 = 正常，~0.1 = 眨眼成缝，~0.7 = 眯眼。
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
        "\(motion.isLive)-\(motion.isWorking)-\(reduceMotion)"
    }

    /// 有 glyph：淡色圆底 + 矢量化角色（与电脑端同路径、同色、同挖空眼睛）。
    private func glyphAvatar(_ glyph: TeamAvatarGlyph) -> some View {
        let ink = GatewayTeamFeed.avatarColor(row)
        let disc = ink.opacity(0.16)
        return Circle()
            .fill(disc)
            .frame(width: size, height: size)
            .overlay {
                Canvas { context, canvasSize in
                    glyph.draw(in: &context, canvasSize: canvasSize,
                               bodyColor: ink, eyeColor: backdrop, eyeSquash: eyeSquash)
                }
                .frame(width: size, height: size)
            }
            .clipShape(Circle())
            .accessibilityHidden(true)
    }

    /// 眨眼 / 眯眼驱动。不引 TimelineView：列表里每个头像持续重绘不划算；
    /// 两个离散档位已足够表达「会动」，也和电脑端 blink 的瞬时压扁一致。
    /// - 空闲：每 2–5 秒眨一次，0.45 → 0.1 → 1（压成一条缝，不是消失）
    /// - 干活/思考：0.66 ↔ 0.80 缓慢呼吸（对应 bt-squint 的 scaleY(.7~.78)）
    private func driveEyes() async {
        guard motion.isLive, !reduceMotion else {
            eyeSquash = 1
            return
        }
        defer { eyeSquash = 1 }
        if motion.isWorking {
            while !Task.isCancelled {
                eyeSquash = 0.66
                try? await Task.sleep(nanoseconds: 1_100_000_000)
                guard !Task.isCancelled else { return }
                eyeSquash = 0.80
                try? await Task.sleep(nanoseconds: 1_100_000_000)
            }
            return
        }
        while !Task.isCancelled {
            let gap = UInt64(Double.random(in: 2...5) * 1_000_000_000)
            try? await Task.sleep(nanoseconds: gap)
            guard !Task.isCancelled else { return }
            for step in [CGFloat(0.45), 0.1] {
                eyeSquash = step
                try? await Task.sleep(nanoseconds: 45_000_000)
                guard !Task.isCancelled else { return }
            }
            eyeSquash = 1
        }
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
