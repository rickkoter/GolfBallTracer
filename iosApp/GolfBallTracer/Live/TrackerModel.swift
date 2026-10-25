import AVFoundation
import SwiftUI
import GolfTracking

/// A saved shot whose ball flight can't be trusted: part of it wasn't recorded ([reasons]), or no flight was
/// found at all. The user is asked whether to keep it.
struct UncertainShot: Identifiable {
    let id: String
    let reasons: [String]
    let noFlight: Bool
}

/**
 * The tracer screen, as on Android: listening starts when the golfer asks; a shot is the strike heard
 * together with the ball leaving; once it lands the tracer is drawn over a still of the swing, the shot is
 * saved with its video, and listening stops until the next Start listening (so the club or golfer can be
 * changed). Settings has the flash, the microphone, curve tuning, tracer styles, debug recordings and the
 * demo flight.
 */
@MainActor
final class TrackerModel: ObservableObject {
    /// What the tracker is doing: waiting for a shot (showing the last one, if any), capturing one, or working it out.
    enum Phase { case listening, capturing, analyzing }

    // Listening
    @Published private(set) var phase: Phase = .listening
    /// The one switch: off when the app starts, and after each shot until Start listening.
    @Published private(set) var isPaused = true
    @Published private(set) var isListening = false
    @Published private(set) var isMicOn = false
    @Published private(set) var micPermission = AVAudioApplication.shared.recordPermission == .granted
    @Published private(set) var micLevelDb: Float = -90
    @Published private(set) var micNote: String?
    @Published private(set) var strikeNote: String?
    @Published private(set) var isVideoAvailable: Bool?
    @Published private(set) var detectorLag: Double = 0
    @Published private(set) var cameraProblem: String?

    // Settings
    @Published private(set) var micEnabled = true
    @Published var flashOn = false { didSet { camera.setTorch(flashOn) } }
    @Published var editMode = false
    @Published var tracerStyle: TracerStyle = TrackerModel.savedStyle() {
        didSet { UserDefaults.standard.set(tracerStyle.name, forKey: "tracerStyle") }
    }
    /// The club and golfer the next shot is tagged with; remembered between launches.
    @Published var club: String = UserDefaults.standard.string(forKey: "club") ?? ClubType.driver.displayName {
        didSet { UserDefaults.standard.set(club, forKey: "club") }
    }
    @Published var golfer: Int = max(1, UserDefaults.standard.integer(forKey: "golfer")) {
        didSet { UserDefaults.standard.set(golfer, forKey: "golfer") }
    }

    // The shot on screen
    @Published private(set) var detectedPoints: [ScreenPoint] = []
    @Published private(set) var teeLocation: CGPoint?
    @Published private(set) var teeBallRadius: Float?
    @Published private(set) var currentShotId: String?
    @Published private(set) var shotStill: CGImage?
    @Published private(set) var detectedSpots: [ScreenPoint] = []
    @Published private(set) var isBallFlightMissing = false
    @Published private(set) var launchSeen = false
    @Published private(set) var isRetracing = false
    @Published var isShotRevealed = false
    @Published private(set) var shotStats: String?
    @Published private(set) var saveNote: String?
    @Published private(set) var videoNote: String?
    @Published private(set) var ballLanding: LaunchPhysics.Landing?
    @Published private(set) var locatorProblem: String?

    // Prompts
    @Published var showLimitPrompt = false
    @Published var uncertainShot: UncertainShot?

    // Debug recording
    @Published private(set) var isDebugRecording = false
    @Published private(set) var isSavingDebug = false
    @Published private(set) var debugSeconds = 0
    @Published private(set) var debugNote: String?
    @Published var debugNoteText = ""
    @Published private(set) var isWritingDebugNote = false
    private var debugNoteAtNs: Int64?

    let camera = CameraController()
    let location = LocationService()
    let orientation = DeviceOrientation()
    let store: ShotStore
    let processor: ShotProcessor
    private let detector = ShotDetector()
    private let microphone = StrikeMicrophone()
    private let recorder = ShotVideoRecorder()
    private let debugRecorder = DebugRecorder()

    /// Bumped by every reset, so a shot still being worked out when the user starts over isn't shown after.
    private var resetCount = 0
    /// When the last reset was, on the frames' clock: anything the detector reports from before it is stale.
    private var resetAtNs: Int64 = 0
    /// Shots handed to the processor but not saved yet still count towards the recording limit.
    private var pendingSaves = 0
    private var stillShift = CGPoint.zero
    private var launchPose: LaunchPhysics.CameraPose?
    private var lastStrikeDb: Float?
    private var capturingSince: Date?
    private var wasBehind = false
    private var started = false
    private var timers: [Timer] = []

    var showingShot: Bool { phase == .listening && (!detectedPoints.isEmpty || currentShotId != nil) }
    var recordingCount: Int { store.recordingCount + pendingSaves }
    var limitReached: Bool { !isListening && !isPaused && recordingCount >= ShotStore.maxRecordings }
    private var atRecordingLimit: Bool { store.recordingCount + pendingSaves >= ShotStore.maxRecordings }

    init(store: ShotStore) {
        self.store = store
        processor = ShotProcessor(store: store)
        camera.wantsFrames = { [detector, debugRecorder] in detector.wantsFrames || debugRecorder.isActive }
        camera.queuedFrames = { [detector] in detector.queuedFrames }
        camera.onFrame = { [detector, debugRecorder] frame in
            detector.offer(frame)
            debugRecorder.onFrame(frame)
            LiveCheck.frame(frame)
        }
        camera.onVideoSample = { [recorder] sample in recorder.append(video: sample) }
        microphone.onAudio = { [recorder, debugRecorder] buffer, firstNs in
            recorder.append(audio: buffer, firstNs: firstNs)
            debugRecorder.onAudio(buffer, firstNs: firstNs)
        }
        microphone.onStrike = { [weak self, detector, debugRecorder] ns, db in
            detector.strikeHeard(at: ns)
            debugRecorder.log("strike-heard", String(format: "%+.1f dB", db), at: ns)
            LiveCheck.log(String(format: "strike at %.3f s, +%.0f dB", Double(ns) / 1e9, db))
            DispatchQueue.main.async { self?.lastStrikeDb = db }
        }
        detector.onEvent = { [weak self] event in self?.handle(event) }
        processor.onUpdate = { [weak self] update in self?.apply(update) }
    }

    private static func savedStyle() -> TracerStyle {
        let name = UserDefaults.standard.string(forKey: "tracerStyle")
        return TracerStyle.entries.first { $0.name == name } ?? .proLime
    }

    // MARK: - Screen and camera

    /// Opens the camera (asking first, the first time), GPS and the compass when the screen appears.
    func appear() {
        UIApplication.shared.isIdleTimerDisabled = true
        orientation.start()
        location.start()
        micPermission = AVAudioApplication.shared.recordPermission == .granted
        startTimers()
        if started {
            camera.start { _ in }
            updateMicrophone()
            if isListening, !recorder.isRecording { recorder.start() }
            return
        }
        started = true
        AVCaptureDevice.requestAccess(for: .video) { granted in
            DispatchQueue.main.async {
                guard granted else {
                    self.cameraProblem = "Camera access is off. Turn it on in Settings › GolfBallTracer to trace shots."
                    return
                }
                self.camera.start { problem in
                    self.cameraProblem = problem
                    self.isVideoAvailable = problem == nil
                    LiveCheck.log("camera \(problem ?? "started") at \(Int(self.camera.cameraFps)) fps, aspect \(self.camera.uprightAspect)")
                    if DevChecks.videoCheck { Task { await DevChecks.runVideoCheck() } }
                    if DevChecks.debugCheck { self.runDebugCheck() }
                    if LiveCheck.requested {
                        LiveCheck.startReporting(queued: { [detector = self.detector] in detector.queuedFrames },
                                                 analysisFps: { [camera = self.camera] in camera.analysisFps })
                        self.resetShot()
                    }
                }
                if AVAudioApplication.shared.recordPermission == .undetermined {
                    StrikeMicrophone.requestAccess { granted in
                        self.micPermission = granted
                        self.updateMicrophone()
                    }
                }
            }
        }
    }

    /// The app went to the background: the camera, microphone and video stop (iOS stops the camera anyway).
    func background() {
        timers.forEach { $0.invalidate() }
        timers = []
        recorder.discard()
        microphone.stop()
        isMicOn = false
        camera.stop()
        orientation.stop()
        location.stop()
        UIApplication.shared.isIdleTimerDisabled = false
    }

    private func startTimers() {
        guard timers.isEmpty else { return }
        // Keep an eye on the detector, the microphone, a capture that never ends, and the debug recording's clock.
        timers.append(Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        })
        // A recording running long with nothing happening is started afresh, so shot videos stay short.
        timers.append(Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.restartIdleRecording() }
        })
    }

    private func tick() {
        micLevelDb = isMicOn ? microphone.levelDb : -90
        detectorLag = detector.lagSeconds
        let behind = detectorLag > 1.5
        if behind, !wasBehind { debugRecorder.log("detector-behind", String(format: "%.1f s, %d frames waiting", detectorLag, detector.queuedFrames)) }
        wasBehind = behind
        // A capture lasts a few seconds; one that never ends would leave the screen stuck, so start over.
        if phase != .capturing {
            capturingSince = nil
        } else if let since = capturingSince {
            if Date().timeIntervalSince(since) > 20, !behind {
                debugRecorder.log("stuck-capture", "no end after \(Int(Date().timeIntervalSince(since))) s")
                resetShot()
            }
        } else {
            capturingSince = Date()
        }
        if isDebugRecording {
            debugSeconds = debugRecorder.seconds
            if debugSeconds >= DebugRecorder.maxSeconds { Task { await stopDebugRecording() } }
        }
    }

    private func restartIdleRecording() {
        let quiet = Double(hostNowNs() - detector.lastActivityNs) / 1e9
        if isListening, !isDebugRecording, phase == .listening, recorder.isRecording, recorder.age > 60, quiet > 3 {
            recorder.discard()
            recorder.start()
        }
    }

    // MARK: - Listening

    private func startListening() {
        guard !isPaused else { return }
        if atRecordingLimit {
            showLimitPrompt = true
            return
        }
        recorder.start()
        detector.startListening()
        isListening = true
        updateMicrophone()
    }

    private func stopListening() {
        detector.stopListening()
        recorder.discard()
        isListening = false
        phase = .listening
        updateMicrophone()
    }

    /// The microphone runs while listening, if wanted and allowed. Without it, shots are looked for in motion
    /// alone. A debug recording always records sound, since working out how strikes sound is what it's for.
    private func updateMicrophone() {
        let wanted = (micEnabled || isDebugRecording) && micPermission && (isListening || isDebugRecording)
        if wanted, !isMicOn {
            isMicOn = true
            microphone.start(enabled: true) { problem in
                if let problem {
                    self.isMicOn = false
                    self.micNote = "Couldn't open the microphone: \(problem)"
                    self.detector.watchWithoutSound = true
                } else {
                    self.micNote = nil
                }
                LiveCheck.log("listening, microphone \(self.isMicOn ? "on" : "off")")
            }
        } else if !wanted, isMicOn {
            microphone.stop()
            isMicOn = false
        }
        detector.watchWithoutSound = !wanted
    }

    /// Settings › Hear the strike: asks for the microphone if it hasn't been allowed.
    func setMicEnabled(_ on: Bool) {
        if on, !micPermission {
            StrikeMicrophone.requestAccess { granted in
                self.micPermission = granted
                self.micNote = granted ? nil : "Microphone not allowed: turn it on in Settings › GolfBallTracer"
                self.micEnabled = true
                self.updateMicrophone()
            }
        } else {
            micEnabled = on
            updateMicrophone()
        }
    }

    func clearShot() {
        detectedPoints = []
        teeLocation = nil
        currentShotId = nil
        isShotRevealed = false
        isBallFlightMissing = false
        isRetracing = false
        shotStats = nil
        saveNote = nil
        videoNote = nil
        detectedSpots = []
        shotStill = nil
        stillShift = .zero
        teeBallRadius = nil
        launchPose = nil
        ballLanding = nil
        locatorProblem = nil
    }

    /**
     * Starts listening for the next shot from scratch, whatever was going on: the shot on screen is cleared,
     * one being captured or worked out is let go, notes are cleared, a pause ends, and frames the detector
     * hadn't got to yet are skipped so it listens to what the camera sees now.
     */
    func resetShot() {
        resetCount += 1
        clearShot()
        strikeNote = nil
        if !isSavingDebug { debugNote = nil }
        let wasCapturing = phase == .capturing
        phase = .listening
        isPaused = false
        resetAtNs = hostNowNs()
        debugRecorder.log("reset", wasCapturing ? "abandoned the shot being captured" : "")
        if isDebugRecording {
            // The debug recording's video keeps running.
            detector.restart()
            isListening = true
        } else if atRecordingLimit {
            stopListening()
            showLimitPrompt = true
        } else {
            detector.restart()
            // A video holding an abandoned shot is started afresh.
            if wasCapturing { recorder.discard() }
            startListening()
        }
        updateMicrophone()
    }

    /// Turns listening off, from the one switch: a shot being followed or worked out is let go, and the
    /// camera analysis, microphone and video stop. A shot already on screen stays, to review it.
    func stopListeningByUser() {
        resetCount += 1
        resetAtNs = hostNowNs()
        strikeNote = nil
        if phase != .listening { clearShot() }
        isPaused = true
        debugRecorder.log("listening-off")
        stopListening()
    }

    /// The Start/Stop listening button. Starting always starts afresh, with the tee GPS cleared.
    func toggleListening() {
        if isPaused {
            resetShot()
            location.reset()
        } else {
            stopListeningByUser()
        }
    }

    /// After a shot: listening goes off (camera analysis, microphone and video) until Start listening.
    private func pauseAfterShot() {
        isPaused = true
        isListening = false
        detector.stopListening()
        updateMicrophone()
    }

    /// Makes room for more recordings: the earliest shots' recordings go (the shots stay).
    func freeRecordings() {
        let ids = store.earliestRecordings(ShotStore.recordingsToFree).map(\.id)
        store.deleteRecordings(ids)
        startListening()
    }

    func discardUncertain(_ uncertain: UncertainShot) {
        processor.discard(uncertain.id)
        if currentShotId == uncertain.id { clearShot() }
        uncertainShot = nil
    }

    // MARK: - Shots

    private func handle(_ event: ShotTriggerEvent) {
        LiveCheck.log("event \(event)")
        if let started = event as? ShotTriggerEventStarted {
            // Already on its way when the user started over.
            guard started.impactNs >= resetAtNs else { return }
            // A phone pointing at the ground isn't filming a shot: it's being carried, or balls are being raked over.
            if orientation.cameraLooksDown {
                debugRecorder.log("shot-ignored", "phone pointing at the ground", at: started.impactNs)
                strikeNote = "🔊 heard a strike with the phone pointing at the ground • not taken as a shot"
                resetAtNs = hostNowNs()
                detector.restart()
                return
            }
            debugRecorder.log("shot-started", String(format: "ball at (%.3f, %.3f) radius %.4f, %@",
                                                     started.ball.x, started.ball.y, started.ball.radius, started.heard ? "heard" : "seen only"), at: started.impactNs)
            clearShot()
            phase = .capturing
            stillShift = CGPoint(x: CGFloat(started.stillShiftX), y: CGFloat(started.stillShiftY))
            teeLocation = CGPoint(x: CGFloat(started.ball.x) + stillShift.x, y: CGFloat(started.ball.y) + stillShift.y)
            teeBallRadius = started.ball.radius
            launchPose = currentPose()
            location.recordLaunch()
            camera.captureStill { [weak self] image in
                guard let self, self.phase == .capturing || self.phase == .analyzing else { return }
                self.shotStill = image
            }
        } else if let captured = event as? ShotTriggerEventCaptured {
            guard phase == .capturing else { return }
            Task { if isDebugRecording { await noteDebugShot(captured.shot) } else { await analyze(captured.shot) } }
        } else if let missed = event as? ShotTriggerEventMissed {
            guard missed.impactNs >= resetAtNs else { return }
            debugRecorder.log("strike-missed", missed.duringSwing ? "during a swing, no ball seen leaving" : "no swing seen", at: missed.impactNs)
            let loud = lastStrikeDb.map { String(format: " (+%.0f dB)", $0) } ?? ""
            let time = Date().formatted(date: .omitted, time: .standard)
            if missed.tooSoon {
                strikeNote = "🔊 \(time) heard a strike\(loud) just as listening started • too soon to see the ball"
            } else if missed.duringSwing {
                strikeNote = "🔊 \(time) heard a strike\(loud) during a swing • no ball seen leaving"
            } else {
                strikeNote = "🔊 \(time) heard a sharp sound\(loud) • no swing seen"
            }
            // Keep what the camera saw, so the miss can be worked out later.
            if missed.duringSwing, !isDebugRecording, let note = strikeNote {
                processor.saveMissedStrike(frames: missed.frames, impactNs: missed.impactNs) { saved in
                    if self.strikeNote == note, saved != nil { self.strikeNote = note + " • frames saved" }
                }
            }
        }
    }

    /// Which way the camera faced and how wide it sees, to estimate a flight that was only glimpsed.
    private func currentPose() -> LaunchPhysics.CameraPose? {
        guard let fov = camera.verticalFieldOfView, let m = orientation.deviceToWorld else { return nil }
        return LiveInputKt.cameraPose(verticalFovRadians: fov, m0: m[0], m1: m[1], m2: m[2], m3: m[3], m4: m[4], m5: m[5], m6: m[6], m7: m[7], m8: m[8])
    }

    /// Finds the ball's flight among everything that moved, then saves the shot with its recording.
    private func analyze(_ shot: CapturedShot) async {
        phase = .analyzing
        let generation = resetCount
        let still = shotStill
        let shift = stillShift
        // The video first. Listening then stays off until the golfer starts it again for the next shot, so
        // there's time to look at this one and change the golfer or club.
        let video = await recorder.stop()
        let videoExpected = isVideoAvailable == true
        pendingSaves += 1
        pauseAfterShot()
        let camera = launchPose
        let fitted = await Task.detached(priority: .userInitiated) { LiveInputKt.fitShot(shot: shot, camera: camera) }.value

        if fitted == nil && !shot.heardImpact {
            // Something bright went from a still spot during some movement, but nothing was heard and nothing
            // flew: not a shot. Carry on listening.
            pendingSaves = max(0, pendingSaves - 1)
            if let video { try? FileManager.default.removeItem(at: video.url) }
            if generation == resetCount {
                clearShot()
                phase = .listening
                shotStats = "Saw movement but no ball flight • still listening"
                isPaused = false
                startListening()
            }
            return
        }
        let problems = AppleShotsKt.coverageProblems(shot: shot, videoStartNs: video?.startNs.map { KotlinLong(value: $0) }, videoExpected: videoExpected)
        debugRecorder.log("shot-coverage", problems.isEmpty ? "complete" : problems.joined(separator: "; "))

        let points = (fitted?.tracer ?? []).map { shifted($0, by: shift) }
        let hasPin = location.landing != nil
        let record = AppleShotsKt.makeShotRecord(
            club: club, golfer: Int32(golfer), tracerStyle: tracerStyle.name,
            launch: location.launch, landing: location.landing,
            // Until the ball is pinned, the live distance is only how far the phone has moved.
            distanceYards: hasPin ? location.distanceYards : 0, distanceMeters: hasPin ? location.distanceMeters : 0,
            points: points, tracerEstimated: fitted?.estimated ?? false
        )
        processor.process(.init(record: record, shot: shot, liveFlight: fitted, clip: video, still: still, stillShift: shift,
                                camera: camera, declinationDegrees: location.declinationDegrees))
        if !problems.isEmpty || fitted == nil { uncertainShot = UncertainShot(id: record.id, reasons: problems, noFlight: fitted == nil) }
        // Started over meanwhile: the shot is saved, but the screen stays as the user left it.
        guard generation == resetCount else { return }

        detectedPoints = points
        estimateLanding(fitted, shot: shot)
        isBallFlightMissing = points.isEmpty
        launchSeen = shot.launchTimestampNs != nil
        let frames = shot.thumbnails
        let seconds = frames.count > 1 ? Double(frames.last!.timestampNs - frames.first!.timestampNs) / 1e9 : 0
        let fps = seconds > 0 ? Double(shot.framesProcessed) / seconds : 0
        var stats = String(format: "%d spots • %.1f s at %.0f fps • strike %@ • ball left %@", shot.candidates.count, seconds, fps,
                           shot.heardImpact ? "heard" : "seen", shot.launchTimestampNs != nil ? "✓" : "✗")
        if let fitted, fitted.estimated {
            stats += String(format: "\nBall seen for %.2f s: the dashed part of the tracer is estimated", fitted.trackedSeconds)
        }
        shotStats = stats
        detectedSpots = shot.candidates.map { shifted(ScreenPoint(x: $0.x, y: $0.y, timestampMs: $0.timestampNs / 1_000_000, confidence: 1), by: shift) }
        currentShotId = record.id
        saveNote = "Saving recording…"
        isRetracing = video != nil
        videoNote = video != nil ? "Tracing again from the full video…" : "No video for this shot"
        phase = .listening
        LiveCheck.log("shot: \(stats.replacingOccurrences(of: "\n", with: " · ")) · tracer \(points.count) points")
    }

    private func shifted(_ p: ScreenPoint, by s: CGPoint) -> ScreenPoint {
        s == .zero ? p : ScreenPoint(x: p.x + Float(s.x), y: p.y + Float(s.y), timestampMs: p.timestampMs, confidence: p.confidence)
    }

    /// Where the ball came down, worked out from its launch on camera, for the ball locator.
    private func estimateLanding(_ fitted: BallFlightFitter.Flight?, shot: CapturedShot) {
        ballLanding = nil
        let tee = location.launch
        if fitted == nil {
            locatorProblem = "No ball flight was traced, so there's nothing to locate."
        } else if tee == nil {
            locatorProblem = "There was no GPS fix when the shot was taken. Wait for GPS before the next shot."
        } else if launchPose == nil {
            locatorProblem = "The phone's motion sensors weren't available while filming."
        } else if shot.ballRadius == nil {
            locatorProblem = "The ball on the tee wasn't measured."
        } else {
            locatorProblem = nil
        }
        guard locatorProblem == nil else { return }
        ballLanding = AppleShotsKt.estimateLanding(flight: fitted, shot: shot, camera: launchPose,
                                                   declinationDegrees: location.declinationDegrees, tee: tee)
        if ballLanding == nil { locatorProblem = "The launch speed didn't look like a real shot, so there's no landing estimate." }
    }

    private func apply(_ update: ShotProcessor.Update) {
        switch update {
        case .saved(let id, let debugExport, _):
            pendingSaves = max(0, pendingSaves - 1)
            if id == currentShotId { saveNote = "Saved to history" + (debugExport.map { " • frames in Files › GolfBallTracer › \($0)" } ?? "") }
        case .retraced(let id, let flight, let points, let note):
            guard id == currentShotId else { return }
            isRetracing = false
            videoNote = note
            if let points, flight != nil {
                detectedPoints = points
                isBallFlightMissing = false
                // The processor worked out the landing from the video's flight and saved it with the shot.
                if let landing = store.find(id)?.estimatedLanding {
                    ballLanding = landing
                    locatorProblem = nil
                }
            }
        }
    }

    /// Pinning the ball's GPS while a shot is on screen gives that shot its measured distance.
    func ballPinned() {
        guard let id = currentShotId, let ball = location.landing else { return }
        let yards = location.distanceYards
        let meters = location.distanceMeters
        store.update(id) { $0.withPinnedBall(landing: ball, distanceYards: yards, distanceMeters: meters) }
    }

    /// Tapping the picture: ends a capture early once the ball has landed, or shows the shot's distance.
    func tapped() {
        if phase == .capturing { detector.finishCapture() } else if showingShot { isShotRevealed = true }
    }

    /// Settings › Tune curve by dragging: the middle of the tracer follows the finger, and the curve is smoothed.
    func dragCurve(to point: CGPoint) {
        guard editMode, !detectedPoints.isEmpty else { return }
        let middle = detectedPoints.count / 2
        let moved = detectedPoints.enumerated().map { i, p in
            i == middle ? ScreenPoint(x: Float(point.x), y: Float(point.y), timestampMs: p.timestampMs, confidence: 1) : p
        }
        detectedPoints = TrajectoryMath.shared.smoothTrajectory(rawPoints: moved, sampleCount: 40)
    }

    /// Saves the shot on screen with any changes (tracer style, GPS, a tuned curve) and returns it, to review.
    func reviewShot() async -> ShotRecord? {
        isShotRevealed = true
        if let id = currentShotId {
            guard let base = store.find(id) else { return nil }
            // The club and golfer stay the shot's own: those chosen meanwhile are for the next shot.
            let edited = base.withEdits(
                tracerStyle: tracerStyle.name,
                launch: location.launch ?? base.launchLocation, landing: location.landing ?? base.landingLocation,
                distanceYards: location.landing != nil ? location.distanceYards : base.distanceYards,
                distanceMeters: location.landing != nil ? location.distanceMeters : base.distanceMeters,
                points: detectedPoints
            )
            return await processor.saveEdits(edited, still: shotStill)
        }
        // The demo flight: no recording, just a snapshot.
        let record = AppleShotsKt.makeShotRecord(
            club: club, golfer: Int32(golfer), tracerStyle: tracerStyle.name, launch: location.launch, landing: location.landing,
            distanceYards: location.distanceYards, distanceMeters: location.distanceMeters, points: detectedPoints, tracerEstimated: false
        )
        return await processor.saveWithoutRecording(record, still: shotStill)
    }

    /// Settings › Show demo flight: a made-up tracer, and a pretend landing 30 m ahead to try the ball locator.
    func showDemoFlight() {
        resetShot()
        teeLocation = CGPoint(x: 0.5, y: 0.8)
        detectedPoints = TracerDrawing.normalized(TrajectoryMath.shared.generateDefaultTracer(viewWidth: 1080, viewHeight: 1920, apexFactor: 0.45))
        if location.distanceYards == 0 { location.setManualDistance(yards: 245) }
        isShotRevealed = false
        if let here = location.current, let facing = orientation.headingDegrees {
            let bearing = LaunchPhysics.shared.normalizeDegrees(d: facing + location.declinationDegrees)
            ballLanding = LaunchPhysics.Landing(position: LaunchPhysics.shared.destination(from: here, bearingDegrees: bearing, meters: 30),
                                               bearingDegrees: bearing, carryMeters: 22, totalMeters: 30, launchSpeedMps: 18, launchAngleDegrees: 32)
            locatorProblem = nil
        } else {
            ballLanding = nil
            locatorProblem = "The demo needs a GPS fix and the compass to place a pretend ball."
        }
    }

    // MARK: - Debug recording

    /// `-debugCheck`: an 8 s debug recording, made and saved without anyone at the phone.
    private func runDebugCheck() {
        Task {
            await startDebugRecording()
            DevChecks.log("debug recording started: \(isDebugRecording)")
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            DevChecks.log("stopping after \(debugRecorder.seconds) s")
            await stopDebugRecording()
            DevChecks.log("debug recording: \(debugNote ?? "nothing")")
        }
    }

    func toggleDebugRecording() {
        Task { if isDebugRecording { await stopDebugRecording() } else { await startDebugRecording() } }
    }

    private func startDebugRecording() async {
        guard !isDebugRecording, !isSavingDebug else { return }
        if !micPermission { setMicEnabled(true) }
        detector.stopListening()
        resetAtNs = hostNowNs()
        clearShot()
        phase = .listening
        isPaused = false
        // The video starts afresh with the recording.
        if let clip = await recorder.stop() { try? FileManager.default.removeItem(at: clip.url) }
        guard debugRecorder.start() else {
            debugNote = "Couldn't start a debug recording"
            startListening()
            return
        }
        debugRecorder.log("settings", "mic \(micEnabled ? "on" : "off"), video \(isVideoAvailable == true ? "on" : "off")")
        recorder.start()
        // Listening even at the recording limit: nothing is saved as a shot while debugging.
        detector.startListening()
        isListening = true
        isDebugRecording = true
        debugNote = nil
        updateMicrophone()
    }

    private func stopDebugRecording() async {
        guard isDebugRecording else { return }
        // A note being written is kept rather than lost with the recording.
        if !debugNoteText.trimmingCharacters(in: .whitespaces).isEmpty { saveDebugNote() } else { isWritingDebugNote = false }
        isDebugRecording = false
        isSavingDebug = true
        // A shot being followed now belongs to the debug recording, which is ending: let it go.
        resetAtNs = hostNowNs()
        if phase == .capturing {
            clearShot()
            phase = .listening
        }
        debugRecorder.stop()
        debugNote = "Saving debug recording…"
        let clip = await recorder.stop()
        let pose = currentPose()
        let mic = isMicOn
        let saved = await Task.detached(priority: .utility) { [debugRecorder] in
            debugRecorder.finish(video: clip?.url, videoStartNs: clip?.startNs, micEnabled: mic, camera: pose)
        }.value
        debugNote = saved.map { "🐞 Debug recording saved to Files › GolfBallTracer › \($0)" } ?? "Couldn't save the debug recording"
        isSavingDebug = false
        // Back to normal listening, limits and all.
        detector.stopListening()
        isListening = false
        updateMicrophone()
        startListening()
    }

    /// In a debug recording a shot is only noted, not saved, and the video keeps running.
    private func noteDebugShot(_ shot: CapturedShot) async {
        phase = .analyzing
        let camera = launchPose
        let fitted = await Task.detached(priority: .userInitiated) { LiveInputKt.fitShot(shot: shot, camera: camera) }.value
        let detail: String
        if let fitted {
            detail = fitted.estimated
                ? String(format: "ball seen %.2f s in %d frames, rest estimated, %d points", fitted.trackedSeconds, fitted.trackedFrames, fitted.tracer.count)
                : String(format: "tracer %d points, ball followed %.2f s", fitted.tracer.count, fitted.trackedSeconds)
        } else {
            detail = "no ball flight found"
        }
        debugRecorder.log("shot-captured", detail)
        clearShot()
        shotStats = "Debug: shot found • \(detail) (not saved)"
        // Offer a note about it, optional: it can be left empty.
        startDebugNote(at: shot.impactTimestampNs?.int64Value ?? hostNowNs())
        phase = .listening
        detector.startListening()
    }

    func startDebugNote(at ns: Int64 = hostNowNs()) {
        // A note half written about an earlier moment is kept before starting the next one.
        if !debugNoteText.trimmingCharacters(in: .whitespaces).isEmpty { saveDebugNote() }
        debugNoteAtNs = ns
        isWritingDebugNote = true
    }

    /// Puts the note typed so far into the debug recording's log, at the shot or moment it's about.
    func saveDebugNote() {
        let text = debugNoteText.trimmingCharacters(in: .whitespacesAndNewlines)
        if !text.isEmpty, isDebugRecording {
            debugRecorder.log("note", text, at: debugNoteAtNs ?? hostNowNs())
            debugNote = "📝 Note saved: \(text)"
        }
        skipDebugNote()
    }

    func skipDebugNote() {
        debugNoteText = ""
        debugNoteAtNs = nil
        isWritingDebugNote = false
    }
}
