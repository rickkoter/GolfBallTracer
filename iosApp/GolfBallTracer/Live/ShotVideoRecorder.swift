import AVFoundation
import Foundation

/// Keeps the camera recording video all the time the tracker is listening, as on Android, so whenever a
/// shot happens the swing before it is already on video. After a shot the recording is stopped and handed
/// over, and a new one started. Frames come from the camera's own frames (and sound from the microphone),
/// written with the host-clock times they were captured at.
final class ShotVideoRecorder {
    /// A finished recording. [startNs] is when its first frame was taken, on the frames' clock.
    struct Clip { let url: URL; let startNs: Int64? }

    private let lock = NSLock()
    private var writer: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var audioInput: AVAssetWriterInput?
    private var audioFormat: CMAudioFormatDescription?
    private var url: URL?
    private var armed = false
    private var startNs: Int64?
    private var startedAt = Date()
    private var counter = 0

    private static let dir: URL = {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("rolling_video", isDirectory: true)
        // Whatever is left from before was never handed over.
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    var isRecording: Bool { lock.lock(); defer { lock.unlock() }; return armed }

    /// How long the current recording has been running, in seconds.
    var age: TimeInterval { isRecording ? Date().timeIntervalSince(startedAt) : 0 }

    /// Starts a recording; it begins with the next camera frame.
    func start() {
        lock.lock(); defer { lock.unlock() }
        guard !armed else { return }
        armed = true
        startNs = nil
        startedAt = Date()
    }

    /// A camera frame, on the camera's queue.
    func append(video sample: CMSampleBuffer) {
        lock.lock(); defer { lock.unlock() }
        guard armed else { return }
        if writer == nil { open(with: sample) }
        guard let input = videoInput, input.isReadyForMoreMediaData, writer?.status == .writing else { return }
        input.append(sample)
    }

    /// Microphone sound, on the audio thread, with when its first sample was captured.
    func append(audio buffer: AVAudioPCMBuffer, firstNs: Int64) {
        lock.lock(); defer { lock.unlock() }
        guard armed, let writer, writer.status == .writing, let input = audioInput, input.isReadyForMoreMediaData,
              let startNs, firstNs >= startNs, let sample = makeSample(buffer, firstNs) else { return }
        input.append(sample)
    }

    /// Stops recording and hands the video over once it's written; nil if there was none.
    func stop() async -> Clip? {
        lock.lock()
        let active = writer
        let file = url
        let start = startNs
        reset()
        lock.unlock()
        guard let active, let file else { return nil }
        videoInput(of: active)?.markAsFinished()
        await active.finishWriting()
        guard active.status == .completed else {
            try? FileManager.default.removeItem(at: file)
            return nil
        }
        return Clip(url: file, startNs: start)
    }

    /// Stops recording and throws the video away.
    func discard() {
        lock.lock()
        let active = writer
        let file = url
        reset()
        lock.unlock()
        guard let active, let file else { return }
        active.cancelWriting()
        try? FileManager.default.removeItem(at: file)
    }

    private func reset() {
        writer = nil
        videoInput = nil
        audioInput = nil
        url = nil
        armed = false
        startNs = nil
    }

    private func videoInput(of w: AVAssetWriter) -> AVAssetWriterInput? { w.inputs.first { $0.mediaType == .video } }

    private func open(with sample: CMSampleBuffer) {
        guard let pixels = CMSampleBufferGetImageBuffer(sample) else { return }
        counter += 1
        let file = Self.dir.appendingPathComponent("rolling_\(Int(Date().timeIntervalSince1970 * 1000))_\(counter).mp4")
        guard let w = try? AVAssetWriter(outputURL: file, fileType: .mp4) else { return }
        let width = CVPixelBufferGetWidth(pixels)
        let height = CVPixelBufferGetHeight(pixels)
        let video = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 16_000_000]
        ])
        video.expectsMediaDataInRealTime = true
        // The camera's frames are sideways; played back, they're turned upright (portrait).
        video.transform = CGAffineTransform(rotationAngle: .pi / 2)
        guard w.canAdd(video) else { return }
        w.add(video)
        let audio = AVAssetWriterInput(mediaType: .audio, outputSettings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVNumberOfChannelsKey: 1,
            AVSampleRateKey: 48_000,
            AVEncoderBitRateKey: 96_000
        ])
        audio.expectsMediaDataInRealTime = true
        if w.canAdd(audio) { w.add(audio) }
        guard w.startWriting() else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sample)
        w.startSession(atSourceTime: pts)
        writer = w
        videoInput = video
        audioInput = w.inputs.contains(audio) ? audio : nil
        audioFormat = nil
        url = file
        startNs = Int64(CMTimeGetSeconds(pts) * 1e9)
    }

    /// The microphone's buffer as a sample buffer timed on the host clock, for the video's sound track.
    private func makeSample(_ buffer: AVAudioPCMBuffer, _ firstNs: Int64) -> CMSampleBuffer? {
        if audioFormat == nil {
            var description: CMAudioFormatDescription?
            CMAudioFormatDescriptionCreate(allocator: kCFAllocatorDefault, asbd: buffer.format.streamDescription,
                                           layoutSize: 0, layout: nil, magicCookieSize: 0, magicCookie: nil,
                                           extensions: nil, formatDescriptionOut: &description)
            audioFormat = description
        }
        guard let format = audioFormat else { return nil }
        let rate = buffer.format.sampleRate
        var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: CMTimeScale(rate)),
                                        presentationTimeStamp: CMTime(seconds: Double(firstNs) / 1e9, preferredTimescale: CMTimeScale(rate)),
                                        decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreate(allocator: kCFAllocatorDefault, dataBuffer: nil, dataReady: false, makeDataReadyCallback: nil,
                                   refcon: nil, formatDescription: format, sampleCount: CMItemCount(buffer.frameLength),
                                   sampleTimingEntryCount: 1, sampleTimingArray: &timing, sampleSizeEntryCount: 0,
                                   sampleSizeArray: nil, sampleBufferOut: &sample) == noErr, let sample else { return nil }
        guard CMSampleBufferSetDataBufferFromAudioBufferList(sample, blockBufferAllocator: kCFAllocatorDefault,
                                                             blockBufferMemoryAllocator: kCFAllocatorDefault, flags: 0,
                                                             bufferList: buffer.audioBufferList) == noErr else { return nil }
        return sample
    }

    /**
     * Copies [source] from [fromSeconds] onward into [target] without re-encoding. Returns where in the
     * source the copy starts (seconds), or nil if it couldn't be done.
     */
    static func trim(_ source: URL, to target: URL, fromSeconds: Double) async -> Double? {
        let asset = AVURLAsset(url: source)
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetPassthrough) else { return nil }
        let duration = (try? await asset.load(.duration)) ?? .zero
        let from = max(0, min(fromSeconds, CMTimeGetSeconds(duration)))
        try? FileManager.default.removeItem(at: target)
        export.outputURL = target
        export.outputFileType = .mp4
        export.timeRange = CMTimeRange(start: CMTime(seconds: from, preferredTimescale: 600), end: duration)
        await export.export()
        return export.status == .completed ? from : nil
    }
}
