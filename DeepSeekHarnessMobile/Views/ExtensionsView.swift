import SwiftUI

// MARK: - 设置 → 全部扩展（参考实现 S14_Extensions）
//
// 定位：**聚合入口页**，不是功能页。
// 插件列表/详情是 11 屏、定时任务是 10 屏，这里只做两个入口 + 一句说明，
// 不重做它们的列表（Chief 2026-10-29 明确要求）。
//
// 导航：两行都通过闭包调用 RootView 既有路由（.plugins / .scheduledTasks），
// 因为 `ScheduledTasksView` 是 RootView 内的 private 类型，不能跨文件构造。

struct ExtensionsView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.colorScheme) private var colorScheme
    private var palette: DSHPalette { DSHPalette.resolve(colorScheme) }

    var onOpenPlugins: () -> Void = {}
    var onOpenScheduledTasks: () -> Void = {}

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DSHSpacing.xl) {
                DSHGroup(nil) {
                    DSHRow("插件",
                           showsDivider: true,
                           action: onOpenPlugins) {
                        DSHRowValue(text: "\(store.pluginCatalog?.plugins.count ?? 0) 个")
                    }
                    DSHRow("定时任务",
                           showsDivider: false,
                           action: onOpenScheduledTasks) {
                        DSHRowValue(text: "\(store.scheduledTasks.count) 个")
                    }
                }

                Text("插件需在桌面端安装，手机端只能启停和查看运行记录。")
                    .appFont(DSHType.meta)
                    .foregroundStyle(palette.ink(0.4))
            }
            .padding(.horizontal, DSHSpacing.lg)
            .padding(.top, DSHSpacing.xs)
            .padding(.bottom, DSHSpacing.xxl)
        }
        .background(palette.pageBackground)
        .navigationTitle("扩展")
        .navigationBarTitleDisplayMode(.inline)
    }
}
