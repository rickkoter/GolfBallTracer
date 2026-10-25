import AVFoundation
import Foundation
import GolfTracking
import UIKit

/**
 * Checks of the video parts over USB, without anyone at the phone (`DEV-CHECK` lines):
 *  - `-videoCheck`: a shot recording copied into Documents/video-check (video.mp4, tracking.json and
 *    capture.zip from a shot's shot_media folder, from either app) is traced again from its video with this
 *    phone's decoder (to compare with the tracing the recording came with), and exported with its tracer
 *    drawn in (Documents/video-check/exported.mp4, with frames before and after the strike as PNGs).
 *  - `-debugCheck`: once the camera is up, an 8 s debug recording is made and saved.
 */
enum DevChecks {
    static let videoCheck = ProcessInfo.processInfo.arguments.contains("-videoCheck")
    static let debugCheck = ProcessInfo.processInfo.arguments.contains("-debugCheck")

    static func log(_ text: String) { print("DEV-CHECK \(text)") }

    @MainActor
    static func runVideoCheck() async {
        let dir = Documents.folder.appendingPathComponent("video-check")
        let video = dir.appendingPathComponent("video.mp4")
        guard let trackingText = try? String(contentsOf: dir.appendingPathComponent("tracking.json"), encoding: .utf8),
              let tracking = AppleShotsKt.readTracking(text: trackingText),
              let capture = try? Data(contentsOf: dir.appendingPathComponent("capture.zip")),
              let shot = AppleShotsKt.capturedShotFromCapture(capture: capture),
              FileManager.default.fileExists(atPath: video.path) else {
            log("no recording in Documents/video-check")
            return
        }
        // 1. Tracing again from the video, with this phone's decoder.
        let source = AssetFrameSource(url: video)
        if let info = source.info() {
            log("video \(info.width)x\(info.height), rotation \(info.rotation), \(String(format: "%.2f", Double(info.durationUs) / 1e6)) s, \(info.frameRate?.doubleValue ?? 0) fps")
        }
        let started = Date()
        let result = await Task.detached { AppleShotsKt.retraceVideo(reader: source, startGuessNs: tracking.videoStartNs, live: shot, camera: nil) }.value
        log(String(format: "retrace in %.1f s: %@ (offset %@)", Date().timeIntervalSince(started), result?.note ?? "failed",
                   result?.offsetNs.map { "\($0.int64Value)" } ?? "none"))
        log("the recording's own video trace: \(tracking.videoNote ?? "none"), followed \(tracking.videoTrackedSeconds?.doubleValue ?? 0) s")

        // 2. Exporting it with the tracer drawn in.
        let points = (tracking.videoTracer?.isEmpty == false ? tracking.videoTracer! : tracking.liveTracer)
        let record = AppleShotsKt.makeShotRecord(club: "7-Iron", golfer: 2, tracerStyle: TracerStyle.proLime.name, launch: nil, landing: nil,
                                                 distanceYards: 0, distanceMeters: 0, points: points, tracerEstimated: false)
            .withRecording(videoPath: video.path, trackingPath: nil, videoStartMs: tracking.videoStartNs.map { KotlinLong(value: $0.int64Value / 1_000_000) },
                           frameAspect: tracking.aspect, estimatedLanding: nil)
        let out = dir.appendingPathComponent("exported.mp4")
        let exportStart = Date()
        do {
            try await TracerVideoExporter.render(record, video: video, to: out) { _ in }
            let asset = AVURLAsset(url: out)
            let duration = try await asset.load(.duration)
            let track = try await asset.loadTracks(withMediaType: .video).first
            let size = try await track?.load(.naturalSize) ?? .zero
            log(String(format: "exported in %.1f s: %.0fx%.0f, %.2f s (traced view %.3f)", Date().timeIntervalSince(exportStart),
                       size.width, size.height, CMTimeGetSeconds(duration), tracking.aspect))
            // Frames before and after the strike, to look at.
            let generator = AVAssetImageGenerator(asset: asset)
            generator.requestedTimeToleranceBefore = .zero
            generator.requestedTimeToleranceAfter = .zero
            if let start = tracking.videoStartNs?.int64Value, let first = points.first, let last = points.last {
                let strike = Double(first.timestampMs - start / 1_000_000) / 1000
                let end = min(Double(last.timestampMs - start / 1_000_000) / 1000, CMTimeGetSeconds(duration) - 0.1)
                for (name, t) in [("before", max(0, strike - 0.8)), ("after", end)] {
                    if let image = try? await generator.image(at: CMTime(seconds: t, preferredTimescale: 600)).image {
                        try? UIImage(cgImage: image).pngData()?.write(to: dir.appendingPathComponent("\(name).png"))
                    }
                }
                log(String(format: "frames saved at %.2f s and %.2f s", max(0, strike - 0.8), end))
            }
        } catch {
            log("export failed: \(error)")
        }
        log("done")
    }
}
