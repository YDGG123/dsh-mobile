import SwiftUI
import UIKit
import ImageIO

/// APNG 在后台解码一次，多个文件预览复用同一组图片。
final class WorkspaceLoadingLogoAnimation: @unchecked Sendable {
    let frames: [UIImage]
    let duration: TimeInterval

    init?(data: Data) {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              CGImageSourceGetCount(source) > 1 else { return nil }
        var images: [UIImage] = []
        var duration: TimeInterval = 0
        for index in 0..<CGImageSourceGetCount(source) {
            guard let image = CGImageSourceCreateImageAtIndex(source, index, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary) else { return nil }
            let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any]
            let png = properties?[kCGImagePropertyPNGDictionary] as? [CFString: Any]
            duration += (png?[kCGImagePropertyAPNGUnclampedDelayTime] as? Double)
                ?? (png?[kCGImagePropertyAPNGDelayTime] as? Double) ?? 0.05
            images.append(UIImage(cgImage: image, scale: 3, orientation: .up))
        }
        self.frames = images
        self.duration = duration
    }

    static let shared: WorkspaceLoadingLogoAnimation? = {
        guard let data = NSDataAsset(name: "OrcarnessLoading")?.data else { return nil }
        return WorkspaceLoadingLogoAnimation(data: data)
    }()
}

struct WorkspaceLoadingLogo: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var animation: WorkspaceLoadingLogoAnimation?

    var body: some View {
        Group {
            if let animation {
                WorkspaceLoadingLogoPlayer(animation: animation, plays: !reduceMotion && scenePhase == .active)
            } else {
                Image("OrcarnessLoadingPoster").resizable().scaledToFit()
            }
        }
        .frame(width: 72, height: 72)
        .accessibilityHidden(true)
        .task {
            let decoded = await Task.detached(priority: .userInitiated) { WorkspaceLoadingLogoAnimation.shared }.value
            guard !Task.isCancelled else { return }
            animation = decoded
        }
    }
}

struct WorkspaceLoadingLogoPlayer: UIViewRepresentable {
    let animation: WorkspaceLoadingLogoAnimation
    let plays: Bool

    func makeUIView(context: Context) -> UIImageView {
        let view = UIImageView(image: animation.frames.first)
        view.contentMode = .scaleAspectFit
        view.animationImages = animation.frames
        view.animationDuration = animation.duration
        view.animationRepeatCount = 0
        view.accessibilityIdentifier = "workspace-loading-logo-player"
        return view
    }

    func updateUIView(_ view: UIImageView, context: Context) {
        if plays && !view.isAnimating { view.startAnimating() }
        else if !plays && view.isAnimating { view.stopAnimating() }
    }

    static func dismantleUIView(_ view: UIImageView, coordinator: ()) {
        view.stopAnimating()
        view.animationImages = nil
    }
}
