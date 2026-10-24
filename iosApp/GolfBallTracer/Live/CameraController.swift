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
    private var stillRequest: ((CGImage?) -> Void)?
    private var configured = false

    /// Called on the frame queue for every frame while [wantsFrames] says so.
    var onFrame: ((LumaFrame) -> Void)?
    var wantsFrames: () -> Bool = { false }

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
        // 30 frames a second, steadily: the tracker's timings were worked out at about that rate.
        do {
            try camera.lockForConfiguration()
            let thirty = CMTime(value: 1, timescale: 30)
            if camera.activeFormat.videoSupportedFrameRateRanges.contains(where: { $0.minFrameDuration <= thirty && thirty <= $0.maxFrameDuration }) {
                camera.activeVideoMinFrameDuration = thirty
                camera.activeVideoMaxFrameDuration = thirty
            }
            camera.unlockForConfiguration()
        } catch {}
        device = camera
        configured = true
        updateFieldOfView()
        return nil
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
