import Foundation
import ImageIO
import UniformTypeIdentifiers
import GolfTracking

/// A check of the live pipeline over USB: launched with `-liveCheck`, the app starts listening by itself
/// and prints, every two seconds, how many frames reached the detector and their size, the strikes heard
/// and the detector's events (`LIVE-CHECK` lines). The first analysis frame is saved to Documents as
/// live-check.png, to see that frames stand upright and are cropped as the preview shows.
enum LiveCheck {
    static let requested = ProcessInfo.processInfo.arguments.contains("-liveCheck")

    private static let lock = NSLock()
    private static var frames = 0
    private static var lastFrame: (Int32, Int32, Int64)?
    private static var savedFrame = false
    private static var timer: Timer?

    static func frame(_ frame: LumaFrame) {
        guard requested else { return }
        lock.lock()
        frames += 1
        lastFrame = (frame.width, frame.height, frame.timestampNs)
        let save = !savedFrame
        savedFrame = true
        lock.unlock()
        if save { saveImage(frame) }
    }

    static func log(_ text: String) {
        guard requested else { return }
        print("LIVE-CHECK \(String(format: "%.1f", Double(hostNowNs()) / 1e9)) \(text)")
    }

    /// Prints the frame count every two seconds, with how far behind the newest frame is.
    static func startReporting(queued: @escaping () -> Int, analysisFps: @escaping () -> Double) {
        guard requested, timer == nil else { return }
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { _ in
            lock.lock()
            let n = frames
            frames = 0
            let last = lastFrame
            lock.unlock()
            let size = last.map { "\($0.0)x\($0.1), newest \(String(format: "%.0f", Double(hostNowNs() - $0.2) / 1e6)) ms old" } ?? "none yet"
            log(String(format: "frames %.1f/s (pacing at %.0f), %@, %d queued", Double(n) / 2, analysisFps(), size, queued()))
        }
    }

    private static func saveImage(_ frame: LumaFrame) {
        let data = frame.pixels() as Data
        guard let provider = CGDataProvider(data: data as CFData),
              let image = CGImage(width: Int(frame.width), height: Int(frame.height), bitsPerComponent: 8, bitsPerPixel: 8,
                                  bytesPerRow: Int(frame.width), space: CGColorSpaceCreateDeviceGray(),
                                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
                                  provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
        else { return }
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("live-check.png")
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else { return }
        CGImageDestinationAddImage(dest, image, nil)
        log(CGImageDestinationFinalize(dest) ? "saved live-check.png (\(frame.width)x\(frame.height))" : "couldn't save live-check.png")
    }
}
