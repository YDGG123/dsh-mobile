import SwiftUI
import DeepSeekHarnessShared

struct FileTypeIcon: View {
    let kind: FileIconKind
    var size: CGFloat = 48

    var body: some View {
        Image("FileIcon_\(kind.assetKey)")
            .renderingMode(.original)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

extension FileIconKind {
    var cardBackground: Color { Color("FileCard_\(colorFamily)") }
}

struct FileReferenceCard: View {
    let path: String
    var description: String? = nil
    var mediaType: String? = nil
    let action: () -> Void

    private var kind: FileIconKind {
        FileIconCatalog.shared.resolve(name: path, mediaType: mediaType)
    }

    var body: some View {
        FileCardWidthLayout {
            Button(action: action) {
                HStack(spacing: 12) {
                    FileTypeIcon(kind: kind)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(path.replacingOccurrences(of: "\\", with: "/").split(separator: "/").last.map(String.init) ?? path)
                            .appFont(.system(size: 15, weight: .medium))
                            .foregroundStyle(.primary)
                            .fixedSize(horizontal: false, vertical: true)
                        if let summary = FileCardDescription.shared.summary(description: description) {
                            Text(summary)
                                .appFont(.system(size: 12))
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                .padding(12)
                .background(kind.cardBackground, in: RoundedRectangle(cornerRadius: 12))
                .contentShape(RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(.plain)
        }
    }
}

struct UploadedFileCard: View {
    let file: PresentedFile
    let action: () -> Void

    var body: some View {
        let name = file.name ?? String(localized: "文件")
        let kind = FileIconCatalog.shared.resolve(name: name, mediaType: nil)
        FileCardWidthLayout {
            Button(action: action) {
                HStack(spacing: 12) {
                    FileTypeIcon(kind: kind)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(name).appFont(.system(size: 15, weight: .medium)).lineLimit(2)
                        if let bytes = file.bytes {
                            Text(ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file))
                                .appFont(.system(size: 12)).foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(12)
                .background(kind.cardBackground, in: RoundedRectangle(cornerRadius: 12))
                .contentShape(RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(.plain)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("user-file-card")
    }
}

/// 限制卡片宽度，同时保留内容的自然宽度；窄容器以可用宽度为上限。
private struct FileCardWidthLayout: Layout {
    private let maximumWidth: CGFloat = 320

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let content = subviews.first else { return .zero }
        let width = min(proposal.width ?? maximumWidth, maximumWidth)
        return content.sizeThatFits(ProposedViewSize(width: width, height: nil))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, anchor: .topLeading, proposal: ProposedViewSize(bounds.size))
    }
}
