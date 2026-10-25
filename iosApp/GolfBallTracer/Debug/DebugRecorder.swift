import AVFoundation
import Foundation
import GolfTracking

/**
 * A debug recording, as on Android: everything the app saw and heard for a while, shot or not, for working
 * out later why a shot was missed. Frames and sound are written to temporary files on a queue of their own
 * as they arrive; [finish] puts them together with the video into a debug-session zip in Files:
 *
 *   session.json – timing, and what the app did (events)
 *   audio.wav    – the microphone, 16-bit mono
 *   frames.bin   – every analysis frame: count, then per frame timestamp, width, height, pixels
 *   video.mp4    – the camera's video
 */
final class DebugRecorder {
    /// Longest debug recording.
    static let maxSeconds = 45
    private static let maxQueued = 150

    private let queue = DispatchQueue(label: "debug-recording", qos: .utility)
    private let lock = NSLock()
    private let dir = FileManager.default.temporaryDirectory.appendingPathComponent("debug_session", isDirectory: true)
    private var framesFile: URL { dir.appendingPathComponent("frames.raw") }
    private var audioFile: URL { dir.appendingPathComponent("audio.pcm") }
    private var framesOut: FileHandle?
    private var audioOut: FileHandle?

    private var active = false
    private(set) var startedNs: Int64 = 0
    private var frames = 0
    private var dropped = 0
    private var queued = 0
    private var events: [DebugSession.Event] = []
    private var audioRate = 0
    private var audioStartNs: Int64 = 0
    private var audioSamples: Int64 = 0

    var isActive: Bool { lock.lock(); defer { lock.unlock() }; return active }

    /// Seconds recorded so far.
    var seconds: Int { isActive ? Int((hostNowNs() - startedNs) / 1_000_000_000) : 0 }

    func start() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if active { return true }
        try? FileManager.default.removeItem(at: dir)
        guard (try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)) != nil,
              FileManager.default.createFile(atPath: framesFile.path, contents: nil),
              FileManager.default.createFile(atPath: audioFile.path, contents: nil) else { return false }
        framesOut = try? FileHandle(forWritingTo: framesFile)
        audioOut = try? FileHandle(forWritingTo: audioFile)
        frames = 0; dropped = 0; queued = 0
        events = []
        audioRate = 0; audioStartNs = 0; audioSamples = 0
        startedNs = hostNowNs()
        active = true
        events.append(DebugSession.Event(timestampNs: startedNs, kind: "start", detail: ""))
        return true
    }

    /// An analysis frame, from the camera's queue.
    func onFrame(_ frame: LumaFrame) {
        lock.lock()
        guard active else { lock.unlock(); return }
        // Several seconds behind: storage can't keep up, so this one goes missing rather than memory running out.
        guard queued < Self.maxQueued else { dropped += 1; lock.unlock(); return }
        queued += 1
        lock.unlock()
        var header = Data(capacity: 16)
        withUnsafeBytes(of: frame.timestampNs.bigEndian) { header.append(contentsOf: $0) }
        withUnsafeBytes(of: frame.width.bigEndian) { header.append(contentsOf: $0) }
        withUnsafeBytes(of: frame.height.bigEndian) { header.append(contentsOf: $0) }
        let pixels = frame.pixels() as Data
        queue.async {
            self.framesOut?.write(header)
            self.framesOut?.write(pixels)
            self.lock.lock()
            self.frames += 1
            self.queued -= 1
            self.lock.unlock()
        }
    }

    /// Microphone sound, from the audio thread, as 16-bit samples.
    func onAudio(_ buffer: AVAudioPCMBuffer, firstNs: Int64) {
        guard isActive, let channel = buffer.floatChannelData?[0] else { return }
        let count = Int(buffer.frameLength)
        var pcm = Data(count: count * 2)
        pcm.withUnsafeMutableBytes { raw in
            let out = raw.bindMemory(to: Int16.self)
            for i in 0..<count { out[i] = Int16(max(-32768, min(32767, (channel[i] * 32767).rounded()))).littleEndian }
        }
        let rate = Int(buffer.format.sampleRate)
        queue.async {
            self.lock.lock()
            if self.audioRate == 0 {
                self.audioRate = rate
                self.audioStartNs = firstNs
            }
            self.audioSamples += Int64(count)
            self.lock.unlock()
            self.audioOut?.write(pcm)
        }
    }

    /// Notes something the app did, for the recording's timeline.
    func log(_ kind: String, _ detail: String = "", at timestampNs: Int64 = hostNowNs()) {
        lock.lock(); defer { lock.unlock() }
        guard active else { return }
        events.append(DebugSession.Event(timestampNs: timestampNs, kind: kind, detail: detail))
    }

    /// Stops recording, so the video can be stopped too before [finish].
    func stop() {
        guard isActive else { return }
        log("stop")
        lock.lock(); active = false; lock.unlock()
        queue.sync {
            try? self.framesOut?.close()
            try? self.audioOut?.close()
            self.framesOut = nil
            self.audioOut = nil
        }
    }

    /// Writes the recording to Files as debug-session_*.zip with [video]. Slow: call off the main thread.
    /// Returns its name, or nil.
    func finish(video: URL?, videoStartNs: Int64?, micEnabled: Bool, camera: LaunchPhysics.CameraPose?) -> String? {
        stop()
        defer {
            if let video { try? FileManager.default.removeItem(at: video) }
            try? FileManager.default.removeItem(at: dir)
        }
        lock.lock()
        let json = AppleShotsKt.debugSessionJson(
            device: DeviceInfo.name, startedNs: startedNs, endedNs: hostNowNs(), frameCount: Int32(frames), framesDropped: Int32(dropped),
            audioSampleRate: Int32(audioRate), audioStartNs: audioStartNs, audioSamples: audioSamples,
            videoStartNs: videoStartNs.map { KotlinLong(value: $0) }, micEnabled: micEnabled, events: events, camera: camera
        )
        let frameCount = Int32(frames)
        let rate = audioRate
        let samples = audioSamples
        lock.unlock()

        let name = "debug-session_\(Date().fileStamp).zip"
        let zip = ZipFileWriter(path: Documents.folder.appendingPathComponent(name).path)
        guard zip.isOpen else { return nil }
        var ok = zip.addData(name: "session.json", data: Data(json.utf8) as NSData as Data, compress: true)
        // Sound before frames, so a replay knows the strikes before it reaches them.
        if rate > 0 {
            ok = ok && zip.addFile(name: "audio.wav", sourcePath: audioFile.path, prefix: AppleShotsKt.wavHeader(sampleRate: Int32(rate), dataBytes: samples * 2), compress: true)
        }
        var count = frameCount.bigEndian
        ok = ok && zip.addFile(name: "frames.bin", sourcePath: framesFile.path, prefix: Data(bytes: &count, count: 4), compress: true)
        if let video, FileManager.default.fileExists(atPath: video.path) {
            ok = ok && zip.addFile(name: "video.mp4", sourcePath: video.path, prefix: nil, compress: false)
        }
        ok = zip.close() && ok
        return ok ? name : nil
    }

    /// Throws the recording away.
    func abandon() {
        stop()
        try? FileManager.default.removeItem(at: dir)
    }
}
