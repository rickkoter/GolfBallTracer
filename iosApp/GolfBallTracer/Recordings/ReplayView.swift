import SwiftUI
import GolfTracking

/// Plays one recording back through the shared shot detection and shows each shot it finds: the ball
/// ringed on the tee and its flight drawn over the frame, as the Android app traced it.
struct ReplayView: View {
    let recording: Recording

    private enum Phase {
        case replaying
        case done(RecordingReplay.Result, seconds: Double)
        case failed(String)
    }

    @State private var state: Phase = .replaying

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                switch state {
                case .replaying:
                    HStack(spacing: 12) {
                        ProgressView()
                        Text("Replaying \(recording.kindLabel.lowercased())…").foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, minHeight: 200)
                case .failed(let message):
                    Label(message, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.secondary)
                case .done(let result, let seconds):
                    summary(result, took: seconds)
                    if result.shots.isEmpty {
                        Text(result.strikes == 0 ? "No strike was heard in this recording." : "No shot was found.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(Array(result.shots.enumerated()), id: \.offset) { index, shot in
                        ShotCard(number: index + 1, shot: shot, showNumber: result.shots.count > 1)
                    }
                    if !result.notes.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Notes").font(.headline)
                            ForEach(Array(result.notes.enumerated()), id: \.offset) { _, note in
                                Text(String(format: "%.1f s · %@", note.atSeconds, note.text)).font(.callout)
                            }
                        }
                    }
                }
            }
            .padding()
        }
        .navigationTitle(recording.kindLabel)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                // To send a shot to whoever is looking into the tracking.
                ShareLink(item: recording.url) { Label("Share", systemImage: "square.and.arrow.up") }
            }
        }
        .task { await replay() }
    }

    private func summary(_ result: RecordingReplay.Result, took seconds: Double) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(recording.name).font(.headline.monospacedDigit())
            Text(String(format: "%d frames over %.1f s · %d strike%@ heard · replayed in %.1f s",
                        result.frameCount, result.seconds, result.strikes, result.strikes == 1 ? "" : "s", seconds))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private func replay() async {
        let url = recording.url
        let started = Date()
        // The replay takes a few seconds of work: off the main thread.
        let outcome: Result<RecordingReplay.Result, Error> = await Task.detached(priority: .userInitiated) {
            do {
                let data = try Data(contentsOf: url)
                return .success(AppleBytesKt.replayRecording(contents: data))
            } catch {
                return .failure(error)
            }
        }.value
        switch outcome {
        case .success(let result): state = .done(result, seconds: Date().timeIntervalSince(started))
        case .failure(let error): state = .failed("Couldn't read this recording: \(error.localizedDescription)")
        }
    }
}

/// One shot: the frame the capture started from, with the ball and its flight drawn on it.
private struct ShotCard: View {
    let number: Int
    let shot: RecordingReplay.ReplayedShot
    let showNumber: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(showNumber ? "Shot \(number): \(shot.summary)" : shot.summary)
                .font(.subheadline.weight(.semibold))
            if let frame = shot.frame, let image = grayImage(frame) {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .aspectRatio(CGFloat(frame.width) / CGFloat(frame.height), contentMode: .fit)
                    .overlay { TracerOverlay(tracer: shot.flight?.tracer ?? [], sightings: shot.flight?.sightings ?? [], ball: shot.ball) }
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .frame(maxWidth: 360)
            }
            if let flight = shot.flight {
                Text("Ball picked out in \(flight.trackedFrames) frames" + (flight.estimated ? " · the dashed part is estimated" : ""))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// A grey image of an analysis frame, from its pixels as the shared code holds them.
private func grayImage(_ frame: LumaFrame) -> CGImage? {
    let pixels = frame.pixels() as Data
    guard let provider = CGDataProvider(data: pixels as CFData) else { return nil }
    return CGImage(
        width: Int(frame.width), height: Int(frame.height),
        bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: Int(frame.width),
        space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
        provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent
    )
}
