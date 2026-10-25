import SwiftUI

/// The Android app's colours, so both apps look alike.
extension Color {
    static let golfDarkBg = Color(hex: 0x0A0F1D)
    static let golfDarkSurface = Color(hex: 0x131C31)
    static let golfDarkCard = Color(hex: 0x1A2642)
    static let golfNeonLime = Color(hex: 0x00FF66)
    static let golfNeonCyan = Color(hex: 0x00E5FF)
    static let golfNeonGold = Color(hex: 0xFFD700)
    static let golfNeonRed = Color(hex: 0xFF3D00)
    static let golfTextSecondary = Color(hex: 0x94A3B8)
    static let golfTextMuted = Color(hex: 0x64748B)

    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }

    /// A colour from the shared code's ARGB values (tracer styles).
    init(argb: Int64) {
        let v = UInt64(bitPattern: argb)
        self.init(.sRGB, red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255,
                  blue: Double(v & 0xFF) / 255, opacity: Double((v >> 24) & 0xFF) / 255)
    }
}

extension UIColor {
    convenience init(argb: Int64) {
        let v = UInt64(bitPattern: argb)
        self.init(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255,
                  blue: CGFloat(v & 0xFF) / 255, alpha: CGFloat((v >> 24) & 0xFF) / 255)
    }
}
