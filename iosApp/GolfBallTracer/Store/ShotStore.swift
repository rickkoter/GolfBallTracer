import Foundation
import GolfTracking

/// The shot history, kept as the Android app keeps it: each shot's record as JSON in shot_data/<id>.json,
/// and its recording (video, tracking, analysis frames, snapshot) in shot_media/<id>/.
@MainActor
final class ShotStore: ObservableObject {
    /// Every shot, newest first.
    @Published private(set) var shots: [ShotRecord] = []

    /// Most shots whose recordings are kept; no more are recorded until some are removed.
    static let maxRecordings = 32
    /// How many of the earliest recordings to offer to remove once the limit is reached.
    static let recordingsToFree = 18

    nonisolated static let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    nonisolated static let dataDir = root.appendingPathComponent("shot_data", isDirectory: true)
    nonisolated static let mediaDir = root.appendingPathComponent("shot_media", isDirectory: true)

    init() {
        try? FileManager.default.createDirectory(at: Self.dataDir, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: Self.mediaDir, withIntermediateDirectories: true)
        load()
    }

    /// Where a shot's video, tracking data and snapshot are kept.
    nonisolated static func recordingDir(_ shotId: String) -> URL { mediaDir.appendingPathComponent(shotId, isDirectory: true) }

    /**
     * A file a shot refers to, as it is on this phone now. Paths are saved whole, and the app's folder moves
     * when the app is reinstalled or updated, so a path that's gone is looked for again under shot_media.
     */
    nonisolated static func resolve(_ path: String?) -> URL? {
        guard let path, !path.isEmpty else { return nil }
        let url = path.hasPrefix("file://") ? URL(string: path) ?? URL(fileURLWithPath: path) : URL(fileURLWithPath: path)
        if FileManager.default.fileExists(atPath: url.path) { return url }
        guard let range = url.path.range(of: "/shot_media/") else { return nil }
        let moved = mediaDir.appendingPathComponent(String(url.path[range.upperBound...]))
        return FileManager.default.fileExists(atPath: moved.path) ? moved : nil
    }

    func load() {
        let files = (try? FileManager.default.contentsOfDirectory(at: Self.dataDir, includingPropertiesForKeys: nil)) ?? []
        shots = files.filter { $0.pathExtension == "json" }
            .compactMap { (try? String(contentsOf: $0, encoding: .utf8)).flatMap { ShotFiles.shared.decodeRecord(text: $0) } }
            .sorted { $0.timestampMs > $1.timestampMs }
    }

    func find(_ id: String) -> ShotRecord? { shots.first { $0.id == id } }

    func save(_ shot: ShotRecord) {
        write(shot)
        load()
    }

    /// Changes a saved shot; returns it as saved, or nil if there's no such shot.
    @discardableResult
    func update(_ id: String, _ change: (ShotRecord) -> ShotRecord) -> ShotRecord? {
        guard let current = find(id) else { return nil }
        let updated = change(current)
        save(updated)
        return updated
    }

    func delete(_ id: String) {
        try? FileManager.default.removeItem(at: Self.dataDir.appendingPathComponent("\(id).json"))
        try? FileManager.default.removeItem(at: Self.recordingDir(id))
        load()
    }

    /// Deletes the recordings of these shots, keeping the shots themselves (with their snapshots).
    func deleteRecordings(_ ids: [String]) {
        for id in ids {
            let dir = Self.recordingDir(id)
            // The snapshot stays: it's the shot's picture in the log.
            for name in [ShotFiles.shared.VIDEO, ShotFiles.shared.TRACKING, ShotFiles.shared.CAPTURE] {
                try? FileManager.default.removeItem(at: dir.appendingPathComponent(name))
            }
            if let shot = find(id) { write(shot.withoutRecording()) }
        }
        load()
    }

    var recordingCount: Int { shots.filter { $0.hasRecording }.count }

    /// The [n] earliest shots that still have a recording.
    func earliestRecordings(_ n: Int) -> [ShotRecord] {
        Array(shots.filter { $0.hasRecording }.sorted { $0.timestampMs < $1.timestampMs }.prefix(n))
    }

    private func write(_ shot: ShotRecord) {
        let url = Self.dataDir.appendingPathComponent("\(shot.id).json")
        try? ShotFiles.shared.encodeRecord(shot: shot).write(to: url, atomically: true, encoding: .utf8)
    }
}

extension ShotRecord {
    /// The shot's video, if it's still on the phone.
    var videoURL: URL? { ShotStore.resolve(videoPath) }
    var snapshotURL: URL? { ShotStore.resolve(snapshotUri) }
    var golferNumber: Int? { golfer.map { Int(truncating: $0) } }
    var style: TracerStyle { TracerStyle.entries.first { $0.name == tracerStyle } ?? .proLime }
    var date: Date { Date(timeIntervalSince1970: Double(timestampMs) / 1000) }
}

extension Date {
    /// "2026-10-25_09-27-58", as both apps name recordings.
    var fileStamp: String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd_HH-mm-ss"
        return f.string(from: self)
    }
}

extension ShotRecord: @retroactive Identifiable {}
