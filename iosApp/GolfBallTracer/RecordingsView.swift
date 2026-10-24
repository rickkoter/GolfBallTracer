import SwiftUI
import UniformTypeIdentifiers

/// The recordings on this phone, newest first; tapping one plays it back through the shot detection.
struct RecordingsView: View {
    @EnvironmentObject private var store: RecordingStore
    @State private var choosingFiles = false

    var body: some View {
        NavigationStack {
            Group {
                if store.recordings.isEmpty {
                    ContentUnavailableView {
                        Label("No recordings yet", systemImage: "figure.golf")
                    } description: {
                        Text("Add recordings saved by the Android app (shot-debug, missed-strike or debug-session zips) with Open, or copy them into GolfBallTracer in the Files app.")
                    } actions: {
                        Button("Open recordings") { choosingFiles = true }
                            .buttonStyle(.borderedProminent)
                    }
                } else {
                    List {
                        ForEach(store.recordings) { recording in
                            NavigationLink(value: recording) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(recording.name).font(.body.monospacedDigit())
                                    Text("\(recording.kindLabel) · \(recording.sizeLabel)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .onDelete { offsets in offsets.map { store.recordings[$0] }.forEach(store.delete) }
                    }
                }
            }
            .navigationTitle("Recordings")
            .navigationDestination(for: Recording.self) { ReplayView(recording: $0) }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { choosingFiles = true } label: { Label("Open", systemImage: "plus") }
                }
            }
            .refreshable { store.reload() }
            .fileImporter(isPresented: $choosingFiles, allowedContentTypes: [.zip], allowsMultipleSelection: true) { result in
                if case .success(let urls) = result { store.importRecordings(urls) }
            }
            .alert("Couldn't add recordings", isPresented: Binding(get: { store.problem != nil }, set: { if !$0 { store.problem = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(store.problem ?? "")
            }
        }
    }
}
