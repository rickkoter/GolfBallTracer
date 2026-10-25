import CoreGraphics
import Foundation
import UIKit
import GolfTracking

/**
 * Looks after a shot once it has been captured, in the background so the next shot can be taken meanwhile,
 * as on Android:
 *  1. keeps its recording: the video from 2.5 s before the strike, the analysis frames and tracking.json;
 *  2. saves the shot to the history with the live tracer, and its analysis to Files as a shot-debug zip;
 *  3. traces it again from the video and switches to that tracer if it's better;
 *  4. bakes the snapshot picture with the final tracer.
 */
@MainActor
final class ShotProcessor {
    enum Update {
        /// Saved with its recording; [debugExport] is the shot-debug zip's name in Files.
        case saved(shotId: String, debugExport: String?, hasVideo: Bool)
        /// Traced again from the video; [points] (shifted onto the still) are set if the video's trace was used.
        case retraced(shotId: String, flight: BallFlightFitter.Flight?, points: [ScreenPoint]?, note: String)
    }

    /// A shot just captured, with what's needed to save it. [record] holds the live tracer, shifted onto [still].
    struct NewShot {
        let record: ShotRecord
        let shot: CapturedShot
        let liveFlight: BallFlightFitter.Flight?
        let clip: ShotVideoRecorder.Clip?
        let still: CGImage?
        let stillShift: CGPoint
        let camera: LaunchPhysics.CameraPose?
        let declinationDegrees: Double
    }

    var onUpdate: ((Update) -> Void)?

    private let store: ShotStore
    private var running = Set<String>()
    private var discarded = Set<String>()
    /// What each baked snapshot shows, so later edits know whether to bake it again.
    private var bakedLooks: [String: String] = [:]
    /// One video at a time: decoding and re-tracing is heavy, and shots come one after another anyway.
    private var retraceTail: Task<Void, Never>?
    private var lastMissedSave = Date.distantPast

    /// Video kept from before the strike: the whole swing.
    static let preRollSeconds = 2.5

    init(store: ShotStore) { self.store = store }

    func process(_ new: NewShot) {
        let id = new.record.id
        running.insert(id)
        Task {
            await run(new)
            running.remove(id)
            if discarded.remove(id) != nil { delete(id) }
        }
    }

    /// Throws a shot away with its recording: one the user said wasn't worth keeping. A shot still being
    /// saved or traced again is thrown away once that's done.
    func discard(_ id: String) {
        if running.contains(id) { discarded.insert(id) } else { delete(id) }
    }

    private func delete(_ id: String) {
        store.delete(id)
        bakedLooks[id] = nil
    }

    private func run(_ new: NewShot) async {
        let shot = new.shot
        let id = new.record.id
        let dir = ShotStore.recordingDir(id)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        // 1. The recording: video from a little before the swing, the analysis frames, the tracking.
        let (video, videoStartNs) = await keepVideo(new.clip, impactNs: shot.impactTimestampNs?.int64Value, target: dir.appendingPathComponent(ShotFiles.shared.VIDEO))
        let liveTracer = new.liveFlight?.tracer ?? []
        let device = DeviceInfo.name
        let captureData = await Task.detached { AppleShotsKt.encodeCapture(shot: shot, tracer: liveTracer, device: device) }.value
        try? (captureData as Data?)?.write(to: dir.appendingPathComponent(ShotFiles.shared.CAPTURE))
        let trackingURL = dir.appendingPathComponent(ShotFiles.shared.TRACKING)
        var tracking = AppleShotsKt.trackingOf(shot: shot, live: new.liveFlight, videoStartNs: videoStartNs.map { KotlinLong(value: $0) })
        try? AppleShotsKt.encodeTracking(tracking: tracking).write(to: trackingURL, atomically: true, encoding: .utf8)

        // 2. Into the history straight away, with where the ball should have landed for the ball locator.
        let liveLanding = AppleShotsKt.estimateLanding(flight: new.liveFlight, shot: shot, camera: new.camera,
                                                       declinationDegrees: new.declinationDegrees, tee: new.record.launchLocation)
        store.save(new.record.withRecording(
            videoPath: video?.path, trackingPath: trackingURL.path,
            videoStartMs: videoStartNs.map { KotlinLong(value: $0 / 1_000_000) }, frameAspect: shot.aspect, estimatedLanding: liveLanding
        ))
        let club = new.record.club
        let golfer = new.record.golferNumber ?? 1
        let debugData = await Task.detached {
            LiveInputKt.encodeShotRecording(shot: shot, flight: new.liveFlight, device: device, club: club, golfer: Int32(golfer))
        }.value
        let debugExport = debugData.flatMap { Documents.write($0 as Data, name: "shot-debug_\(Date().fileStamp).zip") }
        onUpdate?(.saved(shotId: id, debugExport: debugExport, hasVideo: video != nil))
        // Thrown away already: no need to trace it again.
        if discarded.contains(id) { return }

        // 3. Trace again from the video, one video at a time.
        var points = new.record.points
        var tracedFrom = ShotRecord.companion.TRACED_FROM_CAMERA
        var estimated = new.liveFlight?.estimated ?? false
        var startNs = videoStartNs
        var usedFlight: BallFlightFitter.Flight?
        let note: String
        if let video {
            let previous = retraceTail
            let job = Task.detached { () -> VideoRetracer.Result? in
                await previous?.value
                return AppleShotsKt.retraceVideo(reader: AssetFrameSource(url: video), startGuessNs: videoStartNs.map { KotlinLong(value: $0) },
                                                 live: shot, camera: new.camera)
            }
            retraceTail = Task { _ = await job.value }
            let result = await job.value
            if let offset = result?.offsetNs { startNs = offset.int64Value }
            let videoFlight = result?.flight
            let better = videoFlight != nil && VideoRetracer.shared.isBetter(live: new.liveFlight, video: videoFlight)
            if better, let videoFlight {
                usedFlight = videoFlight
                points = videoFlight.tracer.map {
                    ScreenPoint(x: $0.x + Float(new.stillShift.x), y: $0.y + Float(new.stillShift.y), timestampMs: $0.timestampMs, confidence: $0.confidence)
                }
                tracedFrom = ShotRecord.companion.TRACED_FROM_VIDEO
                estimated = videoFlight.estimated
            }
            if result == nil {
                note = "Couldn't trace the video"
            } else if better {
                note = "Traced from the full video (\(result!.note))"
            } else {
                note = "Kept the live trace (video: \(result!.note))"
            }
            tracking = tracking.withVideoTrace(videoStartNs: startNs.map { KotlinLong(value: $0) }, result: result, tracedFrom: tracedFrom)
            try? AppleShotsKt.encodeTracking(tracking: tracking).write(to: trackingURL, atomically: true, encoding: .utf8)
        } else {
            note = "No video was recorded"
        }

        // 4. Bake the snapshot with the final tracer, unless the curve was tuned by hand meanwhile.
        if discarded.contains(id) { return }
        guard let current = store.find(id) else { return }
        let edited = current.points != new.record.points
        let finalPoints = edited ? current.points : points
        let finalShot = current.withFinalTrace(points: finalPoints, tracedFrom: edited ? current.tracedFrom : tracedFrom,
                                               tracerEstimated: edited ? current.tracerEstimated : estimated,
                                               estimatedLanding: current.estimatedLanding, videoStartMs: current.videoStartMs, snapshotUri: nil)
        let snapshotPath = await bake(finalShot, still: new.still)
        let landing = usedFlight.flatMap {
            AppleShotsKt.estimateLanding(flight: $0, shot: shot, camera: new.camera, declinationDegrees: new.declinationDegrees, tee: current.launchLocation)
        } ?? liveLanding
        let saved = store.update(id) {
            $0.withFinalTrace(points: finalShot.points, tracedFrom: finalShot.tracedFrom, tracerEstimated: finalShot.tracerEstimated,
                              estimatedLanding: landing, videoStartMs: startNs.map { KotlinLong(value: $0 / 1_000_000) }, snapshotUri: snapshotPath)
        }
        if let saved { bakedLooks[id] = Self.look(saved) }
        onUpdate?(.retraced(shotId: id, flight: usedFlight, points: edited || usedFlight == nil ? nil : points, note: note))
    }

    /// Cuts the shot out of the rolling recording. Returns the video and when its first frame was taken.
    private func keepVideo(_ clip: ShotVideoRecorder.Clip?, impactNs: Int64?, target: URL) async -> (URL?, Int64?) {
        guard let clip else { return (nil, nil) }
        defer { try? FileManager.default.removeItem(at: clip.url) }
        let from: Double = {
            guard let start = clip.startNs, let impactNs else { return 0 }
            return Double(impactNs - Int64(Self.preRollSeconds * 1e9) - start) / 1e9
        }()
        if let cut = await ShotVideoRecorder.trim(clip.url, to: target, fromSeconds: from) {
            return (target, clip.startNs.map { $0 + Int64(cut * 1e9) })
        }
        // Keep it whole rather than lose it.
        try? FileManager.default.removeItem(at: target)
        if (try? FileManager.default.copyItem(at: clip.url, to: target)) != nil { return (target, clip.startNs) }
        return (nil, nil)
    }

    /// Renders the snapshot into the shot's folder; returns its path.
    private func bake(_ shot: ShotRecord, still: CGImage?) async -> String? {
        let url = ShotStore.recordingDir(shot.id).appendingPathComponent("snapshot_\(Int(Date().timeIntervalSince1970 * 1000)).jpg")
        let data = await Task.detached { TracerDrawing.snapshot(still: still, shot: shot) }.value
        guard let data, (try? data.write(to: url)) != nil else { return nil }
        // Earlier snapshots of this shot are replaced.
        if let old = ShotStore.resolve(store.find(shot.id)?.snapshotUri), old != url { try? FileManager.default.removeItem(at: old) }
        return url.path
    }

    /**
     * Saves changes made after the shot was captured (tracer style, distance, a tuned curve). Once the
     * snapshot has been baked, it's baked again over [still] if what it shows changed.
     */
    func saveEdits(_ edited: ShotRecord, still: CGImage?) async -> ShotRecord {
        guard let saved = store.update(edited.id, {
            // The recording and snapshot belong to the background work; take the user's changes only.
            $0.withEdits(tracerStyle: edited.tracerStyle, launch: edited.launchLocation, landing: edited.landingLocation,
                         distanceYards: edited.distanceYards, distanceMeters: edited.distanceMeters, points: edited.points)
        }) else { return edited }
        guard let baked = bakedLooks[saved.id], let still, Self.look(saved) != baked else { return saved }
        guard let path = await bake(saved, still: still) else { return saved }
        bakedLooks[saved.id] = Self.look(saved)
        return store.update(saved.id) { $0.withSnapshot(snapshotUri: path) } ?? saved
    }

    /// A shot with no recording (the demo flight): saved with a snapshot of [still].
    func saveWithoutRecording(_ record: ShotRecord, still: CGImage?) async -> ShotRecord {
        try? FileManager.default.createDirectory(at: ShotStore.recordingDir(record.id), withIntermediateDirectories: true)
        let path = await bake(record, still: still)
        let shot = record.withSnapshot(snapshotUri: path)
        store.save(shot)
        return shot
    }

    /**
     * Saves the frames around a strike heard during a swing that didn't start a shot to Files as a
     * missed-strike zip, so it can be replayed. At most one every 15 s, so a noisy range doesn't fill the phone.
     */
    func saveMissedStrike(frames: [LumaFrame], impactNs: Int64, onSaved: @escaping (String?) -> Void) {
        guard !frames.isEmpty, Date().timeIntervalSince(lastMissedSave) > 15 else { return }
        lastMissedSave = Date()
        let device = DeviceInfo.name
        Task {
            let data = await Task.detached { AppleShotsKt.encodeMissedStrike(frames: frames, impactNs: impactNs, device: device) }.value
            onSaved(data.flatMap { Documents.write($0 as Data, name: "missed-strike_\(Date().fileStamp).zip") })
        }
    }

    /// What a snapshot shows of the shot.
    private static func look(_ r: ShotRecord) -> String {
        "\(r.club)|\(r.tracerStyle)|\(r.distanceYards)|" + r.points.map { "\($0.x),\($0.y)" }.joined(separator: ";")
    }
}

/// The app's Documents folder: what the Files app shows under GolfBallTracer (Android's Downloads/GolfBallTracer).
enum Documents {
    static let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]

    /// Writes a file there; returns its name for showing, or nil if it couldn't be written.
    static func write(_ data: Data, name: String) -> String? {
        (try? data.write(to: folder.appendingPathComponent(name), options: .atomic)) != nil ? name : nil
    }
}

/// The phone's model, e.g. "iPhone17,1 (iOS 26.0)", as saved with recordings.
enum DeviceInfo {
    static let name: String = {
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
        return "\(machine) (iOS \(UIDevice.current.systemVersion))"
    }()
}
