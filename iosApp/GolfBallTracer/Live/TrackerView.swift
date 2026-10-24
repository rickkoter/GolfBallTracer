import AVFoundation
import SwiftUI
import GolfTracking

/// The live tracer screen: the camera filling the screen, the club and golfer at the top, what's going on
/// in a banner, and one button to start and stop listening. After a shot, its picture with the tracer.
struct TrackerView: View {
    @EnvironmentObject private var store: RecordingStore
    @StateObject private var model = TrackerModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            GeometryReader { geo in
                ZStack {
                    CameraPreview(camera: model.camera)
                    if let still = model.still {
                        Image(decorative: still, scale: 1)
                            .resizable()
                            .scaledToFill()
                            .frame(width: geo.size.width, height: geo.size.height)
                            .clipped()
                    }
                    if model.showsShot || model.phase == .capturing {
                        TracerOverlay(tracer: model.tracer, sightings: model.sightings, ball: model.teeBall,
                                      shift: model.stillShift, lineWidth: 4)
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .ignoresSafeArea()

            VStack(spacing: 10) {
                pickers
                banner
                Spacer()
                controls
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
        .onAppear { model.appear(store: store) }
        .onDisappear { model.disappear() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.appear(store: store) } else if phase == .background { model.disappear() }
        }
    }

    private var pickers: some View {
        HStack(spacing: 10) {
            Menu {
                Picker("Club", selection: $model.club) {
                    ForEach(ClubType.entries, id: \.displayName) { Text($0.displayName).tag($0.displayName) }
                }
            } label: {
                chip(systemImage: "figure.golf", text: model.club)
            }
            Menu {
                Picker("Golfer", selection: $model.golfer) {
                    ForEach(1...4, id: \.self) { Text("Golfer \($0)").tag($0) }
                }
            } label: {
                chip(systemImage: "person.fill", text: "Golfer \(model.golfer)")
            }
            Spacer()
        }
        .disabled(model.phase == .capturing || model.phase == .analyzing)
    }

    private func chip(systemImage: String, text: String) -> some View {
        Label(text, systemImage: systemImage)
            .font(.title3.weight(.semibold))
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial, in: Capsule())
            .foregroundStyle(.white)
    }

    private var banner: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(status).font(.headline)
            if let stats = model.shotStats { Text(stats).font(.caption) }
            if let save = model.saveNote { Text(save).font(.caption).foregroundStyle(.secondary) }
            if let note = model.strikeNote { Text(note).font(.caption) }
            if let problem = model.problem { Text(problem).font(.callout).foregroundStyle(.yellow) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
        .foregroundStyle(.white)
    }

    private var status: String {
        switch model.phase {
        case .idle: return model.showsShot ? "Shot traced" : "Not listening · tap Start listening before the shot"
        case .listening: return model.micAvailable ? "Listening for shot sound" : "Watching for a shot (no microphone)"
        case .capturing: return "Shot! Following the ball…"
        case .analyzing: return "Working out the ball's flight…"
        }
    }

    private var controls: some View {
        HStack(spacing: 12) {
            if model.showsShot && model.phase == .idle {
                Button { model.clearShot() } label: {
                    Text("Clear").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .tint(.white)
            }
            Button {
                if model.showsShot && model.phase == .idle { model.nextShot() } else { model.toggleListening() }
            } label: {
                Label(model.isListening ? "Stop listening" : "Start listening",
                      systemImage: model.isListening ? "stop.circle.fill" : "ear")
                    .font(.title3.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .tint(model.isListening ? .red : .green)
            .disabled(model.phase == .analyzing)
        }
        .controlSize(.large)
    }
}

/// The camera's live picture, filling its space the way the analysis frames are cropped.
private struct CameraPreview: UIViewRepresentable {
    let camera: CameraController

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.camera = camera
        view.previewLayer.session = camera.session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        weak var camera: CameraController?
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

        override func layoutSubviews() {
            super.layoutSubviews()
            if let connection = previewLayer.connection, connection.isVideoRotationAngleSupported(90) {
                connection.videoRotationAngle = 90
            }
            if bounds.height > 0 { camera?.uprightAspect = Float(bounds.width / bounds.height) }
        }
    }
}
