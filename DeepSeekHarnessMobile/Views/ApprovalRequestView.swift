import SwiftUI

struct ApprovalRequestView: View {
    let request: GatewayPendingApprovalRequest
    let status: GatewayApprovalRequestStatus
    let commandPreview: String?
    let details: JSONValue?
    let onDecision: (GatewayApprovalOutcome) -> Void

    @Environment(\.colorScheme) private var colorScheme
    @State private var isCollapsed = false
    @State private var showsDetails = false

    /// 04 审批卡：琥珀 = caution（全 app「等你动手」同色），卡片与描边走设计系统 token
    private var palette: DSHPalette { DSHPalette.resolve(colorScheme) }
    private var amber: Color { palette.caution }
    private let cardShape = RoundedRectangle(cornerRadius: DSHRadius.xl, style: .continuous)

    var body: some View {
        Group {
            if isCollapsed { collapsedCard }
            else { expandedCard }
        }
        .background(palette.cardFill)
        .clipShape(cardShape)
        .overlay { cardShape.strokeBorder(amber.opacity(0.55), lineWidth: 1.5) }
        .padding(.horizontal, DSHSpacing.lg)
        .accessibilityElement(children: .contain)
        .onAppear {
            gatewayApprovalTrace(
                "ui card appeared replay=\(request.replay) status=\(status.debugName) " +
                "hasCommand=\(commandPreview?.isEmpty == false) hasDetails=\(details != nil)"
            )
        }
        .onChangeCompat(of: isCollapsed) { _, collapsed in
            gatewayApprovalTrace("ui card collapsed=\(collapsed) rpcId=\(request.rpcId)")
        }
    }

    private var collapsedCard: some View {
        Button {
            withAnimation(.snappy(duration: 0.24)) { isCollapsed = false }
        } label: {
            HStack(spacing: 12) {
                Circle().fill(amber).frame(width: 9, height: 9)
                VStack(alignment: .leading, spacing: 2) {
                    Text(String(localized: "Agent 正在等待审批"))
                        .appFont(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text(requestReason)
                        .appFont(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.up")
                    .appFont(.caption.weight(.bold))
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 13)
        }
        .buttonStyle(.plain)
        .disabled(status.isBusy)
        .accessibilityLabel(String(localized: "展开审批卡片"))
    }

    private var expandedCard: some View {
        VStack(spacing: 0) {
            header

            VStack(alignment: .leading, spacing: 12) {
                Text(requestReason)
                    .appFont(.body.weight(.medium))
                    .fixedSize(horizontal: false, vertical: true)

                if let commandPreview, !commandPreview.isEmpty {
                    // 长命令（例如多行脚本）不允许撑高整张卡片，否则操作按钮
                    // 会被推出屏幕外。命令预览在固定高度内滚动，按钮始终可见。
                    // 04：深底代码块（两种主题都深，保证语法色一致），限高 96。
                    ScrollView(.vertical) {
                        Text(commandPreview)
                            .appFont(DSHType.mono)
                            .foregroundStyle(Color.white.opacity(0.82))
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .scrollBounceBehaviorCompat(.basedOnSize)
                    .padding(DSHSpacing.md)
                    .frame(maxHeight: 96)
                    .background(
                        DSHPalette(scheme: .dark).codeBackground,
                        in: RoundedRectangle(cornerRadius: DSHRadius.sm, style: .continuous)
                    )
                }

                if let details {
                    detailsDisclosure(details)
                }

                if case .failed(let message) = status {
                    Label(message, systemImage: "exclamationmark.triangle.fill")
                        .appFont(.caption)
                        .foregroundStyle(.red)
                }

                actionButtons
            }
            .padding(18)
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            Circle().fill(amber).frame(width: 9, height: 9)
            Text(String(localized: "等待审批"))
                .appFont(.subheadline.weight(.semibold))
                .foregroundStyle(amber)
            if request.replay {
                Text("· \(String(localized: "已恢复"))")
                    .appFont(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if status.isBusy { ProgressView().controlSize(.small) }
            Button {
                withAnimation(.snappy(duration: 0.24)) { isCollapsed = true }
            } label: {
                Image(systemName: "chevron.down")
                    .appFont(.caption.weight(.bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 30, height: 30)
            }
            .buttonStyle(.plain)
            .disabled(status.isBusy)
            .accessibilityLabel(String(localized: "收起审批卡片"))
        }
        .padding(.leading, 18)
        .padding(.trailing, 9)
        .padding(.vertical, 9)
        // 外层统一裁剪顶部圆角；标题栏自身保持矩形，因此下沿不会出现圆角。
        .background(amber.opacity(colorScheme == .dark ? 0.14 : 0.10))
    }

    private var actionButtons: some View {
        HStack(spacing: DSHSpacing.sm) {
            Button(String(localized: "拒绝")) { onDecision(.rejected) }
                .buttonStyle(DSHSecondaryButtonStyle())
            Button(String(localized: "允许一次")) { onDecision(.allowedOnce) }
                .buttonStyle(DSHPrimaryButtonStyle())
            Spacer(minLength: 0)
        }
        .disabled(status.isBusy)
    }

    private func detailsDisclosure(_ value: JSONValue) -> some View {
        DisclosureGroup(isExpanded: $showsDetails) {
            ScrollView {
                ApprovalDetailsView(value: value.normalizedValue)
                    .padding(.top, 8)
            }
            .scrollBounceBehaviorCompat(.basedOnSize)
            .frame(maxHeight: 190)
        } label: {
            Label(String(localized: "审批详情"), systemImage: "curlybraces")
                .appFont(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(palette.veil(0.06), in: RoundedRectangle(cornerRadius: DSHRadius.sm, style: .continuous))
        .onChangeCompat(of: showsDetails) { _, expanded in
            gatewayApprovalTrace("ui details expanded=\(expanded) rpcId=\(request.rpcId)")
        }
    }

    private var requestReason: String {
        request.localizedReason ?? String(localized: "\(request.toolName) 请求执行需要审批的操作")
    }
}

private struct ApprovalDetailsView: View {
    let value: JSONValue

    var body: some View {
        if let object = value.objectValue {
            VStack(alignment: .leading, spacing: 9) {
                ForEach(object.keys.sorted(), id: \.self) { key in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(key)
                            .appFont(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                        Text(detailText(object[key] ?? .null))
                            .appFont(.system(.caption, design: .monospaced))
                            .foregroundStyle(.primary)
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if key != object.keys.sorted().last {
                        Divider().opacity(0.45)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            Text(value.jsonDisplayText)
                .appFont(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func detailText(_ value: JSONValue) -> String {
        switch value {
        case .string(let text): text
        case .number, .bool, .object, .array, .null: value.jsonDisplayText
        }
    }
}

private extension GatewayApprovalRequestStatus {
    var debugName: String {
        switch self {
        case .idle: "idle"
        case .submitting: "submitting"
        case .accepted: "accepted"
        case .failed: "failed"
        }
    }
}
