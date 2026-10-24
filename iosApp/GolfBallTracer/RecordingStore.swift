import Foundation

/// A recording saved by the app: a shot (shot-debug, missed-strike, golf-shot) or a debug recording.
struct Recording: Identifiable, Hashable {
    let url: URL
    var id: URL { url }
    var name: String { url.deletingPathExtension().lastPathComponent }

    /// What kind of recording, from how the Android app names them.
    var kindLabel: String {
        if name.hasPrefix("debug-session") { return "Debug recording" }
        if name.hasPrefix("missed-strike") { return "Missed strike" }
        return "Shot"
    }

    var sizeLabel: String {
        let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        return ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }
}

/// The recordings kept in the app's Documents folder, which the Files app and Finder can also reach.
@MainActor
final class RecordingStore: ObservableObject {
    @Published private(set) var recordings: [Recording] = []
    @Published var problem: String?

    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]

    init() { reload() }

    func reload() {
        let files = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        recordings = files
            .filter { $0.pathExtension.lowercased() == "zip" }
            .map(Recording.init)
            .sorted { $0.name > $1.name }  // Newest first: names carry the date and time.
    }

    /// Copies recordings in from elsewhere (Files, Drive, another app), replacing any with the same name.
    func importRecordings(_ urls: [URL]) {
        var failed: [String] = []
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let target = folder.appendingPathComponent(url.lastPathComponent)
            do {
                if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
                try FileManager.default.copyItem(at: url, to: target)
            } catch {
                failed.append(url.lastPathComponent)
            }
        }
        problem = failed.isEmpty ? nil : "Couldn't copy \(failed.joined(separator: ", "))"
        reload()
    }

    func delete(_ recording: Recording) {
        try? FileManager.default.removeItem(at: recording.url)
        reload()
    }
}
