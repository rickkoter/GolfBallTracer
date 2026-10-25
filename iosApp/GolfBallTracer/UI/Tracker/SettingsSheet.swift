import SwiftUI
import GolfTracking

/// Settings, as on Android: the flash, hearing the strike, tuning the curve by dragging, the tracer style,
/// debug recordings and the demo flight. On iPhone, also replaying recordings (from either app).
struct SettingsSheet: View {
    @EnvironmentObject private var model: TrackerModel
    @EnvironmentObject private var router: AppRouter
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if model.camera.hasTorch {
                        Toggle(isOn: $model.flashOn) {
                            Label("Flash", systemImage: model.flashOn ? "bolt.fill" : "bolt.slash")
                        }
                    }
                    Toggle(isOn: Binding(get: { model.micEnabled && model.micPermission }, set: { model.setMicEnabled($0) })) {
                        Label("Hear the strike (microphone)", systemImage: model.micEnabled && model.micPermission ? "mic.fill" : "mic.slash")
                    }
                    if !model.micEnabled || !model.micPermission {
                        Text(model.micPermission ? "Without it, shots are looked for in movement alone, which misses more and picks up things that aren't shots."
                             : "The microphone isn't allowed: turn it on in the iPhone's Settings › GolfBallTracer. Without it, shots are looked for in movement alone.")
                            .font(.footnote).foregroundStyle(Color.golfTextSecondary)
                    }
                    Toggle(isOn: $model.editMode) { Label("Tune curve by dragging", systemImage: "pencil") }
                }
                .tint(Color.golfNeonGold)

                Section("Tracer style") {
                    ForEach(TracerStyle.entries, id: \.name) { style in
                        Button { model.tracerStyle = style } label: {
                            HStack(spacing: 12) {
                                Circle().fill(Color(argb: style.colorHex)).frame(width: 20, height: 20)
                                Text(style.displayName).foregroundStyle(.white)
                                Spacer()
                                if style == model.tracerStyle { Image(systemName: "checkmark").foregroundStyle(Color.golfNeonLime) }
                            }
                        }
                    }
                }

                Section {
                    // For finding out why shots are missed: everything seen and heard, shot or not.
                    Button {
                        dismiss()
                        model.toggleDebugRecording()
                    } label: {
                        Label(model.isDebugRecording ? "Stop debug recording" : "Start debug recording", systemImage: "ladybug.fill")
                            .foregroundStyle(Color.golfNeonGold)
                    }
                    .disabled(model.isSavingDebug)
                    Text("Records up to \(DebugRecorder.maxSeconds) s of video, sound and what the shot detector sees and decides, shot or not, to Files › GolfBallTracer. Shots aren't saved meanwhile.")
                        .font(.footnote).foregroundStyle(Color.golfTextSecondary)
                    Button {
                        dismiss()
                        model.showDemoFlight()
                    } label: {
                        Label("Show demo flight", systemImage: "wand.and.stars").foregroundStyle(Color.golfNeonCyan)
                    }
                    Button {
                        dismiss()
                        // Once this sheet is gone: one sheet at a time.
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { router.showRecordings = true }
                    } label: {
                        Label("Replay recordings", systemImage: "film.stack").foregroundStyle(Color.golfNeonCyan)
                    }
                    Text("Plays back shot, missed-strike and debug recordings from either app through the shot detection.")
                        .font(.footnote).foregroundStyle(Color.golfTextSecondary)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.foregroundStyle(Color.golfNeonLime) } }
        }
        .presentationDetents([.large])
    }
}
