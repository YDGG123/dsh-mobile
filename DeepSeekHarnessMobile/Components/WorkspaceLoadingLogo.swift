import SwiftUI

/// 启动/加载标识（2026-10-27 重做）：**不再用 APNG 自绘**。
///
/// 为什么：加载图的鲸形由 `generate_loading_logo.swift` 从 SVG 手写解析生成
/// （只支持部分指令 + 手调缩放/渐变坐标），换矢量后连续两次产出"残缺/不像鲸"的图
/// ——几何计算和填充规则是两层问题，自绘管线修一处漏一处。
/// 而资源目录里的 `OrcarnessOrcaBlack`（用户新矢量）由**系统渲染器**画，
/// 空态页已验证形状正确。所以这里直接用矢量 + SwiftUI 原生动画（呼吸缩放 + 渐变着色），
/// **形状永远正确**，动效交给 SwiftUI。
///
/// 遗留：Android 侧 `orcarness_loading.png` 仍是旧鲸（生成脚本已换源但 swift 生成链
/// 未跑通），等 Android 单独处理；`OrcarnessLoading` dataset 暂留但不再被 iOS 引用。
struct WorkspaceLoadingLogo: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var pulse = false

    var body: some View {
        // ⚠️ **不能加 `.renderingMode(.template)`**（2026-10-27 教训）：
        // 模板渲染只取 alpha、丢掉所有颜色层次——黑鲸+白眼斑+灰眼睛被压成一块纯色剪影，
        // 用户看到的就是"认不出的灰色鲸鱼"。模板只适合单色图标；**多色品牌图必须原始渲染**。
        // 深色模式下黑鲸会"黑上黑"，所以给一层**中性浅色圆角底**（深浅同款，类似 AppIcon 的处理）。
        Image("OrcarnessOrcaBlack")                       // 原始渲染：黑鲸 + 白眼斑 + 灰眼睛全保留
            .resizable()
            .scaledToFit()
            .padding(10)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(Color(white: 0.94))      // 极浅灰，深浅模式同款
                    .shadow(color: .black.opacity(0.08), radius: 6, y: 2)
            )
            .frame(width: 72, height: 72)
            .scaleEffect(pulse ? 1.06 : 0.96)
            .opacity(pulse ? 1.0 : 0.72)
            .accessibilityHidden(true)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.9).repeatForever(autoreverses: true),
                       value: pulse)
            .task(id: animate) {
                guard animate else { pulse = false; return }
                while !Task.isCancelled {
                    withAnimation(.easeInOut(duration: 0.9)) { pulse.toggle() }
                    try? await Task.sleep(for: .seconds(0.9))
                }
            }
    }

    /// 前台且未开启"减弱动态效果"时才呼吸；后台/省电模式静止。
    private var animate: Bool { !reduceMotion && scenePhase == .active }
}
