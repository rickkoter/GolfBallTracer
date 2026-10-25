import AVFoundation
import GolfTracking

/// Decodes a shot's video into grey frames for tracing it again ([VideoRetracer] in the shared code), as
/// Android's VideoFrameReader does with MediaCodec. Called from the background, synchronously.
final class AssetFrameSource: NSObject, VideoFrameSource {
    private let asset: AVURLAsset

    init(url: URL) {
        asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
    }

    private var track: AVAssetTrack? { asset.tracks(withMediaType: .video).first }

    /// How far the stored frames turn clockwise to stand upright, from the track's transform.
    private static func rotation(of track: AVAssetTrack) -> Int32 {
        let t = track.preferredTransform
        let degrees = Int((atan2(t.b, t.a) * 180 / .pi).rounded())
        return Int32(((degrees % 360) + 360) % 360)
    }

    func info() -> VideoFrameSourceInfo? {
        guard let track else { return nil }
        let size = track.naturalSize
        let rate = Double(track.nominalFrameRate)
        return VideoFrameSourceInfo(
            durationUs: Int64(CMTimeGetSeconds(asset.duration) * 1e6),
            rotation: Self.rotation(of: track),
            width: Int32(size.width), height: Int32(size.height),
            frameRate: rate > 0 ? KotlinDouble(value: rate) : nil
        )
    }

    func read(fromUs: Int64, toUs: Int64, longSide: Int32, uprightAspect: Float, rotation: KotlinInt?, onFrame: @escaping (LumaFrame) -> Void) -> Int32 {
        guard let track, let reader = try? AVAssetReader(asset: asset) else { return 0 }
        let turn = rotation.map { Int32(truncating: $0) } ?? Self.rotation(of: track)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        ])
        output.alwaysCopiesSampleData = false
        guard reader.canAdd(output) else { return 0 }
        reader.add(output)
        let from = CMTime(value: max(0, fromUs), timescale: 1_000_000)
        let to = CMTime(value: max(fromUs, toUs), timescale: 1_000_000)
        reader.timeRange = CMTimeRange(start: from, end: CMTimeAdd(to, CMTime(value: 1, timescale: 30)))
        guard reader.startReading() else { return 0 }
        let sampler = CameraFrameSampler(targetLongSide: longSide)
        var count: Int32 = 0
        while let sample = output.copyNextSampleBuffer() {
            let ptsUs = Int64(CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample)) * 1e6)
            guard ptsUs >= fromUs, ptsUs <= toUs, let pixels = CMSampleBufferGetImageBuffer(sample) else {
                if ptsUs > toUs { break }
                continue
            }
            CVPixelBufferLockBaseAddress(pixels, .readOnly)
            if let base = CVPixelBufferGetBaseAddressOfPlane(pixels, 0) {
                let frame = sampler.sample(
                    plane: base.assumingMemoryBound(to: UInt8.self),
                    width: Int32(CVPixelBufferGetWidthOfPlane(pixels, 0)),
                    height: Int32(CVPixelBufferGetHeightOfPlane(pixels, 0)),
                    bytesPerRow: Int32(CVPixelBufferGetBytesPerRowOfPlane(pixels, 0)),
                    rotation: turn, timestampNs: ptsUs * 1000, uprightAspect: uprightAspect
                )
                count += 1
                onFrame(frame)
            }
            CVPixelBufferUnlockBaseAddress(pixels, .readOnly)
        }
        reader.cancelReading()
        return count
    }
}
