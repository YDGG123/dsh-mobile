import DeepSeekHarnessShared
import SwiftUI

/// bridge 会话聊天页（U 单第一批）：历史 + 实时 WS + 发送。
/// iOS 16 红线：单参 onChange、无 @Observable、无 ContentUnavailableView。
struct BridgeConversationView: View {
    let title: String
    @ObservedObject var engine: BridgeChatEngine
    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""
    @State private var pendingJumpId: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                statusBar
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 10) {
                            ForEach(engine.messages) { item in
                                messageRow(item)
                                    .id(item.id)
                            }
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                    }
                    .onChange(of: engine.messages.count) { _ in
                        guard let last = engine.messages.last else { return }
                        withAnimation(.easeOut(duration: 0.18)) {
                            proxy.scrollTo(last.id, anchor: .bottom)
                        }
                    }
                }
                inputBar
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") {
                        engine.stop()
                        dismiss()
                    }
                }
            }
            .onAppear { engine.start() }
            .onDisappear { engine.stop() }
        }
    }

    private var statusBar: some View {
        Group {
            switch engine.status {
            case .connecting:
                banner("正在连接…", color: .secondary)
            case .reconnecting(let attempt):
                banner("连接断开，正在重连（第 \(attempt) 次）", color: .orange)
            case .failed(let message):
                banner("连接异常：\(message)", color: .red)
            case .connected, .idle:
                EmptyView()
            }
        }
    }

    private func banner(_ text: String, color: Color) -> some View {
        HStack(spacing: 6) {
            if case .failed = engine.status {
                Image(systemName: "exclamationmark.triangle.fill")
            } else {
                ProgressView().scaleEffect(0.7)
            }
            Text(text).font(.caption)
            Spacer()
        }
        .foregroundStyle(color)
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .background(color.opacity(0.08))
    }

    private func messageRow(_ item: BridgeChatItem) -> some View {
        HStack {
            if item.isUser { Spacer(minLength: 48) }
            VStack(alignment: item.isUser ? .trailing : .leading, spacing: 3) {
                Text(item.text)
                    .font(.body)
                    .foregroundStyle(item.isUser ? Color.white : Color.primary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        item.isUser ? Color.accentColor : Color(.secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 14, style: .continuous)
                    )
                HStack(spacing: 5) {
                    if item.isStreaming {
                        ProgressView().scaleEffect(0.55)
                    }
                    if item.isDeliveryFailed {
                        Text("发送失败").font(.caption2).foregroundStyle(.red)
                    }
                }
            }
            if !item.isUser { Spacer(minLength: 48) }
        }
    }

    private var inputBar: some View {
        HStack(spacing: 8) {
            TextField("发消息…", text: $draft, axis: .vertical)
                .lineLimit(1...4)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled(true)
            Button {
                let text = draft
                draft = ""
                Task { await engine.send(text) }
            } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 30))
            }
            .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || engine.isSending)
            .accessibilityLabel("发送")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
    }
}

/// sheet 载荷：bot/群行 + 对应聊天引擎。
struct BridgeChatTarget: Identifiable {
    let row: BridgeTeamRow
    let engine: BridgeChatEngine

    var id: String { row.id }
}
