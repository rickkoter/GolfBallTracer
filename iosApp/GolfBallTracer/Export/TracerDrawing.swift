import CoreGraphics
import Foundation
import GolfTracking
import UIKit

/// Drawing a shot's tracer and card into pictures and videos, as the Android app draws them.
enum TracerDrawing {
    /// The tracer cut into stretches seen (solid) and estimated (dashed), each starting where the last ended.
    static func runs(_ points: [ScreenPoint]) -> [(points: [ScreenPoint], estimated: Bool)] {
        guard var estimated = points.first.map({ $0.confidence < 0.5 }) else { return [] }
        var runs: [(points: [ScreenPoint], estimated: Bool)] = []
        var current: [ScreenPoint] = []
        for p in points {
            let e = p.confidence < 0.5
            if e != estimated, !current.isEmpty {
                runs.append((current, estimated))
                current = [current.last!]
                estimated = e
            }
            current.append(p)
        }
        if current.count >= 2 { runs.append((current, estimated)) }
        return runs
    }

    /// Old tracers were saved in pixels of a 1080 × 1920 view rather than as screen fractions.
    static func isPixelTracer(_ points: [ScreenPoint]) -> Bool { points.contains { $0.x > 5 || $0.y > 5 } }

    /// Points as screen fractions, whichever way they were saved.
    static func normalized(_ points: [ScreenPoint]) -> [ScreenPoint] {
        guard isPixelTracer(points) else { return points }
        return points.map { ScreenPoint(x: $0.x / 1080, y: $0.y / 1920, timestampMs: $0.timestampMs, confidence: $0.confidence) }
    }

    /// Draws the tracer over a picture [size] big (y down): glow, core line, and the ball at its end.
    static func drawTracer(_ points: [ScreenPoint], style: TracerStyle, in ctx: CGContext, size: CGSize, width: CGFloat, blurGlow: Bool, endMarker: Bool) {
        let pts = normalized(points)
        guard pts.count >= 2 else { return }
        func at(_ p: ScreenPoint) -> CGPoint { CGPoint(x: CGFloat(p.x) * size.width, y: CGFloat(p.y) * size.height) }
        let core = UIColor(argb: style.colorHex).cgColor
        let glow = UIColor(argb: style.secondaryHex).cgColor
        ctx.saveGState()
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)
        for run in runs(pts) {
            let path = CGMutablePath()
            path.addLines(between: run.points.map(at))
            let dash: [CGFloat] = run.estimated ? [width * 2.5, width * 2.1] : []
            ctx.setLineDash(phase: 0, lengths: dash)
            ctx.saveGState()
            if blurGlow { ctx.setShadow(offset: .zero, blur: width * 1.2, color: glow) }
            ctx.addPath(path)
            ctx.setStrokeColor(glow)
            ctx.setLineWidth(width * 2.1)
            ctx.strokePath()
            ctx.restoreGState()
            ctx.addPath(path)
            ctx.setStrokeColor(core)
            ctx.setLineWidth(width)
            ctx.strokePath()
        }
        ctx.setLineDash(phase: 0, lengths: [])
        if endMarker, let last = pts.last {
            let p = at(last)
            ctx.setStrokeColor(core)
            ctx.setLineWidth(width * 0.42)
            ctx.strokeEllipse(in: CGRect(x: p.x - width * 1.7, y: p.y - width * 1.7, width: width * 3.4, height: width * 3.4))
            ctx.setFillColor(UIColor.white.cgColor)
            ctx.fillEllipse(in: CGRect(x: p.x - width * 0.85, y: p.y - width * 0.85, width: width * 1.7, height: width * 1.7))
        }
        ctx.restoreGState()
    }

    /// The shot's card at the bottom left: distance, golfer, club, date and GPS, as on Android's snapshots.
    static func drawCard(for shot: ShotRecord, in ctx: CGContext, size: CGSize) {
        let w = size.width, h = size.height
        let padding = w * 0.04
        let rect = CGRect(x: padding, y: h - h * 0.22 - padding, width: w * 0.55, height: h * 0.22)
        let style = shot.style
        UIGraphicsPushContext(ctx)
        defer { UIGraphicsPopContext() }
        let card = UIBezierPath(roundedRect: rect, cornerRadius: 24)
        UIColor(red: 15 / 255, green: 23 / 255, blue: 42 / 255, alpha: 200 / 255).setFill()
        card.fill()
        UIColor(argb: style.colorHex).setStroke()
        card.lineWidth = w * 0.004
        card.stroke()
        func text(_ s: String, _ y: CGFloat, size: CGFloat, color: UIColor, bold: Bool) {
            let font = bold ? UIFont.boldSystemFont(ofSize: size) : UIFont.systemFont(ofSize: size)
            // Baseline at y, as Android draws text.
            (s as NSString).draw(at: CGPoint(x: rect.minX + 24, y: y - font.ascender), withAttributes: [.font: font, .foregroundColor: color])
        }
        var y = rect.minY + rect.height * 0.25
        text("⚡ GolfBallTracer", y, size: w * 0.032, color: UIColor(argb: style.colorHex), bold: true)
        y += rect.height * 0.35
        text(shot.distance?.label ?? "— YDS", y, size: w * 0.065, color: .white, bold: true)
        y += rect.height * 0.22
        let date = DateFormatter()
        date.locale = Locale(identifier: "en_US_POSIX")
        date.dateFormat = "MMM dd, yyyy • HH:mm"
        let line = [shot.golferNumber.map { "GOLFER \($0)" }, shot.club.uppercased(), date.string(from: shot.date)].compactMap { $0 }.joined(separator: " • ")
        text(line, y, size: w * 0.026, color: .lightGray, bold: false)
        y += rect.height * 0.16
        let gps = shot.launchLocation.map { String(format: "GPS: %.4f, %.4f", $0.latitude, $0.longitude) } ?? "GPS Telemetry Active"
        text(gps, y, size: w * 0.026, color: .lightGray, bold: false)
    }

    /// The snapshot: [still] with the shot's tracer and card, as a JPEG.
    static func snapshot(still: CGImage?, shot: ShotRecord) -> Data? {
        let size = still.map { CGSize(width: $0.width, height: $0.height) } ?? CGSize(width: 1080, height: 1920)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
            let ctx = context.cgContext
            if let still {
                UIImage(cgImage: still).draw(in: CGRect(origin: .zero, size: size))
            } else {
                UIColor.black.setFill()
                ctx.fill(CGRect(origin: .zero, size: size))
            }
            var points = shot.points
            if points.isEmpty { points = normalized(TrajectoryMath.shared.generateDefaultTracer(viewWidth: 1080, viewHeight: 1920, apexFactor: 0.45)) }
            drawTracer(points, style: shot.style, in: ctx, size: size, width: size.width * 0.012, blurGlow: true, endMarker: true)
            drawCard(for: shot, in: ctx, size: size)
        }
        return image.jpegData(compressionQuality: 0.92)
    }
}
