import CoreMotion
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
            if current { self.trigger.onFrame(frame: frame) }
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

/// Which way the phone points, for the camera's pose (to estimate a flight only glimpsed) and to let go of
/// strikes heard while the camera faces the ground.
final class MotionSensor {
    private let manager = CMMotionManager()

    func start() {
        guard manager.isDeviceMotionAvailable, !manager.isDeviceMotionActive else { return }
        manager.deviceMotionUpdateInterval = 0.1
        manager.startDeviceMotionUpdates(using: .xMagneticNorthZVertical)
    }

    func stop() { manager.stopDeviceMotionUpdates() }

    /// Pointing more than 55° below level (as on Android): filming the ground, not a shot.
    var cameraLooksDown: Bool {
        // The back camera looks along the phone's −z; gravity's z is how far that is downward.
        guard let gravity = manager.deviceMotion?.gravity else { return false }
        return gravity.z < -0.82
    }

    /// Row-major rotation from phone axes (x right, y up the screen, z out of it) to east/north/up.
    var deviceToWorld: [Float]? {
        guard let motion = manager.deviceMotion else { return nil }
        let r = motion.attitude.rotationMatrix
        // Core Motion's reference frame is x north, y west, z up. Whether its matrix maps phone to reference
        // or the other way round is settled by gravity: phone-to-reference must turn "up" (−gravity) into +z.
        let m = [[r.m11, r.m12, r.m13], [r.m21, r.m22, r.m23], [r.m31, r.m32, r.m33]]
        let up = [-motion.gravity.x, -motion.gravity.y, -motion.gravity.z]
        let zIfPhoneToReference = m[2][0] * up[0] + m[2][1] * up[1] + m[2][2] * up[2]
        let zIfReferenceToPhone = m[0][2] * up[0] + m[1][2] * up[1] + m[2][2] * up[2]
        let toReference: [[Double]] = zIfPhoneToReference >= zIfReferenceToPhone
            ? m
            : [[m[0][0], m[1][0], m[2][0]], [m[0][1], m[1][1], m[2][1]], [m[0][2], m[1][2], m[2][2]]]
        // East is minus west, north is north, up is up.
        let east = toReference[1].map { -$0 }
        let north = toReference[0]
        let upRow = toReference[2]
        return (east + north + upRow).map(Float.init)
    }
}
