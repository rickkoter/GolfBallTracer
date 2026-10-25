import SwiftUI
import GolfTracking

/// The tracer over the picture, as Android's BallTracerCanvas draws it: a ring on the ball while its shot is
/// captured; then the flight with its glow (dashed where estimated), sparks along it, the tee marker and a
/// pulsing pin where it ends. Where no flight was found, every spot the detector saw, early cyan to late red.
/// Tapping ends a capture early or shows the shot's distance; with curve tuning on, dragging bends the curve.
struct TracerCanvasView: View {
    @EnvironmentObject private var model: TrackerModel

    var body: some View {
        GeometryReader { geo in
            TimelineView(.animation(minimumInterval: 1 / 30, paused: model.teeLocation == nil && model.detectedPoints.isEmpty)) { timeline in
                Canvas { context, size in
                    let t = timeline.date.timeIntervalSinceReferenceDate
                    // A pulse from 0.8 to 1.4 and back each 2 s.
                    let phase = (t.truncatingRemainder(dividingBy: 2)) / 2
                    let pulse = CGFloat(0.8 + 0.6 * (phase < 0.5 ? phase * 2 : 2 - phase * 2))
                    draw(in: &context, size: size, pulse: pulse)
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: model.editMode ? 4 : 10_000)
                    .onChanged { value in
                        model.dragCurve(to: CGPoint(x: value.location.x / geo.size.width, y: value.location.y / geo.size.height))
                    }
            )
            .simultaneousGesture(TapGesture().onEnded { model.tapped() })
        }
    }

    private func draw(in context: inout GraphicsContext, size: CGSize, pulse: CGFloat) {
        let w = size.width, h = size.height
        let style = model.tracerStyle
        let core = Color(argb: style.colorHex)
        let glow = Color(argb: style.secondaryHex)
        func at(_ p: ScreenPoint) -> CGPoint { CGPoint(x: CGFloat(p.x) * w, y: CGFloat(p.y) * h) }
        func circle(_ c: CGPoint, _ r: CGFloat) -> Path { Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)) }

        // Diagnostic: every spot the detector saw, when no flight was found.
        if model.isBallFlightMissing, let first = model.detectedSpots.map(\.timestampMs).min(), let last = model.detectedSpots.map(\.timestampMs).max() {
            let span = Double(max(1, last - first))
            for spot in model.detectedSpots {
                let f = Double(spot.timestampMs - first) / span
                context.fill(circle(at(spot), w * 0.006), with: .color(Color(red: f, green: 1 - f, blue: 1 - f).opacity(0.7)))
            }
        }

        let points = model.detectedPoints
        // The ball being followed: a ring hugging it.
        if let tee = model.teeLocation, points.isEmpty {
            let c = CGPoint(x: tee.x * w, y: tee.y * h)
            if let radius = model.teeBallRadius {
                let r = max(CGFloat(radius) * h, w * 0.01)
                context.stroke(circle(c, (r + w * 0.02) * pulse), with: .color(glow), lineWidth: w * 0.005)
                context.stroke(circle(c, r + w * 0.006), with: .color(core), lineWidth: w * 0.006)
            } else {
                context.stroke(circle(c, w * 0.055 * pulse), with: .color(glow), lineWidth: w * 0.005)
                context.stroke(circle(c, w * 0.024), with: .color(core), lineWidth: w * 0.008)
                context.fill(circle(c, w * 0.008), with: .color(.white))
            }
        }
        guard points.count >= 2 else { return }

        // Where the ball was seen is drawn solid; where its flight was estimated, dashed.
        for run in TracerDrawing.runs(points) {
            var path = Path()
            path.addLines(run.points.map(at))
            let dash: [CGFloat] = run.estimated ? [w * 0.03, w * 0.025] : []
            context.stroke(path, with: .color(glow), style: StrokeStyle(lineWidth: w * 0.024, lineCap: .round, lineJoin: .round, dash: dash))
            context.stroke(path, with: .color(core), style: StrokeStyle(lineWidth: w * 0.010, lineCap: .round, lineJoin: .round, dash: dash))
        }
        // Sparks along the curve.
        for k in 0..<8 {
            let fraction = Double(k + 1) / 9
            let p = at(points[Int(fraction * Double(points.count - 1))])
            context.fill(circle(p, w * 0.008 * CGFloat(1 + 0.3 * sin(Double(k) * 2))), with: .color(.white))
        }
        // The tee, and a pulsing pin where the flight ends.
        let start = at(points.first!)
        context.fill(circle(start, w * 0.018), with: .color(core))
        context.fill(circle(start, w * 0.008), with: .color(.white))
        let end = at(points.last!)
        context.stroke(circle(end, w * 0.035 * pulse), with: .color(glow), lineWidth: 3)
        context.fill(circle(end, w * 0.015), with: .color(core))
        context.fill(circle(end, w * 0.006), with: .color(.white))
    }
}
