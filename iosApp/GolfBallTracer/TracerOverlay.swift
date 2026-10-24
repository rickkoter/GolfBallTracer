import SwiftUI
import GolfTracking

/// The tee ring, the flight (solid where seen, dashed where estimated) and where the ball was seen, drawn
/// over a picture of what the camera saw. Points are screen fractions; [shift] moves them onto a picture
/// taken after the camera moved a little.
struct TracerOverlay: View {
    let tracer: [ScreenPoint]
    var sightings: [ScreenPoint] = []
    var ball: TeeBall?
    var shift: CGPoint = .zero
    var lineWidth: CGFloat = 3

    var body: some View {
        Canvas { context, size in
            func at(_ x: Float, _ y: Float) -> CGPoint {
                CGPoint(x: (CGFloat(x) + shift.x) * size.width, y: (CGFloat(y) + shift.y) * size.height)
            }
            let seen = tracer.filter { $0.confidence >= 0.5 }
            let estimated = tracer.filter { $0.confidence < 0.5 }
            let lime = Color(red: 0.47, green: 1.0, blue: 0.24)
            if seen.count > 1 {
                var path = Path()
                path.addLines(seen.map { at($0.x, $0.y) })
                // A soft glow under the line, as on Android.
                context.stroke(path, with: .color(lime.opacity(0.35)), style: StrokeStyle(lineWidth: lineWidth * 3, lineCap: .round, lineJoin: .round))
                context.stroke(path, with: .color(lime), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
            }
            if !estimated.isEmpty {
                var path = Path()
                path.addLines((seen.last.map { [$0] } ?? []).map { at($0.x, $0.y) } + estimated.map { at($0.x, $0.y) })
                context.stroke(path, with: .color(lime), style: StrokeStyle(lineWidth: lineWidth * 0.85, lineCap: .round, dash: [7, 6]))
            }
            for sighting in sightings {
                let p = at(sighting.x, sighting.y)
                context.fill(Path(ellipseIn: CGRect(x: p.x - 3, y: p.y - 3, width: 6, height: 6)), with: .color(.yellow))
            }
            if let ball {
                let p = at(ball.x, ball.y)
                let r = max(8, CGFloat(ball.radius) * size.height * 2.2)
                context.stroke(Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r)), with: .color(.cyan), lineWidth: 2.5)
            }
        }
        .allowsHitTesting(false)
    }
}
