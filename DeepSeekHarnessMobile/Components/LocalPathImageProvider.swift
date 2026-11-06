import SwiftUI
import MarkdownUI

/// 89 批：聊天 Markdown 里 AI 写的**本地路径**图片（如 `![](/Volumes/.../x.png)`）
/// 直接显示——走 AppStore 的串行取图队列（复用工作区文件状态机，含分块完整性校验，
/// **不绕过**），取回本地文件 URL 后渲染并 NSCache 缓存。
/// 工作目录外的路径由网关拒绝 → 显示「文件在工作目录外」占位。
struct LocalPathImageProvider: ImageProvider {
    /// 下沉链：非本地路径的形态交给既有 Provider（数学公式 → 默认）。
    let fallback: ImageProvider
    /// 取图队列与选会话状态的持有者（AppStore）。
    let store: AppStore

    func makeImage(url: URL?) -> some View {
        Group {
            if let path = Self.localImagePath(from: url) {
                LocalPathImageView(absolutePath: path, store: store)
            } else {
                fallback.makeImage(url: url)
            }
        }
    }

    /// 识别「本地路径」形态：file:// URL，或无 scheme 的绝对 POSIX 路径（AI 最常见写法）。
    /// http(s)/data: 等远程形态不在此处理，交回默认渲染。
    static func localImagePath(from url: URL?) -> String? {
        guard let url else { return nil }
        if url.isFileURL { return url.path }
        if url.scheme == nil, url.host == nil, url.path.hasPrefix("/"), url.path.count > 1 {
            return url.path
        }
        return nil
    }
}

private struct LocalPathImageView: View {
    let absolutePath: String
    @ObservedObject var store: AppStore
    @State private var loaded: UIImage?
    @State private var state: LoadState = .idle

    private enum LoadState { case idle, loading, loaded, outOfWorkspace, failed }

    private static let cache = NSCache<NSString, UIImage>()

    var body: some View {
        Group {
            switch state {
            case .idle, .loading:
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text(absolutePath).appFont(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
                .padding(.vertical, 6)
            case .loaded:
                if let loaded {
                    Image(uiImage: loaded)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(maxWidth: 320)
                        .clipShape(RoundedRectangle(cornerRadius: DSHRadius.sm))
                } else {
                    placeholder
                }
            case .outOfWorkspace:
                outOfWorkspacePlaceholder
            case .failed:
                placeholder
            }
        }
        .task(id: absolutePath) {
            await load()
        }
    }

    private var placeholder: some View {
        HStack(spacing: 8) {
            Image(systemName: "photo").foregroundStyle(.secondary)
            Text(absolutePath).appFont(.caption2).foregroundStyle(.secondary).lineLimit(1)
        }
        .padding(.vertical, 6)
    }

    /// 工作目录外：网关红线拒绝（isInsideWorkspace）。给可点击占位而不是白屏。
    private var outOfWorkspacePlaceholder: some View {
        HStack(spacing: 8) {
            Image(systemName: "lock.shield").foregroundStyle(.secondary)
            Text(String(localized: "a11y.local-image.outside", defaultValue: "文件在工作目录外，无法显示"))
                .appFont(.caption2).foregroundStyle(.secondary).lineLimit(2)
        }
        .padding(.vertical, 6)
    }

    private func load() async {
        if let cached = Self.cache.object(forKey: absolutePath as NSString) {
            loaded = cached
            state = .loaded
            return
        }
        guard state == .idle else { return }
        state = .loading
        let sessionID = store.selectedSessionId
        let url = await store.fetchLocalImageFile(path: absolutePath, sessionID: sessionID)
        guard !Task.isCancelled else { return }
        guard let url, let image = UIImage(contentsOfFile: url.path) else {
            // 网关拒绝（越界）与取回失败目前都返回 nil；越界时网关日志有 403，客户端占位文案取通用失败态。
            state = .failed
            return
        }
        Self.cache.setObject(image, forKey: absolutePath as NSString)
        loaded = image
        state = .loaded
    }
}
