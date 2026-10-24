import AVFoundation
import CoreImage
import GolfTracking

/// The back camera: shows the preview and hands each frame's grey pixels to the shot detector, cut to
/// what the preview shows, on the host clock the microphone's strikes are timed on too.
final class CameraController: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    /// Starting and stopping the session block, so they run here rather than on the main thread.
    private let sessionQueue = DispatchQueue(label: "camera-session")
    /// Frames arrive here; the heavy work is on the shot detector's own queue.
    private let frameQueue = DispatchQueue(label: "camera-frames", qos: .userInteractive)
    private let sampler = CameraFrameSampler(targetLongSide: 640)
    private let ciContext = CIContext()
    private let lock = NSLock()

    private var aspect: Float = 9.0 / 19.5
    /// Lets through as many frames as the detector keeps up with, up to 60 a second.
    private let pacer = FramePacer(maxFps: 60, fallbackFps: 30, behindSeconds: 1, retrySeconds: 10)
    private var stillRequest: ((CGImage?) -> Void)?
    private var configured = false

    /// Called on the frame queue for every frame while [wantsFrames] says so.
    var onFrame: ((LumaFrame) -> Void)?
    var wantsFrames: () -> Bool = { false }
    /// Frames still waiting for the detector, for pacing.
    var queuedFrames: () -> Int = { 0 }

    /// The camera's frame rate, once set up.
    private(set) var cameraFps: Double = 30
    /// The rate frames are analysed at now: the camera's, or 30 when the detector can't keep up.
    var analysisFps: Double { min(cameraFps, pacer.fps) }
    /// The camera's vertical field of view as the preview shows it upright, in radians, once known.
    private(set) var verticalFieldOfView: Double?

    /// The preview's width over its height, which the analysis frames are cropped to.
    var uprightAspect: Float {
        get { lock.lock(); defer { lock.unlock() }; return aspect }
        set {
            lock.lock(); aspect = newValue; lock.unlock()
            updateFieldOfView()
        }
    }

    private var device: AVCaptureDevice?

    /// Sets the camera up (once) and starts it. Calls back on the main thread with a problem, if any.
    func start(_ done: @escaping (String?) -> Void) {
        sessionQueue.async {
            var problem: String?
            if !self.configured { problem = self.configure() }
            if problem == nil, !self.session.isRunning { self.session.startRunning() }
            DispatchQueue.main.async { done(problem) }
        }
    }

    func stop() {
        sessionQueue.async { if self.session.isRunning { self.session.stopRunning() } }
    }

    /// The next frame as a colour picture, cropped as the preview shows it, for drawing the shot over.
    func captureStill(_ done: @escaping (CGImage?) -> Void) {
        frameQueue.async { self.stillRequest = done }
    }

    private func configure() -> String? {
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            return "No back camera on this device."
        }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        // 1080p, as the Android app films: the analysis frames come out about 640 pixels tall either way.
        // (A format chosen below for its frame rate takes over from this preset.)
        session.sessionPreset = session.canSetSessionPreset(.hd1920x1080) ? .hd1920x1080 : .high
        // The microphone is used on its own (see StrikeMicrophone), not through the capture session.
        session.automaticallyConfiguresApplicationAudioSession = false
        do {
            let input = try AVCaptureDeviceInput(device: camera)
            guard session.canAddInput(input) else { return "Couldn't use the camera." }
            session.addInput(input)
        } catch {
            return "Couldn't open the camera: \(error.localizedDescription)"
        }
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: frameQueue)
        guard session.canAddOutput(output) else { return "Couldn't read the camera's frames." }
        session.addOutput(output)
        cameraFps = useFastestFormat(camera)
        device = camera
        configured = true
        updateFieldOfView()
        return nil
    }

    /// Films 1080p at the fastest rate the camera has, up to 60 frames a second: newer iPhones film the ball's
    /// first moments in twice as many frames. Full-range YUV and full-sensor (unbinned) formats are preferred at
    /// the same rate. Returns the rate chosen.
    private func useFastestFormat(_ camera: AVCaptureDevice) -> Double {
        func rate(_ format: AVCaptureDevice.Format) -> Double {
            min(60, format.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 0)
        }
        func rank(_ format: AVCaptureDevice.Format) -> (Double, Int, Int) {
            let fullRange = CMFormatDescriptionGetMediaSubType(format.formatDescription) == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
            return (rate(format), fullRange ? 1 : 0, format.isVideoBinned ? 0 : 1)
        }
        let hd = camera.formats.filter {
            let size = CMVideoFormatDescriptionGetDimensions($0.formatDescription)
            return size.width == 1920 && size.height == 1080
        }
        guard let best = hd.max(by: { rank($0) < rank($1) }), rate(best) >= 30 else { return 30 }
        let fps = rate(best)
        do {
            try camera.lockForConfiguration()
            camera.activeFormat = best
            let duration = CMTime(value: 1, timescale: CMTimeScale(fps.rounded()))
            camera.activeVideoMinFrameDuration = duration
            camera.activeVideoMaxFrameDuration = duration
            camera.unlockForConfiguration()
        } catch {
            return 30
        }
        return fps.rounded()
    }

    /// The sensor's long side is upright on screen; the preview fills the screen, cropping one way or the other.
    private func updateFieldOfView() {
        guard let device else { return }
        let horizontal = Double(device.activeFormat.videoFieldOfView) * .pi / 180
        let dims = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
        let imageAspect = Double(min(dims.width, dims.height)) / Double(max(dims.width, dims.height))
        let screen = Double(uprightAspect)
        // A screen wider than the picture crops its top and bottom off.
        let shown = screen > imageAspect ? imageAspect / screen : 1
        verticalFieldOfView = 2 * atan(tan(horizontal / 2) * shown)
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        if let request = stillRequest {
            stillRequest = nil
            let image = still(pixels)
            DispatchQueue.main.async { request(image) }
        }
        guard wantsFrames(), let onFrame else { return }
        // Presentation times are on the host clock, as AVAudioTime's host times are.
        let timestampNs = Int64(CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer)) * 1e9)
        guard pacer.keep(timestampNs: timestampNs, queuedFrames: Int32(queuedFrames())) else { return }
        CVPixelBufferLockBaseAddress(pixels, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixels, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddressOfPlane(pixels, 0) else { return }
        let frame = sampler.sample(
            plane: base.assumingMemoryBound(to: UInt8.self),
            width: Int32(CVPixelBufferGetWidthOfPlane(pixels, 0)),
            height: Int32(CVPixelBufferGetHeightOfPlane(pixels, 0)),
            bytesPerRow: Int32(CVPixelBufferGetBytesPerRowOfPlane(pixels, 0)),
            // The back camera's frames are sideways: turned a quarter clockwise they stand upright.
            rotation: 90,
            timestampNs: timestampNs,
            uprightAspect: uprightAspect
        )
        onFrame(frame)
    }

    /// The frame upright and cropped to the preview's shape, as the screen shows it.
    private func still(_ pixels: CVPixelBuffer) -> CGImage? {
        let upright = CIImage(cvPixelBuffer: pixels).oriented(.right)
        let extent = upright.extent
        let wanted = CGFloat(uprightAspect)
        var crop = extent
        if extent.width / extent.height > wanted {
            crop.size.width = extent.height * wanted
            crop.origin.x += (extent.width - crop.width) / 2
        } else {
            crop.size.height = extent.width / wanted
            crop.origin.y += (extent.height - crop.height) / 2
        }
        return ciContext.createCGImage(upright, from: crop.integral)
    }
}
