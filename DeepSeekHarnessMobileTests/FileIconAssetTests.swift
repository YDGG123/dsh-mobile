import XCTest
import SwiftUI
import UIKit
import DeepSeekHarnessShared
@testable import DeepSeekHarnessMobile

@MainActor
final class FileIconAssetTests: XCTestCase {
    func testEverySharedFormatHasAnOriginalVectorAndMatchingAdaptiveBackground() throws {
        let kinds = FileIconKind.entries
        XCTAssertEqual(kinds.count, 72)
        for kind in kinds {
            let image = try XCTUnwrap(UIImage(named: "FileIcon_\(kind.assetKey)"), kind.assetKey)
            XCTAssertEqual(image.size.width, 48, kind.assetKey)
            XCTAssertEqual(image.size.height, 48, kind.assetKey)
            XCTAssertNotEqual(image.renderingMode, .alwaysTemplate, kind.assetKey)
            let color = try XCTUnwrap(UIColor(named: "FileCard_\(kind.colorFamily)"), kind.assetKey)
            for style in [UIUserInterfaceStyle.light, .dark] {
                let resolved = color.resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
                var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
                XCTAssertTrue(resolved.getRed(&red, green: &green, blue: &blue, alpha: &alpha))
                let rgb = Int(kind.cardBackgroundRgb(dark: style == .dark))
                XCTAssertEqual(red, CGFloat((rgb >> 16) & 255) / 255, accuracy: 0.004, kind.assetKey)
                XCTAssertEqual(green, CGFloat((rgb >> 8) & 255) / 255, accuracy: 0.004, kind.assetKey)
                XCTAssertEqual(blue, CGFloat(rgb & 255) / 255, accuracy: 0.004, kind.assetKey)
                XCTAssertEqual(alpha, 1)
            }
        }
    }

    func testFileCardsRenderInBothThemesAndAppFontScales() throws {
        let defaults = UserDefaults.standard
        let previousScale = defaults.object(forKey: AppFontScale.key)
        let previousFollowsSystem = defaults.object(forKey: AppFontScale.followsSystemKey)
        defer {
            if let previousScale { defaults.set(previousScale, forKey: AppFontScale.key) }
            else { defaults.removeObject(forKey: AppFontScale.key) }
            if let previousFollowsSystem { defaults.set(previousFollowsSystem, forKey: AppFontScale.followsSystemKey) }
            else { defaults.removeObject(forKey: AppFontScale.followsSystemKey) }
        }
        defaults.set(false, forKey: AppFontScale.followsSystemKey)
        for scheme in [ColorScheme.light, .dark] {
            var normalHeight: CGFloat = 0
            var normalImageData: Data?
            for largeText in [false, true] {
                defaults.set(largeText ? 1.5 : 1.0, forKey: AppFontScale.key)
                let content = VStack(alignment: .trailing, spacing: 12) {
                    FileReferenceCard(path: "三亚国庆旅行攻略.pdf", description: "2026 国庆海南三亚旅游攻略 · A4 共 8 页（天气/交通/住宿/7 日行程/门票预约/美食/免税避坑）") {}
                    FileReferenceCard(path: "数据汇总.xlsx") {}
                    FileReferenceCard(path: "FilePreview.kt") {}
                    FileReferenceCard(path: "附件.unknown") {}
                }
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(16)
                .frame(width: 390)
                .environment(\.colorScheme, scheme)
                let renderer = ImageRenderer(content: content)
                renderer.scale = 2
                let image = try XCTUnwrap(renderer.uiImage)
                XCTAssertGreaterThan(image.size.height, 300)
                if largeText {
                    XCTAssertGreaterThanOrEqual(image.size.height, normalHeight)
                    XCTAssertNotEqual(image.pngData(), normalImageData, "大字号应实际影响文件名渲染")
                } else {
                    normalHeight = image.size.height
                    normalImageData = image.pngData()
                }
                let attachment = XCTAttachment(image: image)
                attachment.name = "file-cards-\(scheme)-\(largeText ? "large" : "normal")"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
        }
    }

    func testCardsHugContentAndWrapWithinAvailableWidth() throws {
        func render(_ name: String, description: String? = nil, width: CGFloat) throws -> UIImage {
            let renderer = ImageRenderer(content: FileReferenceCard(path: name, description: description) {})
            renderer.proposedSize = ProposedViewSize(width: width, height: nil)
            return try XCTUnwrap(renderer.uiImage)
        }
        let short = try render("a.pdf", width: 600)
        let name = String(repeating: "旅行攻略完整版_", count: 8) + ".pdf"
        let summary = String(repeating: "保留简短说明与页数，超过最大宽度后自动换行。", count: 3)
        let long = try render(name, description: summary, width: 600)
        let narrow = try render(name, description: summary, width: 220)
        XCTAssertLessThan(short.size.width, long.size.width)
        XCTAssertLessThanOrEqual(long.size.width, 320.5)
        XCTAssertLessThanOrEqual(narrow.size.width, 220.5)
        XCTAssertGreaterThan(long.size.height, short.size.height)
        XCTAssertGreaterThan(narrow.size.height, long.size.height)
        let attachment = XCTAttachment(image: long)
        attachment.name = "file-card-wrapped"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testPreviewHeaderWrapsFileNameInBothThemes() throws {
        let defaults = UserDefaults.standard
        let oldScale = defaults.object(forKey: AppFontScale.key)
        let oldFollowsSystem = defaults.object(forKey: AppFontScale.followsSystemKey)
        defer {
            if let oldScale { defaults.set(oldScale, forKey: AppFontScale.key) }
            else { defaults.removeObject(forKey: AppFontScale.key) }
            if let oldFollowsSystem { defaults.set(oldFollowsSystem, forKey: AppFontScale.followsSystemKey) }
            else { defaults.removeObject(forKey: AppFontScale.followsSystemKey) }
        }
        defaults.set(false, forKey: AppFontScale.followsSystemKey)
        let longName = "2026 国庆海南三亚旅游攻略完整版_含住宿交通与每日行程安排.pdf"
        for scheme in [ColorScheme.light, .dark] {
            for scale in [1.0, 1.5] {
                defaults.set(scale, forKey: AppFontScale.key)
                for width: CGFloat in [320, 390] {
                    func render(_ name: String) throws -> UIImage {
                        let header = WorkspaceFilePreviewHeader(fileName: name, onClose: {}, onSave: {})
                            .frame(width: width)
                            .background {
                                // 模拟文档滚动到 Header 后方，验证材质保留底层色彩。
                                LinearGradient(colors: [.teal, .blue], startPoint: .topLeading, endPoint: .bottomTrailing)
                            }
                            .environment(\.colorScheme, scheme)
                        // Menu 由 UIKit 承载，使用真实视图层级截图，避免 ImageRenderer 的占位图。
                        let controller = UIHostingController(rootView: header)
                        // `safeAreaRegions` 是 iOS 16.4+ API：主 App 侧同样带保护
                        // （ConversationViewport.swift 的 `if #available(iOS 16.4, *)`），
                        // 测试别漏——漏了整条测试 target 在 16.0 部署目标下编不过。
                        if #available(iOS 16.4, *) { controller.safeAreaRegions = [] }
                        controller.overrideUserInterfaceStyle = scheme == .dark ? .dark : .light
                        let size = controller.sizeThatFits(in: CGSize(width: width, height: 1_000))
                        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
                        let previousWindow = scene.windows.first(where: \.isKeyWindow)
                        let window = UIWindow(windowScene: scene)
                        window.frame = CGRect(origin: .zero, size: size)
                        window.rootViewController = controller
                        window.makeKeyAndVisible()
                        defer {
                            window.isHidden = true
                            previousWindow?.makeKeyAndVisible()
                        }
                        controller.view.frame = CGRect(origin: .zero, size: size)
                        controller.view.layoutIfNeeded()
                        return UIGraphicsImageRenderer(size: size).image { _ in
                            controller.view.drawHierarchy(in: controller.view.bounds, afterScreenUpdates: true)
                        }
                    }
                    let short = try render("a.pdf")
                    let long = try render(longName)
                    XCTAssertEqual(long.size.width, width, accuracy: 0.5)
                    // 原生玻璃控件包含系统内边距，短标题高度由按钮决定。
                    XCTAssertGreaterThan(long.size.height, short.size.height, "长文件名应换行撑高 Header")
                    let attachment = XCTAttachment(image: long)
                    attachment.name = "preview-header-\(scheme)-\(scale)-\(Int(width))"
                    attachment.lifetime = .keepAlways
                    add(attachment)
                }
            }
        }
    }

}
