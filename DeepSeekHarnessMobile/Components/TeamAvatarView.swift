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

struct TeamAvatarView: View {
    let row: GatewayTeamFeed.Row
    let size: CGFloat

    private static let log = Logger(subsystem: "ai.dsh.mobile.ios", category: "team-avatar")

    var body: some View {
        let image = GatewayTeamFeed.avatarImage(row)
        let glyph = row.glyph.flatMap { $0.canRender ? $0 : nil }
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
                               bodyColor: ink, eyeColor: disc)
                }
                .frame(width: size, height: size)
            }
            .clipShape(Circle())
            .accessibilityHidden(true)
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
