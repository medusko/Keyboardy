import AppKit
import SwiftUI

public struct ThemePalette {
    public let isDark: Bool
    public let background: Color
    public let surface: Color
    public let surfaceVariant: Color
    public let border: Color
    public let accentBlue: Color
    public let accentBluePressed: Color
    public let textPrimary: Color
    public let textSecondary: Color
    public let chipActiveBg: Color
    public let chipActiveText: Color
    public let chipInactiveBg: Color
    public let chipInactiveText: Color
    public let successGreen: Color

    public static let dark = ThemePalette(
        isDark: true,
        background: Color(hex: 0x101216),
        surface: Color(hex: 0x191C23),
        surfaceVariant: Color(hex: 0x222631),
        border: Color(hex: 0x2E3342),
        accentBlue: Color(hex: 0x2E6BD9),
        accentBluePressed: Color(hex: 0x4782EE),
        textPrimary: Color(hex: 0xFFFFFF),
        textSecondary: Color(hex: 0xB8C2D4),
        chipActiveBg: Color(hex: 0x2E6BD9),
        chipActiveText: Color(hex: 0xFFFFFF),
        chipInactiveBg: Color(hex: 0x191C23),
        chipInactiveText: Color(hex: 0xD8E0EE),
        successGreen: Color(hex: 0x00C853)
    )

    public static let light = ThemePalette(
        isDark: false,
        background: Color(hex: 0xF6F1E8),
        surface: Color(hex: 0xFFFDF9),
        surfaceVariant: Color(hex: 0xECE5D9),
        border: Color(hex: 0xDCD4C6),
        accentBlue: Color(hex: 0x2E6BD9),
        accentBluePressed: Color(hex: 0x1E54B7),
        textPrimary: Color(hex: 0x1E1A16),
        textSecondary: Color(hex: 0x6B6358),
        chipActiveBg: Color(hex: 0x2E6BD9),
        chipActiveText: Color(hex: 0xFFFFFF),
        chipInactiveBg: Color(hex: 0xFFFDF9),
        chipInactiveText: Color(hex: 0x4E473E),
        successGreen: Color(hex: 0x00A844)
    )

    public static func forMode(isDark: Bool) -> ThemePalette {
        isDark ? .dark : .light
    }
}

extension Color {
    init(hex: UInt32, alpha: Double = 1.0) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}
