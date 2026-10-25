import AVFoundation
import SwiftUI
import GolfTracking

/// The Tracer View, as on Android: the camera filling the screen; the card with the golfer, club and GPS
/// buttons and the settings gear at the top; the status banner below it; the tracer over a still of the
/// swing once a shot is in; and Start/Stop listening, Locate Ball and Review at the bottom.
struct TrackerView: View {
    @EnvironmentObject private var model: TrackerModel
    @EnvironmentObject private var router: AppRouter
    @Environment(\.scenePhase) private var scenePhase
    @State private var showSettings = false
    @State private var showClubs = false
    @State private var showLocator = false
    @State private var opening = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            GeometryReader { geo in
                ZStack {
                    CameraPreview(camera: model.camera)
                    // Once a shot has been captured, freeze on the still from just after the strike.
                    if let still = model.shotStill, model.phase != .capturing {
                        Image(decorative: still, scale: 1)
                            .resizable()
                            .frame(width: geo.size.width, height: geo.size.height)
                    }
                    TracerCanvasView()
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .ignoresSafeArea()

            VStack(spacing: 8) {
                HStack(spacing: 8) {
                    TelemetryHUD(showClubs: $showClubs)
                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape.fill")
                            .font(.system(size: 18))
                            .foregroundStyle(.white)
                            .frame(width: 44, height: 44)
                            .background(Color.golfDarkCard.opacity(0.85), in: Circle())
                    }
                    .accessibilityLabel("Settings")
                }
                StatusBanner()
                Spacer()
                if model.isDebugRecording && model.isWritingDebugNote { DebugNoteCard() }
                controls
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .onAppear { model.appear() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.appear() } else if phase == .background { model.background() }
        }
        .onReceive(model.location.$landing.dropFirst()) { _ in DispatchQueue.main.async { model.ballPinned() } }
        .sheet(isPresented: $showSettings) { SettingsSheet().environmentObject(model).environmentObject(router) }
        .sheet(isPresented: $showClubs) { ClubSheet(selected: $model.club).presentationDetents([.medium, .large]) }
        .sheet(isPresented: $showLocator) {
            BallLocatorView(landing: model.ballLanding, unavailableReason: model.locatorProblem, pinned: false)
                .environmentObject(model.location).environmentObject(model.orientation)
        }
        .alert("Recording limit reached", isPresented: $model.showLimitPrompt) {
            Button("Remove \(min(ShotStore.recordingsToFree, model.store.recordingCount)) recordings") { model.freeRecordings() }
            Button("Not now", role: .cancel) {}
        } message: {
            Text("\(ShotStore.maxRecordings) shots have recordings saved, the most kept on the phone. Remove the recordings of the earliest \(min(ShotStore.recordingsToFree, model.store.recordingCount)) shots to keep recording? Those shots stay in your history with their tracer snapshots and distances. Share any you want to keep from the Shot Log first.")
        }
        .alert(uncertainTitle, isPresented: Binding(get: { model.uncertainShot != nil }, set: { if !$0 { model.uncertainShot = nil } }), presenting: model.uncertainShot) { uncertain in
            Button("Keep") { model.uncertainShot = nil }
            Button("Discard", role: .destructive) { model.discardUncertain(uncertain) }
        } message: { uncertain in
            Text(uncertainMessage(uncertain))
        }
    }

    private var uncertainTitle: String {
        (model.uncertainShot?.reasons.isEmpty ?? true) ? "No ball flight found" : "Part of this shot wasn't recorded"
    }

    private func uncertainMessage(_ u: UncertainShot) -> String {
        let why: String
        if u.reasons.isEmpty {
            why = "A strike was heard and a ball went, but it wasn't seen flying. It may not have been a shot."
        } else {
            why = (u.noFlight ? "No ball flight was found, and it may be because:" : "The ball flight drawn may be wrong or cut short:")
                + "\n" + u.reasons.map { "• \($0)" }.joined(separator: "\n")
        }
        return why + "\n\nKeep this shot in your history?"
    }

    /// The listening switch, plus Locate Ball and Review once a shot has been recorded; during a debug
    /// recording, Add note and Stop debug.
    private var controls: some View {
        HStack(spacing: 8) {
            if !model.isDebugRecording && !model.isSavingDebug {
                let on = !model.isPaused
                Button { model.toggleListening() } label: {
                    Label(on ? "Stop listening" : "Start listening", systemImage: on ? "stop.fill" : "play.fill")
                        .font(.system(size: 14, weight: .bold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .foregroundStyle(on ? .white : Color.golfDarkBg)
                        .padding(.horizontal, 12)
                        .frame(height: 44)
                        .background(on ? Color.golfDarkCard.opacity(0.9) : Color.golfNeonCyan, in: RoundedRectangle(cornerRadius: 22))
                }
            } else {
                Spacer()
                Button { model.startDebugNote() } label: {
                    Label("Add note", systemImage: "square.and.pencil")
                        .font(.system(size: 14, weight: .bold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .foregroundStyle(Color.golfNeonGold)
                        .padding(.horizontal, 12)
                        .frame(height: 44)
                        .overlay(RoundedRectangle(cornerRadius: 22).stroke(Color.golfNeonGold.opacity(0.6)))
                }
                .disabled(!model.isDebugRecording || model.isWritingDebugNote)
                Button { model.toggleDebugRecording() } label: {
                    HStack(spacing: 6) {
                        if model.isSavingDebug { ProgressView().tint(Color.golfDarkBg) } else { Image(systemName: "stop.fill") }
                        Text(model.isSavingDebug ? "Saving…" : "Stop debug")
                    }
                    .font(.system(size: 14, weight: .bold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    .foregroundStyle(Color.golfDarkBg)
                    .padding(.horizontal, 12)
                    .frame(height: 44)
                    .background(Color.golfNeonGold, in: RoundedRectangle(cornerRadius: 22))
                }
                .disabled(!model.isDebugRecording)
            }
            if model.showingShot && !model.isDebugRecording && !model.isSavingDebug {
                Spacer()
                Button { showLocator = true } label: {
                    Label("Locate Ball", systemImage: "location.fill")
                        .font(.system(size: 14, weight: .bold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .foregroundStyle(Color.golfNeonCyan)
                        .padding(.horizontal, 12)
                        .frame(height: 44)
                        .background(Color.golfDarkCard.opacity(0.9), in: RoundedRectangle(cornerRadius: 22))
                }
                Button {
                    opening = true
                    Task {
                        if let shot = await model.reviewShot() { router.review(shot.id) }
                        opening = false
                    }
                } label: {
                    HStack(spacing: 6) {
                        if model.isRetracing || opening { ProgressView().tint(Color.golfDarkBg) } else { Image(systemName: "checkmark.circle.fill") }
                        Text("Review")
                    }
                    .font(.system(size: 14, weight: .bold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    .foregroundStyle(Color.golfDarkBg)
                    .padding(.horizontal, 12)
                    .frame(height: 44)
                    .background(Color.golfNeonLime, in: RoundedRectangle(cornerRadius: 22))
                }
                .disabled(opening)
            } else if !model.isDebugRecording && !model.isSavingDebug {
                Spacer()
            }
        }
    }
}

/// What's going on, as on Android: listening, a shot being followed or worked out, the shot saved, the
/// microphone and video, recordings used, and notes about strikes, saving and debug recordings. Tapping it
/// offers to make room once the recordings are full.
private struct StatusBanner: View {
    @EnvironmentObject private var model: TrackerModel

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 14))
                .foregroundStyle(model.phase == .capturing ? Color.golfNeonRed : Color.golfNeonCyan)
                .padding(.top, 1)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                ForEach(details, id: \.self) { line in
                    Text(line).font(.system(size: 11)).foregroundStyle(.white.opacity(0.8))
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(background, in: RoundedRectangle(cornerRadius: 12))
        .onTapGesture { if model.limitReached { model.showLimitPrompt = true } }
    }

    private var icon: String {
        if model.phase == .capturing { return "record.circle.fill" }
        return model.isListening ? "ear.fill" : "pause.circle.fill"
    }

    private var background: Color {
        if model.phase == .capturing { return Color.golfNeonRed.opacity(0.35) }
        if model.isDebugRecording { return Color.golfNeonGold.opacity(0.3) }
        return Color.golfDarkCard.opacity(0.85)
    }

    private var title: String {
        if model.isDebugRecording && model.phase == .listening {
            return String(format: "🐞 Debug recording %d:%02d / 0:%02d • swing and hit as usual • ■ to stop",
                          model.debugSeconds / 60, model.debugSeconds % 60, DebugRecorder.maxSeconds)
        }
        if model.phase == .analyzing { return "⏳ Finding ball flight…" }
        if model.phase == .capturing { return "🔴 Shot detected! Following the ball…" }
        if model.showingShot {
            let next = model.isPaused ? "Start listening for the next shot" : "Listening for the next shot"
            let what: String
            if model.isBallFlightMissing && !model.launchSeen { what = "⚠️ Didn't see the ball leave" }
            else if model.isBallFlightMissing { what = "⚠️ Ball flight not found" }
            else if model.detectedPoints.contains(where: { $0.confidence < 0.5 }) { what = "⛳ Shot saved! Dashed part of the tracer is estimated" }
            else { what = "⛳ Shot saved!" }
            return what + " • " + next
        }
        if model.isPaused { return "⏹ Not listening • Tap Start listening to pick up shots" }
        if model.limitReached { return "⚠️ \(ShotStore.maxRecordings) shot recordings stored • Tap here to free up space" }
        return model.isMicOn ? "👂 Listening for shot sound" : "👀 Watching for shots (microphone off)"
    }

    private var details: [String] {
        guard model.phase == .listening, !model.isPaused || model.showingShot else {
            return [model.cameraProblem, model.micNote, model.debugNote].compactMap { $0 }
        }
        let mic: String
        if !model.micEnabled { mic = "off (watching only)" }
        else if !model.micPermission { mic = "not allowed • turn it on in ⚙ Settings" }
        else if model.isMicOn { mic = "on " + levelBar(model.micLevelDb) }
        else { mic = "couldn't open" }
        let video: String
        switch model.isVideoAvailable { case true?: video = "on"; case false?: video = "not available"; case nil: video = "starting" }
        return [
            model.cameraProblem,
            model.detectorLag > 1.5 ? String(format: "⏳ Shot detection is %.1f s behind the camera • catching up", model.detectorLag) : nil,
            model.shotStats, model.saveNote, model.videoNote,
            model.showingShot ? nil : "🎤 Mic \(mic) • 🎥 Video \(video) • Recordings \(model.recordingCount)/\(ShotStore.maxRecordings)",
            model.micNote, model.strikeNote, model.debugNote
        ].compactMap { $0 }
    }

    /// A little bar showing how loud it is, so it's easy to see the microphone is hearing things.
    private func levelBar(_ db: Float) -> String {
        let filled = max(0, min(8, Int((db + 70) / 60 * 8)))
        return String(repeating: "▮", count: filled) + String(repeating: "▯", count: 8 - filled)
    }
}

/// A note for the debug recording, about the shot just captured or the moment it was started.
private struct DebugNoteCard: View {
    @EnvironmentObject private var model: TrackerModel
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .trailing, spacing: 8) {
            TextField("Note (optional), e.g. topped it, phone moved", text: $model.debugNoteText, axis: .vertical)
                .lineLimit(1...3)
                .textFieldStyle(.roundedBorder)
                .focused($focused)
            HStack {
                Button("Skip") { model.skipDebugNote() }.foregroundStyle(.white)
                Button("Save note") { model.saveDebugNote() }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.golfNeonGold)
                    .foregroundStyle(Color.golfDarkBg)
                    .disabled(model.debugNoteText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(12)
        .background(Color.golfDarkCard.opacity(0.95), in: RoundedRectangle(cornerRadius: 14))
        .onAppear { focused = true }
    }
}

/// The camera's live picture, filling its space the way the analysis frames are cropped.
struct CameraPreview: UIViewRepresentable {
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
