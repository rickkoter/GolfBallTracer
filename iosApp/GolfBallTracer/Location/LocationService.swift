import CoreLocation
import CoreMotion
import Foundation
import GolfTracking

/// GPS, as on Android: where the phone is, the tee and ball positions locked by the golfer, and the
/// distance between them (or, before the ball is pinned, how far the phone has moved from the tee).
@MainActor
final class LocationService: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var current: GpsCoordinate?
    @Published private(set) var launch: GpsCoordinate?
    @Published private(set) var landing: GpsCoordinate?
    @Published private(set) var distanceYards: Double = 0
    @Published private(set) var distanceMeters: Double = 0
    @Published private(set) var authorized = false
    /// True north minus magnetic north here, from the compass; 0 until known.
    private(set) var declinationDegrees: Double = 0

    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.headingFilter = 1
    }

    func start() {
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            authorized = true
            manager.startUpdatingLocation()
            if CLLocationManager.headingAvailable() { manager.startUpdatingHeading() }
        default: authorized = false
        }
    }

    func stop() {
        manager.stopUpdatingLocation()
        manager.stopUpdatingHeading()
    }

    /// Locks where the phone is now as the tee. Returns it, or nil without a fix yet.
    @discardableResult
    func recordLaunch() -> GpsCoordinate? {
        launch = current
        landing = nil
        distanceYards = 0
        distanceMeters = 0
        return current
    }

    /// Locks where the phone is now as where the ball landed, and measures the shot.
    @discardableResult
    func recordLanding() -> GpsCoordinate? {
        guard let here = current else { return nil }
        landing = here
        if let tee = launch { setDistance(meters: TrajectoryMath.shared.calculateGpsDistanceMeters(start: tee, end: here)) }
        return here
    }

    func setManualDistance(yards: Double) {
        distanceYards = yards
        distanceMeters = TrajectoryMath.shared.yardsToMeters(yards: yards)
    }

    func reset() {
        launch = nil
        landing = nil
        distanceYards = 0
        distanceMeters = 0
    }

    private func setDistance(meters: Double) {
        distanceMeters = meters
        distanceYards = TrajectoryMath.shared.metersToYards(meters: meters)
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in self.start() }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last, loc.horizontalAccuracy >= 0 else { return }
        let coord = GpsCoordinate(latitude: loc.coordinate.latitude, longitude: loc.coordinate.longitude,
                                  altitude: loc.altitude, accuracyMeters: Float(loc.horizontalAccuracy))
        Task { @MainActor in
            self.current = coord
            // Walking to the ball: the live distance from the tee, until the ball is pinned.
            if let tee = self.launch, self.landing == nil {
                self.setDistance(meters: TrajectoryMath.shared.calculateGpsDistanceMeters(start: tee, end: coord))
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateHeading heading: CLHeading) {
        guard heading.trueHeading >= 0, heading.headingAccuracy >= 0 else { return }
        var d = heading.trueHeading - heading.magneticHeading
        if d > 180 { d -= 360 } else if d < -180 { d += 360 }
        Task { @MainActor in self.declinationDegrees = d }
    }
}

/// Which way the phone points, from its motion sensors: the rotation (phone axes to east/north/up, for the
/// camera's pose) and the compass heading, as on Android. Headings are from magnetic north.
final class DeviceOrientation: ObservableObject {
    private let manager = CMMotionManager()
    private let queue = OperationQueue()
    private let lock = NSLock()
    private var rotation: [Float]?
    private var gravityZ: Double?

    /// Where the phone points, degrees from magnetic north: where its top edge points when held flat,
    /// where the camera looks when held up. Smoothed so it doesn't jitter.
    @Published private(set) var headingDegrees: Double?

    func start() {
        guard manager.isDeviceMotionAvailable, !manager.isDeviceMotionActive else { return }
        manager.deviceMotionUpdateInterval = 0.05
        manager.startDeviceMotionUpdates(using: .xMagneticNorthZVertical, to: queue) { [weak self] motion, _ in
            guard let self, let motion else { return }
            let r = Self.deviceToWorld(motion)
            self.lock.lock()
            self.rotation = r
            self.gravityZ = motion.gravity.z
            self.lock.unlock()
            // Held flat, the phone points where its top edge points; held up, where the camera looks.
            let heading = r[8] > 0.7 ? atan2(Double(r[1]), Double(r[4])) * 180 / .pi : atan2(-Double(r[2]), -Double(r[5])) * 180 / .pi
            DispatchQueue.main.async { self.smooth(heading) }
        }
    }

    func stop() { manager.stopDeviceMotionUpdates() }

    /// Row-major rotation from phone axes to east/north/up, or nil before the first reading.
    var deviceToWorld: [Float]? { lock.lock(); defer { lock.unlock() }; return rotation }

    /// Pointing more than 55° below level (as on Android): filming the ground, not a shot.
    var cameraLooksDown: Bool {
        lock.lock(); defer { lock.unlock() }
        // The back camera looks along the phone's −z; gravity's z is how far that is downward.
        return (gravityZ ?? 0) < -0.82
    }

    private var smoothed: Double?

    private func smooth(_ heading: Double) {
        let normal = (heading.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
        guard let p = smoothed else { smoothed = normal; headingDegrees = normal; return }
        // On the circle, so it doesn't spin across north.
        let x = 0.8 * cos(p * .pi / 180) + 0.2 * cos(normal * .pi / 180)
        let y = 0.8 * sin(p * .pi / 180) + 0.2 * sin(normal * .pi / 180)
        let next = (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
        smoothed = next
        if abs((headingDegrees ?? -999) - next) > 0.5 { headingDegrees = next }
    }

    /// Core Motion's reference frame is x north, y west, z up. Whether its matrix maps phone to reference or
    /// the other way round is settled by gravity: phone-to-reference must turn "up" (−gravity) into +z.
    private static func deviceToWorld(_ motion: CMDeviceMotion) -> [Float] {
        let r = motion.attitude.rotationMatrix
        let m = [[r.m11, r.m12, r.m13], [r.m21, r.m22, r.m23], [r.m31, r.m32, r.m33]]
        let up = [-motion.gravity.x, -motion.gravity.y, -motion.gravity.z]
        let zIfPhoneToReference = m[2][0] * up[0] + m[2][1] * up[1] + m[2][2] * up[2]
        let zIfReferenceToPhone = m[0][2] * up[0] + m[1][2] * up[1] + m[2][2] * up[2]
        let toReference: [[Double]] = zIfPhoneToReference >= zIfReferenceToPhone
            ? m
            : [[m[0][0], m[1][0], m[2][0]], [m[0][1], m[1][1], m[2][1]], [m[0][2], m[1][2], m[2][2]]]
        // East is minus west, north is north, up is up.
        return (toReference[1].map { -$0 } + toReference[0] + toReference[2]).map(Float.init)
    }
}
