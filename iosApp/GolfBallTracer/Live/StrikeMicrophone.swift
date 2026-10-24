import AVFoundation
import GolfTracking

/// Listens on the microphone for club strikes, with the shared strike detector, and reports when each
/// happened on the host clock the camera's frames are timed on.
final class StrikeMicrophone {
    private let engine = AVAudioEngine()
    private var listener: StrikeListener?
    private var isRunning = false
    /// Starting and stopping the audio engine can take a moment, so it's done here, in order.
    private let queue = DispatchQueue(label: "microphone")

    /// Called on the audio thread with when the strike happened (ns) and how far above the background it was (dB).
    var onStrike: ((Int64, Float) -> Void)?

    static func requestAccess(_ done: @escaping (Bool) -> Void) {
        AVAudioApplication.requestRecordPermission { granted in DispatchQueue.main.async { done(granted) } }
    }

    /// Starts listening (when [enabled]); calls back on the main thread with a problem, if any.
    func start(enabled: Bool, _ done: @escaping (String?) -> Void) {
        queue.async {
            var problem: String?
            if enabled {
                do { try self.startNow() } catch { problem = error.localizedDescription }
            }
            DispatchQueue.main.async { done(problem) }
        }
    }

    func stop() {
        queue.async { self.stopNow() }
    }

    private func startNow() throws {
        guard !isRunning else { return }
        let audio = AVAudioSession.sharedInstance()
        // Measurement mode leaves out the voice processing and gain control, which smooth away a strike's sharp edge.
        try audio.setCategory(.playAndRecord, mode: .measurement, options: [.mixWithOthers, .defaultToSpeaker])
        try audio.setActive(true)
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { throw MicrophoneProblem.noInput }
        let rate = format.sampleRate
        let listener = StrikeListener(sampleRate: Int32(rate))
        self.listener = listener
        input.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, when in
            guard let samples = buffer.floatChannelData?[0] else { return }
            let count = Int(buffer.frameLength)
            let firstNs: Int64
            if when.isHostTimeValid {
                firstNs = Int64(AVAudioTime.seconds(forHostTime: when.hostTime) * 1e9)
            } else {
                // Without a time, take it that the last sample was captured just now.
                firstNs = Int64(AVAudioTime.seconds(forHostTime: mach_absolute_time()) * 1e9) - Int64(Double(count) / rate * 1e9)
            }
            for strike in listener.feed(samples: samples, count: Int32(count), firstSampleNs: firstNs) {
                self?.onStrike?(strike.timestampNs, strike.decibels)
            }
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            throw error
        }
        isRunning = true
    }

    private func stopNow() {
        guard isRunning else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        listener = nil
        isRunning = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    enum MicrophoneProblem: LocalizedError {
        case noInput
        var errorDescription: String? { "No microphone input is available." }
    }
}

/// The host clock, in nanoseconds: what camera frames and strikes are timed on.
func hostNowNs() -> Int64 {
    Int64(AVAudioTime.seconds(forHostTime: mach_absolute_time()) * 1e9)
}
