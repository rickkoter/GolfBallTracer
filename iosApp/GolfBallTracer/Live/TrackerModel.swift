import AVFoundation
import SwiftUI
import GolfTracking

/// The live tracer, as on Android: listening starts when the golfer asks; a shot is the strike heard
/// together with the ball leaving; once it lands the tracer is drawn over the picture and listening stops
/// until the next Start listening, so the club or golfer can be changed. Every shot is saved as a recording.
@MainActor
final class TrackerModel: ObservableObject {
    enum Phase: Equatable { case idle, listening, capturing, analyzing }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var problem: String?
    @Published private(set) var strikeNote: String?
    @Published private(set) var micAvailable = true

    // The shot on screen: a picture of the moment with its tracer.
    @Published private(set) var still: CGImage?
    @Published private(set) var stillShift: CGPoint = .zero
    @Published private(set) var tracer: [ScreenPoint] = []
    @Published private(set) var sightings: [ScreenPoint] = []
    @Published private(set) var teeBall: TeeBall?
    @Published private(set) var shotStats: String?
    @Published private(set) var saveNote: String?

    /// The club and golfer the next shot is saved with; remembered between launches.
    @Published var club: String = UserDefaults.standard.string(forKey: "club") ?? ClubType.driver.displayName {
        didSet { UserDefaults.standard.set(club, forKey: "club") }
    }
    @Published var golfer: Int = max(1, UserDefaults.standard.integer(forKey: "golfer")) {
        didSet { UserDefaults.standard.set(golfer, forKey: "golfer") }
    }

    let camera = CameraController()
    private let detector = ShotDetector()
    private let microphone = StrikeMicrophone()
    private let motion = MotionSensor()
    private weak var store: RecordingStore?

    /// Events from before the user last started over are let go.
    private var resetAtNs: Int64 = 0
    private var launchPose: LaunchPhysics.CameraPose?
    private var lastStrikeDb: Float?
    private var started = false

    var isListening: Bool { phase != .idle }
    var showsShot: Bool { still != nil }

    init() {
        camera.wantsFrames = { [detector] in detector.wantsFrames }
        camera.onFrame = { [detector] frame in
            detector.offer(frame)
            LiveCheck.frame(frame)
        }
        microphone.onStrike = { [weak self, detector] ns, db in
            detector.strikeHeard(at: ns)
            LiveCheck.log(String(format: "strike at %.3f s, +%.0f dB", Double(ns) / 1e9, db))
            DispatchQueue.main.async { self?.lastStrikeDb = db }
        }
        detector.onEvent = { [weak self] event in self?.handle(event) }
    }

    /// Opens the camera (asking first, the first time) when the screen appears.
    func appear(store: RecordingStore) {
        self.store = store
        UIApplication.shared.isIdleTimerDisabled = true
        motion.start()
        guard !started else { camera.start { _ in }; return }
        started = true
        AVCaptureDevice.requestAccess(for: .video) { granted in
            DispatchQueue.main.async {
                guard granted else {
                    self.problem = "Camera access is off. Turn it on in Settings › GolfBallTracer to trace shots."
                    return
                }
                self.camera.start { problem in
                    self.problem = problem
                    LiveCheck.log("camera \(problem ?? "started"), aspect \(self.camera.uprightAspect), vertical view \(self.camera.verticalFieldOfView.map { String(format: "%.1f°", $0 * 180 / .pi) } ?? "?")")
                    if LiveCheck.requested {
                        LiveCheck.startReporting { [detector = self.detector] in detector.queuedFrames }
                        self.startListening()
                    }
                }
            }
        }
    }

    func disappear() {
        stopListening()
        camera.stop()
        motion.stop()
        UIApplication.shared.isIdleTimerDisabled = false
    }

    func toggleListening() {
        if isListening { stopListening() } else { startListening() }
    }

    func startListening() {
        guard phase == .idle else { return }
        clearShot()
        strikeNote = nil
        resetAtNs = hostNowNs()
        phase = .listening
        StrikeMicrophone.requestAccess { granted in
            self.microphone.start(enabled: granted) { problem in
                guard self.phase == .listening else { return }
                self.micAvailable = granted && problem == nil
                if let problem { self.strikeNote = "Couldn't start the microphone: \(problem)" }
                LiveCheck.log("listening, microphone \(self.micAvailable ? "on" : "off")\(problem.map { ": \($0)" } ?? "")")
                // Without the microphone, shots are looked for in bursts of motion instead.
                self.detector.watchWithoutSound = !self.micAvailable
                self.detector.startListening()
            }
        }
    }

    func stopListening() {
        detector.stopListening()
        microphone.stop()
        resetAtNs = hostNowNs()
        if phase != .analyzing { phase = .idle }
    }

    /// Back to the live picture, listening for the next shot.
    func nextShot() {
        clearShot()
        startListening()
    }

    func clearShot() {
        still = nil
        tracer = []
        sightings = []
        teeBall = nil
        shotStats = nil
        saveNote = nil
        stillShift = .zero
    }

    private func handle(_ event: ShotTriggerEvent) {
        LiveCheck.log("event \(event)")
        if let started = event as? ShotTriggerEventStarted {
            guard started.impactNs >= resetAtNs else { return }
            // A phone pointing at the ground isn't filming a shot: it's being carried, or balls raked over.
            if motion.cameraLooksDown {
                strikeNote = "🔊 Heard a strike with the phone pointing at the ground · not taken as a shot"
                resetAtNs = hostNowNs()
                detector.restart()
                return
            }
            clearShot()
            phase = .capturing
            teeBall = started.ball
            stillShift = CGPoint(x: CGFloat(started.stillShiftX), y: CGFloat(started.stillShiftY))
            launchPose = currentPose()
            camera.captureStill { [weak self] image in
                // The shot may have been let go meanwhile.
                guard let self, self.phase == .capturing || self.phase == .analyzing else { return }
                self.still = image
            }
        } else if let captured = event as? ShotTriggerEventCaptured {
            guard phase == .capturing else { return }
            analyze(captured.shot)
        } else if let missed = event as? ShotTriggerEventMissed {
            guard missed.impactNs >= resetAtNs else { return }
            let loud = lastStrikeDb.map { String(format: " (+%.0f dB)", $0) } ?? ""
            let time = Date().formatted(date: .omitted, time: .standard)
            if missed.tooSoon {
                strikeNote = "🔊 \(time) heard a strike\(loud) just as listening started · too soon to see the ball"
            } else if missed.duringSwing {
                strikeNote = "🔊 \(time) heard a strike\(loud) during a swing · no ball seen leaving"
            } else {
                strikeNote = "🔊 \(time) heard a sharp sound\(loud) · no swing seen"
            }
        }
    }

    private func analyze(_ shot: CapturedShot) {
        phase = .analyzing
        // Listening stays off until the golfer starts it again, so there's time to look at this shot.
        detector.stopListening()
        microphone.stop()
        let pose = launchPose
        let club = self.club
        let golfer = self.golfer
        let device = Self.deviceName
        Task {
            let flight = await Task.detached(priority: .userInitiated) { LiveInputKt.fitShot(shot: shot, camera: pose) }.value
            if flight == nil && !shot.heardImpact {
                // Something left a still spot during some movement, but nothing was heard and nothing flew.
                self.clearShot()
                self.phase = .idle
                self.strikeNote = "Saw movement but no ball flight · still listening"
                self.startListening()
                return
            }
            self.tracer = flight?.tracer ?? []
            self.sightings = flight?.sightings ?? []
            self.shotStats = Self.stats(shot, flight)
            self.phase = .idle
            self.saveNote = "Saving recording…"
            let saved = await Task.detached(priority: .utility) { () -> URL? in
                guard let data = LiveInputKt.encodeShotRecording(shot: shot, flight: flight, device: device, club: club, golfer: Int32(golfer)) else { return nil }
                return Self.write(data)
            }.value
            self.saveNote = saved != nil ? "Saved to Recordings" : "Couldn't save this shot's recording"
            LiveCheck.log("shot: \(self.shotStats ?? "") · tracer \(self.tracer.count) points · saved \(saved?.lastPathComponent ?? "no")")
            self.store?.reload()
        }
    }

    private func currentPose() -> LaunchPhysics.CameraPose? {
        guard let fov = camera.verticalFieldOfView, let m = motion.deviceToWorld else { return nil }
        return LiveInputKt.cameraPose(verticalFovRadians: fov, m0: m[0], m1: m[1], m2: m[2], m3: m[3], m4: m[4], m5: m[5], m6: m[6], m7: m[7], m8: m[8])
    }

    private static func stats(_ shot: CapturedShot, _ flight: BallFlightFitter.Flight?) -> String {
        let frames = shot.thumbnails
        let seconds = frames.count > 1 ? Double(frames.last!.timestampNs - frames.first!.timestampNs) / 1e9 : 0
        let fps = seconds > 0 ? Double(shot.framesProcessed) / seconds : 0
        var text = String(format: "%d spots · %.1f s at %.0f fps · strike %@ · ball left %@",
                          shot.candidates.count, seconds, fps,
                          shot.heardImpact ? "heard" : "seen",
                          shot.launchTimestampNs != nil ? "✓" : "✗")
        if let flight, flight.estimated {
            text += String(format: "\nBall seen for %.2f s: the dashed part of the tracer is estimated", flight.trackedSeconds)
        } else if flight == nil {
            text += "\nNo ball flight was found"
        }
        return text
    }

    nonisolated private static func write(_ data: Data) -> URL? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd_HH-mm-ss"
        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let url = folder.appendingPathComponent("shot_\(formatter.string(from: Date())).zip")
        do {
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            return nil
        }
    }

    /// The model, e.g. "iPhone17,1", as saved with each recording.
    static let deviceName: String = {
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
        return "\(machine) (iOS \(UIDevice.current.systemVersion))"
    }()
}
