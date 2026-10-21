import CryptoKit
import Foundation
import Security

/// Mobile Bridge 传输层（Docs/bot-team-port-design.md §5，v1.2 双模式）。
///
/// - 模式 B（默认）：配对 JSON 无 tlsPin ⇒ 系统默认证书链校验（公网受信），
///   只保留「origin 绑定 + 禁重定向」两条额外约束；
/// - 模式 A：配对 JSON 带 tlsPin（sha256:<64hex>，自签叶子）⇒ 手工校验单张叶子
///   自签名、有效期与 SPKI pin，锚定该叶子后 SecTrust 评估。
///
/// 两种模式都：禁用重定向；请求 URL 必须与配对 origin 同 scheme/host/port 且落在
/// /mobile/v1/ 下；ATS 不加任何例外。
enum BridgeTransportError: LocalizedError, Equatable {
    case invalidOrigin
    case insecureOrigin
    case invalidPin
    case boundOriginMismatch
    case unexpectedStatus(Int)
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .invalidOrigin: return "服务器地址无效"
        case .insecureOrigin: return "必须使用 HTTPS"
        case .invalidPin: return "配对码中的设备身份无效"
        case .boundOriginMismatch: return "请求地址与已配对服务器不一致"
        case .unexpectedStatus(let status): return "服务器返回 \(status)"
        case .invalidResponse: return "无法解析服务器响应"
        }
    }
}

/// origin 校验：镜像 mobile-sdk validateServerUrl（无 path/query/凭据；HTTPS 强制）。
enum BridgeOriginValidation {
    static func origin(from value: String, allowInsecureLoopback: Bool = false) -> URL? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var components = URLComponents(string: trimmed) else { return nil }
        guard let scheme = components.scheme?.lowercased(), ["https", "http"].contains(scheme) else { return nil }
        guard scheme == "https" || (allowInsecureLoopback && isLoopback(components.host)) else { return nil }
        guard let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil else { return nil }
        let path = components.path
        guard path.isEmpty || path == "/" || path == "/mobile/v1" || path == "/mobile/v1/" else { return nil }
        components.path = ""
        guard let safe = components.string else { return nil }
        return URL(string: safe.hasSuffix("/") ? String(safe.dropLast()) : safe)
    }

    /// 请求 URL 必须与配对 origin 同 scheme/host/port，且路径在 /mobile/v1/ 下（LocalTLS.validateLocalURL 等价）。
    static func meetsBinding(_ url: URL, origin: URL, socket: Bool = false) -> Bool {
        guard let base = URLComponents(url: origin, resolvingAgainstBaseURL: false),
              let target = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return false }
        let expectedScheme = socket ? "wss" : "https"
        let httpSchemeAlternate = (base.scheme == "http" && !socket) ? "http" : nil
        let schemeMatches = target.scheme == expectedScheme || target.scheme == httpSchemeAlternate
        return schemeMatches
            && target.host == base.host
            && (target.port ?? 443) == (base.port ?? 443)
            && target.user == nil && target.password == nil
            && (target.fragment == nil || socket)
            && target.path.hasPrefix("/mobile/v1/")
    }

    /// pin 格式与 SDK validateTlsPin 一致。
    static func isValidPin(_ value: String) -> Bool {
        value.range(of: "^sha256:[a-f0-9]{64}$", options: .regularExpression) != nil
    }

    private static func isLoopback(_ host: String?) -> Bool {
        guard let host else { return false }
        return host == "127.0.0.1" || host == "localhost" || host == "[::1]" || host == "::1"
    }
}

/// 指数退避：750ms 起，上限 15s（对齐 SDK reconnectDelayMs/reconnectMaxDelayMs）。
enum BridgeBackoff {
    static let base: TimeInterval = 0.75
    static let maximum: TimeInterval = 15

    static func delay(attempt: Int) -> TimeInterval {
        let exponent = min(max(attempt, 0), 8)
        return min(maximum, base * pow(2, Double(exponent)))
    }
}

struct BridgeTransportConfig {
    let origin: URL
    let tlsPin: String?
    let allowInsecureLoopback: Bool

    init?(originString: String, tlsPin: String?, allowInsecureLoopback: Bool = false) {
        guard let origin = BridgeOriginValidation.origin(from: originString, allowInsecureLoopback: allowInsecureLoopback) else {
            return nil
        }
        if let tlsPin, !BridgeOriginValidation.isValidPin(tlsPin) { return nil }
        self.origin = origin
        self.tlsPin = tlsPin
        self.allowInsecureLoopback = allowInsecureLoopback
    }

    var isPinned: Bool { tlsPin != nil }
}

/// 双模式 trust delegate：移植参考实现 LocalTLS.swift（DER 解析 SPKI、自签 ECDSA 校验、单证书链）。
final class BridgeTrustDelegate: NSObject, URLSessionTaskDelegate {
    private let config: BridgeTransportConfig

    init(config: BridgeTransportConfig) {
        self.config = config
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        // 两模式共同红线：禁跟随重定向（等价 SDK redirect: error）。
        completionHandler(nil)
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        // 模式 B：交给系统做证书链校验（ATS 默认严格）。
        guard let pin = config.tlsPin else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        // 模式 A：自签叶子 + SPKI pin。
        guard BridgeOriginValidation.isValidPin(pin),
              challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let expected = URLComponents(url: config.origin, resolvingAgainstBaseURL: false),
              unbracketedHost(challenge.protectionSpace.host) == unbracketedHost(expected.host),
              challenge.protectionSpace.port == (expected.port ?? 443),
              let trust = challenge.protectionSpace.serverTrust,
              SecTrustGetCertificateCount(trust) == 1,
              let certificate = SecTrustGetCertificateAtIndex(trust, 0),
              let spki = Self.certificateSPKI(certificate),
              "sha256:" + SHA256.hash(data: spki).map({ String(format: "%02x", $0) }).joined() == pin
        else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        let peerHost = unbracketedHost(challenge.protectionSpace.host) ?? ""
        SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, peerHost as CFString))
        SecTrustSetAnchorCertificates(trust, [certificate] as CFArray)
        SecTrustSetAnchorCertificatesOnly(trust, true)
        guard SecTrustEvaluateWithError(trust, nil) else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }

    private func unbracketedHost(_ value: String?) -> String? {
        guard let value else { return nil }
        return value.hasPrefix("[") && value.hasSuffix("]") ? String(value.dropFirst().dropLast()) : value
    }

    /// SecKey 的外部 EC 表示不含 SPKI 包装，这里直接读证书 DER 里的精确 SPKI 段。
    private static func certificateSPKI(_ certificate: SecCertificate) -> Data? {
        let bytes = [UInt8](SecCertificateCopyData(certificate) as Data)
        func node(_ start: Int) -> (tag: UInt8, body: Int, end: Int)? {
            guard start >= 0, start + 2 <= bytes.count else { return nil }
            let first = Int(bytes[start + 1])
            var offset = start + 2
            var size = first
            if first >= 128 {
                let count = first & 127
                guard count > 0, count <= 4, offset + count <= bytes.count else { return nil }
                size = 0
                for _ in 0..<count {
                    size = (size << 8) | Int(bytes[offset])
                    offset += 1
                }
            }
            guard size >= 0, size <= bytes.count - offset else { return nil }
            return (bytes[start], offset, offset + size)
        }
        guard let outer = node(0), outer.tag == 0x30, outer.end == bytes.count,
              let tbs = node(outer.body), tbs.tag == 0x30 else { return nil }
        var at = tbs.body
        if let version = node(at), version.tag == 0xA0 { at = version.end }
        var issuer: Data?
        var subject: Data?
        var validity: (Date, Date)?
        func date(_ field: (tag: UInt8, body: Int, end: Int)) -> Date? {
            guard field.tag == 0x17 || field.tag == 0x18,
                  let text = String(bytes: bytes[field.body..<field.end], encoding: .ascii),
                  text.range(
                    of: field.tag == 0x17 ? "^[0-9]{12}Z$" : "^[0-9]{14}Z$",
                    options: .regularExpression
                  ) != nil else { return nil }
            let parser = DateFormatter()
            parser.locale = Locale(identifier: "en_US_POSIX")
            parser.timeZone = TimeZone(secondsFromGMT: 0)
            parser.isLenient = false
            parser.twoDigitStartDate = Date(timeIntervalSince1970: -631_152_000)
            parser.dateFormat = field.tag == 0x17 ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'"
            return parser.date(from: text)
        }
        for index in 0..<5 {
            guard let field = node(at), field.end <= tbs.end else { return nil }
            if index == 2 { issuer = Data(bytes[at..<field.end]) }
            if index == 4 { subject = Data(bytes[at..<field.end]) }
            if index == 3, let from = node(field.body), let to = node(from.end), to.end == field.end,
               let start = date(from), let end = date(to) {
                validity = (start, end)
            }
            at = field.end
        }
        // 显式锚定的叶子仍必须未过期且自签名有效。
        guard issuer == subject, let validity, validity.0 <= Date(), validity.1 > Date(),
              let spki = node(at), spki.tag == 0x30, spki.end <= tbs.end,
              let algorithm = node(tbs.end), let signature = node(algorithm.end), signature.tag == 3,
              signature.end == outer.end, signature.body < signature.end, bytes[signature.body] == 0,
              let key = SecCertificateCopyKey(certificate),
              SecKeyVerifySignature(
                key,
                .ecdsaSignatureMessageX962SHA256,
                Data(bytes[outer.body..<tbs.end]) as CFData,
                Data(bytes[(signature.body + 1)..<signature.end]) as CFData,
                nil
              ) else { return nil }
        return Data(bytes[at..<spki.end])
    }
}

/// P1 传输执行器：/info、/state、/conversations 三端点；WS 留 P3。
final class BridgeTransport {
    let config: BridgeTransportConfig
    private let trustDelegate: BridgeTrustDelegate
    private let session: URLSession
    private let requestTimeout: TimeInterval = 45

    init(config: BridgeTransportConfig) {
        self.config = config
        self.trustDelegate = BridgeTrustDelegate(config: config)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 45
        configuration.timeoutIntervalForResource = 60
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        self.session = URLSession(
            configuration: configuration,
            delegate: trustDelegate,
            delegateQueue: nil
        )
    }

    deinit {
        session.invalidateAndCancel()
    }

    func fetchInfo() async throws -> String {
        try await fetch(path: "/info", token: nil)
    }

    func fetchState(token: String) async throws -> String {
        try await fetch(path: "/state", token: token)
    }

    func fetchConversations(token: String) async throws -> String {
        try await fetch(path: "/conversations", token: token)
    }

    func refreshSession(refreshToken: String) async throws -> String {
        try await post(path: "/auth/refresh", token: nil, jsonBody: ["refreshToken": refreshToken])
    }

    func fetch(path: String, token: String?) async throws -> String {
        try await request(path: path, method: "GET", token: token, body: nil)
    }

    func post(path: String, token: String?, jsonBody: [String: Any]) async throws -> String {
        let data = try JSONSerialization.data(withJSONObject: jsonBody)
        return try await request(path: path, method: "POST", token: token, body: data)
    }

    /// WS 连接（§2.7）：wss + /mobile/v1/events，同一 trust delegate 生效。
    func webSocketTask(path: String) throws -> URLSessionWebSocketTask {
        var components = URLComponents(url: config.origin, resolvingAgainstBaseURL: false)
        components?.scheme = components?.scheme == "http" ? "ws" : "wss"
        guard let base = components?.url else { throw BridgeTransportError.invalidOrigin }
        let url = base.appendingPathComponent("mobile/v1" + path)
        guard BridgeOriginValidation.meetsBinding(url, origin: config.origin, socket: true) else {
            throw BridgeTransportError.boundOriginMismatch
        }
        return session.webSocketTask(with: url)
    }

    /// P2 写操作/设备管理：method/path/body 全部由 KMP effect 描述，平台只执行。
    func send(method: String, path: String, bodyJson: String?, token: String) async throws -> String {
        let body = bodyJson.flatMap { $0.data(using: .utf8) }
        return try await request(path: path, method: method, token: token, body: body)
    }

    private func request(path: String, method: String, token: String?, body: Data?) async throws -> String {
        let url = config.origin.appendingPathComponent("mobile/v1" + path)
        guard BridgeOriginValidation.meetsBinding(url, origin: config.origin) else {
            throw BridgeTransportError.boundOriginMismatch
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = requestTimeout
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token {
            // token 永不出现在 URL。
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw BridgeTransportError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            throw BridgeResponseStatus(status: http.statusCode, body: String(data: data, encoding: .utf8) ?? "")
        }
        guard let text = String(data: data, encoding: .utf8) else { throw BridgeTransportError.invalidResponse }
        return text
    }
}

/// 非 2xx 响应：status + 原始 body（错误信封由 KMP 分族解码）。
struct BridgeResponseStatus: LocalizedError {
    let status: Int
    let body: String

    var errorDescription: String? { "服务器返回 \(status)" }
}
