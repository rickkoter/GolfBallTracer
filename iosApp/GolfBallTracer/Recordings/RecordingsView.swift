import SwiftUI
import UniformTypeIdentifiers

/// The recordings on this phone, newest first; tapping one plays it back through the shot detection,
/// and each can be shared (to send to whoever is looking into the tracking).
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
                        Text("Shots traced on the Tracer tab are saved here. Recordings from the Android app (shot-debug, missed-strike or debug-session zips) can be added with Open, or copied into GolfBallTracer in the Files app.")
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
                            .contextMenu {
                                ShareLink(item: recording.url) { Label("Share", systemImage: "square.and.arrow.up") }
                            }
                            .swipeActions(edge: .leading) {
                                ShareLink(item: recording.url) { Label("Share", systemImage: "square.and.arrow.up") }
                                    .tint(.blue)
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
