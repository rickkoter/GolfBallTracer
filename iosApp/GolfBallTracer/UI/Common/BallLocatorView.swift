import SwiftUI
import GolfTracking

/// Points the way to where the ball came down, as on Android: the distance from here (green within 3 yards,
/// yellow within 15, red further), an arrow that turns with the phone, the compass bearing, and the landing
/// spot. For a shot whose ball was pinned on GPS, it points to the pin.
struct BallLocatorView: View {
    let landing: LaunchPhysics.Landing?
    let unavailableReason: String?
    /// [landing] is where the ball was pinned on GPS, rather than an estimate from the video.
    let pinned: Bool
    @EnvironmentObject private var location: LocationService
    @EnvironmentObject private var orientation: DeviceOrientation
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 14) {
            Text("Ball Locator").font(.title2.bold()).foregroundStyle(.white).padding(.top, 20)
            if let landing {
                if let here = location.current {
                    body(landing, here)
                } else {
                    Text("Waiting for GPS…").foregroundStyle(Color.golfTextSecondary)
                }
                if pinned {
                    Text(String(format: "Ball pinned at %.6f, %.6f", landing.position.latitude, landing.position.longitude))
                        .font(.footnote).foregroundStyle(Color.golfTextSecondary)
                } else {
                    Text(String(format: "Landing (estimated): %.6f, %.6f", landing.position.latitude, landing.position.longitude))
                        .font(.footnote).foregroundStyle(Color.golfTextSecondary)
                    Text(String(format: "About %.0f yd carry, %.0f yd with roll · left at %.0f mph, %.0f° up",
                                landing.carryMeters / 0.9144, landing.totalMeters / 0.9144, landing.launchSpeedMps * 2.23694, landing.launchAngleDegrees))
                        .font(.footnote).foregroundStyle(Color.golfTextMuted)
                    Text("A rough estimate from the video; look around this spot.").font(.caption).foregroundStyle(Color.golfTextMuted)
                }
            } else {
                Text(unavailableReason ?? "No landing estimate for this shot.")
                    .multilineTextAlignment(.center).foregroundStyle(Color.golfTextSecondary).padding(.horizontal)
            }
            Spacer()
            Button("Close") { dismiss() }.font(.headline).foregroundStyle(Color.golfNeonLime).padding(.bottom, 20)
        }
        .frame(maxWidth: .infinity)
        .background(Color.golfDarkBg)
        .presentationDetents([.medium, .large])
        .onAppear {
            location.start()
            orientation.start()
        }
    }

    @ViewBuilder
    private func body(_ landing: LaunchPhysics.Landing, _ here: GpsCoordinate) -> some View {
        let meters = TrajectoryMath.shared.calculateGpsDistanceMeters(start: here, end: landing.position)
        let yards = meters / 0.9144
        let bearing = LaunchPhysics.shared.bearing(from: here, to: landing.position)
        let color: Color = yards <= 3 ? Color(hex: 0x00E676) : yards <= 15 ? Color(hex: 0xFFD600) : Color(hex: 0xFF3D00)
        ZStack {
            Circle().fill(Color.golfDarkCard)
            if let heading = orientation.headingDegrees {
                // Turned by how far the ball is from where the phone points (the compass reads magnetic north).
                Arrow()
                    .fill(color)
                    .frame(width: 100, height: 140)
                    .rotationEffect(.degrees(bearing - (heading + location.declinationDegrees)))
                    .animation(.easeOut(duration: 0.2), value: heading)
                Circle().stroke(.white.opacity(0.3), lineWidth: 2).frame(width: 140, height: 140)
            } else {
                Text("No compass").foregroundStyle(Color.golfTextMuted)
            }
        }
        .frame(width: 180, height: 180)
        // Close in, to the yard and a bit: the last few yards are what finding the ball comes down to.
        Text(yards < 10 ? String(format: "%.1f yd", yards) : String(format: "%.0f yd", yards))
            .font(.system(size: 44, weight: .heavy)).foregroundStyle(color)
        Text(yards <= 3 ? "You're there. Look around your feet." : String(format: "Head %@ · %.0f°", compassPoint(bearing), bearing))
            .font(.headline).foregroundStyle(.white)
        if here.accuracyMeters > 0 {
            Text(String(format: "GPS ±%.0f yd", Double(here.accuracyMeters) / 0.9144)).font(.footnote).foregroundStyle(Color.golfTextMuted)
        }
    }

    private func compassPoint(_ bearing: Double) -> String {
        ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][Int(((bearing + 22.5).truncatingRemainder(dividingBy: 360)) / 45)]
    }
}

private struct Arrow: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.midX, y: r.minY))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.85, y: r.minY + r.height * 0.55))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.6, y: r.minY + r.height * 0.55))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.6, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.4, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.4, y: r.minY + r.height * 0.55))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.15, y: r.minY + r.height * 0.55))
        p.closeSubpath()
        return p
    }
}

/// The ball locator for a saved shot: its landing estimate, or else where its ball was pinned on GPS.
struct ShotBallLocator: View {
    let shot: ShotRecord

    var body: some View {
        let pinned = shot.estimatedLanding == nil && shot.landingLocation != nil
        BallLocatorView(
            landing: shot.estimatedLanding ?? shot.landingLocation.map { AppleShotsKt.pinnedLanding(ball: $0, tee: shot.launchLocation) },
            unavailableReason: "This shot has no landing estimate or pinned ball position.",
            pinned: pinned
        )
    }
}

extension ShotRecord {
    /// Whether the ball locator has somewhere to point to for this shot.
    var isLocatable: Bool { estimatedLanding != nil || landingLocation != nil }
}
