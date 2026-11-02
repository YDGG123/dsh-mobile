import Foundation
import SwiftUI
import UIKit

struct ConversationItem: Identifiable, Sendable {
    /// `switchNotice` 是纯展示条目（S27 切换提示，细线 + 文案）：由视图层在渲染前注入，
    /// 不来自事件流、不写回 store、不进 KMP 轨迹/轮次统计。
    enum Kind: Equatable, Sendable { case user, context, assistant, reasoning, tool, jsonTool, toolResult, status, system, switchNotice }
    let id: String
    let kind: Kind
    let title: String
    let text: String
    let images: [GatewayImageAttachment]
    let isError: Bool
    let date: Date
    let files: [PresentedFile]
    let knownFiles: [PresentedFile]
    /// 源事件的 `seq`（KMP 投影 `ConversationItem.seq`，2026-11-02 新增）。
    /// **跨源精确去重键的一半**：与 bridge 消息的 `seq` 同源（宿主事件 seq）。
    /// ⚠️ 目前 **KMP 侧不是所有类目都填**（14 处构造点里 5 处已填，用户消息两处在内）；
    /// 其余为 nil 时，可从 `id`（`sessionId-seq` 形态）拆出 seq 兜底。
    /// ⚠️ bridge 侧 `seq` 是 `Long`、此处是 `Int` ⇒ 比对前先归一类型。
    let seq: Int?

    /// 群聊发言者名（**只许来自 bridge 的 `author.name`（服务端）**，禁止本地推断/用会话标题凑）。
    /// 渲染规则（2026-11-02 定）：`senderName != nil` ⇒ 显示名字；
    /// `nil && kind == .user` ⇒ 「我」；**`nil && assistant` ⇒ 不显示任何名字（绝不回落成「我」）**。
    let senderName: String?

    init(
        id: String,
        kind: Kind,
        title: String,
        text: String,
        images: [GatewayImageAttachment] = [],
        isError: Bool,
        date: Date,
        files: [PresentedFile] = [],
        knownFiles: [PresentedFile] = [],
        seq: Int? = nil,
        senderName: String? = nil
    ) {
        self.id = id
        self.kind = kind
        self.title = title
        self.text = text
        self.images = images
        self.isError = isError
        self.files = files
        self.knownFiles = knownFiles
        self.date = date
        self.seq = seq
        self.senderName = senderName
    }


}

/// A session-scoped presentation channel. It is deliberately not an
/// `ObservableObject`: streaming text must update the timeline without
/// invalidating the whole `ConversationView` (composer, pager and header).
/// The UIKit viewport subscribes directly and owns all scroll decisions.
@MainActor
final class ConversationTimeline {
    struct Snapshot {
        let items: [ConversationItem]
        let revision: Int
        var historyPresentationStamp: HistoryPresentationStamp? = nil
    }

    private var items: [ConversationItem] = []
    private var revision = 0
    private var historyPresentationStamp: HistoryPresentationStamp?
    private var observers: [UUID: (Snapshot) -> Void] = [:]

    var currentSnapshot: Snapshot {
        Snapshot(items: items, revision: revision, historyPresentationStamp: historyPresentationStamp)
    }

    func publish(_ items: [ConversationItem], historyPresentationStamp: HistoryPresentationStamp? = nil) {
        self.items = items
        if let historyPresentationStamp { self.historyPresentationStamp = historyPresentationStamp }
        revision &+= 1
        let snapshot = currentSnapshot
        for observer in observers.values {
            observer(snapshot)
        }
    }

    func clearHistoryPresentationStamp(operationID: UUID) {
        if historyPresentationStamp?.operationID == operationID { historyPresentationStamp = nil }
    }

    @discardableResult
    func observe(_ observer: @escaping (Snapshot) -> Void) -> UUID {
        let id = UUID()
        observers[id] = observer
        observer(currentSnapshot)
        return id
    }

    func removeObserver(_ id: UUID) {
        observers[id] = nil
    }
}

/// Coalesces an arbitrarily bursty WebSocket stream to the device's display
/// cadence. Every packet is retained in the raw event log, while projection
/// always consumes the newest tail once per frame. There is no timer backlog
/// and no relationship to scroll state; tracking and deceleration continue to
/// receive touches while the model keeps advancing behind them.
@MainActor
final class ConversationProjectionDriver {
    private final class DisplayLinkTarget: NSObject {
        weak var owner: ConversationProjectionDriver?

        @MainActor @objc func tick() {
            owner?.displayLinkDidFire()
        }
    }

    private let action: () async -> Void
    private let preferredFramesPerSecond: Float?
    private let target = DisplayLinkTarget()
    private lazy var displayLink: CADisplayLink = {
        let link = CADisplayLink(target: target, selector: #selector(DisplayLinkTarget.tick))
        link.add(to: .main, forMode: .common)
        if let preferredFramesPerSecond {
            link.preferredFrameRateRange = CAFrameRateRange(
                minimum: preferredFramesPerSecond,
                maximum: preferredFramesPerSecond,
                preferred: preferredFramesPerSecond
            )
        }
        link.isPaused = true
        return link
    }()
    private var isDirty = false
    private var isProjecting = false

    init(preferredFramesPerSecond: Float? = nil, action: @escaping () async -> Void) {
        self.action = action
        self.preferredFramesPerSecond = preferredFramesPerSecond
        target.owner = self
        _ = displayLink
    }

    func invalidate() {
        isDirty = true
        displayLink.isPaused = false
    }

    func stop() {
        isDirty = false
        displayLink.isPaused = true
    }

    private func displayLinkDidFire() {
        guard isDirty, !isProjecting else { return }
        isDirty = false
        isProjecting = true
        Task { @MainActor [weak self] in
            guard let self else { return }
            await self.action()
            self.isProjecting = false
            self.displayLink.isPaused = !self.isDirty
        }
    }
}
