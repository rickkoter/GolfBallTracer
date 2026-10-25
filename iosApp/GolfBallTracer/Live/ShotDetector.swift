import Foundation
import GolfTracking

/// Runs the shared shot trigger on its own queue, so the camera only hands frames over and is never held
/// up while a shot is found or captured. Frames queue up meanwhile rather than being dropped, so the
/// start of the flight isn't lost. Events are delivered on the main thread.
final class ShotDetector {
    private let queue = DispatchQueue(label: "shot-detector", qos: .userInitiated)
    private let lock = NSLock()
    private var queued = 0
    /// Bumped when listening starts over, so frames already queued are let go.
    private var generation = 0
    private var trigger: LiveShotTrigger!

    /// Frames thrown away because the detector had fallen hopelessly behind.
    private(set) var droppedFrames = 0

    var onEvent: ((ShotTriggerEvent) -> Void)?

    init() {
        trigger = LiveShotTrigger { [weak self] event in
            DispatchQueue.main.async { self?.onEvent?(event) }
        }
    }

    /// Whether frames are wanted (listening or capturing); the camera skips converting them otherwise.
    var wantsFrames: Bool { trigger.wantsFrames }

    /// Whether to look for shots in bursts of motion too, for when there's no microphone to hear them.
    var watchWithoutSound = false {
        didSet { let on = watchWithoutSound; queue.async { self.trigger.watchWithoutSound = on } }
    }

    var queuedFrames: Int { lock.lock(); defer { lock.unlock() }; return queued }

    /// When swing-sized motion was last seen, on the frames' clock.
    var lastActivityNs: Int64 { trigger.lastActivityNs }

    /// The newest frame looked at so far, on the frames' clock; 0 before any.
    private var lastSeenFrameNs: Int64 = 0

    /// How far behind the camera the detector is, in seconds, or 0 when it's keeping up (or idle).
    var lagSeconds: Double {
        lock.lock(); let waiting = queued; lock.unlock()
        guard lastSeenFrameNs > 0, waiting > 0 else { return 0 }
        return max(0, Double(hostNowNs() - lastSeenFrameNs) / 1e9)
    }

    func offer(_ frame: LumaFrame) {
        lock.lock()
        // About 10 s of frames: beyond that the detector has fallen hopelessly behind.
        guard queued < 300 else { droppedFrames += 1; lock.unlock(); return }
        queued += 1
        let mine = generation
        lock.unlock()
        queue.async {
            self.lock.lock()
            self.queued -= 1
            let current = mine == self.generation
            self.lock.unlock()
            if current {
                self.lastSeenFrameNs = frame.timestampNs
                self.trigger.onFrame(frame: frame)
            }
        }
    }

    func strikeHeard(at timestampNs: Int64) {
        queue.async { self.trigger.onImpactSound(timestampNs: timestampNs) }
    }

    func startListening() {
        queue.async { self.trigger.startListening() }
    }

    /// Stops listening, letting go of any shot being captured and of frames still waiting.
    func stopListening() {
        lock.lock(); generation += 1; lock.unlock()
        queue.async { self.trigger.stopListening() }
    }

    /// Starts listening afresh from what the camera sees now.
    func restart() {
        lock.lock(); generation += 1; lock.unlock()
        queue.async {
            self.trigger.stopListening()
            self.trigger.startListening()
        }
    }

    func finishCapture() {
        queue.async { self.trigger.finishCapture() }
    }
}
