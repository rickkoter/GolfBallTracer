import Foundation

/// A recording: a shot traced on this phone (shot), or one saved by the Android app (shot-debug,
/// missed-strike, golf-shot) or a debug recording.
struct Recording: Identifiable, Hashable {
    let url: URL
    var id: URL { url }
    var name: String { url.deletingPathExtension().lastPathComponent }

    /// What kind of recording, from how the apps name them.
    var kindLabel: String {
        if name.hasPrefix("debug-session") { return "Debug recording" }
        if name.hasPrefix("missed-strike") { return "Missed strike" }
        return "Shot"
    }

    /// When it was made, from the date and time in its name (as both apps name them), else the file's date.
    var date: Date {
        if let match = name.firstMatch(of: /\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}/) {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.dateFormat = "yyyy-MM-dd_HH-mm-ss"
            if let date = formatter.date(from: String(match.output)) { return date }
        }
        return (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
    }

    var sizeLabel: String {
        let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        return ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }
}

/// The recordings kept in the app's Documents folder, which the Files app and Finder can also reach.
@MainActor
final class RecordingStore: ObservableObject {
    @Published private(set) var recordings: [Recording] = []  // Newest first.
    @Published var problem: String?

    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]

    init() { reload() }

    func reload() {
        let files = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        recordings = files
            .filter { $0.pathExtension.lowercased() == "zip" }
            .map(Recording.init)
            .sorted { $0.date > $1.date }
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
