import AVFoundation
import CoreImage
import Photos
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import GolfTracking

/// Shot history as CSV, one row per shot, for Google Sheets or Excel (the same columns as on Android).
enum ShotHistoryCsv {
    private static let header = ["Date", "Time", "Golfer", "Club", "Distance (yd)", "Distance from",
                                 "Tee latitude", "Tee longitude", "Landing latitude", "Landing longitude",
                                 "Tracer style", "Notes", "Shot ID"]

    static func build(_ shots: [ShotRecord]) -> String {
        let date = DateFormatter(); date.locale = Locale(identifier: "en_US_POSIX"); date.dateFormat = "yyyy-MM-dd"
        let time = DateFormatter(); time.locale = Locale(identifier: "en_US_POSIX"); time.dateFormat = "HH:mm:ss"
        func f(_ v: Double, _ digits: Int) -> String { String(format: "%.\(digits)f", locale: Locale(identifier: "en_US_POSIX"), v) }
        let rows = shots.sorted { $0.timestampMs < $1.timestampMs }.map { shot -> [String] in
            [date.string(from: shot.date), time.string(from: shot.date), shot.golferNumber.map(String.init) ?? "", shot.club,
             shot.distance.map { f($0.yards, 1) } ?? "", shot.distance.map { $0.estimated ? "Estimate" : "GPS" } ?? "",
             shot.launchLocation.map { f($0.latitude, 6) } ?? "", shot.launchLocation.map { f($0.longitude, 6) } ?? "",
             shot.landingLocation.map { f($0.latitude, 6) } ?? "", shot.landingLocation.map { f($0.longitude, 6) } ?? "",
             shot.style.displayName, shot.notes, shot.id]
        }
        return ([header] + rows).map { $0.map(escape).joined(separator: ",") }.joined(separator: "\r\n") + "\r\n"
    }

    /// Quotes a field holding a comma, quote or line break, doubling any quotes inside.
    private static func escape(_ field: String) -> String {
        field.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) ? "\"" + field.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : field
    }

    static func fileName() -> String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd"
        return "GolfBallTracer-shots-\(f.string(from: Date())).csv"
    }
}

/// A CSV file for the "save as" picker.
struct CsvDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.commaSeparatedText] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws { text = String(decoding: configuration.file.regularFileContents ?? Data(), as: UTF8.self) }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}

/// Each shot's whole recording as a golf-shot zip (as the Android app shares them), for the share sheet.
enum ShotBundles {
    static func make(_ shots: [ShotRecord]) async -> [URL] {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("shared_shots", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var urls: [URL] = []
        for shot in shots where shot.hasRecording {
            let folder = ShotStore.recordingDir(shot.id)
            let url = dir.appendingPathComponent("golf-shot_\(shot.date.fileStamp).zip")
            let ok = await Task.detached { () -> Bool in
                let tracking = try? Data(contentsOf: folder.appendingPathComponent(ShotFiles.shared.TRACKING))
                let capture = try? Data(contentsOf: folder.appendingPathComponent(ShotFiles.shared.CAPTURE))
                let video = shot.videoURL.flatMap { try? Data(contentsOf: $0) }
                guard let zip = AppleShotsKt.encodeShotBundle(shot: shot, tracking: tracking, capture: capture, video: video) else { return false }
                return (try? (zip as Data).write(to: url)) != nil
            }.value
            if ok { urls.append(url) }
        }
        return urls
    }
}

/**
 * Exports shot videos with the tracer drawn in, as on Android: each shot's recording is cropped to the view
 * the tracer was traced in (as Shot Review shows it), the tracer grows along with the ball, and a caption
 * gives the club, golfer and distance. The videos are saved to Photos, and kept here to share.
 */
enum TracerVideoExporter {
    struct Outcome {
        var saved: [URL] = []
        var withoutVideo = 0
        var failed = 0
        var notInPhotos = false

        var summary: String {
            var parts = [saved.count == 1 ? "Saved 1 video" : "Saved \(saved.count) videos"]
            parts[0] += notInPhotos ? " (Photos access is off: share them instead)" : " to Photos"
            if withoutVideo > 0 { parts.append("\(withoutVideo) without a video") }
            if failed > 0 { parts.append("\(failed) couldn't be made") }
            return parts.joined(separator: " • ")
        }
    }

    static func hasVideo(_ shot: ShotRecord) -> Bool { shot.videoURL != nil }

    /// Makes and saves the video of each shot in turn; [progress] gets the fraction of the whole export done.
    static func export(_ shots: [ShotRecord], progress: @escaping (Double) -> Void) async -> Outcome {
        var outcome = Outcome()
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("exported_videos", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let allowed = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        outcome.notInPhotos = !(allowed == .authorized || allowed == .limited)
        for (i, shot) in shots.enumerated() {
            guard let video = shot.videoURL else { outcome.withoutVideo += 1; continue }
            let out = dir.appendingPathComponent("golf-shot_\(shot.date.fileStamp).mp4")
            do {
                try await render(shot, video: video, to: out) { p in progress((Double(i) + p) / Double(shots.count)) }
                if !outcome.notInPhotos {
                    try await PHPhotoLibrary.shared().performChanges {
                        PHAssetCreationRequest.forAsset().addResource(with: .video, fileURL: out, options: nil)
                    }
                }
                outcome.saved.append(out)
            } catch {
                print("TracerVideoExporter: \(error)")
                outcome.failed += 1
            }
            progress(Double(i + 1) / Double(shots.count))
        }
        return outcome
    }

    /// Re-encodes [video] into [out] with [shot]'s tracer and caption drawn in.
    static func render(_ shot: ShotRecord, video: URL, to out: URL, progress: @escaping (Double) -> Void) async throws {
        let asset = AVURLAsset(url: video)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw ExportError.noVideo }
        let natural = try await track.load(.naturalSize)
        let transform = try await track.load(.preferredTransform)
        let turned = natural.applying(transform)
        let upright = CGSize(width: abs(turned.width), height: abs(turned.height))
        // Cropped like the preview the tracer was traced in, so the tracer lines up.
        let aspect = CGFloat(shot.frameAspect?.floatValue ?? Float(9.0 / 16.0))
        var crop = CGRect(origin: .zero, size: upright)
        if upright.width / upright.height > aspect {
            crop.size.width = upright.height * aspect
            crop.origin.x = (upright.width - crop.width) / 2
        } else {
            crop.size.height = upright.width / aspect
            crop.origin.y = (upright.height - crop.height) / 2
        }
        let renderSize = CGSize(width: (crop.width / 2).rounded() * 2, height: (crop.height / 2).rounded() * 2)
        let overlay = Overlay(shot: shot, size: renderSize)
        let composition = AVMutableVideoComposition(asset: asset) { request in
            var image = request.sourceImage
            // Frames still sideways (not turned by the track's transform) are turned upright here.
            if image.extent.width > image.extent.height, upright.width < upright.height { image = image.oriented(.right) }
            image = image.transformed(by: CGAffineTransform(translationX: -image.extent.minX, y: -image.extent.minY))
            image = image.cropped(to: crop).transformed(by: CGAffineTransform(translationX: -crop.minX, y: -crop.minY))
            let ms = Int64(CMTimeGetSeconds(request.compositionTime) * 1000)
            request.finish(with: overlay.image(atMs: ms).composited(over: image), context: nil)
        }
        composition.renderSize = renderSize
        try? FileManager.default.removeItem(at: out)
        guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetHighestQuality) else { throw ExportError.noSession }
        session.videoComposition = composition
        session.outputURL = out
        session.outputFileType = .mp4
        let ticker = Task {
            while !Task.isCancelled {
                progress(Double(session.progress))
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
        }
        await session.export()
        ticker.cancel()
        if session.status != .completed { throw session.error ?? ExportError.failed }
    }

    enum ExportError: Error { case noVideo, noSession, failed }

    /// The tracer as far as the ball had got by each moment (all of it when the video's timing isn't known),
    /// solid where seen and dashed where estimated, plus the caption: drawn once per change, then reused.
    private final class Overlay {
        private let shot: ShotRecord
        private let size: CGSize
        private let points: [ScreenPoint]
        private let lock = NSLock()
        private var cache: [Int: CIImage] = [:]

        init(shot: ShotRecord, size: CGSize) {
            self.shot = shot
            self.size = size
            points = TracerDrawing.normalized(shot.points)
        }

        func image(atMs ms: Int64) -> CIImage {
            let shown: Int
            if let start = shot.videoStartMs?.int64Value { shown = points.filter { $0.timestampMs <= start + ms }.count } else { shown = points.count }
            lock.lock(); defer { lock.unlock() }
            if let cached = cache[shown] { return cached }
            let image = draw(Array(points.prefix(shown)))
            cache[shown] = image
            return image
        }

        private func draw(_ shown: [ScreenPoint]) -> CIImage {
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            format.opaque = false
            let ui = UIGraphicsImageRenderer(size: size, format: format).image { context in
                let ctx = context.cgContext
                caption(in: ctx)
                guard shown.count >= 2 else { return }
                TracerDrawing.drawTracer(shown, style: shot.style, in: ctx, size: size, width: size.height * 0.011, blurGlow: false, endMarker: false)
                let last = shown.last!
                let r = size.height * 0.011 * 0.8
                ctx.setFillColor(UIColor.white.cgColor)
                ctx.fillEllipse(in: CGRect(x: CGFloat(last.x) * size.width - r, y: CGFloat(last.y) * size.height - r, width: 2 * r, height: 2 * r))
            }
            return CIImage(cgImage: ui.cgImage!)
        }

        private func caption(in ctx: CGContext) {
            let line = [shot.club.uppercased(), shot.golferNumber.map { "GOLFER \($0)" }, shot.distance?.label].compactMap { $0 }.joined(separator: "  •  ")
            let font = UIFont.boldSystemFont(ofSize: size.height * 0.022)
            let pad = font.pointSize * 0.6
            let gap = font.pointSize * 1.3
            let title = "GolfBallTracer"
            let width = max((title as NSString).size(withAttributes: [.font: font]).width, line.isEmpty ? 0 : (line as NSString).size(withAttributes: [.font: font]).width)
            let rect = CGRect(x: size.width * 0.04, y: size.height * 0.03, width: width + 2 * pad, height: (line.isEmpty ? 1 : 2) * gap + pad)
            UIColor(red: 15 / 255, green: 23 / 255, blue: 42 / 255, alpha: 170 / 255).setFill()
            UIBezierPath(roundedRect: rect, cornerRadius: pad).fill()
            (title as NSString).draw(at: CGPoint(x: rect.minX + pad, y: rect.minY + pad), withAttributes: [.font: font, .foregroundColor: UIColor(argb: shot.style.colorHex)])
            if !line.isEmpty {
                (line as NSString).draw(at: CGPoint(x: rect.minX + pad, y: rect.minY + pad + gap), withAttributes: [.font: font, .foregroundColor: UIColor.white])
            }
        }
    }
}

/// The share sheet, for files.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// Files to hand to the share sheet.
struct SharedFiles: Identifiable {
    let id = UUID()
    let urls: [URL]
}
