import DeepSeekHarnessShared
import MarkdownUI
import SwiftUI
import UIKit

struct DSHCodeSyntaxHighlighter: CodeSyntaxHighlighter {
    var dark = false

    private struct Key: Hashable {
        let source: String
        let language: String?
        let dark: Bool
    }

    private static let cache = MarkdownRenderProductCache<Key, NSAttributedString>(
        countLimit: 128, costLimit: 4 * 1_024 * 1_024
    )

    func highlightCode(_ code: String, language: String?) -> Text {
        // 合并自我方内联实现（原 ConversationView）：JSON 代码块走专用高亮（键/值/字面量着色），
        // 其余语言走 shared 的 CodeSyntaxHighlighting 分词高亮。
        if isJSON(code, language: language) {
            return Text(JSONSyntaxHighlighter.highlight(code))
        }
        return Text(highlightedCode(code, language: language))
    }

    private func isJSON(_ code: String, language: String?) -> Bool {
        if let language, ["json", "jsonc"].contains(language.lowercased()) { return true }
        return JSONSyntaxHighlighter.isJSON(code)
    }

    func highlightedCode(_ code: String, language: String?) -> AttributedString {
        let value = attributedCode(code, language: language)
        let source = code as NSString
        var result = AttributedString()
        // 显式转为 SwiftUI 的 Color；NSAttributedString 的 UIKit 属性不能直接交给 Text。
        value.enumerateAttribute(.foregroundColor, in: NSRange(location: 0, length: value.length)) { color, range, _ in
            var part = AttributedString(source.substring(with: range))
            if let color = color as? UIColor { part.foregroundColor = Color(uiColor: color) }
            result += part
        }
        return result
    }

    func attributedCode(_ code: String, language: String?) -> NSAttributedString {
        Self.cache.value(for: Key(source: code, language: language, dark: dark)) {
            let result = NSMutableAttributedString(string: code)
            let tokens = CodeSyntaxHighlighting.shared.highlight(source: code, info: language)
            for token in tokens {
                let start = Int(token.start)
                let end = Int(token.endExclusive)
                guard start >= 0, end > start, end <= result.length else { continue }
                let rgb = UInt32(bitPattern: CodeSyntaxHighlighting.shared.color(kind: token.kind, dark: dark))
                let color = UIColor(red: CGFloat((rgb >> 16) & 0xff) / 255,
                                    green: CGFloat((rgb >> 8) & 0xff) / 255,
                                    blue: CGFloat(rgb & 0xff) / 255, alpha: 1)
                result.addAttribute(.foregroundColor, value: color, range: NSRange(location: start, length: end - start))
            }
            return (NSAttributedString(attributedString: result), code.utf16.count * 12)
        }
    }
}

// MARK: - JSON 代码块高亮（合并自我方内联实现，见上 highlightCode 的 JSON 分支）
enum JSONSyntaxHighlighter {
    private static let expression = try! NSRegularExpression(
        pattern: #"\"(?:\\.|[^\"\\])*\"|-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|\b(?:true|false|null)\b"#
    )
    private static let keySuffixExpression = try! NSRegularExpression(pattern: #"\s*:"#)
    private static let highlighted = MarkdownRenderProductCache<String, AttributedString>(
        countLimit: 128, costLimit: 4 * 1_024 * 1_024
    )
    private static let classifications = MarkdownRenderProductCache<String, Bool>(
        countLimit: 128, costLimit: 1_024 * 1_024
    )

    static func isJSON(_ source: String) -> Bool {
        classifications.value(for: source) {
            let object = source.data(using: .utf8).flatMap { try? JSONSerialization.jsonObject(with: $0) }
            return (object.map(JSONSerialization.isValidJSONObject) ?? false, source.utf16.count * 2)
        }
    }

    static func highlight(_ source: String) -> AttributedString {
        highlighted.value(for: source) {
            // Store semantic colors without theme-resolved UIColor/font metrics.
            (makeHighlight(source), source.utf16.count * 12)
        }
    }

    private static func makeHighlight(_ source: String) -> AttributedString {
        let string = source as NSString
        let fullRange = NSRange(location: 0, length: string.length)
        let matches = expression.matches(in: source, range: fullRange)
        var result = AttributedString()
        var cursor = 0

        for match in matches {
            if match.range.location > cursor {
                result += styled(string.substring(with: NSRange(location: cursor, length: match.range.location - cursor)), color: .primary)
            }
            let token = string.substring(with: match.range)
            result += styled(token, color: color(for: token, in: source, utf16Length: string.length, after: NSMaxRange(match.range)))
            cursor = NSMaxRange(match.range)
        }
        if cursor < string.length {
            result += styled(string.substring(from: cursor), color: .primary)
        }
        return result
    }

    private static func color(for token: String, in source: String, utf16Length: Int, after location: Int) -> Color {
        if token.hasPrefix("\"") {
            // Match the original text range: copying every remaining suffix
            // makes key detection quadratic on long JSON blocks.
            let isKey = keySuffixExpression.firstMatch(
                in: source, options: .anchored,
                range: NSRange(location: location, length: utf16Length - location)
            ) != nil
            return isKey ? DSHColor.ocean : Color(red: 0.10, green: 0.55, blue: 0.38)
        }
        if token == "true" || token == "false" { return DSHColor.purple }
        if token == "null" { return .red.opacity(0.8) }
        return DSHColor.cautionAA
    }

    private static func styled(_ value: String, color: Color) -> AttributedString {
        var result = AttributedString(value)
        result.foregroundColor = color
        return result
    }
}
