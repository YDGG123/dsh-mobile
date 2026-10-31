#!/usr/bin/env swift
// 从品牌 SVG 的原始路径生成透明 APNG；所有帧独立编码，便于双端一致播放。
import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
// 2026-10-27：数据源换成用户新虎鲸矢量（build 37 的 `DshOrcaMark`，现名 `OrcarnessOrcaBlack`）。
// 它有 **8 条 path**（旧版只有 1 条），且用了 **Q 二次曲线** —— 旧解析只取 firstMatch
// 且只支持 M/C/Z，照搬会画出残缺形状/直接崩。这里改成：遍历全部 path，按序并入同一个
// CGPath；指令支持 M/L/H/V/C/Q/Z（H/V 是 1 参数的直线）。
let source = root.appendingPathComponent("DeepSeekHarnessMobile/Resources/Assets.xcassets/OrcarnessOrcaBlack.imageset/orcarness-orca-black.svg")
let svg = try String(contentsOf: source, encoding: .utf8)
let pathRegex = try NSRegularExpression(pattern: #"<path d="([^"]+)""#)
let matches = pathRegex.matches(in: svg, range: NSRange(svg.startIndex..., in: svg))
precondition(!matches.isEmpty, "SVG 里没有 path")
let tokenRegex = try NSRegularExpression(pattern: #"[MCQZLHV]|[-+]?(?:\d*\.\d+|\d+\.?\d*)"#)
let path = CGMutablePath()
for match in matches {
    let pathData = String(svg[Range(match.range(at: 1), in: svg)!])
    let tokens = tokenRegex.matches(in: pathData, range: NSRange(pathData.startIndex..., in: pathData)).map {
        String(pathData[Range($0.range, in: pathData)!])
    }
    var index = 0
    func number() -> CGFloat { defer { index += 1 }; return CGFloat(Double(tokens[index])!) }
    while index < tokens.count {
        let command = tokens[index]
        index += 1
        switch command {
        case "M": path.move(to: CGPoint(x: number(), y: number()))
        case "L": path.addLine(to: CGPoint(x: number(), y: number()))
        case "H": path.addLine(to: CGPoint(x: number(), y: path.currentPoint.y))
        case "V": path.addLine(to: CGPoint(x: path.currentPoint.x, y: number()))
        case "C":
            let a = CGPoint(x: number(), y: number())
            let b = CGPoint(x: number(), y: number())
            let end = CGPoint(x: number(), y: number())
            path.addCurve(to: end, control1: a, control2: b)
        case "Q":
            let c = CGPoint(x: number(), y: number())
            let end = CGPoint(x: number(), y: number())
            path.addQuadCurve(to: end, control: c)
        case "Z": path.closeSubpath()
        default: fatalError("不支持的 SVG 路径指令：\(command)")
        }
    }
}

let side = 192
let frameCount = 48
let frameMilliseconds = 50
let colorSpace = CGColorSpaceCreateDeviceRGB()
let directory = root.appendingPathComponent("Design/loading-logo", isDirectory: true)
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

func encodePNG(_ image: CGImage) -> Data {
    let data = NSMutableData()
    let destination = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, image, nil)
    precondition(CGImageDestinationFinalize(destination))
    return data as Data
}

func frame(_ phase: Double) -> CGImage {
    let context = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8,
                            bytesPerRow: side * 4, space: colorSpace,
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    context.translateBy(x: 0, y: CGFloat(side))
    context.scaleBy(x: 1, y: -1)
    let angle = phase * 2 * Double.pi
    // 气泡轻轻升起；首尾位置、透明度和鲸鱼姿态连续，不出现循环跳帧。
    for bubble in 0..<3 {
        let t = (phase + Double(bubble) / 3).truncatingRemainder(dividingBy: 1)
        let opacity = pow(sin(t * Double.pi), 2) * 0.48
        let x = 151 + Double(bubble % 2) * 10 + sin(angle + Double(bubble)) * 3
        let y = 126 - t * 84
        let radius = 2.4 + Double(bubble) * 0.7
        context.setStrokeColor(CGColor(red: 0.32, green: 0.62, blue: 0.96, alpha: opacity))
        context.setLineWidth(1.5)
        context.strokeEllipse(in: CGRect(x: x, y: y, width: radius * 2, height: radius * 2))
    }
    context.saveGState()
    context.translateBy(x: 92, y: 92 + sin(angle) * 5)
    context.rotate(by: sin(angle) * 0.055)
    // 缩放**从 path 包围盒推导**，不再硬编码 0.155（那是按旧 SVG 882×832 调的；
    // 换 1024×1024 新矢量后没重算 → 图形溢出画布、残缺——2026-10-27 返工教训：
    // 换数据源第一件事是找硬编码的几何参数）。
    let box = path.boundingBoxOfPath
    let fit = 150.0 / max(box.width, box.height)   // 目标占 192 画布约 150pt，四边留白
    let scale = fit * (1 + sin(angle) * 0.018)
    context.scaleBy(x: scale, y: scale)
    // 包围盒中心平移到画布中心（长宽比保持：两轴同一 scale，不拉变形）
    context.translateBy(x: -(box.minX + box.width / 2), y: -(box.minY + box.height / 2))
    context.addPath(path)
    context.clip()
    let gradient = CGGradient(colorsSpace: colorSpace, colors: [
        CGColor(red: 0.16, green: 0.39, blue: 0.80, alpha: 1),
        CGColor(red: 0.36, green: 0.67, blue: 0.99, alpha: 1)
    ] as CFArray, locations: [0, 1])!
    // 渐变坐标同样从包围盒推导（旧坐标 -520,700 是旧画布的）
    context.drawLinearGradient(gradient,
        start: CGPoint(x: box.minX, y: box.maxY), end: CGPoint(x: box.maxX, y: box.minY),
        options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    let shimmerX = box.minX - 150 + phase * (box.width + 300)
    let shimmer = CGGradient(colorsSpace: colorSpace, colors: [
        CGColor(gray: 1, alpha: 0), CGColor(gray: 1, alpha: 0.50), CGColor(gray: 1, alpha: 0)
    ] as CFArray, locations: [0, 0.5, 1])!
    context.drawLinearGradient(shimmer, start: CGPoint(x: shimmerX, y: box.minY), end: CGPoint(x: shimmerX + box.width * 0.18, y: box.maxY), options: [])
    context.restoreGState()
    return context.makeImage()!
}

func uint32(_ value: UInt32) -> Data {
    var value = value.bigEndian
    return withUnsafeBytes(of: &value) { Data($0) }
}
func read32(_ data: Data, _ offset: Int) -> UInt32 {
    data[offset..<offset+4].reduce(0) { ($0 << 8) | UInt32($1) }
}
func crc32(_ data: Data) -> UInt32 {
    var crc = UInt32.max
    for byte in data {
        crc ^= UInt32(byte)
        for _ in 0..<8 { crc = (crc >> 1) ^ (crc & 1 == 1 ? 0xEDB88320 : 0) }
    }
    return ~crc
}
func chunk(_ type: String, _ data: Data) -> Data {
    let body = Data(type.utf8) + data
    return uint32(UInt32(data.count)) + body + uint32(crc32(body))
}
func chunks(_ data: Data) -> [(String, Data)] {
    var result = [(String, Data)]()
    var offset = 8
    while offset < data.count {
        let length = Int(read32(data, offset))
        let type = String(data: data[offset+4..<offset+8], encoding: .ascii)!
        result.append((type, data.subdata(in: offset+8..<offset+8+length)))
        offset += 12 + length
    }
    return result
}

var animation = Data([137, 80, 78, 71, 13, 10, 26, 10])
var sequence: UInt32 = 0
for i in 0..<frameCount {
    let png = encodePNG(frame(Double(i) / Double(frameCount)))
    let parts = chunks(png)
    if i == 0 {
        animation += chunk("IHDR", parts.first { $0.0 == "IHDR" }!.1)
        animation += chunk("acTL", uint32(UInt32(frameCount)) + uint32(0))
        try png.write(to: directory.appendingPathComponent("poster.png"))
    }
    // 全画布 SOURCE 帧，避免透明图层叠加留下残影，也无需复杂的 disposal 合成。
    var control = uint32(sequence) + uint32(UInt32(side)) + uint32(UInt32(side)) + uint32(0) + uint32(0)
    control += Data([0, UInt8(frameMilliseconds), 3, 232, 0, 0]) // delay=50/1000s, dispose=NONE, blend=SOURCE
    animation += chunk("fcTL", control)
    sequence += 1
    for part in parts where part.0 == "IDAT" {
        if i == 0 { animation += chunk("IDAT", part.1) }
        else {
            animation += chunk("fdAT", uint32(sequence) + part.1)
            sequence += 1
        }
    }
}
animation += chunk("IEND", Data())
let destinations = [
    "Design/loading-logo/whaleness-loading.apng",
    "DeepSeekHarnessMobile/Resources/Assets.xcassets/OrcarnessLoading.dataset/orcarness-loading.apng",
    "androidApp/src/main/res/raw/orcarness_loading.png"
]
for relative in destinations {
    let url = root.appendingPathComponent(relative)
    try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try animation.write(to: url)
}
let asset = #"{"data":[{"filename":"orcarness-loading.apng","idiom":"universal"}],"info":{"author":"xcode","version":1}}"#
try asset.write(to: root.appendingPathComponent("DeepSeekHarnessMobile/Resources/Assets.xcassets/OrcarnessLoading.dataset/Contents.json"), atomically: true, encoding: .utf8)
print("已生成 \(frameCount) 帧 / \(Double(frameCount * frameMilliseconds) / 1000)s / \(animation.count) 字节的透明循环 APNG")

let poster = try Data(contentsOf: directory.appendingPathComponent("poster.png"))
let posterAsset = root.appendingPathComponent("DeepSeekHarnessMobile/Resources/Assets.xcassets/OrcarnessLoadingPoster.imageset")
try FileManager.default.createDirectory(at: posterAsset, withIntermediateDirectories: true)
try poster.write(to: posterAsset.appendingPathComponent("poster.png"))
try #"{"images":[{"filename":"poster.png","idiom":"universal"}],"info":{"author":"xcode","version":1}}"#
    .write(to: posterAsset.appendingPathComponent("Contents.json"), atomically: true, encoding: .utf8)
try poster.write(to: root.appendingPathComponent("androidApp/src/main/res/drawable-nodpi/orcarness_loading_poster.png"))
