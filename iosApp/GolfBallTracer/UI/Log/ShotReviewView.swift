import AVFoundation
import SwiftUI
import GolfTracking

/// A shot's review, as on Android: its video on a loop with the tracer growing along with the ball (or its
/// snapshot), where the tracer came from, the distance, club, golfer and GPS, the ball locator, and
/// exporting the video with its tracer, sharing its recording and sharing its snapshot.
struct ShotReviewView: View {
    let shotId: String
    @EnvironmentObject private var store: ShotStore
    @EnvironmentObject private var router: AppRouter
    @EnvironmentObject private var tracker: TrackerModel
    @State private var showSnapshot = false
    @State private var locating = false
    @State private var packing = false
    @State private var exportProgress: Double?
    @State private var sharing: SharedFiles?
    @State private var toast: Toast?

    var body: some View {
        Group {
            if let shot = store.find(shotId) { content(shot) } else { Text("This shot was deleted.").foregroundStyle(Color.golfTextSecondary) }
        }
        .background(Color.golfDarkBg)
        .navigationTitle("Shot Review")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Color.golfDarkBg, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbar { toolbar }
        .sheet(item: $sharing) { ShareSheet(items: $0.urls) }
        .sheet(isPresented: $locating) {
            if let shot = store.find(shotId) {
                ShotBallLocator(shot: shot).environmentObject(tracker.location).environmentObject(tracker.orientation)
            }
        }
        .overlay(alignment: .bottom) {
            if let toast { ToastView(toast: toast, onShare: { sharing = SharedFiles(urls: $0); self.toast = nil }, onClose: { self.toast = nil }) }
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            if let shot = store.find(shotId) {
                if shot.videoURL != nil {
                    // The video with the tracer drawn in, saved to Photos to post or send.
                    Button { exportVideo(shot) } label: {
                        if let p = exportProgress { ProgressView(value: p).progressViewStyle(.circular).tint(Color.golfNeonLime) }
                        else { Image(systemName: "film").foregroundStyle(Color.golfNeonLime) }
                    }
                    .disabled(exportProgress != nil)
                    .accessibilityLabel("Export video with tracer")
                }
                if shot.hasRecording {
                    // The recording and tracking data as one zip, e.g. to Google Drive for analysis later.
                    Button {
                        packing = true
                        Task {
                            let urls = await ShotBundles.make([shot])
                            packing = false
                            if !urls.isEmpty { sharing = SharedFiles(urls: urls) }
                        }
                    } label: {
                        if packing { ProgressView().tint(Color.golfNeonCyan) } else { Image(systemName: "video.fill").foregroundStyle(Color.golfNeonCyan) }
                    }
                    .disabled(packing)
                    .accessibilityLabel("Share recording")
                }
                Button {
                    if let url = shot.snapshotURL { sharing = SharedFiles(urls: [url]) }
                } label: { Image(systemName: "square.and.arrow.up").foregroundStyle(Color.golfNeonLime) }
                .disabled(shot.snapshotURL == nil)
                .accessibilityLabel("Share snapshot")
            }
        }
    }

    private func exportVideo(_ shot: ShotRecord) {
        exportProgress = 0
        Task {
            let outcome = await TracerVideoExporter.export([shot]) { p in Task { @MainActor in exportProgress = p } }
            exportProgress = nil
            toast = Toast(text: outcome.saved.isEmpty ? "Couldn't make the video" : outcome.summary, share: outcome.saved.isEmpty ? nil : outcome.saved)
        }
    }

    private func content(_ shot: ShotRecord) -> some View {
        ScrollView {
            VStack(spacing: 18) {
                let video = shot.videoURL
                ZStack {
                    Color.golfDarkCard
                    if let video, !showSnapshot {
                        ShotVideoPlayer(url: video, points: TracerDrawing.normalized(shot.points), style: shot.style,
                                        videoStartMs: shot.videoStartMs?.int64Value, frameAspect: CGFloat(shot.frameAspect?.floatValue ?? Float(9.0 / 16.0)))
                    } else if let url = shot.snapshotURL, let image = UIImage(contentsOfFile: url.path) {
                        // The whole snapshot, card and all.
                        Image(uiImage: image).resizable().scaledToFit()
                    } else {
                        Image(systemName: "figure.golf").font(.system(size: 64)).foregroundStyle(Color.golfNeonLime)
                    }
                }
                .frame(height: video != nil && !showSnapshot ? 460 : 380)
                .clipShape(RoundedRectangle(cornerRadius: 20))

                if video != nil {
                    HStack {
                        Text((shot.tracedFrom == ShotRecord.companion.TRACED_FROM_VIDEO ? "Tracer from the full video" : "Tracer from live camera")
                             + (shot.tracerEstimated ? " • dashed part estimated" : "") + " • tap video to pause")
                            .font(.caption).foregroundStyle(Color.golfTextSecondary)
                        Spacer()
                        if shot.snapshotURL != nil {
                            Button(showSnapshot ? "Show video" : "Show snapshot") { showSnapshot.toggle() }
                                .font(.caption).foregroundStyle(Color.golfNeonCyan)
                        }
                    }
                }

                telemetry(shot)

                Button { locating = true } label: {
                    Label(shot.isLocatable ? "Locate Ball" : "No landing spot to locate", systemImage: "location.fill")
                        .font(.headline)
                        .foregroundStyle(shot.isLocatable ? Color.golfNeonCyan : Color.golfTextMuted)
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.golfTextMuted.opacity(0.5)))
                }
                .disabled(!shot.isLocatable)

                Button {
                    router.logPath = []
                    router.tab = .tracer
                } label: {
                    Text("Record Next Shot").font(.headline).foregroundStyle(Color.golfDarkBg)
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .background(Color.golfNeonLime, in: RoundedRectangle(cornerRadius: 16))
                }
            }
            .padding(16)
            // Room to scroll clear of the tab bar.
            .padding(.bottom, 70)
        }
    }

    private func telemetry(_ shot: ShotRecord) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("SHOT TELEMETRY").font(.system(size: 12, weight: .bold)).kerning(1).foregroundStyle(Color.golfNeonLime)
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    let distance = shot.distance
                    Text("Total Distance").font(.caption).foregroundStyle(Color.golfTextSecondary)
                    Text(distance?.label ?? "—").font(.system(size: 28, weight: .black)).foregroundStyle(.white)
                    Text(distance == nil ? "Pin the ball's GPS to measure it" : distance!.estimated ? "Estimated from the launch" : "Measured on GPS")
                        .font(.footnote).foregroundStyle(Color.golfTextMuted)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 4) {
                    Text("Club").font(.caption).foregroundStyle(Color.golfTextSecondary)
                    Text(shot.club.uppercased() + (shot.golferNumber.map { " • GOLFER \($0)" } ?? ""))
                        .font(.system(size: 16, weight: .bold)).foregroundStyle(Color.golfNeonLime)
                        .padding(.horizontal, 12).padding(.vertical, 4)
                        .background(Color.golfNeonLime.opacity(0.15), in: RoundedRectangle(cornerRadius: 8))
                }
            }
            Divider().overlay(Color.white.opacity(0.1))
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Tee GPS Location").font(.caption).foregroundStyle(Color.golfTextSecondary)
                    Text(shot.launchLocation.map { String(format: "%.4f, %.4f", $0.latitude, $0.longitude) } ?? "N/A")
                        .font(.system(size: 13, weight: .medium)).foregroundStyle(.white)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text("Landing GPS Pin").font(.caption).foregroundStyle(Color.golfTextSecondary)
                    Text(shot.landingLocation.map { String(format: "%.4f, %.4f", $0.latitude, $0.longitude) } ?? "Estimated")
                        .font(.system(size: 13, weight: .medium)).foregroundStyle(Color.golfNeonCyan)
                }
            }
        }
        .padding(20)
        .background(Color.golfDarkCard, in: RoundedRectangle(cornerRadius: 20))
    }
}

/**
 * Plays a shot's recording on a loop with its tracer drawn over it, growing in step with the ball when the
 * video's timing is known. The video fills its space; the tracer is drawn in the part of the video the shot
 * was traced in ([frameAspect], the center of the picture). Tapping pauses and resumes.
 */
struct ShotVideoPlayer: View {
    let url: URL
    let points: [ScreenPoint]
    let style: TracerStyle
    let videoStartMs: Int64?
    let frameAspect: CGFloat
    @StateObject private var player = LoopingPlayer()

    var body: some View {
        GeometryReader { geo in
            ZStack {
                PlayerLayerView(player: player.player)
                TimelineView(.animation(minimumInterval: 1 / 30)) { _ in
                    Canvas { context, size in draw(in: &context, size: size, positionMs: player.positionMs) }
                }
                .allowsHitTesting(false)
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .contentShape(Rectangle())
            .onTapGesture { player.toggle() }
        }
        .task(id: url) { await player.load(url) }
        .onDisappear { player.player.pause() }
    }

    private func draw(in context: inout GraphicsContext, size: CGSize, positionMs: Int64) {
        let shown = videoStartMs.map { start in points.filter { $0.timestampMs <= start + positionMs } } ?? points
        guard shown.count >= 2, player.videoAspect > 0 else { return }
        // The video covers the space; the traced view is the center of the video at the traced shape.
        let va = player.videoAspect
        let videoSize = size.width / size.height > va ? CGSize(width: size.width, height: size.width / va) : CGSize(width: size.height * va, height: size.height)
        let traced = frameAspect < va ? CGSize(width: videoSize.height * frameAspect, height: videoSize.height) : CGSize(width: videoSize.width, height: videoSize.width / frameAspect)
        let origin = CGPoint(x: (size.width - traced.width) / 2, y: (size.height - traced.height) / 2)
        func at(_ p: ScreenPoint) -> CGPoint { CGPoint(x: origin.x + CGFloat(p.x) * traced.width, y: origin.y + CGFloat(p.y) * traced.height) }
        let width: CGFloat = 6
        for run in TracerDrawing.runs(shown) {
            var path = Path()
            path.addLines(run.points.map(at))
            let dash: [CGFloat] = run.estimated ? [width * 3, width * 2.5] : []
            context.stroke(path, with: .color(Color(argb: style.secondaryHex)), style: StrokeStyle(lineWidth: width * 2.2, lineCap: .round, lineJoin: .round, dash: dash))
            context.stroke(path, with: .color(Color(argb: style.colorHex)), style: StrokeStyle(lineWidth: width, lineCap: .round, lineJoin: .round, dash: dash))
        }
        let head = at(shown.last!)
        context.fill(Path(ellipseIn: CGRect(x: head.x - width * 0.8, y: head.y - width * 0.8, width: width * 1.6, height: width * 1.6)), with: .color(.white))
    }
}

/// A video playing on a loop, with where it's at.
@MainActor
final class LoopingPlayer: ObservableObject {
    let player = AVQueuePlayer()
    private var looper: AVPlayerLooper?
    /// The video's shape once upright.
    @Published private(set) var videoAspect: CGFloat = 0

    var positionMs: Int64 { Int64(CMTimeGetSeconds(player.currentTime()) * 1000) }

    func load(_ url: URL) async {
        let asset = AVURLAsset(url: url)
        if let track = try? await asset.loadTracks(withMediaType: .video).first,
           let size = try? await track.load(.naturalSize), let t = try? await track.load(.preferredTransform) {
            let turned = size.applying(t)
            videoAspect = abs(turned.width) / max(1, abs(turned.height))
        }
        let item = AVPlayerItem(asset: asset)
        looper = AVPlayerLooper(player: player, templateItem: item)
        player.isMuted = false
        player.play()
    }

    func toggle() { if player.rate > 0 { player.pause() } else { player.play() } }
}

private struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> LayerView {
        let view = LayerView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ view: LayerView, context: Context) {}

    final class LayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
}
