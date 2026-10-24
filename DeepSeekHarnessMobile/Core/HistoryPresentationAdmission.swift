import Foundation

enum HistoryPresentationOutcome: String {
    case presented, empty, failed, cancelled, detached
}

enum HistoryPresentationLifecycleEvent {
    case began(UUID)
    case required(UUID, projectionEpoch: Int)
    case issued(UUID, projectionEpoch: Int)
    case terminal(UUID)
    case presented(UUID, projectionEpoch: Int)
    case finished(UUID, HistoryPresentationOutcome)
    case rejected(String)
}

struct HistoryPresentationConsumer {
    let id: UUID
    let onEvent: (HistoryPresentationLifecycleEvent) -> Void
}

struct HistoryPresentationStamp: Equatable, Sendable {
    let operationID: UUID
    let projectionEpoch: Int
}

/// UI 请求到实际呈现之间的准入；不持有网络 cursor、分页预算或领域 loading 状态。
@MainActor
final class HistoryPresentationAdmission {
    private struct Operation {
        let id: UUID
        let consumerID: UUID
        let minimumHistoryEventSequence: Int64
        var requiredEpoch: Int?
        var presentedEpoch: Int?
        var issuedEpochs: Set<Int> = []
        var terminalObserved = false
        var accepted = false
    }

    private var consumers: [String: HistoryPresentationConsumer] = [:]
    private var operations: [String: Operation] = [:]

    func attach(sessionID: String, consumer: HistoryPresentationConsumer) {
        if let previous = consumers[sessionID], previous.id != consumer.id { finish(sessionID, outcome: .detached) }
        consumers[sessionID] = consumer
    }

    func detach(sessionID: String, consumerID: UUID) {
        guard consumers[sessionID]?.id == consumerID else { return }
        finish(sessionID, outcome: .detached)
        consumers[sessionID] = nil
    }

    func begin(sessionID: String, consumerID: UUID, domainLoading: Bool, hasMore: Bool, producerBusy: Bool,
        minimumHistoryEventSequence: Int64 = 0, activate: Bool = true) -> UUID? {
        guard let consumer = consumers[sessionID], consumer.id == consumerID else { return nil }
        let reason: String?
        if operations[sessionID] != nil { reason = "presentation_pending" }
        else if domainLoading { reason = "domain_loading" }
        else if !hasMore { reason = "no_more_history" }
        else if producerBusy { reason = "projection_pending" }
        else { reason = nil }
        if let reason { consumer.onEvent(.rejected(reason)); return nil }
        let operation = Operation(id: UUID(), consumerID: consumerID, minimumHistoryEventSequence: minimumHistoryEventSequence)
        operations[sessionID] = operation
        if activate { accept(sessionID: sessionID, operationID: operation.id) }
        return operation.id
    }

    func accept(sessionID: String, operationID: UUID) {
        guard var operation = operations[sessionID], operation.id == operationID, !operation.accepted else { return }
        operation.accepted = true
        operations[sessionID] = operation
        consumers[sessionID]?.onEvent(.began(operation.id))
    }

    func activeConsumerID(sessionID: String) -> UUID? { consumers[sessionID]?.id }
    func isPending(sessionID: String) -> Bool { operations[sessionID] != nil }
    func matches(sessionID: String, operationID: UUID) -> Bool { operations[sessionID]?.id == operationID }

    func requireProjection(sessionID: String, epoch: Int, historyEventSequence: Int64) {
        guard var operation = operations[sessionID], operation.accepted,
              historyEventSequence >= operation.minimumHistoryEventSequence else { return }
        operation.requiredEpoch = max(operation.requiredEpoch ?? epoch, epoch)
        operations[sessionID] = operation
        consumers[sessionID]?.onEvent(.required(operation.id, projectionEpoch: epoch))
    }

    func stamp(sessionID: String, committedEpoch: Int) -> HistoryPresentationStamp? {
        guard var operation = operations[sessionID], operation.accepted, let required = operation.requiredEpoch,
              committedEpoch >= required else { return nil }
        let isNew = operation.issuedEpochs.insert(committedEpoch).inserted
        operations[sessionID] = operation
        if isNew { consumers[sessionID]?.onEvent(.issued(operation.id, projectionEpoch: committedEpoch)) }
        return HistoryPresentationStamp(operationID: operation.id, projectionEpoch: committedEpoch)
    }

    func acknowledge(sessionID: String, consumerID: UUID, stamp: HistoryPresentationStamp, domainLoading: Bool) {
        guard var operation = operations[sessionID], operation.id == stamp.operationID,
              operation.consumerID == consumerID, consumers[sessionID]?.id == consumerID,
              operation.issuedEpochs.contains(stamp.projectionEpoch) else { return }
        let isNew = operation.presentedEpoch.map { stamp.projectionEpoch > $0 } ?? true
        operation.presentedEpoch = max(operation.presentedEpoch ?? stamp.projectionEpoch, stamp.projectionEpoch)
        operations[sessionID] = operation
        if isNew { consumers[sessionID]?.onEvent(.presented(operation.id, projectionEpoch: stamp.projectionEpoch)) }
        completeIfPresented(sessionID, domainLoading: domainLoading)
    }

    func terminal(sessionID: String, domainLoading: Bool, failure: HistoryPresentationOutcome? = nil,
        historyEventSequence: Int64? = nil, operationID: UUID? = nil) {
        guard var operation = operations[sessionID], operationID == nil || operation.id == operationID,
              historyEventSequence.map({ $0 >= operation.minimumHistoryEventSequence }) ?? true else { return }
        guard operation.accepted else { finish(sessionID, outcome: .cancelled); return }
        operation.terminalObserved = true
        operations[sessionID] = operation
        consumers[sessionID]?.onEvent(.terminal(operation.id))
        if let failure { finish(sessionID, outcome: failure); return }
        guard !domainLoading else { return }
        if operation.requiredEpoch == nil { finish(sessionID, outcome: .empty) }
        else { completeIfPresented(sessionID, domainLoading: domainLoading) }
    }

    func cancel(sessionID: String, outcome: HistoryPresentationOutcome = .cancelled, operationID: UUID? = nil) {
        guard operationID == nil || operations[sessionID]?.id == operationID else { return }
        finish(sessionID, outcome: outcome)
    }

    func cancelAll(outcome: HistoryPresentationOutcome) {
        for id in Array(operations.keys) { finish(id, outcome: outcome) }
    }

    private func completeIfPresented(_ sessionID: String, domainLoading: Bool) {
        guard let operation = operations[sessionID], operation.terminalObserved, !domainLoading,
              let required = operation.requiredEpoch, let presented = operation.presentedEpoch,
              presented >= required else { return }
        finish(sessionID, outcome: .presented)
    }

    private func finish(_ sessionID: String, outcome: HistoryPresentationOutcome) {
        guard let operation = operations.removeValue(forKey: sessionID),
              let consumer = consumers[sessionID], consumer.id == operation.consumerID else { return }
        if operation.accepted { consumer.onEvent(.finished(operation.id, outcome)) }
        else { consumer.onEvent(.rejected("other")) }
    }
}
