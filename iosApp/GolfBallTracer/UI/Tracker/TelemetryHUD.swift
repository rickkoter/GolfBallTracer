import SwiftUI
import GolfTracking

/// The card at the top of the Tracer View, as on Android: the brand (or the shot's distance once revealed),
/// the golfer and club the next shot is tagged with, and the two GPS buttons: lock the tee, pin the ball.
struct TelemetryHUD: View {
    @EnvironmentObject private var model: TrackerModel
    @Binding var showClubs: Bool

    var body: some View {
        HUDCard(location: model.location, showClubs: $showClubs)
    }
}

private struct HUDCard: View {
    @EnvironmentObject private var model: TrackerModel
    @ObservedObject var location: LocationService
    @Binding var showClubs: Bool

    var body: some View {
        HStack(spacing: 6) {
            // Brand, or the shot distance once it has been revealed.
            Group {
                if model.isShotRevealed && location.distanceYards > 0 {
                    Text(String(format: "%.0f YDS", location.distanceYards))
                        .font(.system(size: 15, weight: .heavy))
                } else {
                    HStack(spacing: 4) {
                        Image(systemName: "figure.golf").font(.system(size: 14))
                        // The name gives way first when the card is narrow.
                        Text("GolfBallTracer").font(.system(size: 11, weight: .bold)).lineLimit(1).minimumScaleFactor(0.6)
                    }
                }
            }
            .foregroundStyle(Color.golfNeonLime)
            .frame(maxWidth: .infinity, alignment: .leading)
            .layoutPriority(-1)

            // Golfer: a short menu of golfers 1 to 4.
            Menu {
                ForEach(1...Int(ShotRecord.companion.MAX_GOLFERS), id: \.self) { n in
                    Button { model.golfer = n } label: {
                        if n == model.golfer { Label("Golfer \(n)", systemImage: "checkmark") } else { Text("Golfer \(n)") }
                    }
                }
            } label: {
                selection("GOLFER \(model.golfer)", color: .golfNeonCyan)
            }
            Button { showClubs = true } label: { selection(model.club.uppercased(), color: .golfNeonLime) }

            gpsButton(icon: location.launch != nil ? "location.fill" : "location.slash", active: location.launch != nil, color: .golfNeonLime,
                      label: location.launch != nil ? "Tee GPS locked" : "Set tee GPS") {
                if location.recordLaunch() == nil { location.start() }
            }
            gpsButton(icon: "location.north.fill", active: location.landing != nil, color: .golfNeonCyan,
                      label: location.landing != nil ? "Ball GPS pinned" : "Pin ball GPS") {
                location.recordLanding()
            }
        }
        .padding(.leading, 10)
        .padding(.trailing, 4)
        .frame(height: 44)
        .background(Color.golfDarkCard.opacity(0.85), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.golfNeonLime.opacity(0.35), lineWidth: 1))
    }

    /// A tappable choice on the card, such as the club or the golfer.
    private func selection(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.system(size: 13, weight: .heavy))
            .lineLimit(1)
            .fixedSize()
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(color.opacity(0.15), in: RoundedRectangle(cornerRadius: 8))
    }

    private func gpsButton(icon: String, active: Bool, color: Color, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(active ? Color.golfDarkBg : color)
                .frame(width: 30, height: 30)
                .background(active ? color : Color.golfDarkSurface, in: RoundedRectangle(cornerRadius: 8))
        }
        .frame(width: 34, height: 40)
        .accessibilityLabel(label)
    }
}

/// Picking the club, with its category, as on Android.
struct ClubSheet: View {
    @Binding var selected: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(ClubType.entries, id: \.displayName) { club in
                Button {
                    selected = club.displayName
                    dismiss()
                } label: {
                    HStack {
                        Text(club.displayName).foregroundStyle(club.displayName == selected ? Color.golfNeonLime : .white)
                        Spacer()
                        Text(club.category).foregroundStyle(Color.golfTextSecondary)
                    }
                    .font(.system(size: 17))
                }
            }
            .navigationTitle("Select Club")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
